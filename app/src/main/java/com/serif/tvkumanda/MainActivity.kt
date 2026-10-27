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
    const val PREV = 88; const val PLAY_PAUSE = 85; const val NEXT = 87
    const val DEL = 67; const val ENTER = 66
}

enum class ConnState { IDLE, CONNECTING, CONNECTED, ERROR }

class MainActivity : ComponentActivity() {

    private lateinit var remote: TvRemote
    // Komutlar sırayla gitsin diye tek iş parçacığı
    private val io = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val scope = MainScope()

    private var state by mutableStateOf(ConnState.IDLE)
    private var message by mutableStateOf("Bağlı değil")
    private var tvName by mutableStateOf("")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        remote = TvRemote(filesDir)
        val prefs = getSharedPreferences("ayarlar", Context.MODE_PRIVATE)
        val savedIp = prefs.getString("ip", "") ?: ""

        setContent {
            RemoteTheme {
                RemoteScreen(
                    initialIp = savedIp,
                    state = state,
                    message = message,
                    tvName = tvName,
                    onConnect = { ip ->
                        prefs.edit().putString("ip", ip).apply()
                        connect(ip)
                    },
                    onKey = ::key,
                    onText = ::sendText,
                    onCommand = ::send
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
                state = ConnState.CONNECTED
                message = ip
            } catch (e: Exception) {
                state = ConnState.ERROR
                message = "Bağlanamadı: ${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    private fun send(command: String) {
        if (state != ConnState.CONNECTED) { message = "Önce TV'ye bağlan"; return }
        scope.launch {
            try {
                withContext(io) { remote.shell(command) }
            } catch (e: Exception) {
                state = ConnState.ERROR
                message = "Bağlantı koptu: ${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    private fun key(code: Int) = send("input keyevent $code")

    private fun sendText(text: String) {
        if (text.isEmpty()) return
        send(buildTextCommand(text))
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

/** "input text" komutu: Türkçe harfleri sadeleştirir, boşluk ve tırnakları kaçırır. */
fun buildTextCommand(text: String): String {
    val tr = mapOf(
        'ı' to 'i', 'İ' to 'I', 'ğ' to 'g', 'Ğ' to 'G', 'ü' to 'u', 'Ü' to 'U',
        'ş' to 's', 'Ş' to 'S', 'ö' to 'o', 'Ö' to 'O', 'ç' to 'c', 'Ç' to 'C'
    )
    val sb = StringBuilder()
    for (raw in text) {
        when (val c = tr[raw] ?: raw) {
            ' ' -> sb.append("%s")
            '\'' -> sb.append("'\\''")
            else -> sb.append(c)
        }
    }
    return "input text '$sb'"
}

fun launchApp(pkg: String) =
    "monkey -p $pkg -c android.intent.category.LEANBACK_LAUNCHER 1"
