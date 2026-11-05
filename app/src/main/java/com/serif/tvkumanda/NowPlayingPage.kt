package com.serif.tvkumanda

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * TV'de çalan medya (2 Kasım sonrası, Serif'in isteği ve onayı, 5 Kasım 2026):
 * Kumanda ekranında alt sağda (Kısayollar'ın üstünde) çalan uygulamanın küçük simgesi durur ([NowPlayingChip]);
 * dokununca tam ekran "Şimdi oynatılıyor" sayfası açılır ([NowPlayingPage]). Eski oynatıcı kartı kaldırıldı.
 */

internal fun hms(ms: Long): String {
    val t = (ms / 1000).coerceAtLeast(0)
    return if (t >= 3600) "%d:%02d:%02d".format(t / 3600, (t % 3600) / 60, t % 60) else "%d:%02d".format(t / 60, t % 60)
}

/** Uygulama simgesi; yoksa oynat işareti. */
@Composable
private fun AppTile(bmp: ImageBitmap?, size: Dp, radius: Dp) {
    Box(Modifier.size(size).clip(RoundedCornerShape(radius)).background(Navy.Btn), contentAlignment = Alignment.Center) {
        if (bmp != null) Image(bmp, null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        else Icon(Icons.Filled.PlayArrow, null, tint = Navy.Sub, modifier = Modifier.size(size / 2))
    }
}

/** Kumanda ekranında çalan uygulamanın küçük yuvarlak simgesi; sağ altta durum rozeti (çalıyor / duraklatıldı). */
@Composable
fun NowPlayingChip(np: NowPlaying, apps: List<TvApp>, onClick: () -> Unit) {
    val bmp = apps.firstOrNull { it.pkg == np.pkg }?.icon
    val inter = remember { MutableInteractionSource() }
    val scale = pressScale(inter)
    Box(
        Modifier.size(56.dp).graphicsLayer { scaleX = scale; scaleY = scale }
            .glow(Glass.Purple, 14.dp)
            .clip(CircleShape).background(Color(0xFF13122B))
            .border(2.5.dp, Glass.PlayRing, CircleShape)
            .pressable(inter, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        AppTile(bmp, 34.dp, 10.dp)
        Box(
            Modifier.align(Alignment.BottomEnd).padding(end = 3.dp, bottom = 3.dp).size(18.dp)
                .clip(CircleShape).background(Glass.Purple),
            contentAlignment = Alignment.Center
        ) {
            Icon(if (np.playing) Icons.Filled.PlayArrow else Icons.Filled.Pause, null, tint = Color.White, modifier = Modifier.size(12.dp))
        }
    }
}

/**
 * Tam ekran "Şimdi oynatılıyor": başlık, sarma çubuğu, medya tuşları, TV sesi ve en altta çalan uygulamanın adı.
 * Çalan şey bitince (TV 3 sn boyunca çalan bildirmezse) sayfa kendiliğinden kapanır.
 */
@Composable
fun NowPlayingPage(ui: UiState, apps: List<TvApp>, onKey: (Int) -> Unit, onSeek: (Long) -> Unit, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    val live = LiveUi.nowPlaying
    var last by remember { mutableStateOf(live) }
    LaunchedEffect(live) { if (live != null) last = live }
    LaunchedEffect(live == null) {
        if (live == null) { kotlinx.coroutines.delay(3000); onClose() }
    }
    val np = live ?: last ?: return

    // Çalarken konum her yarım saniyede ilerler
    var now by remember { mutableLongStateOf(android.os.SystemClock.elapsedRealtime()) }
    LaunchedEffect(np.playing, np.at) {
        while (np.playing) { kotlinx.coroutines.delay(500); now = android.os.SystemClock.elapsedRealtime() }
    }
    var dragging by remember { mutableStateOf<Float?>(null) }
    val pos = np.position(now)
    val dur = np.durationMs
    val app = apps.firstOrNull { it.pkg == np.pkg }
    val appLabel = app?.name ?: appName(np.pkg)
    val title = np.title.ifBlank { appLabel }
    val screenH = LocalConfiguration.current.screenHeightDp
    val art = (screenH * 0.28f).coerceIn(140f, 240f).dp

    GlassText {
        Box(
            Modifier.fillMaxSize().glassBackdrop()
                // Arkadaki ekrana dokunulmasın
                .pointerInput(Unit) { detectTapGestures { } }
        ) {
            Column(
                Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 22.dp).padding(top = 6.dp, bottom = 14.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Üst: ✕ + başlık
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onClose), contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.Close, "Kapat", tint = Color.White, modifier = Modifier.size(26.dp))
                    }
                    Spacer(Modifier.width(14.dp))
                    Text("Şimdi oynatılıyor", color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.weight(1f))
                // Büyük görsel: TV kapak görseli vermediği için uygulamanın simgesi
                Box(
                    Modifier.size(art).clip(RoundedCornerShape(art * 0.19f))
                        .background(Brush.linearGradient(listOf(Color(0xFF161A46), Color(0xFF0B0C24))))
                        .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(art * 0.19f)),
                    contentAlignment = Alignment.Center
                ) { AppTile(app?.icon, art * 0.52f, art * 0.13f) }
                Spacer(Modifier.height(22.dp))
                Text(title, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold, maxLines = 2,
                    overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                if (np.artist.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(np.artist, color = Navy.Sub, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                }
                Spacer(Modifier.weight(1f))
                // Sarma çubuğu ve süreler (süre bilinmiyorsa gösterilmez)
                if (dur > 0) {
                    val shown = dragging?.let { (it * dur).toLong() } ?: pos
                    androidx.compose.material3.Slider(
                        value = dragging ?: (pos.toFloat() / dur).coerceIn(0f, 1f),
                        onValueChange = { dragging = it },
                        onValueChangeFinished = { dragging?.let { onSeek((it * dur).toLong()) }; dragging = null },
                        enabled = np.canSeek,
                        colors = androidx.compose.material3.SliderDefaults.colors(
                            thumbColor = Color(0xFFAFC0FF), activeTrackColor = Navy.Accent, inactiveTrackColor = Navy.Btn,
                            disabledThumbColor = Navy.Sub, disabledActiveTrackColor = Navy.Sub, disabledInactiveTrackColor = Navy.Btn
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(hms(shown), color = Navy.Sub, fontSize = 12.sp)
                        Text(hms(dur), color = Navy.Sub, fontSize = 12.sp)
                    }
                }
                Spacer(Modifier.height(10.dp))
                MediaKeysRow(Modifier.fillMaxWidth().height(80.dp), onKey)
                Spacer(Modifier.height(14.dp))
                GlassVolumePill(ui, onKey, Modifier.fillMaxWidth().height(58.dp))
                Spacer(Modifier.height(12.dp))
                // En altta TV'nin adı yerine çalan uygulamanın adı
                Row(
                    Modifier.fillMaxWidth().height(48.dp).glass(RoundedCornerShape(24.dp), strength = 0.65f),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AppTile(app?.icon, 28.dp, 8.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(appLabel, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}
