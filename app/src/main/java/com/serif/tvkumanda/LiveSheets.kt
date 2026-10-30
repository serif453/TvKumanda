package com.serif.tvkumanda

import android.Manifest
import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import java.io.File

// ====================================================================================
//  Ekran kaydı (TV ekranı → video), Ekran yansıtma (telefon ekranı → TV), Hoparlör (telefon sesi → TV)
// ====================================================================================

// ---------------- Ekran kaydı ----------------

private enum class RecPhase { CHECKING, IDLE, RECORDING, ON_TV, PULLING, SAVED }

@Composable
internal fun ScreenRecTool() {
    val context = LocalContext.current
    val run = rememberRunner()
    var phase by remember { mutableStateOf(RecPhase.CHECKING) }
    var seconds by remember { mutableStateOf(60) }
    var startedAt by remember { mutableStateOf(0L) }   // 0: bu oturumda başlatılmadı (önceden sürüyor)
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    var tvBytes by remember { mutableStateOf(0L) }
    var file by remember { mutableStateOf<File?>(null) }
    var busy by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<ToolResult?>(null) }

    fun pull() {
        phase = RecPhase.PULLING; result = null; saved = false
        run {
            val dir = File(context.cacheDir, "rec").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val f = File(dir, ScreenRec.fileName(System.currentTimeMillis()))
            io { ScreenRec.pull(f) }
                .onSuccess { file = f; phase = RecPhase.SAVED }
                .onFailure { result = ToolResult(false, errorText(it)); phase = RecPhase.IDLE }
        }
    }

    // Açılışta TV'de süren ya da bitmiş ama alınmamış kayıt var mı
    LaunchedEffect(Unit) {
        io { ScreenRec.status() }
            .onSuccess { st ->
                tvBytes = st.bytes
                phase = when {
                    st.running -> RecPhase.RECORDING
                    st.bytes > 0 -> RecPhase.ON_TV
                    else -> RecPhase.IDLE
                }
            }
            .onFailure { result = ToolResult(false, errorText(it)); phase = RecPhase.IDLE }
    }
    // Kayıt sürerken sayaç ve TV'yi yoklama; komut kendiliğinden bittiyse video telefona alınır
    LaunchedEffect(phase) {
        if (phase != RecPhase.RECORDING) return@LaunchedEffect
        var tick = 0
        while (true) {
            delay(1000)
            now = System.currentTimeMillis()
            if (++tick % 2 == 0) {
                val st = io { ScreenRec.status() }.getOrNull() ?: continue
                tvBytes = st.bytes
                if (!st.running) {
                    if (st.bytes > 0) pull() else { result = ToolResult(false, "Kayıt oluşmadı"); phase = RecPhase.IDLE }
                    break
                }
            }
        }
    }

    when (phase) {
        RecPhase.CHECKING -> Loading("TV'ye bakılıyor…")
        RecPhase.IDLE -> {
            Text("Ne kadar kaydedilsin?", color = Pal.Sub, fontSize = 13.sp)
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Pal.Btn).padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                for (s in ScreenRec.DURATIONS) {
                    val sel = seconds == s
                    Box(
                        Modifier.weight(1f).height(44.dp).clip(RoundedCornerShape(12.dp))
                            .background(if (sel) Pal.Blue else Color.Transparent)
                            .clickable { seconds = s },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(if (s < 60) "$s sn" else "${s / 60} dk", color = if (sel) Color.White else Pal.Text, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "Ses kaydedilmez. Android tek kaydı en fazla 3 dakikayla sınırlar. Korumalı uygulamalarda (Netflix vb.) görüntü siyah çıkar. " +
                    "Süre dolmadan Durdur'a basarsan o ana kadarki kayıt alınır.",
                color = Pal.Sub, fontSize = 12.sp
            )
            Spacer(Modifier.height(16.dp))
            MainButton(if (busy) "Başlatılıyor…" else "Kaydı başlat", enabled = !busy) {
                busy = true; result = null
                run {
                    io { ScreenRec.start(seconds) }
                        .onSuccess { startedAt = System.currentTimeMillis(); now = startedAt; phase = RecPhase.RECORDING }
                        .onFailure { result = ToolResult(false, errorText(it)) }
                    busy = false
                }
            }
        }
        RecPhase.RECORDING -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(Pal.Red))
                Spacer(Modifier.width(8.dp))
                val el = if (startedAt > 0) ((now - startedAt) / 1000).coerceAtLeast(0) else -1L
                Text(
                    if (el >= 0) "Kaydediliyor · %d:%02d".format(el / 60, el % 60) else "TV'de bir kayıt sürüyor",
                    color = Pal.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold
                )
            }
            Spacer(Modifier.height(16.dp))
            MainButton(if (busy) "Bitiriliyor…" else "Durdur ve telefona al", enabled = !busy, danger = true) {
                busy = true
                run {
                    io { ScreenRec.stop() }
                        .onSuccess { pull() }
                        .onFailure { result = ToolResult(false, errorText(it)) }
                    busy = false
                }
            }
        }
        RecPhase.ON_TV -> {
            Text("TV'de alınmamış bir kayıt var (${mb(tvBytes)}).", color = Pal.Text, fontSize = 14.sp)
            Spacer(Modifier.height(14.dp))
            MainButton("Telefona al") { pull() }
            Spacer(Modifier.height(10.dp))
            SecondaryButton("Sil", Modifier.fillMaxWidth()) {
                run {
                    io { ScreenRec.discard() }
                    tvBytes = 0; phase = RecPhase.IDLE
                }
            }
        }
        RecPhase.PULLING -> Loading("Video telefona alınıyor…")
        RecPhase.SAVED -> {
            val f = file
            Text("Kayıt hazır${f?.let { " · ${mb(it.length())}" }.orEmpty()}", color = Pal.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SecondaryButton(if (saved) "Kaydedildi" else "Galeriye kaydet", Modifier.weight(1f), enabled = !saved) {
                    val ff = f ?: return@SecondaryButton
                    runCatching { saveVideoToGallery(context, ff) }
                        .onSuccess { saved = true; result = ToolResult(true, "Galeride \"TV Kumanda\" klasörüne kaydedildi") }
                        .onFailure { result = ToolResult(false, errorText(it)) }
                }
                SecondaryButton("Paylaş", Modifier.weight(1f)) {
                    val ff = f ?: return@SecondaryButton
                    runCatching {
                        val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.files", ff)
                        val send = Intent(Intent.ACTION_SEND).setType("video/mp4").putExtra(Intent.EXTRA_STREAM, uri)
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        context.startActivity(Intent.createChooser(send, "Kaydı paylaş"))
                    }.onFailure { result = ToolResult(false, errorText(it)) }
                }
            }
            Spacer(Modifier.height(10.dp))
            MainButton("Yeni kayıt") { phase = RecPhase.IDLE; result = null }
        }
    }
    ResultLine(result)
}

private fun mb(bytes: Long): String = if (bytes < 1024 * 1024) "${bytes / 1024} KB" else "%.1f MB".format(bytes / 1048576.0)

private fun saveVideoToGallery(context: Context, f: File) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) throw IllegalStateException("Kaydetme Android 10 ve üstünde çalışır; Paylaş'ı kullan")
    val values = ContentValues().apply {
        put(MediaStore.Video.Media.DISPLAY_NAME, f.name)
        put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
        put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/TV Kumanda")
    }
    val resolver = context.contentResolver
    val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: throw java.io.IOException("Galeriye yazılamadı")
    resolver.openOutputStream(uri)!!.use { out -> f.inputStream().use { it.copyTo(out) } }
}

// ---------------- TV'ye gönderme ----------------

internal object LiveSend {
    @Volatile private var lastDevice: DlnaDevice? = null

    /** Yayın adresini TV'de açar: önce DLNA oynatıcısı, olmazsa TV'nin kendi oynatıcısı. Hangi yolun işlediğini söyler. */
    fun toTv(context: Context, url: String, screen: Boolean): String {
        val ip = TvTools.currentIp()
        val mime = if (screen) "video/mp2t" else "audio/aac"
        val title = if (screen) "Telefon ekranı" else "Telefonun sesi"
        val dlna = DlnaClient(context.applicationContext)
        val dev = runCatching { dlna.discover(2500) }.getOrDefault(emptyList()).firstOrNull { it.ip == ip }
        if (dev != null) {
            val r = runCatching { dlna.load(dev, url, mime, title, if (screen) MediaKind.VIDEO else MediaKind.MUSIC) }
            if (r.isSuccess) { lastDevice = dev; return "TV'ye DLNA ile gönderildi" }
            DiagLog.log("Canlı yayın: DLNA gönderimi olmadı (${r.exceptionOrNull()?.message.orEmpty()})")
        }
        TvTools.openStream(url, mime)
        return "TV'deki oynatıcıda açıldı"
    }

    /** Yayın bitince TV'deki DLNA oynatıcısını da durdurmayı dener (hata verirse sessizce geçer). */
    fun stopTv(context: Context) {
        val dev = lastDevice ?: return
        lastDevice = null
        runCatching { DlnaClient(context.applicationContext).stop(dev) }
    }
}

// ---------------- Ekran yansıtma ve hoparlör ----------------

@Composable
internal fun LiveTool(screen: Boolean) {
    val context = LocalContext.current
    val run = rememberRunner()
    val clipboard = LocalClipboardManager.current
    var result by remember { mutableStateOf<ToolResult?>(null) }
    var sendResult by remember { mutableStateOf<ToolResult?>(null) }
    var sending by remember { mutableStateOf(false) }
    val phase = LiveStream.phase
    val mine = LiveStream.screen == screen
    val otherActive = LiveStream.active && !mine

    fun sendToTv() {
        run {
            sending = true; sendResult = null
            io { LiveSend.toTv(context, LiveStream.url, screen) }
                .onSuccess { sendResult = ToolResult(true, it) }
                .onFailure { sendResult = ToolResult(false, errorText(it)) }
            sending = false
        }
    }

    val projection = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val d = r.data
        if (r.resultCode == Activity.RESULT_OK && d != null) {
            LiveService.start(context.applicationContext, r.resultCode, d, screen, TvTools.currentIp())
        } else {
            result = ToolResult(false, "Ekran yakalama izni verilmedi")
        }
    }
    fun askProjection() {
        val mpm = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection.launch(mpm.createScreenCaptureIntent())
    }
    val mic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted && !screen) result = ToolResult(false, "Telefonun sesini yayınlamak için mikrofon izni gerekli")
        else askProjection()   // görüntülü yayında mikrofon izni verilmezse yayın sessiz devam eder
    }
    fun begin() {
        result = null; sendResult = null
        if (TvTools.currentIp().isBlank()) { result = ToolResult(false, "Önce TV'ye bağlan"); return }
        if (!screen && Build.VERSION.SDK_INT < 29) {
            result = ToolResult(false, "Telefon sesini yakalamak Android 10 ve üstünü ister"); return
        }
        val has = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (Build.VERSION.SDK_INT >= 29 && !has) mic.launch(Manifest.permission.RECORD_AUDIO) else askProjection()
    }
    fun stopAll() {
        run {
            io { LiveSend.stopTv(context) }
            LiveService.stop(context.applicationContext)
            sendResult = null
        }
    }

    // Yayın açılınca TV'ye bir kez kendiliğinden gönderilir
    LaunchedEffect(phase) {
        if (phase == LiveStream.Phase.LIVE && mine && !LiveStream.autoSent) {
            LiveStream.autoSent = true
            sendToTv()
        }
    }

    when {
        otherActive -> {
            Text(
                if (LiveStream.screen) "Şu an ekran yansıtma açık. Önce onu durdur." else "Şu an telefonun sesi yayınlanıyor. Önce onu durdur.",
                color = Pal.Text, fontSize = 14.sp
            )
            Spacer(Modifier.height(14.dp))
            MainButton("Yayını durdur", danger = true) { stopAll() }
        }
        mine && phase == LiveStream.Phase.STARTING -> Loading("Yayın hazırlanıyor…")
        mine && phase == LiveStream.Phase.LIVE -> {
            val watching = LiveStream.viewers > 0
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(if (watching) Pal.Green else Pal.Amber))
                Spacer(Modifier.width(8.dp))
                Text(
                    if (watching) "Yayın açık · TV izliyor" else "Yayın açık · TV bekleniyor",
                    color = Pal.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold
                )
            }
            Spacer(Modifier.height(6.dp))
            val details = buildList {
                if (screen && LiveStream.sizeText.isNotEmpty()) add("Görüntü ${LiveStream.sizeText}")
                if (screen) add(if (LiveStream.hasAudio) "ses var" else "ses yok")
            }.joinToString(" · ")
            if (details.isNotEmpty()) Text(details, color = Pal.Sub, fontSize = 12.sp)
            if (sending) Loading("TV'ye gönderiliyor…")
            ResultLine(sendResult)
            if (!watching && !sending) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "TV yayını açmazsa aşağıdaki adresi kopyalayıp TV'deki bir oynatıcıya (ör. VLC → ağ akışı aç) yapıştırabilirsin.",
                    color = Pal.Sub, fontSize = 12.sp
                )
            }
            Spacer(Modifier.height(12.dp))
            Text(LiveStream.url, color = Pal.Sub, fontSize = 12.sp)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SecondaryButton("Adresi kopyala", Modifier.weight(1f)) {
                    clipboard.setText(AnnotatedString(LiveStream.url))
                    result = ToolResult(true, "Adres kopyalandı")
                }
                SecondaryButton("TV'de yeniden aç", Modifier.weight(1f), enabled = !sending) { sendToTv() }
            }
            Spacer(Modifier.height(10.dp))
            MainButton("Yayını durdur", danger = true) { stopAll() }
        }
        else -> {
            Text(
                if (screen)
                    "Telefonun ekranı ve sesi TV'de görünür. TV'de 2-5 saniye gecikme olur: slayt, fotoğraf, harita ve uygulama göstermek için uygundur, " +
                        "oyun için değil. Korumalı uygulamalar (Netflix vb.) siyah çıkar. Telefonu dik tutmak en iyisidir."
                else
                    "Telefonda çalan müzik ve diğer sesler TV'nin hoparlöründen çalar. 2-5 saniye gecikme olur: müzik, podcast ve radyo için uygundur; " +
                        "telefonda video izlerken ses geriden gelir, onun için Cast sekmesini kullan. Bazı uygulamalar (ör. Spotify) sesinin yakalanmasına " +
                        "izin vermez, o zaman sessiz çıkar.",
                color = Pal.Sub, fontSize = 12.sp
            )
            if (mine && phase == LiveStream.Phase.ERROR && LiveStream.error != null) {
                ResultLine(ToolResult(false, "Yayın durdu: ${LiveStream.error}"))
            }
            Spacer(Modifier.height(16.dp))
            MainButton(if (screen) "Yansıtmayı başlat" else "Sesi TV'ye gönder") { begin() }
        }
    }
    ResultLine(result)
}
