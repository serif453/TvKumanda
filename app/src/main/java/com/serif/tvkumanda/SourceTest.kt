package com.serif.tvkumanda

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * GEÇİCİ: Kaynak tuşunun Philips'te neden bazen çalışmadığını bulmak için test penceresi.
 * Sorun çözülünce bu dosya ve bağlantıları kaldırılacak.
 */
object SourceTest {
    /** ADB bağlı mı (Standart değil). */
    var isAdb: () -> Boolean = { false }
    /** Yolu dener; kısa sonuç metni döner. Arka planda çağrılır. */
    var tryWay: (Int) -> String = { "Bağlı değil" }
    /** TV'deki son tuş olayları ve giriş cihazları. Arka planda çağrılır. */
    var report: () -> String = { "Bağlı değil" }
}

@Composable
fun SourceTestDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val adb = remember { SourceTest.isAdb() }
    var reportText by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    val ways = if (adb) listOf(
        1 to "Yol 1 · Yardımcı (şu anki)",
        2 to "Yol 2 · Yedek yol",
        3 to "Yol 3 · Basılı tutup bırak",
        4 to "Yol 4 · Kumandadan geliyormuş gibi"
    ) else listOf(
        5 to "Standart · Kısa basış",
        6 to "Standart · Basılı tutup bırak"
    )

    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().clip18().background(Color(0xFF242426)).padding(18.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text("Kaynak tuşu testi", color = Pal.Text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(
                "Her yolu birkaç kez dene; kaynak listesini her seferinde açanı bana söyle.",
                color = Pal.Sub, fontSize = 13.sp
            )
            Spacer(Modifier.height(12.dp))
            for ((id, label) in ways) {
                TestButton(label, enabled = !busy) {
                    busy = true
                    scope.launch {
                        val r = withContext(Dispatchers.IO) { runCatching { SourceTest.tryWay(id) }.getOrElse { "Hata: ${it.message}" } }
                        busy = false
                        Toast.makeText(context, r, Toast.LENGTH_SHORT).show()
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
            if (adb) {
                Spacer(Modifier.height(8.dp))
                Text("Son basılan tuşlar", color = Pal.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Önce TV'nin kendi kumandasında SOURCES'a bas, sonra aşağıya dokun.",
                    color = Pal.Sub, fontSize = 13.sp
                )
                Spacer(Modifier.height(8.dp))
                TestButton("Son basılan tuşları oku", enabled = !busy) {
                    busy = true
                    scope.launch {
                        reportText = withContext(Dispatchers.IO) { runCatching { SourceTest.report() }.getOrElse { "Hata: ${it.message}" } }
                        busy = false
                    }
                }
                if (reportText.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    SelectionContainer {
                        Text(
                            reportText, color = Pal.Text, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                            modifier = Modifier.fillMaxWidth().clip18().background(Color(0xFF1A1A1C)).padding(10.dp)
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    TestButton("Kopyala", enabled = true) {
                        clipboard.setText(AnnotatedString(reportText))
                        Toast.makeText(context, "Kopyalandı", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            TestButton("Kapat", enabled = true, onClick = onDismiss)
        }
    }
}

private fun Modifier.clip18() = this.then(Modifier.clip(RoundedCornerShape(18.dp)))

@Composable
private fun TestButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().clip18().background(if (enabled) Pal.Btn else Pal.Bg)
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Text(label, color = if (enabled) Pal.Text else Pal.Sub, fontSize = 14.sp)
    }
}

/** `dumpsys input` çıktısından son tuş olaylarını ve giriş cihazlarını kısaltır. */
fun summarizeInputDump(dump: String): String {
    val lines = dump.lines()
    val sb = StringBuilder()
    sb.append("Son tuş olayları:\n")
    val i = lines.indexOfFirst { it.trim().startsWith("RecentQueue") }
    if (i >= 0) {
        lines.drop(i + 1).takeWhile { it.contains("Event(") || it.isBlank() }.filter { it.contains("KeyEvent") }
            .takeLast(12).forEach { sb.append(it.trim().take(240)).append('\n') }
    } else sb.append("(bulunamadı)\n")
    sb.append("\nGiriş cihazları:\n")
    findInputDevices(dump).forEach { (id, name, src) -> sb.append("$id · $name · kaynak=0x${Integer.toHexString(src)}\n") }
    return sb.toString().trim()
}

/** Input Reader bölümündeki cihazlar: (kimlik, ad, kaynak bitleri). */
fun findInputDevices(dump: String): List<Triple<Int, String, Int>> {
    val out = ArrayList<Triple<Int, String, Int>>()
    val lines = dump.lines()
    val reader = lines.indexOfFirst { it.contains("Input Reader State") }.takeIf { it >= 0 } ?: 0
    var idx = reader
    while (idx < lines.size) {
        val m = Regex("""^\s*Device (-?\d+): (.+)$""").find(lines[idx])
        if (m != null) {
            val id = m.groupValues[1].toInt()
            val name = m.groupValues[2].trim()
            var src = 0
            for (j in idx + 1 until minOf(idx + 12, lines.size)) {
                Regex("""Sources:\s*0x([0-9a-fA-F]+)""").find(lines[j])?.let { src = it.groupValues[1].toLong(16).toInt() }
                if (src != 0) break
            }
            out += Triple(id, name, src)
        }
        if (idx > reader && lines[idx].contains("Input Dispatcher State")) break
        idx++
    }
    return out
}
