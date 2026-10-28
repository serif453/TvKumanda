package com.serif.tvkumanda

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

enum class MediaKind(val label: String, val mimeFilter: String, val fallbackMime: String) {
    PHOTO("Fotoğraf", "image/*", "image/jpeg"),
    VIDEO("Video", "video/*", "video/mp4"),
    MUSIC("Müzik", "audio/*", "audio/mpeg")
}

enum class CastPhase { NONE, PREPARING, BUFFERING, PLAYING, PAUSED, ENDED, ERROR }

/** Cast ekranının durumu. Süreler saniye cinsinden. */
data class CastUi(
    val phase: CastPhase = CastPhase.NONE,
    val title: String = "",
    val kind: MediaKind = MediaKind.VIDEO,
    val position: Double = 0.0,
    val duration: Double = 0.0,
    val error: String = ""
)

enum class Tab { REMOTE, CAST }

/** Alt menü: Kumanda / Cast. */
@Composable
fun BottomNav(tab: Tab, onTab: (Tab) -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(64.dp).padding(top = 6.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        NavItem(AppIcons.Tv, "Kumanda", tab == Tab.REMOTE) { onTab(Tab.REMOTE) }
        NavItem(AppIcons.Cast, "Cast", tab == Tab.CAST) { onTab(Tab.CAST) }
    }
}

@Composable
private fun NavItem(icon: ImageVector, label: String, selected: Boolean, onClick: () -> Unit) {
    Column(
        Modifier.clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick).padding(horizontal = 18.dp, vertical = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(if (selected) Pal.Btn else Pal.Bg)
                .padding(horizontal = 18.dp, vertical = 4.dp)
        ) {
            Icon(icon, label, tint = if (selected) Pal.Text else Pal.Sub, modifier = Modifier.size(24.dp))
        }
        Text(label, color = if (selected) Pal.Text else Pal.Sub, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

/** Cast sekmesi: tür seçimi ve oynatıcı kartı. */
@Composable
fun CastScreen(
    cast: CastUi,
    connected: Boolean,
    onPick: (MediaKind) -> Unit,
    onToggle: () -> Unit,
    onSeek: (Double) -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier.fillMaxWidth().padding(top = 16.dp)) {
        Text("TV'de aç", color = Pal.Text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Text(
            if (connected) "Telefondaki fotoğraf, video ya da müziği TV'de oynat"
            else "Önce bir TV'ye bağlan",
            color = Pal.Sub, fontSize = 14.sp
        )
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            KindButton(AppIcons.Photo, MediaKind.PHOTO.label, Modifier.weight(1f), connected) { onPick(MediaKind.PHOTO) }
            KindButton(AppIcons.Movie, MediaKind.VIDEO.label, Modifier.weight(1f), connected) { onPick(MediaKind.VIDEO) }
            KindButton(AppIcons.MusicNote, MediaKind.MUSIC.label, Modifier.weight(1f), connected) { onPick(MediaKind.MUSIC) }
        }
        Spacer(Modifier.weight(1f))
        if (cast.phase != CastPhase.NONE) {
            PlayerCard(cast, onToggle, onSeek, onStop)
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun KindButton(icon: ImageVector, label: String, modifier: Modifier, enabled: Boolean, onClick: () -> Unit) {
    Column(
        modifier
            .height(110.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(Pal.Btn)
            .clickable(enabled = enabled, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(icon, null, tint = if (enabled) Pal.Text else Pal.Sub, modifier = Modifier.size(36.dp))
        Spacer(Modifier.height(8.dp))
        Text(label, color = if (enabled) Pal.Text else Pal.Sub, fontSize = 15.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun PlayerCard(cast: CastUi, onToggle: () -> Unit, onSeek: (Double) -> Unit, onStop: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(Brush.verticalGradient(listOf(Pal.Btn, Pal.Pad)))
            .padding(18.dp)
    ) {
        Text(
            cast.title.ifBlank { cast.kind.label },
            color = Pal.Text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
            maxLines = 1, overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(2.dp))
        Text(
            when (cast.phase) {
                CastPhase.PREPARING -> "TV'ye gönderiliyor…"
                CastPhase.BUFFERING -> "Yükleniyor…"
                CastPhase.PLAYING -> if (cast.kind == MediaKind.PHOTO) "TV'de gösteriliyor" else "Oynatılıyor"
                CastPhase.PAUSED -> "Duraklatıldı"
                CastPhase.ENDED -> "Bitti"
                CastPhase.ERROR -> cast.error.ifBlank { "Oynatılamadı" }
                CastPhase.NONE -> ""
            },
            color = if (cast.phase == CastPhase.ERROR) Pal.Red else Pal.Sub,
            fontSize = 13.sp
        )

        val seekable = cast.kind != MediaKind.PHOTO && cast.duration > 0
        if (seekable) {
            // Sürüklerken çubuk parmağı izler, bırakınca TV'de o noktaya gidilir
            var dragging by remember { mutableStateOf<Float?>(null) }
            val fraction = dragging ?: (cast.position / cast.duration).toFloat().coerceIn(0f, 1f)
            Slider(
                value = fraction,
                onValueChange = { dragging = it },
                onValueChangeFinished = { dragging?.let { onSeek(it * cast.duration) }; dragging = null },
                colors = SliderDefaults.colors(thumbColor = Pal.Text, activeTrackColor = Pal.Blue, inactiveTrackColor = Pal.BtnPressed),
                modifier = Modifier.fillMaxWidth()
            )
            Row(Modifier.fillMaxWidth()) {
                Text(timeText(fraction * cast.duration), color = Pal.Sub, fontSize = 12.sp)
                Spacer(Modifier.weight(1f))
                Text(timeText(cast.duration), color = Pal.Sub, fontSize = 12.sp)
            }
        } else {
            Spacer(Modifier.height(12.dp))
        }

        Row(
            Modifier.fillMaxWidth().padding(top = 6.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (cast.kind != MediaKind.PHOTO) {
                val playing = cast.phase == CastPhase.PLAYING || cast.phase == CastPhase.BUFFERING
                RoundAction(if (playing) AppIcons.Pause else AppIcons.PlayArrow, if (playing) "Duraklat" else "Oynat", big = true, onClick = onToggle)
                Spacer(Modifier.width(24.dp))
            }
            RoundAction(AppIcons.Stop, "Durdur", big = false, onClick = onStop)
        }
    }
}

@Composable
private fun RoundAction(icon: ImageVector, desc: String, big: Boolean, onClick: () -> Unit) {
    val size = if (big) 64.dp else 52.dp
    Box(
        Modifier.size(size).clip(CircleShape).background(if (big) Pal.Blue else Pal.BtnPressed).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, desc, tint = Pal.Text, modifier = Modifier.size(if (big) 32.dp else 26.dp))
    }
}

private fun timeText(sec: Double): String {
    val s = sec.toLong().coerceAtLeast(0)
    val h = s / 3600; val m = (s % 3600) / 60; val r = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, r) else "%d:%02d".format(m, r)
}
