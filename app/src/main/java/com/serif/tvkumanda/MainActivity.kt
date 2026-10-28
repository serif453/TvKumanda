package com.serif.tvkumanda

import android.content.Context
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.*
import java.util.concurrent.Executors

// Android tuş kodları
object Keys {
    const val UP = 19; const val DOWN = 20; const val LEFT = 21; const val RIGHT = 22
    const val OK = 23; const val BACK = 4; const val HOME = 3; const val MENU = 82
    const val VOL_UP = 24; const val VOL_DOWN = 25; const val MUTE = 164; const val POWER = 26
    const val CH_UP = 166; const val CH_DOWN = 167; const val ASSIST = 231
    const val PREV = 88; const val PLAY_PAUSE = 85; const val NEXT = 87
    const val DEL = 67; const val ENTER = 66
    fun digit(n: Int) = 7 + n // KEYCODE_0 = 7
}

enum class ConnState { IDLE, CONNECTING, CONNECTED, ERROR }

class MainActivity : ComponentActivity() {

    // İki bağımsız bağlantı yöntemi; hangisinin kullanılacağını kullanıcı seçer
    private lateinit var adb: TvRemote
    private lateinit var atv: AtvRemote
    private var method by mutableStateOf(ConnMethod.ADB)
    private val remote: RemoteLink get() = if (method == ConnMethod.STANDARD) atv else adb
    private var pairing by mutableStateOf(false)
    private var pairingError by mutableStateOf("")
    private var currentIp = ""
    // Tuşlar sırayla gitsin diye tek iş parçacığı
    private val io = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val scope = MainScope()

    private var state by mutableStateOf(ConnState.IDLE)
    private var message by mutableStateOf("Bağlı değil")
    private var tvName by mutableStateOf("")
    private var fastMode by mutableStateOf(false)
    private var apps by mutableStateOf<List<TvApp>>(emptyList())
    private var volume by mutableStateOf<Int?>(null)
    private var volumeMax by mutableStateOf(100)
    private var volumeJob: Job? = null
    private var volumeTick by mutableStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        adb = TvRemote(applicationContext)
        atv = AtvRemote(applicationContext)
        atv.onVolume = { level, max -> runOnUiThread { volume = level; volumeMax = max } }
        atv.onDisconnected = { runOnUiThread { onStandardDisconnected() } }
        val prefs = getSharedPreferences("ayarlar", Context.MODE_PRIVATE)
        val savedIp = prefs.getString("ip", "") ?: ""
        method = runCatching { ConnMethod.valueOf(prefs.getString("method", null) ?: "ADB") }.getOrDefault(ConnMethod.ADB)

        setContent {
            RemoteTheme {
                RemoteScreen(
                    ui = UiState(state, message, tvName, fastMode, apps, volume, volumeMax, volumeTick, method, pairing, pairingError),
                    initialIp = savedIp,
                    onConnect = { ip ->
                        prefs.edit().putString("ip", ip).apply()
                        connect(ip)
                    },
                    onKey = ::key,
                    onText = ::sendText,
                    onMouse = ::mouse,
                    onLaunch = { app -> run { this.launch(app) } },
                    onMethod = { m ->
                        if (m != method) {
                            prefs.edit().putString("method", m.name).apply()
                            switchMethod(m)
                        }
                    },
                    onPairCode = ::submitPairingCode,
                    onPairCancel = ::cancelPairing
                )
            }
        }

        if (savedIp.isNotBlank()) connect(savedIp)
    }

    private fun connect(ip: String) {
        if (ip.isBlank()) { state = ConnState.ERROR; message = "IP adresi gir"; return }
        currentIp = ip
        state = ConnState.CONNECTING
        message = if (method == ConnMethod.ADB) "Bağlanıyor… TV'de izin isterse onayla" else "Bağlanıyor…"
        val link = remote
        scope.launch {
            try {
                val model = withContext(io) { link.connectTo(ip) }
                onConnected(model, ip)
            } catch (e: AtvRemote.PairingRequired) {
                startPairing(ip)
            } catch (e: Exception) {
                state = ConnState.ERROR
                message = "Bağlanamadı: ${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    private fun onConnected(model: String, ip: String) {
        tvName = model
        fastMode = remote.isFast
        state = ConnState.CONNECTED
        message = ip
        apps = emptyList()
        loadApps()
        refreshVolume(0)
    }

    /** Yöntem değişince açık bağlantı kapatılır; diğer yönteme otomatik geçiş yapılmaz. */
    private fun switchMethod(m: ConnMethod) {
        val old = remote
        method = m
        pairing = false
        Thread { old.close() }.start()
        state = ConnState.IDLE
        fastMode = false
        apps = emptyList()
        volume = null
        message = "Bağlı değil"
    }

    // ---------- Standart bağlantı: eşleştirme ----------

    private fun startPairing(ip: String) {
        state = ConnState.CONNECTING
        message = "Eşleştirme başlatılıyor… TV'de kod görünecek"
        scope.launch {
            try {
                withContext(io) { atv.startPairing(ip) }
                pairingError = ""
                pairing = true
                message = "TV ekranındaki kodu gir"
            } catch (e: Exception) {
                state = ConnState.ERROR
                message = "Eşleştirme başlatılamadı: ${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    private fun submitPairingCode(code: String) {
        scope.launch {
            try {
                withContext(io) { atv.finishPairing(code) }
                pairing = false
                message = "Eşleşti, bağlanıyor…"
                val model = withContext(io) { atv.connectTo(currentIp) }
                onConnected(model, currentIp)
            } catch (e: AtvRemote.BadCode) {
                pairingError = e.message ?: "Kod hatalı"   // pencere açık kalır, yeniden denenebilir
            } catch (e: Exception) {
                pairing = false
                state = ConnState.ERROR
                message = "Eşleştirilemedi: ${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    private fun cancelPairing() {
        pairing = false
        scope.launch(io) { atv.cancelPairing() }
        state = ConnState.IDLE
        message = "Eşleştirme iptal edildi"
    }

    /** TV bağlantıyı kapattıysa (ör. uyku sonrası) bir kez sessizce yeniden bağlanmayı dener. */
    private fun onStandardDisconnected() {
        if (method != ConnMethod.STANDARD || state != ConnState.CONNECTED) return
        val ip = currentIp
        scope.launch {
            delay(1000)
            try {
                val model = withContext(io) { atv.connectTo(ip) }
                if (method == ConnMethod.STANDARD) tvName = model
            } catch (e: Exception) {
                if (method == ConnMethod.STANDARD) {
                    state = ConnState.ERROR
                    message = "Bağlantı koptu: ${e.message ?: e.javaClass.simpleName}"
                }
            }
        }
    }

    private fun loadApps() {
        scope.launch {
            val link = remote
            apps = withContext(Dispatchers.IO) { runCatching { link.listApps() }.getOrDefault(emptyList()) }
        }
    }

    /** Ses değişince kısa bir bekleme sonrası TV'den gerçek ses seviyesini okur. */
    private fun refreshVolume(delayMs: Long = 350) {
        volumeJob?.cancel()
        volumeJob = scope.launch {
            delay(delayMs)
            val link = remote
            val v = withContext(Dispatchers.IO) { runCatching { link.volume() }.getOrNull() }
            if (v != null) { volume = v.first; volumeMax = v.second }
        }
    }

    /** Bağlantı üzerinde arka planda iş çalıştırır. */
    private fun run(action: RemoteLink.() -> Unit) {
        if (state != ConnState.CONNECTED) { message = "Önce TV'ye bağlan"; return }
        val link = remote
        scope.launch {
            try {
                withContext(io) { link.action() }
                fastMode = remote.isFast
            } catch (e: Exception) {
                state = ConnState.ERROR
                fastMode = false
                message = "Bağlantı koptu: ${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    private fun key(code: Int) {
        run { this.key(code) }
        if (code == Keys.VOL_UP || code == Keys.VOL_DOWN || code == Keys.MUTE) {
            // Anında göster (tahmini), ardından TV'den gerçek değeri oku
            volume?.let { v ->
                volume = when (code) {
                    Keys.VOL_UP -> (v + 1).coerceAtMost(volumeMax)
                    Keys.VOL_DOWN -> (v - 1).coerceAtLeast(0)
                    else -> v
                }
            }
            volumeTick++
            refreshVolume()
        }
    }

    private fun mouse(line: String) {
        if (state != ConnState.CONNECTED) return
        val link = remote
        scope.launch(io) { runCatching { link.mouse(line) } }
    }

    private fun sendText(value: String) {
        if (value.isEmpty()) return
        run { this.text(value) }
    }

    // Telefonun ses tuşları TV'nin sesini kontrol eder
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (state == ConnState.CONNECTED &&
            (code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN)
        ) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                key(if (code == KeyEvent.KEYCODE_VOLUME_UP) Keys.VOL_UP else Keys.VOL_DOWN)
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        Thread { adb.close(); atv.close(); io.close() }.start()
    }
}

data class UiState(
    val state: ConnState,
    val message: String,
    val tvName: String,
    val fastMode: Boolean,
    val apps: List<TvApp>,
    val volume: Int?,
    val volumeMax: Int,
    val volumeTick: Int,
    val method: ConnMethod,
    val pairing: Boolean,
    val pairingError: String
)
