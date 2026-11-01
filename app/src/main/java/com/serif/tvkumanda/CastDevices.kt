package com.serif.tvkumanda

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.CastConnected
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Speaker
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Cast cihaz seçimi, dünyadaki cast düğmeleri gibi (Serif'in isteği, 2 Kasım 2026): tek cast düğmesi; basınca alttan
 * panel açılır ve o an arar; Chromecast ve DLNA cihazları tek listede; dokununca bağlanılır. Kumanda bağlantısından
 * bağımsızdır. Bağlı değilken bir öğeye dokunulursa ya da paylaşımdan dosya gelirse aynı panel açılır, seçince o öğe oynar.
 * Son cihazı hatırlama Ayarlar → Cast'ten (`AppSettings.castRemember`).
 *
 * MainActivity panelin açılıp kapanmasını ve eylemlerini buradan yönetir (uzun parametre zinciri yerine).
 */
object CastPanel {
    var open by mutableStateOf(false)
    /** Cihaz seçilince oynayacak öğenin adı (yoksa null). */
    var waiting by mutableStateOf<String?>(null)
    /** Son kullanılan cihazın adı; ayara göre listede başta gösterilir (null: gösterilmez). */
    var lastName by mutableStateOf<String?>(null)

    var onOpen: () -> Unit = {}
    var onClose: () -> Unit = {}
    var onSearch: () -> Unit = {}
    var onDisconnect: () -> Unit = {}
}

/** Bağlı cast cihazının adı (yoksa null). Seçim tek: ya Chromecast ya DLNA. */
fun castDeviceName(cc: CcUi, dlna: DlnaUi): String? = cc.selected?.name ?: dlna.selected?.name

/** Sağ üstteki cast düğmesi: bağlı değilken boş simge + "Bağlan", bağlıyken dolu simge + cihaz adı. */
@Composable
fun CastButton(cc: CcUi, dlna: DlnaUi, modifier: Modifier = Modifier) {
    val name = castDeviceName(cc, dlna)
    val shape = RoundedCornerShape(20.dp)
    Row(
        modifier.clip(shape)
            .then(
                if (name != null) Modifier.background(Pal.Blue.copy(alpha = 0.18f)).border(1.dp, Pal.Blue.copy(alpha = 0.7f), shape)
                else Modifier.glass(shape)
            )
            .clickable { CastPanel.onOpen() }
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            if (name != null) Icons.Filled.CastConnected else Icons.Filled.Cast, null,
            tint = if (name != null) Color(0xFF9DB8FF) else Pal.Text, modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            name ?: "Bağlan", color = if (name != null) Color(0xFFCFDBFF) else Pal.Text,
            fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 140.dp)
        )
    }
}

private class CastRowItem(val key: String, val name: String, val sub: String, val dlna: Boolean, val selected: Boolean, val pick: () -> Unit)

/** Alttan açılan "Cihaza yayınla" paneli. Açılınca MainActivity iki aramayı da başlatır; bulunanlar geldikçe listelenir. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CastDevicePanel(cc: CcUi, dlna: DlnaUi, onCcPick: (FoundTv?) -> Unit, onDlnaPick: (DlnaDevice) -> Unit) {
    if (!CastPanel.open) return
    val connected = castDeviceName(cc, dlna)
    val searching = cc.searching || dlna.searching
    val last = CastPanel.lastName
    // Seçili cihaz aramada henüz bulunmadıysa da listede görünür
    val ccList = (listOfNotNull(cc.selected?.takeIf { s -> cc.devices.none { it.ip == s.ip } }) + cc.devices)
    val dlnaList = (listOfNotNull(dlna.selected?.takeIf { s -> dlna.devices.none { it.key == s.key } }) + dlna.devices)
    val rows = ccList.map { d ->
        CastRowItem("cc:" + d.ip, d.name, d.ip, false, d.ip == cc.selected?.ip) { onCcPick(d) }
    } + dlnaList.map { d ->
        CastRowItem("dlna:" + d.key, d.name, d.ip, true, d.key == dlna.selected?.key) { onDlnaPick(d) }
    }
    // Son kullanılan (ayar açıksa) en üstte; sonra Chromecast, sonra DLNA
    val sorted = if (last != null) rows.sortedBy { if (it.name == last) 0 else 1 } else rows

    ModalBottomSheet(
        onDismissRequest = { CastPanel.onClose() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Color(0xFF242426)
    ) {
        Column(
            Modifier.fillMaxWidth().heightIn(max = (LocalConfiguration.current.screenHeightDp * 0.75f).dp)
                .verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 28.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Cihaza yayınla", color = Pal.Text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                if (searching) {
                    CircularProgressIndicator(Modifier.size(16.dp), color = Pal.Sub, strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Aranıyor", color = Pal.Sub, fontSize = 13.sp)
                } else {
                    Row(
                        Modifier.clip(RoundedCornerShape(12.dp)).clickable { CastPanel.onSearch() }.padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Outlined.Refresh, null, tint = Pal.Blue, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Tekrar ara", color = Pal.Blue, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
            CastPanel.waiting?.let {
                Text("Seçince \"$it\" hemen oynar", color = Pal.Sub, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp))
            }
            Spacer(Modifier.height(12.dp))

            for (r in sorted) DeviceRow(r, isLast = last != null && r.name == last && !r.selected)

            if (sorted.isEmpty()) {
                Text(
                    if (searching) "Ağdaki cihazlar aranıyor…" else "Cihaz bulunamadı. TV açık ve telefonla aynı Wi-Fi ağında mı?",
                    color = Pal.Sub, fontSize = 14.sp, modifier = Modifier.padding(vertical = 18.dp)
                )
            }

            if (connected != null) {
                Spacer(Modifier.height(14.dp))
                Box(
                    Modifier.fillMaxWidth().height(46.dp).clip(RoundedCornerShape(23.dp)).background(Pal.Red.copy(alpha = 0.15f))
                        .clickable { CastPanel.onDisconnect() },
                    contentAlignment = Alignment.Center
                ) { Text("Bağlantıyı kes", color = Pal.Red, fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
            }
        }
    }
}

@Composable
private fun DeviceRow(r: CastRowItem, isLast: Boolean) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .then(if (r.selected) Modifier.background(Pal.Blue.copy(alpha = 0.14f)) else Modifier)
            .clickable(onClick = r.pick)
            .padding(horizontal = 10.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(if (r.dlna) Icons.Outlined.Speaker else AppIcons.Tv, null, tint = Pal.Text, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(r.name, color = Pal.Text, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                if (isLast) "Son kullanılan · ${r.sub}" else r.sub,
                color = Pal.Sub, fontSize = 12.sp, maxLines = 1
            )
        }
        Tag(if (r.dlna) "DLNA · deneysel" else "Chromecast", if (r.dlna) Pal.Amber else Pal.Blue)
        if (r.selected) {
            Spacer(Modifier.width(8.dp))
            Icon(Icons.Filled.Check, "Bağlı", tint = Pal.Blue, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun Tag(text: String, color: Color) {
    Text(
        text, color = color, fontSize = 11.sp, fontWeight = FontWeight.Medium,
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(color.copy(alpha = 0.15f)).padding(horizontal = 7.dp, vertical = 2.dp)
    )
}
