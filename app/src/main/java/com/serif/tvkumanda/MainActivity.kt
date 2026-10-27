package com.serif.tvkumanda

import android.content.Context
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.*
import java.util.concurrent.Executors

// Android tuş kodları
object Keys {
    const val UP = 19; const val DOWN = 20; const val LEFT = 21; const val RIGHT = 22
    const val OK = 23; const val BACK = 4; const val HOME = 3; const val MENU = 82
    const val VOL_UP = 24; const val VOL_DOWN = 25; const val MUTE = 164; const val POWER = 26
    const val PREV = 88; const val PLAY_PAUSE = 85; const val NEXT = 87
}

class MainActivity : ComponentActivity() {

    private lateinit var remote: TvRemote
    // Komutlar sırayla gitsin diye tek iş parçacığı
    private val io = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val scope = MainScope()

    private var status by mutableStateOf("Bağlı değil")
    private var connected by mutableStateOf(false)
    private var busy by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        remote = TvRemote(filesDir)
        val prefs = getSharedPreferences("ayarlar", Context.MODE_PRIVATE)
        val savedIp = prefs.getString("ip", "") ?: ""

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFF4FC3F7),
                    background = Color.Black,
                    surface = Color(0xFF121212),
                    surfaceVariant = Color(0xFF1E1E1E)
                )
            ) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    RemoteScreen(
                        initialIp = savedIp,
                        status = status,
                        connected = connected,
                        busy = busy,
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
        }

        if (savedIp.isNotBlank()) connect(savedIp)
    }

    private fun connect(ip: String) {
        if (ip.isBlank()) { status = "IP adresi gir"; return }
        busy = true
        status = "Bağlanıyor… TV'de izin isterse onayla"
        scope.launch {
            try {
                val model = withContext(io) { remote.connect(ip) }
                connected = true
                status = "Bağlandı: $model"
            } catch (e: Exception) {
                connected = false
                status = "Bağlanamadı: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                busy = false
            }
        }
    }

    private fun send(command: String) {
        if (!connected) { status = "Önce TV'ye bağlan"; return }
        scope.launch {
            try {
                withContext(io) { remote.shell(command) }
            } catch (e: Exception) {
                connected = false
                status = "Bağlantı koptu: ${e.message ?: e.javaClass.simpleName}"
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
        if (connected && (code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN)) {
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

private fun launchApp(pkg: String) =
    "monkey -p $pkg -c android.intent.category.LEANBACK_LAUNCHER 1"

@Composable
fun RemoteScreen(
    initialIp: String,
    status: String,
    connected: Boolean,
    busy: Boolean,
    onConnect: (String) -> Unit,
    onKey: (Int) -> Unit,
    onText: (String) -> Unit,
    onCommand: (String) -> Unit
) {
    var ip by remember { mutableStateOf(initialIp) }
    var text by remember { mutableStateOf("") }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Bağlantı
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = ip,
                onValueChange = { ip = it },
                label = { Text("TV IP adresi") },
                placeholder = { Text("192.168.1.50") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            Button(onClick = { onConnect(ip.trim()) }, enabled = !busy) {
                Text(if (busy) "…" else "Bağlan")
            }
        }
        Text(
            status,
            color = if (connected) Color(0xFF81C784) else Color(0xFFFFB74D),
            fontSize = 14.sp
        )

        // Üst sıra
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            RButton("⏻ Güç", Modifier.weight(1f), danger = true) { onKey(Keys.POWER) }
            RButton("Menü", Modifier.weight(1f)) { onKey(Keys.MENU) }
        }

        DPad(onKey)

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            RIcon(Icons.Filled.ArrowBack, "Geri", Modifier.weight(1f)) { onKey(Keys.BACK) }
            RIcon(Icons.Filled.Home, "Ana ekran", Modifier.weight(1f)) { onKey(Keys.HOME) }
        }

        // Ses
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            RButton("Ses −", Modifier.weight(1f)) { onKey(Keys.VOL_DOWN) }
            RButton("Sessiz", Modifier.weight(1f)) { onKey(Keys.MUTE) }
            RButton("Ses +", Modifier.weight(1f)) { onKey(Keys.VOL_UP) }
        }

        // Medya
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            RButton("⏮", Modifier.weight(1f)) { onKey(Keys.PREV) }
            RButton("⏯", Modifier.weight(1f)) { onKey(Keys.PLAY_PAUSE) }
            RButton("⏭", Modifier.weight(1f)) { onKey(Keys.NEXT) }
        }

        // Metin yazma
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("TV'ye yaz") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { onText(text); text = "" }),
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            Button(onClick = { onText(text); text = "" }) { Text("Gönder") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            RButton("⌫ Sil", Modifier.weight(1f)) { onKey(67) }
            RButton("Enter", Modifier.weight(1f)) { onKey(66) }
        }

        // Uygulamalar
        Text("Uygulamalar", fontWeight = FontWeight.Bold, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            RButton("YouTube", Modifier.weight(1f)) { onCommand(launchApp("com.google.android.youtube.tv")) }
            RButton("Netflix", Modifier.weight(1f)) { onCommand(launchApp("com.netflix.ninja")) }
            RButton("Ayarlar", Modifier.weight(1f)) { onCommand("am start -a android.settings.SETTINGS") }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
fun DPad(onKey: (Int) -> Unit) {
    val haptic = LocalHapticFeedback.current
    Box(
        Modifier
            .size(270.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        ArrowBtn(Icons.Filled.KeyboardArrowUp, Modifier.align(Alignment.TopCenter).padding(top = 6.dp)) { onKey(Keys.UP) }
        ArrowBtn(Icons.Filled.KeyboardArrowDown, Modifier.align(Alignment.BottomCenter).padding(bottom = 6.dp)) { onKey(Keys.DOWN) }
        ArrowBtn(Icons.Filled.KeyboardArrowLeft, Modifier.align(Alignment.CenterStart).padding(start = 6.dp)) { onKey(Keys.LEFT) }
        ArrowBtn(Icons.Filled.KeyboardArrowRight, Modifier.align(Alignment.CenterEnd).padding(end = 6.dp)) { onKey(Keys.RIGHT) }
        Box(
            Modifier
                .align(Alignment.Center)
                .size(104.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary)
                .clickable {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onKey(Keys.OK)
                },
            contentAlignment = Alignment.Center
        ) {
            Text("OK", color = Color.Black, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun ArrowBtn(icon: ImageVector, modifier: Modifier, onClick: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    IconButton(
        onClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); onClick() },
        modifier = modifier.size(76.dp)
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(52.dp), tint = Color.White)
    }
}

@Composable
fun RButton(label: String, modifier: Modifier = Modifier, danger: Boolean = false, onClick: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    FilledTonalButton(
        onClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); onClick() },
        modifier = modifier.height(56.dp),
        shape = RoundedCornerShape(16.dp),
        colors = if (danger) ButtonDefaults.filledTonalButtonColors(containerColor = Color(0xFF5D1F1F))
        else ButtonDefaults.filledTonalButtonColors()
    ) {
        Text(label, fontSize = 16.sp)
    }
}

@Composable
fun RIcon(icon: ImageVector, label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    FilledTonalButton(
        onClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); onClick() },
        modifier = modifier.height(56.dp),
        shape = RoundedCornerShape(16.dp)
    ) {
        Icon(icon, contentDescription = null)
        Spacer(Modifier.width(6.dp))
        Text(label, fontSize = 16.sp)
    }
}
