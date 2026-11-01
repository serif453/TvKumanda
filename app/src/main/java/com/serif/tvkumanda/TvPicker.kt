package com.serif.tvkumanda

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.SettingsRemote
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/** "TV'ler" penceresinin ve TV panelinin cam renkleri (kumandadaki tasarımla aynı aile). */
object GlassSheet {
    /** Büyük pencerenin zemini: arka planın orta tonu. */
    val Bg = Color(0xFF1C1B40)
    val Dialog = Color(0xFF1E1C42)
    val Menu = Color(0xFF29265A)
    /** Pencere içindeki kartlar: yarı saydam beyaz, kumandadaki cam tuşlar gibi. */
    val Card = Color.White.copy(alpha = 0.07f)
    val Border = Color.White.copy(alpha = 0.12f)
    /** Kartın içindeki çukur alanlar (seçili olmayan düğme, giriş kutusu). */
    val Inset = Color.Black.copy(alpha = 0.22f)
    val AccentSoft = Glass.Purple.copy(alpha = 0.22f)
    val Ring = Brush.horizontalGradient(listOf(Glass.Purple, Glass.Blue))
}

/**
 * TV adı kutusuna (üstteki "pil") dokununca hemen altından açılan küçük cam panel (Serif'in isteği, 1 Kasım 2026).
 * Her gün yapılanlar burada: kayıtlı TV'ler arasında geçiş, bağlı TV'nin ADB / Standart seçimi, IR kumandalar.
 * Ağda arama, IP ile ekleme, yeniden adlandırma, unutma ve yardım en alttaki "TV ekle / yönet" satırının açtığı
 * büyük pencerede ([TvSheet]).
 * Genişlik: ayarlar ve güç düğmelerinin iç kenarları arası (pilden biraz geniş, yazılar sıkışmasın diye).
 * Boy içeriğe göre; en fazla ekranın yarısı kadar, fazlası kaydırılır.
 */
@Composable
fun TvPickerPanel(
    open: Boolean,
    topOffset: Dp,
    ui: UiState,
    onPickSaved: (SavedTv) -> Unit,
    onWake: (SavedTv) -> Unit,
    onManage: () -> Unit,
    onClose: () -> Unit
) {
    BackHandler(enabled = open, onBack = onClose)
    // Arkadaki kumanda hafifçe kararır; dokununca panel kapanır
    AnimatedVisibility(open, enter = fadeIn(tween(160)), exit = fadeOut(tween(140))) {
        Box(
            Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClose)
        )
    }
    // Panel pilin altından aşağı doğru açılır
    AnimatedVisibility(
        open,
        modifier = Modifier.fillMaxWidth().padding(top = topOffset, start = 68.dp, end = 68.dp),
        enter = expandVertically(tween(220), expandFrom = Alignment.Top) + fadeIn(tween(160)),
        exit = shrinkVertically(tween(180), shrinkTowards = Alignment.Top) + fadeOut(tween(140))
    ) {
        PanelBody(ui, onPickSaved, onWake, onManage, onClose)
    }
}

@Composable
private fun PanelBody(
    ui: UiState,
    onPickSaved: (SavedTv) -> Unit,
    onWake: (SavedTv) -> Unit,
    onManage: () -> Unit,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val irOk = remember { IrSender.available(context) }
    LaunchedEffect(Unit) { if (irOk) IrUi.load(context) }
    // Kayıtlı TV'ler açık mı: panel açılınca kısa bir deneme (büyük penceredekiyle aynı)
    var reachable by remember { mutableStateOf<Set<String>?>(null) }
    LaunchedEffect(ui.savedTvs.map { it.ip }) {
        val ips = ui.savedTvs.map { it.ip }
        reachable = coroutineScope {
            ips.map { ip -> async(Dispatchers.IO) { if (TvPing.isOnline(ip)) ip else null } }.mapNotNull { it.await() }.toSet()
        }
    }
    val maxH = (LocalConfiguration.current.screenHeightDp * 0.55f).dp
    val shape = RoundedCornerShape(24.dp)
    GlassText {
        Column(
            Modifier.fillMaxWidth().shadow(18.dp, shape, ambientColor = Color.Black, spotColor = Color.Black).clip(shape)
                // Altındaki tuşlar görünmesin diye önce koyu, dolu bir zemin; üstüne kumandadaki cam katman
                .background(Brush.verticalGradient(listOf(Color(0xFF2A2656), Color(0xFF1C1B40))))
                .glass(shape, strength = 0.8f)
                .heightIn(max = maxH)
                .verticalScroll(rememberScrollState())
                .padding(8.dp)
        ) {
            for (tv in ui.savedTvs) {
                val online = reachable?.contains(tv.ip)
                TvRow(
                    tv, ui, online,
                    onPick = { m -> onClose(); onPickSaved(tv.copy(method = m)) },
                    onWake = { onClose(); onWake(tv) }
                )
            }
            if (irOk) for (r in IrUi.saved) IrRow(r) { onClose(); IrUi.page = r.id }
            Box(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp).height(1.dp).background(Color.White.copy(alpha = 0.08f)))
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable { onClose(); onManage() }
                    .padding(horizontal = 10.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Filled.Add, null, tint = Glass.Lavender, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Text("TV ekle / yönet", color = Glass.Lavender, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Icon(Icons.Filled.KeyboardArrowRight, null, tint = Glass.Sub, modifier = Modifier.size(20.dp))
            }
        }
    }
}

@Composable
private fun TvRow(tv: SavedTv, ui: UiState, online: Boolean?, onPick: (ConnMethod) -> Unit, onWake: () -> Unit) {
    val here = ui.state == ConnState.CONNECTED && ui.message == tv.ip
    val busy = ui.state == ConnState.CONNECTING
    val shape = RoundedCornerShape(16.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape)
            .then(if (here) Modifier.background(Glass.Purple.copy(alpha = 0.18f)) else Modifier)
            .clickable(enabled = !here && !busy) { onPick(tv.method) }
            .padding(horizontal = 10.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(AppIcons.Tv, null, tint = if (here) Glass.Lavender else Glass.Text, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(tv.display, color = Glass.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val methodName = { m: ConnMethod -> if (m == ConnMethod.ADB) "ADB" else "Standart" }
                val (color, text) = when {
                    here -> Pal.Green to "Bağlı · ${methodName(ui.method)}"
                    online == true -> Glass.Sub to "Açık · ${methodName(tv.method)}"
                    online == false -> Pal.Red.copy(alpha = 0.8f) to "Kapalı"
                    else -> Glass.Sub to "Bakılıyor…"
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(6.dp).clip(CircleShape).background(color))
                    Spacer(Modifier.width(5.dp))
                    Text(text, color = color, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                }
            }
            if (here) {
                Icon(Icons.Filled.Check, "Bağlı", tint = Glass.Lavender, modifier = Modifier.size(20.dp))
            } else if (online == false && tv.mac.isNotBlank()) {
                Row(
                    Modifier.clip(RoundedCornerShape(12.dp)).background(GlassSheet.AccentSoft).clickable(onClick = onWake)
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Outlined.PowerSettingsNew, null, tint = Glass.Lavender, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Uyandır", color = Glass.Lavender, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        // Bağlı TV'de bağlantı türü: ADB | Standart
        if (here) {
            Spacer(Modifier.height(10.dp))
            Row(
                Modifier.fillMaxWidth().height(36.dp).clip(RoundedCornerShape(12.dp)).background(GlassSheet.Inset).padding(3.dp)
            ) {
                for ((m, label) in listOf(ConnMethod.ADB to "ADB", ConnMethod.STANDARD to "Standart")) {
                    val sel = ui.method == m
                    Box(
                        Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(10.dp))
                            .then(if (sel) Modifier.background(GlassSheet.Ring) else Modifier)
                            .clickable(enabled = !sel && !busy) { onPick(m) },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            label, color = if (sel) Color.White else Glass.Sub, fontSize = 13.sp,
                            fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Medium
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun IrRow(r: IrSaved, onOpen: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable(onClick = onOpen)
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Outlined.SettingsRemote, null, tint = Glass.Text, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(r.name, color = Glass.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("IR kumanda · ${r.brand}", color = Glass.Sub, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
