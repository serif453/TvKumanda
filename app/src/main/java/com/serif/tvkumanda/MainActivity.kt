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

    private lateinit var remote: TvRemote
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
        remote = TvRemote(applicationContext)
        val prefs = getSharedPreferences("ayarlar", Context.MODE_PRIVATE)
        val savedIp = prefs.getString("ip", "") ?: ""

        setContent {
            RemoteTheme {
                RemoteScreen(
                    ui = UiState(state, message, tvName, fastMode, apps, volume, volumeMax, volumeTick),
                    initialIp = savedIp,
                    onConnect = { ip ->
                        prefs.edit().putString("ip", ip).apply()
                        connect(ip)
                    },
                    onKey = ::key,
                    onText = ::sendText,
                    onLaunch = { app -> send(remote.launchCommand(app)) }
                )
            }
        }

        if (savedIp.isNotBlank()) connect(savedIp)
    }

    private fun connect(ip: String) {
        if (ip.isBlank()) { state = ConnState.ERROR; message = "IP adresi gir"; return }
        state = ConnState.CONNECTING
        message = "Bağlanıyor… TV'de izin isterse onayla"
        scope.launch {
            try {
                val model = withContext(io) { remote.connect(ip) }
                tvName = model
                fastMode = remote.isFast
                state = ConnState.CONNECTED
                message = ip
                loadApps()
                refreshVolume(0)
            } catch (e: Exception) {
                state = ConnState.ERROR
                message = "Bağlanamadı: ${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    private fun loadApps() {
        scope.launch {
            apps = withContext(Dispatchers.IO) { runCatching { remote.listApps() }.getOrDefault(emptyList()) }
        }
    }

    /** Ses değişince kısa bir bekleme sonrası TV'den gerçek ses seviyesini okur. */
    private fun refreshVolume(delayMs: Long = 350) {
        volumeJob?.cancel()
        volumeJob = scope.launch {
            delay(delayMs)
            val v = withContext(Dispatchers.IO) { runCatching { remote.volume() }.getOrNull() }
            if (v != null) { volume = v.first; volumeMax = v.second }
        }
    }

    /** Bağlantı üzerinde arka planda iş çalıştırır. */
    private fun run(action: TvRemote.() -> Unit) {
        if (state != ConnState.CONNECTED) { message = "Önce TV'ye bağlan"; return }
        scope.launch {
            try {
                withContext(io) { remote.action() }
                fastMode = remote.isFast
            } catch (e: Exception) {
                state = ConnState.ERROR
                fastMode = false
                message = "Bağlantı koptu: ${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    private fun send(command: String) {
        run { this.shell(command) }
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
        Thread { remote.close(); io.close() }.start()
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
    val volumeTick: Int
)
