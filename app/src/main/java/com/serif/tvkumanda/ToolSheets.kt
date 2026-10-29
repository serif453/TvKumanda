package com.serif.tvkumanda

import android.content.ContentValues
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Build
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** İşlevi hazır olan araçlar. */
enum class ToolId { SYSTEM_INFO, FREE_RAM, CLEAR_CACHE, REBOOT, SCREENSHOT, ANIMATION }

/** Pencere içindeki sonuç satırı. */
private data class ToolResult(val ok: Boolean, val text: String)

/** Araç penceresi: alttan açılır, üstte simge-ad-açıklama, ortada içerik, altta ana buton ve sonuç. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolSheet(id: ToolId, name: String, icon: ImageVector, onDismiss: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Color(0xFF242426)
    ) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).clip(CircleShape).background(Pal.Btn), contentAlignment = Alignment.Center) {
                    Icon(icon, null, tint = Pal.Text, modifier = Modifier.size(24.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(name, color = Pal.Text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text(description(id), color = Pal.Sub, fontSize = 13.sp)
                }
            }
            Spacer(Modifier.height(18.dp))
            when (id) {
                ToolId.SYSTEM_INFO -> SystemInfoTool()
                ToolId.FREE_RAM -> FreeRamTool()
                ToolId.CLEAR_CACHE -> ClearCacheTool()
                ToolId.REBOOT -> RebootTool(onDismiss)
                ToolId.SCREENSHOT -> ScreenshotTool()
                ToolId.ANIMATION -> AnimationTool()
            }
        }
    }
}

private fun description(id: ToolId) = when (id) {
    ToolId.SYSTEM_INFO -> "TV'nin donanım ve yazılım bilgileri"
    ToolId.FREE_RAM -> "Arka planda bekleyen uygulamaları kapatır"
    ToolId.CLEAR_CACHE -> "Uygulamaların geçici dosyalarını siler"
    ToolId.REBOOT -> "TV'yi kapatıp yeniden açar"
    ToolId.SCREENSHOT -> "TV ekranının görüntüsünü telefona alır"
    ToolId.ANIMATION -> "Menü geçişlerini hızlandırır"
}

/** Arka planda TV işi çalıştırmak için küçük yardımcı. */
@Composable
private fun rememberRunner(): (suspend () -> Unit) -> Unit {
    val scope = rememberCoroutineScope()
    return remember { { block -> scope.launch { block() } } }
}

private suspend fun <T> io(block: () -> T): Result<T> = withContext(Dispatchers.IO) { runCatching(block) }

private fun errorText(e: Throwable) = "Olmadı: ${e.message ?: e.javaClass.simpleName}"

// ---------------- Ortak parçalar ----------------

@Composable
private fun MainButton(text: String, enabled: Boolean = true, danger: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(26.dp))
            .background(if (!enabled) Pal.Btn else if (danger) Pal.Red else Pal.Blue)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = if (enabled) Color.White else Pal.Sub, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun SecondaryButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        modifier.height(48.dp).clip(RoundedCornerShape(24.dp)).background(Pal.Btn)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = if (enabled) Pal.Text else Pal.Sub, fontSize = 15.sp, fontWeight = FontWeight.Medium)
    }
}

/** ⚠ araçlar için: ilk dokunuşta "Emin misin?", ikincide işlem. 4 sn içinde dokunulmazsa geri döner. */
@Composable
private fun ConfirmButton(text: String, enabled: Boolean, onConfirm: () -> Unit) {
    var armed by remember { mutableStateOf(false) }
    LaunchedEffect(armed) { if (armed) { delay(4000); armed = false } }
    MainButton(if (armed) "Emin misin? Tekrar dokun" else text, enabled, danger = armed) {
        if (armed) { armed = false; onConfirm() } else armed = true
    }
}

@Composable
private fun ResultLine(r: ToolResult?) {
    if (r == null) return
    Spacer(Modifier.height(12.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            if (r.ok) Icons.Outlined.CheckCircle else Icons.Outlined.ErrorOutline, null,
            tint = if (r.ok) Pal.Green else Pal.Red, modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(8.dp))
        Text(r.text, color = if (r.ok) Pal.Text else Pal.Red, fontSize = 14.sp)
    }
}

@Composable
private fun Loading(text: String = "Okunuyor…") {
    Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(18.dp), color = Pal.Sub, strokeWidth = 2.dp)
        Spacer(Modifier.width(10.dp))
        Text(text, color = Pal.Sub, fontSize = 14.sp)
    }
}

/** Dolu/toplam çubuğu. */
@Composable
private fun UsageBar(label: String, used: Long, total: Long, text: String) {
    val f = if (total > 0) (used.toFloat() / total).coerceIn(0f, 1f) else 0f
    Column {
        Row {
            Text(label, color = Pal.Sub, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Text(text, color = Pal.Text, fontSize = 13.sp)
        }
        Spacer(Modifier.height(6.dp))
        Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(Pal.Btn)) {
            Box(
                Modifier.fillMaxWidth(f).fillMaxHeight().clip(RoundedCornerShape(4.dp))
                    .background(if (f > 0.85f) Pal.Amber else Pal.Blue)
            )
        }
    }
}

// ---------------- 1. Sistem bilgisi ----------------

@Composable
private fun SystemInfoTool() {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val info by produceState<Result<TvTools.SystemInfo>?>(null) { value = io { TvTools.systemInfo() } }
    val r = info
    when {
        r == null -> Loading()
        r.isFailure -> ResultLine(ToolResult(false, errorText(r.exceptionOrNull()!!)))
        else -> {
            val si = r.getOrThrow()
            si.mem?.let { UsageBar("RAM", it.usedKb, it.totalKb, "${TvTools.mb(it.usedKb)} / ${TvTools.mb(it.totalKb)}"); Spacer(Modifier.height(12.dp)) }
            si.disk?.let { UsageBar("Depolama", it.usedKb, it.totalKb, "${TvTools.gb(it.usedKb)} / ${TvTools.gb(it.totalKb)}"); Spacer(Modifier.height(14.dp)) }
            Column(Modifier.clip(RoundedCornerShape(16.dp)).background(Pal.Btn).padding(horizontal = 14.dp, vertical = 6.dp)) {
                si.rows.filter { it.first != "RAM" && it.first != "Depolama" }.forEach { (k, v) ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 7.dp)) {
                        Text(k, color = Pal.Sub, fontSize = 13.sp, modifier = Modifier.width(130.dp))
                        Text(v, color = Pal.Text, fontSize = 13.sp)
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            MainButton("Kopyala") {
                clipboard.setText(AnnotatedString(si.rows.joinToString("\n") { "${it.first}: ${it.second}" }))
                Toast.makeText(context, "Kopyalandı", Toast.LENGTH_SHORT).show()
            }
        }
    }
}

// ---------------- 2. RAM boşalt ----------------

@Composable
private fun FreeRamTool() {
    val run = rememberRunner()
    var mem by remember { mutableStateOf<TvTools.MemInfo?>(null) }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<ToolResult?>(null) }
    LaunchedEffect(Unit) {
        io { TvTools.memInfo() }.onSuccess { mem = it }.onFailure { result = ToolResult(false, errorText(it)) }
    }
    val m = mem
    if (m == null && result == null) Loading()
    m?.let { UsageBar("RAM", it.usedKb, it.totalKb, "${TvTools.mb(it.usedKb)} / ${TvTools.mb(it.totalKb)}") }
    Spacer(Modifier.height(16.dp))
    MainButton(if (busy) "Boşaltılıyor…" else "Boşalt", enabled = !busy && m != null) {
        busy = true; result = null
        run {
            io { TvTools.freeRam() }
                .onSuccess { (after, freed) ->
                    after?.let { mem = it }
                    result = ToolResult(true, if (freed > 0) "${TvTools.mb(freed)} boşaldı" else "Zaten boştu, boşaltılacak bir şey yoktu")
                }
                .onFailure { result = ToolResult(false, errorText(it)) }
            busy = false
        }
    }
    ResultLine(result)
}

// ---------------- 3. Önbellek temizle ----------------

@Composable
private fun ClearCacheTool() {
    val run = rememberRunner()
    var size by remember { mutableStateOf<Long?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<ToolResult?>(null) }
    LaunchedEffect(Unit) {
        io { TvTools.cacheSize() }.onSuccess { size = it }.onFailure { result = ToolResult(false, errorText(it)) }
        loaded = true
    }
    if (!loaded) Loading()
    else {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Pal.Btn).padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Toplam önbellek", color = Pal.Sub, fontSize = 14.sp, modifier = Modifier.weight(1f))
            Text(size?.let { "≈ ${TvTools.bytes(it)}" } ?: "Bilinmiyor", color = Pal.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(8.dp))
        Text("Girişlerin, ayarların ve kişisel veriler silinmez.", color = Pal.Sub, fontSize = 12.sp)
    }
    Spacer(Modifier.height(16.dp))
    MainButton(if (busy) "Temizleniyor…" else "Temizle", enabled = !busy && loaded) {
        busy = true; result = null
        run {
            io { TvTools.clearCache() }
                .onSuccess { freed ->
                    size = 0
                    result = ToolResult(true, if (freed > 0) "${TvTools.mb(freed)} temizlendi" else "Önbellek temizlendi")
                }
                .onFailure { result = ToolResult(false, errorText(it)) }
            busy = false
        }
    }
    ResultLine(result)
}

// ---------------- 4. Yeniden başlat ----------------

@Composable
private fun RebootTool(onDismiss: () -> Unit) {
    val run = rememberRunner()
    var busy by remember { mutableStateOf(false) }
    Text(
        "TV kapanıp açılır; bu yaklaşık bir dakika sürer. TV açılınca uygulama kendiliğinden yeniden bağlanır.",
        color = Pal.Sub, fontSize = 13.sp
    )
    Spacer(Modifier.height(16.dp))
    ConfirmButton(if (busy) "Gönderiliyor…" else "Yeniden başlat", enabled = !busy) {
        busy = true
        run {
            io { TvTools.reboot() }
            onDismiss()
        }
    }
}

// ---------------- 5. Ekran görüntüsü ----------------

@Composable
private fun ScreenshotTool() {
    val context = LocalContext.current
    val run = rememberRunner()
    var shot by remember { mutableStateOf<File?>(null) }
    var image by remember { mutableStateOf<ImageBitmap?>(null) }
    var busy by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<ToolResult?>(null) }

    fun capture() {
        busy = true; result = null; saved = false
        run {
            val dir = File(context.cacheDir, "shots").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val f = File(dir, "TV_${System.currentTimeMillis()}.png")
            io {
                TvTools.screenshot(f)
                BitmapFactory.decodeFile(f.absolutePath)?.asImageBitmap() ?: throw java.io.IOException("Görüntü açılamadı")
            }.onSuccess { shot = f; image = it }
                .onFailure { result = ToolResult(false, errorText(it)) }
            busy = false
        }
    }

    val img = image
    if (img != null) {
        Image(
            img, "Ekran görüntüsü",
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxWidth().aspectRatio(img.width.toFloat() / img.height).clip(RoundedCornerShape(14.dp))
        )
        Spacer(Modifier.height(8.dp))
        Text("Korumalı uygulamalarda (Netflix vb.) video kısmı siyah çıkar.", color = Pal.Sub, fontSize = 12.sp)
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SecondaryButton(if (saved) "Kaydedildi" else "Kaydet", Modifier.weight(1f), enabled = !saved) {
                val f = shot ?: return@SecondaryButton
                runCatching { saveToGallery(context, f) }
                    .onSuccess { saved = true; result = ToolResult(true, "Galeride \"TV Kumanda\" albümüne kaydedildi") }
                    .onFailure { result = ToolResult(false, errorText(it)) }
            }
            SecondaryButton("Paylaş", Modifier.weight(1f)) {
                val f = shot ?: return@SecondaryButton
                runCatching {
                    val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.files", f)
                    val send = Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM, uri)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    context.startActivity(Intent.createChooser(send, "Ekran görüntüsünü paylaş"))
                }.onFailure { result = ToolResult(false, errorText(it)) }
            }
        }
        Spacer(Modifier.height(10.dp))
    }
    MainButton(if (busy) "Çekiliyor…" else if (img == null) "Çek" else "Yeniden çek", enabled = !busy) { capture() }
    ResultLine(result)
}

private fun saveToGallery(context: android.content.Context, f: File) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) throw IllegalStateException("Kaydetme Android 10 ve üstünde çalışır; Paylaş'ı kullan")
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, f.name)
        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
        put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/TV Kumanda")
    }
    val resolver = context.contentResolver
    val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: throw java.io.IOException("Galeriye yazılamadı")
    resolver.openOutputStream(uri)!!.use { out -> f.inputStream().use { it.copyTo(out) } }
}

// ---------------- 6. Animasyon hızı ----------------

@Composable
private fun AnimationTool() {
    val run = rememberRunner()
    var current by remember { mutableStateOf<Float?>(null) }
    var choice by remember { mutableStateOf<Float?>(null) }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<ToolResult?>(null) }
    LaunchedEffect(Unit) {
        io { TvTools.animationScale() }.onSuccess { current = it; choice = it }.onFailure { result = ToolResult(false, errorText(it)) }
    }
    val options = listOf(0f to "Kapalı", 0.5f to "0.5x", 1f to "1x (varsayılan)")
    if (current == null && result == null) Loading()
    else {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Pal.Btn).padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            for ((v, label) in options) {
                val sel = choice == v
                Box(
                    Modifier.weight(1f).height(44.dp).clip(RoundedCornerShape(12.dp))
                        .background(if (sel) Pal.Blue else Color.Transparent)
                        .clickable { choice = v; result = null },
                    contentAlignment = Alignment.Center
                ) {
                    Text(label, color = if (sel) Color.White else Pal.Text, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text("Kapalı ya da 0.5x menüleri hızlandırır; 1x fabrika ayarıdır.", color = Pal.Sub, fontSize = 12.sp)
    }
    Spacer(Modifier.height(16.dp))
    val c = choice
    ConfirmButton(if (busy) "Uygulanıyor…" else "Uygula", enabled = !busy && c != null && c != current) {
        val v = c ?: return@ConfirmButton
        busy = true
        run {
            io { TvTools.setAnimationScale(v) }
                .onSuccess { current = v; result = ToolResult(true, "Animasyon hızı ${options.first { it.first == v }.second.substringBefore(" (")} yapıldı") }
                .onFailure { result = ToolResult(false, errorText(it)) }
            busy = false
        }
    }
    ResultLine(result)
}
