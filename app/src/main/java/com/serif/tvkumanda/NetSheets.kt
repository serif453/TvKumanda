package com.serif.tvkumanda

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.io.File
import java.net.Inet4Address
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ---------------- Telefonun ağ durumu ----------------

/** Telefonun hangi ağlara bağlı olduğu (Wi-Fi, VPN, mobil veri) ve Wi-Fi adresi. */
internal object PhoneNet {
    data class Info(val wifi: Boolean, val vpn: Boolean, val cellular: Boolean, val ip: String?, val prefix: Int?)

    fun read(context: Context): Info {
        val cm = context.applicationContext.getSystemService(ConnectivityManager::class.java)
            ?: return Info(false, false, false, null, null)
        var wifi = false
        var vpn = false
        var cellular = false
        var ip: String? = null
        var prefix: Int? = null
        @Suppress("DEPRECATION")
        val networks = runCatching { cm.allNetworks.toList() }.getOrDefault(emptyList())
        for (n in networks) {
            val caps = cm.getNetworkCapabilities(n) ?: continue
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) { vpn = true; continue }
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) cellular = true
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                wifi = true
                val addr = cm.getLinkProperties(n)?.linkAddresses?.firstOrNull { it.address is Inet4Address }
                if (addr != null && ip == null) {
                    ip = addr.address.hostAddress
                    prefix = addr.prefixLength
                }
            }
        }
        return Info(wifi, vpn, cellular, ip, prefix)
    }

    fun label(i: Info): String = buildList {
        if (i.wifi) add("Wi-Fi")
        if (i.cellular) add("mobil veri")
        if (i.vpn) add("VPN açık")
    }.joinToString(" · ").ifBlank { "ağ yok" }
}

// ---------------- Bağlantı testi ----------------

@Composable
internal fun ConnTestTool() {
    val context = LocalContext.current
    val run = rememberRunner()
    val saved = remember { TvTools.savedTvs() }
    var ip by remember { mutableStateOf(TvTools.currentIp().ifBlank { saved.firstOrNull()?.ip.orEmpty() }) }
    var busy by remember { mutableStateOf(false) }
    var step by remember { mutableStateOf("") }
    var report by remember { mutableStateOf<ConnCheck.Report?>(null) }
    var result by remember { mutableStateOf<ToolResult?>(null) }

    fun start() {
        val target = ip.trim()
        if (!ConnCheck.isIpv4(target)) { result = ToolResult(false, "Geçerli bir IP adresi değil (ör. 192.168.1.50)"); return }
        busy = true; report = null; result = null
        run {
            step = "Telefonun ağı okunuyor…"
            val net = io { PhoneNet.read(context) }.getOrNull() ?: PhoneNet.Info(false, false, false, null, null)
            step = "Kumanda kapısı deneniyor…"
            val remote = io { ConnCheck.probe(target, ConnCheck.PORT_REMOTE) }.getOrNull() ?: ConnCheck.Probe(ConnCheck.Port.TIMEOUT)
            step = "Eşleştirme kapısı deneniyor…"
            val pairing = io { ConnCheck.probe(target, ConnCheck.PORT_PAIRING) }.getOrNull() ?: ConnCheck.Probe(ConnCheck.Port.TIMEOUT)
            step = "ADB kapısı deneniyor…"
            val adb = io { ConnCheck.probe(target, ConnCheck.PORT_ADB) }.getOrNull() ?: ConnCheck.Probe(ConnCheck.Port.TIMEOUT)
            // Gecikme: açık bir kapıya birkaç kez daha bağlanılır
            val openPort = listOf(ConnCheck.PORT_REMOTE to remote, ConnCheck.PORT_ADB to adb, ConnCheck.PORT_PAIRING to pairing)
                .firstOrNull { it.second.state == ConnCheck.Port.OPEN }?.first
            val latencies = mutableListOf<Long>()
            if (openPort != null) {
                step = "Gecikme ölçülüyor…"
                repeat(5) {
                    io { ConnCheck.probe(target, openPort) }.getOrNull()
                        ?.takeIf { it.state == ConnCheck.Port.OPEN }?.let { latencies += it.ms }
                    delay(150)
                }
            }
            val r = ConnCheck.evaluate(
                ConnCheck.Input(
                    target, net.wifi, net.vpn, net.cellular, net.ip, net.prefix,
                    remote, pairing, adb, latencies,
                    paired = TvTools.isPaired(target), adbMode = TvTools.isAdb()
                )
            )
            DiagLog.log(ConnCheck.summary(target, r))
            report = r
            busy = false
            step = ""
        }
    }

    Column(Modifier.verticalScroll(rememberScrollState())) {
        Text(
            "TV'ye neden bağlanılamadığını adım adım bulur. TV'ye bağlı olmasan da çalışır.",
            color = Pal.Sub, fontSize = 13.sp
        )
        Spacer(Modifier.height(14.dp))
        if (saved.isNotEmpty()) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (tv in saved) {
                    val sel = tv.ip == ip.trim()
                    Text(
                        tv.display, color = if (sel) Color.White else Pal.Text, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                        modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(if (sel) Pal.Blue else Pal.Btn)
                            .clickable(enabled = !busy) { ip = tv.ip; report = null; result = null }
                            .padding(horizontal = 14.dp, vertical = 8.dp)
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
        }
        OutlinedTextField(
            value = ip, onValueChange = { ip = it; report = null; result = null }, singleLine = true, enabled = !busy,
            placeholder = { Text("TV'nin IP adresi (ör. 192.168.1.50)", color = Pal.Sub) },
            shape = RoundedCornerShape(16.dp),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Pal.Text, unfocusedTextColor = Pal.Text, disabledTextColor = Pal.Sub,
                focusedBorderColor = Pal.Blue, unfocusedBorderColor = Pal.Sub.copy(alpha = 0.5f), cursorColor = Pal.Blue
            ),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(16.dp))
        MainButton(
            if (busy) step.ifBlank { "Test ediliyor…" } else if (report == null) "Testi başlat" else "Yeniden test et",
            enabled = !busy && ip.isNotBlank()
        ) { start() }
        ResultLine(result)

        report?.let { r ->
            Spacer(Modifier.height(16.dp))
            val (icon, color) = levelLook(r.level)
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(color.copy(alpha = 0.14f)).padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(icon, null, tint = color, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(10.dp))
                Text(r.headline, color = Pal.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(10.dp))
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Pal.Btn).padding(horizontal = 14.dp, vertical = 4.dp)) {
                r.rows.forEach { row ->
                    val (ri, rc) = levelLook(row.level)
                    Row(Modifier.fillMaxWidth().padding(vertical = 9.dp)) {
                        Icon(ri, null, tint = rc, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(row.title, color = Pal.Text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            if (row.detail.isNotBlank()) {
                                Spacer(Modifier.height(2.dp))
                                Text(row.detail, color = Pal.Sub, fontSize = 12.sp, lineHeight = 16.sp)
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Text("Sonuç teşhis kaydına da yazıldı.", color = Pal.Sub, fontSize = 12.sp)
        }
    }
}

private fun levelLook(level: ConnCheck.Level) = when (level) {
    ConnCheck.Level.OK -> Icons.Outlined.CheckCircle to Pal.Green
    ConnCheck.Level.WARN -> Icons.Outlined.WarningAmber to Pal.Amber
    ConnCheck.Level.FAIL -> Icons.Outlined.ErrorOutline to Pal.Red
    ConnCheck.Level.INFO -> Icons.Outlined.Info to Pal.Sub
}

// ---------------- Teşhis kaydı ----------------

private fun diagHeader(context: Context): List<Pair<String, String>> {
    val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?"
    val net = runCatching { PhoneNet.label(PhoneNet.read(context)) }.getOrDefault("?")
    return listOf(
        "Uygulama" to "TV Kumanda $version",
        "Telefon" to "${Build.MANUFACTURER} ${Build.MODEL}",
        "Android" to "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
        "Telefonun ağı" to net
    ) + TvTools.diagInfo()
}

@Composable
internal fun DiagLogTool() {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var mask by remember { mutableStateOf(true) }
    var tick by remember { mutableStateOf(0) }
    var clearArmed by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<ToolResult?>(null) }
    val events = remember(tick) { DiagLog.snapshot() }
    val header = remember(tick) { diagHeader(context) }
    LaunchedEffect(clearArmed) { if (clearArmed) { delay(4000); clearArmed = false } }

    fun shown(s: String) = if (mask) DiagLog.maskIps(s) else s
    fun reportText() = DiagLog.report(header, events, mask)

    // Pencere küçük ekranlarda taşmasın diye kaydırılabilir
    Column(Modifier.verticalScroll(rememberScrollState())) {
        Column(Modifier.clip(RoundedCornerShape(16.dp)).background(Pal.Btn).padding(horizontal = 14.dp, vertical = 6.dp)) {
            header.forEach { (k, v) ->
                Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                    Text(k, color = Pal.Sub, fontSize = 12.sp, modifier = Modifier.width(110.dp))
                    Text(shown(v), color = Pal.Text, fontSize = 12.sp)
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Son olaylar (${events.size})", color = Pal.Sub, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text("IP'leri gizle", color = Pal.Sub, fontSize = 12.sp)
            Spacer(Modifier.width(8.dp))
            Switch(
                checked = mask, onCheckedChange = { mask = it },
                colors = SwitchDefaults.colors(checkedTrackColor = Pal.Blue, checkedThumbColor = Color.White, uncheckedTrackColor = Pal.Bg, uncheckedThumbColor = Pal.Sub)
            )
        }
        Spacer(Modifier.height(6.dp))
        val scroll = rememberScrollState()
        LaunchedEffect(events, scroll.maxValue) { scroll.scrollTo(scroll.maxValue) }   // en yeni olay altta görünür
        Box(
            Modifier.fillMaxWidth().heightIn(min = 70.dp, max = 230.dp).clip(RoundedCornerShape(14.dp))
                .background(Color.Black.copy(alpha = 0.35f)).padding(10.dp)
        ) {
            if (events.isEmpty()) {
                Text("Henüz kayıt yok. Bağlanma, Cast ve araç hataları burada birikir.", color = Pal.Sub, fontSize = 12.sp)
            } else {
                Text(
                    shown(events.joinToString("\n")), color = Pal.Text, fontSize = 11.sp, lineHeight = 15.sp,
                    fontFamily = FontFamily.Monospace, modifier = Modifier.verticalScroll(scroll)
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text("Kayıt yalnızca bu telefonda durur; sen paylaşmadıkça hiçbir yere gönderilmez.", color = Pal.Sub, fontSize = 12.sp)
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SecondaryButton("Kopyala", Modifier.weight(1f)) {
                clipboard.setText(AnnotatedString(reportText()))
                Toast.makeText(context, "Kopyalandı", Toast.LENGTH_SHORT).show()
            }
            SecondaryButton(if (clearArmed) "Emin misin?" else "Temizle", Modifier.weight(1f), enabled = events.isNotEmpty()) {
                if (clearArmed) {
                    clearArmed = false
                    DiagLog.clear()
                    tick++
                    result = ToolResult(true, "Kayıt temizlendi")
                } else clearArmed = true
            }
        }
        Spacer(Modifier.height(10.dp))
        MainButton("Paylaş") {
            runCatching {
                val dir = File(context.cacheDir, "diag").apply { mkdirs() }
                dir.listFiles()?.forEach { it.delete() }
                val name = "TvKumanda-teshis-${SimpleDateFormat("yyyyMMdd-HHmm", Locale.ROOT).format(Date())}.txt"
                val f = File(dir, name).apply { writeText(reportText()) }
                val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.files", f)
                val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_STREAM, uri)
                    .putExtra(Intent.EXTRA_SUBJECT, "TV Kumanda teşhis kaydı")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                context.startActivity(Intent.createChooser(send, "Teşhis kaydını paylaş"))
            }.onFailure { result = ToolResult(false, errorText(it)) }
        }
        ResultLine(result)
    }
}
