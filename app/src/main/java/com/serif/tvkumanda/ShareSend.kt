package com.serif.tvkumanda

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Paylaş menüsündeki "TV'ye gönder": her türlü dosya TV'nin Download klasörüne gider (ADB gerekir).
 * Paylaşım gelince [ShareSend.job] dolar; pencere açılır, TV'ye bağlanınca gönderim kendiliğinden başlar.
 */
object ShareSend {
    var job by mutableStateOf<SendJob?>(null)
    /** Standart bağlantıdayken "ADB ile bağlan": bağlantı türünü değiştirip aynı TV'ye yeniden bağlanır (MainActivity kurar). */
    var useAdb: () -> Unit = {}
    /** APK için seçim yapılınca işi başlatır (MainActivity kurar). */
    var start: () -> Unit = {}
    /** Kurulan uygulamayı TV'de açar (MainActivity kurar). */
    var openApp: (String) -> Unit = {}

    /** Dosya adı ya da türü APK paketi mi (.apk, parçalı .apks / .xapk / .apkm). */
    fun isApk(c: android.content.Context, u: Uri): Boolean {
        if (c.contentResolver.getType(u) == "application/vnd.android.package-archive") return true
        val n = runCatching { TvFiles.displayName(c, u) }.getOrDefault("").lowercase()
        return n.endsWith(".apk") || n.endsWith(".apks") || n.endsWith(".xapk") || n.endsWith(".apkm")
    }
}

enum class SendPhase { CHOOSE, WAITING, NEED_ADB, SENDING, DONE, FAILED }

/** [apk]: paylaşılan dosyaların hepsi APK ise önce "TV'ye kur / Download'a gönder" seçimi sorulur. */
class SendJob(val uris: List<Uri>, val apk: Boolean = false) {
    var phase by mutableStateOf(if (apk) SendPhase.CHOOSE else SendPhase.WAITING)
    /** true: TV'ye kurulur, false: Download klasörüne gönderilir. */
    var install by mutableStateOf(false)
    /** Kurulumdan sonra "TV'de aç" için paket adı. */
    var installedPkg by mutableStateOf<String?>(null)
    var stage by mutableStateOf("")
    var progress by mutableFloatStateOf(0f)
    var message by mutableStateOf("")
    val cancel = TvFiles.Cancel()
    val busy get() = phase == SendPhase.SENDING
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareSendSheet(job: SendJob, connState: ConnState, onShowInBrowser: () -> Unit) {
    // Gönderim sürerken pencere yanlışlıkla kaydırılıp kapanmasın (durdurmak için "Durdur")
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { it != SheetValue.Hidden || !job.busy }
    )
    fun close() { if (!job.busy) ShareSend.job = null }
    ModalBottomSheet(onDismissRequest = ::close, sheetState = sheetState, containerColor = Color(0xFF242426)) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).clip(CircleShape).background(Pal.Btn), contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.UploadFile, null, tint = Pal.Text, modifier = Modifier.size(24.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(if (job.install) "TV'ye kur" else "TV'ye gönder", color = Pal.Text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text(when {
                        job.phase == SendPhase.CHOOSE -> if (job.uris.size > 1) "${job.uris.size} APK" else "APK dosyası"
                        job.install -> if (job.uris.size > 1) "${job.uris.size} APK · TV'ye kurulacak" else "APK · TV'ye kurulacak"
                        else -> "${job.uris.size} dosya · TV'nin Download klasörüne"
                    }, color = Pal.Sub, fontSize = 13.sp)
                }
            }
            Spacer(Modifier.height(18.dp))
            when (job.phase) {
                SendPhase.CHOOSE -> {
                    Text(if (job.uris.size > 1) "Bunlar uygulama paketi. TV'ye kurabilir ya da sadece Download klasörüne gönderebilirsin."
                        else "Bu bir uygulama paketi. TV'ye kurabilir ya da sadece Download klasörüne gönderebilirsin.",
                        color = Pal.Text, fontSize = 14.sp)
                    Spacer(Modifier.height(14.dp))
                    MainButton(if (job.uris.size > 1) "Hepsini TV'ye kur" else "TV'ye kur") {
                        job.install = true; job.phase = SendPhase.WAITING; ShareSend.start()
                    }
                    Spacer(Modifier.height(10.dp))
                    SecondaryButton("Download'a gönder", Modifier.fillMaxWidth()) {
                        job.install = false; job.phase = SendPhase.WAITING; ShareSend.start()
                    }
                    Spacer(Modifier.height(10.dp))
                    SecondaryButton("Vazgeç", Modifier.fillMaxWidth(), onClick = ::close)
                }
                SendPhase.WAITING -> {
                    if (connState == ConnState.CONNECTING) Loading("TV'ye bağlanılıyor…")
                    else Text(if (job.install) "Önce TV'ye bağlan; bağlanınca kurulum kendiliğinden başlar." else "Önce TV'ye bağlan; bağlanınca gönderim kendiliğinden başlar.", color = Pal.Sub, fontSize = 14.sp)
                    Spacer(Modifier.height(14.dp))
                    SecondaryButton("Vazgeç", Modifier.fillMaxWidth(), onClick = ::close)
                }
                SendPhase.NEED_ADB -> {
                    Text("${if (job.install) "Uygulama kurmak" else "Dosya göndermek"} için ADB bağlantısı gerekir. Standart bağlantı (Google'ın kumanda yolu) dosya taşıyamaz.",
                        color = Pal.Text, fontSize = 14.sp)
                    Spacer(Modifier.height(14.dp))
                    MainButton("ADB ile bağlan") { ShareSend.useAdb() }
                    Spacer(Modifier.height(10.dp))
                    SecondaryButton("Vazgeç", Modifier.fillMaxWidth(), onClick = ::close)
                }
                SendPhase.SENDING -> {
                    Progress(job.stage, job.progress)
                    Spacer(Modifier.height(14.dp))
                    MainButton(if (job.cancel.cancelled) "Durduruluyor…" else "Durdur", enabled = !job.cancel.cancelled, danger = true) {
                        job.cancel.cancelled = true
                        job.stage = "Durduruluyor…"
                    }
                }
                SendPhase.DONE -> {
                    ResultLine(ToolResult(true, job.message))
                    Spacer(Modifier.height(14.dp))
                    if (job.install) {
                        job.installedPkg?.let { pkg -> MainButton("TV'de aç") { ShareSend.openApp(pkg); ShareSend.job = null } }
                    } else MainButton("TV dosya gezgininde göster", onClick = onShowInBrowser)
                    Spacer(Modifier.height(10.dp))
                    SecondaryButton("Kapat", Modifier.fillMaxWidth(), onClick = ::close)
                }
                SendPhase.FAILED -> {
                    ResultLine(ToolResult(false, job.message))
                    Spacer(Modifier.height(14.dp))
                    SecondaryButton("Kapat", Modifier.fillMaxWidth(), onClick = ::close)
                }
            }
        }
    }
}
