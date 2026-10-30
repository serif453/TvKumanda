package com.serif.tvkumanda

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay

/**
 * "TV'ler" penceresi: kayıtlı TV'ler (her TV tek kart, ADB / Standart düğmeleriyle),
 * ağda bulunanlar (pencere açılınca aranır), IP ile ekleme ve yardım.
 */
@Composable
fun TvSheet(
    initialIp: String,
    ui: UiState,
    onConnect: (String) -> Unit,
    onMethod: (ConnMethod) -> Unit,
    onPickSaved: (SavedTv) -> Unit,
    onForget: (SavedTv) -> Unit,
    onRename: (SavedTv, String) -> Unit,
    onDisconnect: () -> Unit,
    onWake: (SavedTv) -> Unit
) {
    val context = LocalContext.current
    val discovery = remember { TvDiscovery(context) }
    var found by remember { mutableStateOf<List<FoundTv>>(emptyList()) }
    var scanTick by remember { mutableIntStateOf(0) }
    var scanning by remember { mutableStateOf(true) }
    LaunchedEffect(scanTick) {
        scanning = true
        discovery.start { found = it }
        delay(12_000)
        discovery.stop()
        scanning = false
    }
    DisposableEffect(Unit) { onDispose { discovery.stop() } }
    // Kayıtlı TV'ler açık mı (kısa bağlantı denemesi)
    var reachable by remember { mutableStateOf<Set<String>?>(null) }
    LaunchedEffect(ui.savedTvs.map { it.ip }, scanTick) {
        val ips = ui.savedTvs.map { it.ip }
        reachable = coroutineScope { ips.map { ip -> async(Dispatchers.IO) { if (TvPing.isOnline(ip)) ip else null } }.mapNotNull { it.await() }.toSet() }
    }
    val online = (reachable ?: emptySet()) + found.map { it.ip }
    var help by remember { mutableStateOf(false) }
    var manual by remember { mutableStateOf(ui.savedTvs.isEmpty() && initialIp.isBlank()) }

    Column(Modifier.verticalScroll(rememberScrollState())) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("TV'ler", color = Pal.Text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text("TV ve telefon aynı Wi-Fi ağında olmalı.", color = Navy.Sub, fontSize = 13.sp)
            }
            Box(Modifier.size(44.dp).clip(CircleShape).clickable { help = true }, contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.HelpOutline, "Yardım", tint = Navy.Sub, modifier = Modifier.size(24.dp))
            }
        }

        // ---- Kayıtlı TV'ler ----
        if (ui.savedTvs.isNotEmpty()) {
            SectionLabel("KAYITLI TV'LER")
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                for (tv in ui.savedTvs) {
                    SavedTvCard(
                        tv, ui,
                        online = when { reachable == null && tv.ip !in online -> null; else -> tv.ip in online },
                        onConnectWith = { m -> onPickSaved(tv.copy(method = m)) },
                        onRename = { onRename(tv, it) }, onForget = { onForget(tv) },
                        onDisconnect = onDisconnect, onWake = { onWake(tv) }
                    )
                }
            }
        }

        // ---- Ağda bulunan TV'ler ----
        SectionLabel("AĞDA BULUNANLAR")
        val newOnes = found.filter { f -> ui.savedTvs.none { it.ip == f.ip } }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            for (f in newOnes) FoundCard(f, ui.state != ConnState.CONNECTING) { m -> onMethod(m); onConnect(f.ip) }
        }
        Spacer(Modifier.height(if (newOnes.isEmpty()) 0.dp else 8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (scanning) {
                CircularProgressIndicator(Modifier.size(16.dp), color = Navy.Sub, strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text("Aranıyor…", color = Navy.Sub, fontSize = 13.sp)
            } else {
                Text(if (newOnes.isEmpty()) "Yeni TV bulunamadı" else "${newOnes.size} yeni TV bulundu", color = Navy.Sub, fontSize = 13.sp, modifier = Modifier.weight(1f))
                Row(
                    Modifier.clip(RoundedCornerShape(12.dp)).background(Navy.Btn).clickable { scanTick++ }.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Outlined.Refresh, null, tint = Navy.Accent, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Tekrar ara", color = Navy.Accent, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                }
            }
        }

        // ---- IP ile ekle ----
        Spacer(Modifier.height(18.dp))
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { manual = !manual }.padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("IP adresiyle ekle", color = Pal.Text, fontSize = 15.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            Icon(if (manual) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, null, tint = Navy.Sub)
        }
        if (manual) ManualAdd(initialIp, ui) { ip, m -> onMethod(m); onConnect(ip) }

        // ---- Durum ----
        if (ui.state == ConnState.ERROR || ui.state == ConnState.CONNECTING) {
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(ui.state)
                Spacer(Modifier.width(8.dp))
                Text(ui.message, color = if (ui.state == ConnState.ERROR) Pal.Red else Pal.Amber, fontSize = 13.sp)
            }
        }

        // ---- Yardım satırı ----
        Spacer(Modifier.height(16.dp))
        Text(
            "ADB ve Standart nedir? TV'de ADB nasıl açılır?",
            color = Navy.Accent, fontSize = 13.sp, fontWeight = FontWeight.Medium,
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { help = true }.padding(vertical = 6.dp)
        )
        val version = remember { runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() }
        if (version != null) {
            Spacer(Modifier.height(10.dp))
            Text("TV Kumanda · Sürüm $version", color = Navy.Sub, fontSize = 12.sp)
        }
    }
    if (help) HelpDialog { help = false }
}

@Composable
private fun SectionLabel(text: String) {
    Spacer(Modifier.height(18.dp))
    Text(text, color = Navy.Sub, fontSize = 12.sp, letterSpacing = 1.sp, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun SavedTvCard(
    tv: SavedTv, ui: UiState, online: Boolean?,
    onConnectWith: (ConnMethod) -> Unit, onRename: (String) -> Unit, onForget: () -> Unit,
    onDisconnect: () -> Unit, onWake: () -> Unit
) {
    val connectedHere = ui.state == ConnState.CONNECTED && ui.message == tv.ip
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var forgetAsk by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(18.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(Navy.Btn)
            .border(1.dp, if (connectedHere) Navy.Accent else Navy.Border, shape)
            .padding(start = 14.dp, end = 4.dp, top = 12.dp, bottom = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(AppIcons.Tv, null, tint = if (connectedHere) Navy.Accent else Pal.Text, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(tv.display, color = Pal.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    if (tv.nick.isNotBlank()) "${tv.name.removePrefix("TPV ")} · ${tv.ip}" else tv.ip,
                    color = Navy.Sub, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val (color, text) = when {
                        connectedHere -> Pal.Green to "Bağlı · ${if (ui.method == ConnMethod.ADB) "ADB" else "Standart"}"
                        online == true -> Navy.Sub to "Ağda açık"
                        online == false -> Pal.Red.copy(alpha = 0.8f) to "Kapalı ya da bulunamadı"
                        else -> Navy.Sub to "Kontrol ediliyor…"
                    }
                    Box(Modifier.size(7.dp).clip(CircleShape).background(color))
                    Spacer(Modifier.width(6.dp))
                    Text(text, color = color, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                }
            }
            Box {
                Box(Modifier.size(40.dp).clip(CircleShape).clickable { menu = true }, contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.MoreVert, "Diğer", tint = Navy.Sub)
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = Navy.Btn) {
                    DropdownMenuItem(text = { Text("Yeniden adlandır", color = Pal.Text) }, onClick = { menu = false; renaming = true })
                    if (connectedHere) DropdownMenuItem(text = { Text("Bağlantıyı kes", color = Pal.Text) }, onClick = { menu = false; onDisconnect() })
                    DropdownMenuItem(text = { Text("Unut", color = Pal.Red) }, onClick = { menu = false; forgetAsk = true })
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.padding(end = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((m, label) in listOf(ConnMethod.ADB to "ADB", ConnMethod.STANDARD to "Standart")) {
                val active = connectedHere && ui.method == m
                MethodChip(label, active, Modifier.weight(1f), enabled = ui.state != ConnState.CONNECTING && !active) { onConnectWith(m) }
            }
            if (online == false && tv.mac.isNotBlank()) {
                Row(
                    Modifier.clip(RoundedCornerShape(12.dp)).background(Navy.AccentSoft).clickable(onClick = onWake).padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Outlined.PowerSettingsNew, null, tint = Color(0xFFAFC0FF), modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Uyandır", color = Color(0xFFAFC0FF), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
    if (renaming) RenameDialog(tv.display, tv.name.removePrefix("TPV ")) { n -> renaming = false; if (n != null) onRename(n) }
    if (forgetAsk) AlertDialog(
        onDismissRequest = { forgetAsk = false },
        containerColor = Navy.Card,
        title = { Text("TV unutulsun mu?", color = Pal.Text) },
        text = { Text("\"${tv.display}\" kayıtlı TV'lerden kaldırılır. İstediğinde yeniden ekleyebilirsin.", color = Navy.Sub) },
        confirmButton = { TextButton(onClick = { forgetAsk = false; onForget() }) { Text("Unut", color = Pal.Red) } },
        dismissButton = { TextButton(onClick = { forgetAsk = false }) { Text("Vazgeç", color = Navy.Sub) } }
    )
}

@Composable
private fun MethodChip(label: String, active: Boolean, modifier: Modifier, enabled: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier.height(40.dp).clip(shape)
            .then(if (active) Modifier.background(RingBrush) else Modifier.background(Navy.Card).border(1.dp, Navy.Border, shape))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            if (active) "$label · bağlı" else "$label ile bağlan",
            color = if (active) Color.White else Pal.Text, fontSize = 13.sp, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium
        )
    }
}

@Composable
private fun FoundCard(f: FoundTv, enabled: Boolean, onConnect: (ConnMethod) -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(Navy.Btn).border(1.dp, Navy.Border, shape).padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(AppIcons.Tv, null, tint = Navy.Accent, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(f.name.removePrefix("TPV "), color = Pal.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(f.ip, color = Navy.Sub, fontSize = 12.sp)
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MethodChip("ADB", false, Modifier.weight(1f), enabled) { onConnect(ConnMethod.ADB) }
            MethodChip("Standart", false, Modifier.weight(1f), enabled) { onConnect(ConnMethod.STANDARD) }
        }
    }
}

@Composable
private fun ManualAdd(initialIp: String, ui: UiState, onConnect: (String, ConnMethod) -> Unit) {
    var ip by remember { mutableStateOf(initialIp) }
    var m by remember { mutableStateOf(ui.method) }
    Column(Modifier.clip(RoundedCornerShape(18.dp)).background(Navy.Btn).border(1.dp, Navy.Border, RoundedCornerShape(18.dp)).padding(14.dp)) {
        OutlinedTextField(
            value = ip, onValueChange = { ip = it }, singleLine = true,
            placeholder = { Text("192.168.1.50", color = Navy.Sub) },
            shape = RoundedCornerShape(14.dp),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { if (ip.isNotBlank()) onConnect(ip.trim(), m) }),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Pal.Text, unfocusedTextColor = Pal.Text,
                focusedBorderColor = Navy.Accent, unfocusedBorderColor = Navy.Border, cursorColor = Navy.Accent
            ),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth().height(40.dp).clip(RoundedCornerShape(12.dp)).background(Navy.Card).padding(3.dp)) {
            for ((mm, label) in listOf(ConnMethod.ADB to "ADB", ConnMethod.STANDARD to "Standart")) {
                val sel = mm == m
                Box(
                    Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(10.dp))
                        .then(if (sel) Modifier.background(RingBrush) else Modifier).clickable { m = mm },
                    contentAlignment = Alignment.Center
                ) { Text(label, color = if (sel) Color.White else Navy.Sub, fontSize = 14.sp, fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal) }
            }
        }
        Spacer(Modifier.height(10.dp))
        Box(
            Modifier.fillMaxWidth().height(46.dp).clip(RoundedCornerShape(23.dp)).background(RingBrush)
                .clickable(enabled = ip.isNotBlank() && ui.state != ConnState.CONNECTING) { onConnect(ip.trim(), m) },
            contentAlignment = Alignment.Center
        ) { Text(if (ui.state == ConnState.CONNECTING) "Bağlanıyor…" else "Bağlan", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
    }
}

@Composable
private fun RenameDialog(current: String, model: String, onDone: (String?) -> Unit) {
    var text by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = { onDone(null) },
        containerColor = Navy.Card,
        title = { Text("TV'nin adı", color = Pal.Text) },
        text = {
            Column {
                Text("Kumandada bu ad görünür, örneğin \"Salon TV\". Boş bırakırsan model adı kullanılır.", color = Navy.Sub, fontSize = 13.sp)
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = text, onValueChange = { text = it.take(30) }, singleLine = true,
                    placeholder = { Text(model, color = Navy.Sub) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Pal.Text, unfocusedTextColor = Pal.Text,
                        focusedBorderColor = Navy.Accent, unfocusedBorderColor = Navy.Border, cursorColor = Navy.Accent
                    )
                )
            }
        },
        confirmButton = { TextButton(onClick = { onDone(if (text.trim() == model) "" else text) }) { Text("Kaydet", color = Navy.Accent) } },
        dismissButton = { TextButton(onClick = { onDone(null) }) { Text("Vazgeç", color = Navy.Sub) } }
    )
}

@Composable
private fun HelpDialog(onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        containerColor = Navy.Card,
        title = { Text("Bağlantı türleri", color = Pal.Text) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                HelpBlock(
                    "Standart",
                    "Google'ın TV kumandası bağlantısı. TV'de hiçbir ayar gerekmez; ilk bağlanmada TV ekranında çıkan kodu yazarsın. " +
                        "Tuşlar, ses, yazma ve sesli arama çalışır. Uygulama listesi, fare, Araçlar ve bazı tuşlar çalışmaz."
                )
                HelpBlock(
                    "ADB",
                    "Geliştirici bağlantısı. Her şey çalışır: uygulama listesi, fare, Kaynak tuşu, Araçlar'ın hepsi, dosya gönderme. " +
                        "TV'de bir kez açman gerekir."
                )
                Text("TV'de ADB nasıl açılır?", color = Pal.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                listOf(
                    "TV'de Ayarlar → Cihaz tercihleri → Hakkında'ya gir.",
                    "\"Derleme\" (Build) satırına art arda 7 kez bas. \"Artık geliştiricisiniz\" yazar.",
                    "Bir geri dön; Cihaz tercihleri'nde \"Geliştirici seçenekleri\" çıkar.",
                    "Orada \"USB hata ayıklama\" ya da \"ADB hata ayıklama\"yı aç.",
                    "Uygulamada TV'ye ADB ile bağlan; TV'de çıkan izin sorusunda \"Her zaman izin ver\"i seçip Tamam'a bas."
                ).forEachIndexed { i, t ->
                    Row(Modifier.padding(vertical = 3.dp)) {
                        Text("${i + 1}.", color = Navy.Accent, fontSize = 13.sp, modifier = Modifier.width(20.dp))
                        Text(t, color = Navy.Sub, fontSize = 13.sp)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text("Menü adları markaya göre biraz farklı olabilir.", color = Navy.Sub, fontSize = 12.sp)
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Tamam", color = Navy.Accent) } }
    )
}

@Composable
private fun HelpBlock(title: String, text: String) {
    Text(title, color = Pal.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(4.dp))
    Text(text, color = Navy.Sub, fontSize = 13.sp)
    Spacer(Modifier.height(14.dp))
}
