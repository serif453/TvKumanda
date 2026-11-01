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
 * TV panelinin büyük hâlindeki "yönet" bölümleri (Serif'in isteği, 1 Kasım 2026: alttan ayrı pencere yerine
 * panel aşağı ve yanlara doğru büyür): IR kumanda ekle, ağda bulunanlar (TV'ye bağlı değilken açılınca aranır),
 * IP ile ekleme, bağlantı durumu ve yardım. Kayıtlı TV'ler panelin üstündeki satırlarda ([TvPickerPanel]).
 */
@Composable
fun TvManageSections(
    initialIp: String,
    ui: UiState,
    onConnect: (String) -> Unit,
    onMethod: (ConnMethod) -> Unit,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val discovery = remember { TvDiscovery(context) }
    var found by remember { mutableStateOf<List<FoundTv>>(emptyList()) }
    var scanTick by remember { mutableIntStateOf(0) }
    // Bir TV'ye bağlıyken açılınca aranmaz; "Tekrar ara" elle arar (Serif'in isteği)
    val autoScan = remember { ui.state != ConnState.CONNECTED }
    var scanned by remember { mutableStateOf(autoScan) }
    var scanning by remember { mutableStateOf(autoScan) }
    LaunchedEffect(scanTick) {
        if (scanTick == 0 && !autoScan) return@LaunchedEffect
        scanned = true
        scanning = true
        discovery.start { found = it }
        delay(12_000)
        discovery.stop()
        scanning = false
    }
    DisposableEffect(Unit) { onDispose { discovery.stop() } }
    var help by remember { mutableStateOf(false) }
    var manual by remember { mutableStateOf(ui.savedTvs.isEmpty() && initialIp.isBlank()) }

    Column(Modifier.padding(horizontal = 10.dp)) {
        // ---- IR kumandalar (telefonda IR verici varsa) ----
        IrSection { id -> onClose(); IrUi.page = id }

        // ---- Ağda bulunan TV'ler ----
        SectionLabel("AĞDA BULUNANLAR")
        val newOnes = found.filter { f -> ui.savedTvs.none { it.ip == f.ip } }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            for (f in newOnes) FoundCard(f, ui.state != ConnState.CONNECTING) { m -> onMethod(m); onConnect(f.ip) }
        }
        Spacer(Modifier.height(if (newOnes.isEmpty()) 0.dp else 8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (scanning) {
                CircularProgressIndicator(Modifier.size(16.dp), color = Glass.Sub, strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text("Aranıyor…", color = Glass.Sub, fontSize = 13.sp)
            } else {
                // "Tekrar ara" solda (Serif'in isteği), sonuç yazısı sağında
                Row(
                    Modifier.clip(RoundedCornerShape(12.dp)).background(GlassSheet.Card).clickable { scanTick++ }.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Outlined.Refresh, null, tint = Glass.Lavender, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Tekrar ara", color = Glass.Lavender, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                }
                if (scanned) {
                    Spacer(Modifier.width(12.dp))
                    Text(
                        if (newOnes.isEmpty()) "Yeni TV bulunamadı" else "${newOnes.size} yeni TV bulundu",
                        color = Glass.Sub, fontSize = 13.sp
                    )
                }
            }
        }

        // ---- IP ile ekle ----
        Spacer(Modifier.height(18.dp))
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { manual = !manual }.padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("IP adresiyle ekle", color = Glass.Text, fontSize = 15.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            Icon(if (manual) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, null, tint = Glass.Sub)
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
        Spacer(Modifier.height(14.dp))
        Text(
            "ADB ve Standart nedir? TV'de ADB nasıl açılır?",
            color = Glass.Lavender, fontSize = 13.sp, fontWeight = FontWeight.Medium,
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { help = true }.padding(vertical = 6.dp)
        )
        val version = remember { runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() }
        if (version != null) {
            Spacer(Modifier.height(8.dp))
            Text("TV Kumanda · Sürüm $version", color = Glass.Sub, fontSize = 12.sp)
        }
        Spacer(Modifier.height(8.dp))
    }
    if (help) HelpDialog { help = false }
}

/** Kayıtlı TV satırının ⋮ düğmesi: Yeniden adlandır · Bağlantıyı kes · Unut (unutmak onay ister). */
@Composable
fun TvRowMenu(tv: SavedTv, connectedHere: Boolean, onRename: (String) -> Unit, onForget: () -> Unit, onDisconnect: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var forgetAsk by remember { mutableStateOf(false) }
    Box {
        Box(Modifier.size(36.dp).clip(CircleShape).clickable { menu = true }, contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.MoreVert, "Diğer", tint = Glass.Sub)
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = GlassSheet.Menu) {
            DropdownMenuItem(text = { Text("Yeniden adlandır", color = Glass.Text) }, onClick = { menu = false; renaming = true })
            if (connectedHere) DropdownMenuItem(text = { Text("Bağlantıyı kes", color = Glass.Text) }, onClick = { menu = false; onDisconnect() })
            DropdownMenuItem(text = { Text("Unut", color = Pal.Red) }, onClick = { menu = false; forgetAsk = true })
        }
    }
    if (renaming) RenameDialog(tv.display, tv.name.removePrefix("TPV ")) { n -> renaming = false; if (n != null) onRename(n) }
    if (forgetAsk) AlertDialog(
        onDismissRequest = { forgetAsk = false },
        containerColor = GlassSheet.Dialog,
        title = { Text("TV unutulsun mu?", color = Glass.Text) },
        text = { Text("\"${tv.display}\" kayıtlı TV'lerden kaldırılır. İstediğinde yeniden ekleyebilirsin.", color = Glass.Sub) },
        confirmButton = { TextButton(onClick = { forgetAsk = false; onForget() }) { Text("Unut", color = Pal.Red) } },
        dismissButton = { TextButton(onClick = { forgetAsk = false }) { Text("Vazgeç", color = Glass.Sub) } }
    )
}

@Composable
private fun SectionLabel(text: String) {
    Spacer(Modifier.height(18.dp))
    Text(text, color = Glass.Sub, fontSize = 12.sp, letterSpacing = 1.sp, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun MethodChip(label: String, active: Boolean, modifier: Modifier, enabled: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier.height(40.dp).clip(shape)
            .then(if (active) Modifier.background(GlassSheet.Ring) else Modifier.background(GlassSheet.Inset).border(1.dp, GlassSheet.Border, shape))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            if (active) "$label · bağlı" else "$label ile bağlan",
            color = if (active) Color.White else Glass.Text, fontSize = 13.sp, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium
        )
    }
}

@Composable
private fun FoundCard(f: FoundTv, enabled: Boolean, onConnect: (ConnMethod) -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(GlassSheet.Card).border(1.dp, GlassSheet.Border, shape).padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(AppIcons.Tv, null, tint = Glass.Lavender, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(f.name.removePrefix("TPV "), color = Glass.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(f.ip, color = Glass.Sub, fontSize = 12.sp)
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
    Column(Modifier.clip(RoundedCornerShape(18.dp)).background(GlassSheet.Card).border(1.dp, GlassSheet.Border, RoundedCornerShape(18.dp)).padding(14.dp)) {
        OutlinedTextField(
            value = ip, onValueChange = { ip = it }, singleLine = true,
            placeholder = { Text("192.168.1.50", color = Glass.Sub) },
            shape = RoundedCornerShape(14.dp),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { if (ip.isNotBlank()) onConnect(ip.trim(), m) }),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Glass.Text, unfocusedTextColor = Glass.Text,
                focusedBorderColor = Glass.Lavender, unfocusedBorderColor = GlassSheet.Border, cursorColor = Glass.Lavender
            ),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth().height(40.dp).clip(RoundedCornerShape(12.dp)).background(GlassSheet.Inset).padding(3.dp)) {
            for ((mm, label) in listOf(ConnMethod.ADB to "ADB", ConnMethod.STANDARD to "Standart")) {
                val sel = mm == m
                Box(
                    Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(10.dp))
                        .then(if (sel) Modifier.background(GlassSheet.Ring) else Modifier).clickable { m = mm },
                    contentAlignment = Alignment.Center
                ) { Text(label, color = if (sel) Color.White else Glass.Sub, fontSize = 14.sp, fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal) }
            }
        }
        Spacer(Modifier.height(10.dp))
        Box(
            Modifier.fillMaxWidth().height(46.dp).clip(RoundedCornerShape(23.dp)).background(GlassSheet.Ring)
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
        containerColor = GlassSheet.Dialog,
        title = { Text("TV'nin adı", color = Glass.Text) },
        text = {
            Column {
                Text("Kumandada bu ad görünür, örneğin \"Salon TV\". Boş bırakırsan model adı kullanılır.", color = Glass.Sub, fontSize = 13.sp)
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = text, onValueChange = { text = it.take(30) }, singleLine = true,
                    placeholder = { Text(model, color = Glass.Sub) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Glass.Text, unfocusedTextColor = Glass.Text,
                        focusedBorderColor = Glass.Lavender, unfocusedBorderColor = GlassSheet.Border, cursorColor = Glass.Lavender
                    )
                )
            }
        },
        confirmButton = { TextButton(onClick = { onDone(if (text.trim() == model) "" else text) }) { Text("Kaydet", color = Glass.Lavender) } },
        dismissButton = { TextButton(onClick = { onDone(null) }) { Text("Vazgeç", color = Glass.Sub) } }
    )
}

@Composable
private fun HelpDialog(onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        containerColor = GlassSheet.Dialog,
        title = { Text("Bağlantı türleri", color = Glass.Text) },
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
                Text("TV'de ADB nasıl açılır?", color = Glass.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                listOf(
                    "TV'de Ayarlar → Cihaz tercihleri → Hakkında'ya gir.",
                    "\"Derleme\" (Build) satırına art arda 7 kez bas. \"Artık geliştiricisiniz\" yazar.",
                    "Bir geri dön; Cihaz tercihleri'nde \"Geliştirici seçenekleri\" çıkar.",
                    "Orada \"USB hata ayıklama\" ya da \"ADB hata ayıklama\"yı aç.",
                    "Uygulamada TV'ye ADB ile bağlan; TV'de çıkan izin sorusunda \"Her zaman izin ver\"i seçip Tamam'a bas."
                ).forEachIndexed { i, t ->
                    Row(Modifier.padding(vertical = 3.dp)) {
                        Text("${i + 1}.", color = Glass.Lavender, fontSize = 13.sp, modifier = Modifier.width(20.dp))
                        Text(t, color = Glass.Sub, fontSize = 13.sp)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text("Menü adları markaya göre biraz farklı olabilir.", color = Glass.Sub, fontSize = 12.sp)
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Tamam", color = Glass.Lavender) } }
    )
}

@Composable
private fun HelpBlock(title: String, text: String) {
    Text(title, color = Glass.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(4.dp))
    Text(text, color = Glass.Sub, fontSize = 13.sp)
    Spacer(Modifier.height(14.dp))
}
