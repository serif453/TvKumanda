package com.serif.tvkumanda

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Araçlar sekmesindeki tek bir araç; [id] doluysa işlevi hazırdır. */
private data class Tool(
    val name: String,
    val icon: ImageVector,
    val adb: Boolean = false,
    val experimental: Boolean = false,
    val warning: Boolean = false,
    val id: ToolId? = null
)

private data class ToolGroup(val title: String, val tools: List<Tool>)

private val toolGroups = listOf(
    ToolGroup("Sistem", listOf(
        Tool("Sistem bilgisi", Icons.Outlined.Info, adb = true, id = ToolId.SYSTEM_INFO),
        Tool("RAM boşalt", Icons.Outlined.Memory, adb = true, id = ToolId.FREE_RAM),
        Tool("Önbellek temizle", Icons.Outlined.CleaningServices, adb = true, id = ToolId.CLEAR_CACHE),
        Tool("Yeniden başlat", Icons.Outlined.RestartAlt, adb = true, id = ToolId.REBOOT)
    )),
    ToolGroup("Uygulamalar", listOf(
        Tool("Uygulama yöneticisi", Icons.Outlined.Apps, adb = true, id = ToolId.APP_MANAGER),
        Tool("Gereksiz uygulamaları kapat", Icons.Outlined.Block, adb = true, warning = true),
        Tool("APK yükle", Icons.Outlined.InstallMobile, adb = true, id = ToolId.APK_INSTALL),
        Tool("Uygulama yedekle", Icons.Outlined.Backup, adb = true, id = ToolId.APP_BACKUP)
    )),
    ToolGroup("Dosyalar", listOf(
        Tool("Dosya gönder", Icons.Outlined.UploadFile, adb = true, id = ToolId.SEND_FILES),
        Tool("TV dosya gezgini", Icons.Outlined.FolderOpen, adb = true, id = ToolId.FILE_BROWSER),
        // Dosya gezginindeki "▶ TV" düğmesiyle çalışır; kutucuk gezgini açar
        Tool("TV oynatıcısında aç", Icons.Outlined.OndemandVideo, adb = true, experimental = true, id = ToolId.FILE_BROWSER)
    )),
    ToolGroup("Ekran", listOf(
        Tool("Ekran görüntüsü", Icons.Outlined.Screenshot, adb = true, id = ToolId.SCREENSHOT),
        Tool("Ekran kaydı", Icons.Outlined.Videocam, adb = true, experimental = true),
        Tool("Animasyon hızı", Icons.Outlined.Animation, adb = true, warning = true, id = ToolId.ANIMATION)
    )),
    ToolGroup("Gelişmiş", listOf(
        Tool("Özel DNS", Icons.Outlined.Dns, adb = true, warning = true),
        Tool("Ana ekran değiştir", Icons.Outlined.Home, adb = true, warning = true),
        Tool("ADB komut satırı", Icons.Outlined.Terminal, adb = true),
        Tool("Sistem kayıtları", Icons.Outlined.Article, adb = true)
    )),
    ToolGroup("Zamanlama ve güç", listOf(
        Tool("Uyku zamanlayıcısı", Icons.Outlined.Bedtime),
        Tool("Zamanlı açma", Icons.Outlined.Alarm),
        Tool("Ekran süresi sınırı", Icons.Outlined.HourglassBottom),
        Tool("Ses sınırı", Icons.Outlined.VolumeDown)
    )),
    ToolGroup("İçerik açma", listOf(
        Tool("Bağlantıyı TV'de aç", Icons.Outlined.Link, id = ToolId.OPEN_LINK),
        Tool("Panodan yaz", Icons.Outlined.ContentPaste, id = ToolId.CLIPBOARD)
    )),
    ToolGroup("Gelişmiş tuşlar", listOf(
        Tool("Medya tuşları", Icons.Outlined.QueueMusic, id = ToolId.MEDIA_KEYS),
        Tool("Sistem kısayolları", Icons.Outlined.Keyboard, id = ToolId.SYSTEM_KEYS)
    )),
    ToolGroup("Ağ ve test", listOf(
        Tool("Ağ tarayıcı", Icons.Outlined.Radar),
        Tool("Bağlantı testi", Icons.Outlined.NetworkCheck),
        Tool("Tüm TV'lere komut", Icons.Outlined.Devices),
        Tool("Teşhis kaydını paylaş", Icons.Outlined.BugReport)
    )),
    ToolGroup("Deneysel", listOf(
        Tool("Ekran yansıtma", Icons.Outlined.ScreenShare, experimental = true),
        Tool("Telefonu TV hoparlörüne bağla", Icons.Outlined.Speaker, experimental = true)
    ))
)

/** Araçlar sekmesi: konu gruplarına ayrılmış, satırda üç kutucuk. İşlevler sonra eklenecek. */
@Composable
fun ToolsScreen(method: ConnMethod, connected: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val adbReady = method == ConnMethod.ADB && connected
    var open by remember { mutableStateOf<Tool?>(null) }
    open?.let { t -> ToolSheet(t.id!!, t.name, t.icon, adbReady) { open = null } }
    // Bağlantı koparsa açık araç penceresi kapanır
    LaunchedEffect(adbReady, connected) {
        open?.let { t -> if (!connected || (t.adb && !adbReady)) open = null }
        if (!adbReady) ToolPages.page = null
    }
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        Spacer(Modifier.height(10.dp))
        Text("Araçlar", color = Pal.Text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(8.dp).clip(CircleShape)
                    .background(if (adbReady) Pal.Green else if (connected) Pal.Amber else Pal.Sub)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                when {
                    adbReady -> "ADB bağlı · tüm araçlar kullanılabilir"
                    connected -> "Standart bağlantı · ADB gerektiren araçlar soluk görünür"
                    else -> "TV bağlı değil"
                },
                color = Pal.Sub, fontSize = 13.sp
            )
        }

        // İlerleme: hazır araç sayısı (hepsi hazır olunca kendiliğinden kaybolur)
        val all = toolGroups.flatMap { it.tools }
        val ready = all.count { it.id != null }
        if (ready < all.size) {
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("$ready / ${all.size} araç hazır", color = Pal.Sub, fontSize = 13.sp)
                Spacer(Modifier.width(10.dp))
                Box(Modifier.weight(1f).height(4.dp).clip(RoundedCornerShape(2.dp)).background(Pal.Btn)) {
                    Box(Modifier.fillMaxWidth(ready.toFloat() / all.size).fillMaxHeight().clip(RoundedCornerShape(2.dp)).background(Pal.Blue))
                }
            }
        }

        for (group in toolGroups) {
            Spacer(Modifier.height(20.dp))
            Text(group.title, color = Pal.Sub, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            for (row in group.tools.chunked(3)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    for (tool in row) {
                        val dimmed = tool.adb && !adbReady && connected
                        ToolTile(tool, dimmed, Modifier.weight(1f)) {
                            val msg = when {
                                tool.adb && method != ConnMethod.ADB -> "Bu araç ADB bağlantısı gerektirir"
                                tool.id == null -> "Yakında"
                                !connected -> "Önce TV'ye bağlan"
                                else -> null
                            }
                            if (msg != null) Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                            else if (tool.id == ToolId.APP_MANAGER || tool.id == ToolId.FILE_BROWSER) ToolPages.page = tool.id
                            else open = tool
                        }
                    }
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
                Spacer(Modifier.height(10.dp))
            }
        }
        // Son satır alt menünün arkasında kalmasın
        Spacer(Modifier.height(BarSpace))
    }
}

@Composable
private fun ToolTile(tool: Tool, dimmed: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .height(116.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(Pal.Btn)
            .clickable(onClick = onClick)
            .alpha(if (dimmed) 0.4f else 1f)
    ) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 6.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(tool.icon, tool.name, tint = Pal.Text, modifier = Modifier.size(28.dp))
            Spacer(Modifier.height(8.dp))
            Text(
                tool.name, color = Pal.Text, fontSize = 12.sp, lineHeight = 14.sp,
                textAlign = TextAlign.Center, maxLines = 2
            )
            // İşlevi henüz eklenmemiş araçlar; işlev gelince kendiliğinden kalkar
            if (tool.id == null) {
                Spacer(Modifier.height(5.dp))
                Text(
                    "Yakında", color = Pal.Sub, fontSize = 9.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(Color.White.copy(alpha = 0.07f))
                        .padding(horizontal = 6.dp, vertical = 1.dp)
                )
            }
        }
        // Rozetler: sol üstte ADB, sağ üstte deneysel / uyarı
        if (tool.adb) Badge("ADB", Pal.Blue, Modifier.align(Alignment.TopStart))
        when {
            tool.experimental -> Badge("deneysel", Pal.Purple, Modifier.align(Alignment.TopEnd))
            tool.warning -> Text("⚠", color = Pal.Amber, fontSize = 13.sp, modifier = Modifier.align(Alignment.TopEnd).padding(6.dp))
        }
    }
}

@Composable
private fun Badge(text: String, color: Color, modifier: Modifier) {
    Text(
        text, color = color, fontSize = 9.sp, fontWeight = FontWeight.Bold,
        modifier = modifier.padding(6.dp).clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.18f)).padding(horizontal = 5.dp, vertical = 1.dp)
    )
}
