package com.serif.tvkumanda

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private data class Shortcut(val name: String, val detail: String, val icon: ImageVector)

/** Örnek kısayollar — şimdilik yalnızca görünüm için. */
private val sampleShortcuts = listOf(
    Shortcut("Wake-on-LAN", "TV'yi ağdan uyandır", Icons.Outlined.PowerSettingsNew),
    Shortcut("Netflix'i aç", "1 adım", Icons.Outlined.Movie),
    Shortcut("Sesi 10'a getir", "1 adım", Icons.Outlined.VolumeUp),
    Shortcut("Uyku modu", "30 dk sonra kapat", Icons.Outlined.Bedtime),
    Shortcut("YouTube'da ara", "3 adım", Icons.Outlined.Search)
)

/** Örnek favori kanallar (numara, ad). */
private val sampleChannels = listOf("1" to "Kanal 1", "5" to "Kanal 5", "8" to "Kanal 8")

/** Kısayollar sekmesi: favori kanallar ve kullanıcının kendi tek dokunuşluk kısayolları. */
@Composable
fun ShortcutsScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val soon = { Toast.makeText(context, "Yakında", Toast.LENGTH_SHORT).show() }
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        Spacer(Modifier.height(10.dp))
        Text("Kısayollar", color = Pal.Text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text("Sık yaptıklarını tek dokunuşa indir", color = Pal.Sub, fontSize = 13.sp)

        // Favori kanallar: satırda 3 küçük kutu, sonda ekleme kutusu
        Spacer(Modifier.height(20.dp))
        Text("Favori kanallar", color = Pal.Sub, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        val channelCells: List<(@Composable (Modifier) -> Unit)> =
            sampleChannels.map { (no, name) -> { m: Modifier -> ChannelTile(no, name, m, soon) } } +
                listOf({ m: Modifier -> AddTile("Kanal ekle", m.height(76.dp), soon) })
        for (row in channelCells.chunked(3)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                for (cell in row) cell(Modifier.weight(1f))
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
            Spacer(Modifier.height(10.dp))
        }

        // Kendi kısayolların: satırda 2 büyük kutu, sonda "Yeni kısayol"
        Spacer(Modifier.height(10.dp))
        Text("Kısayollarım", color = Pal.Sub, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        val cells: List<(@Composable (Modifier) -> Unit)> =
            sampleShortcuts.map { sc -> { m: Modifier -> ShortcutTile(sc, m, soon) } } +
                listOf({ m: Modifier -> AddTile("Yeni kısayol", m.height(96.dp), soon) })
        for (row in cells.chunked(2)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                for (cell in row) cell(Modifier.weight(1f))
                repeat(2 - row.size) { Spacer(Modifier.weight(1f)) }
            }
            Spacer(Modifier.height(10.dp))
        }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun ShortcutTile(sc: Shortcut, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier
            .height(96.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(Pal.Btn)
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Icon(sc.icon, sc.name, tint = Pal.Text, modifier = Modifier.size(26.dp))
        Column {
            Text(sc.name, color = Pal.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(sc.detail, color = Pal.Sub, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun ChannelTile(no: String, name: String, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier
            .height(76.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(Pal.Btn)
            .clickable(onClick = onClick)
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(no, color = Pal.Text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text(name, color = Pal.Sub, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
    }
}

/** Kesik çizgili "ekle" kutusu. */
@Composable
private fun AddTile(label: String, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier
            .clip(RoundedCornerShape(18.dp))
            .drawBehind {
                drawRoundRect(
                    color = Pal.Sub.copy(alpha = 0.6f),
                    cornerRadius = CornerRadius(18.dp.toPx()),
                    style = Stroke(width = 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f)))
                )
            }
            .clickable(onClick = onClick)
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(Icons.Outlined.Add, label, tint = Pal.Sub, modifier = Modifier.size(24.dp))
        Spacer(Modifier.height(4.dp))
        Text(label, color = Pal.Sub, fontSize = 12.sp, textAlign = TextAlign.Center)
    }
}
