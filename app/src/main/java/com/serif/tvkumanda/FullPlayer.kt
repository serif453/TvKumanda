package com.serif.tvkumanda

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/**
 * Tam ekran oynatıcı: büyük görsel, başlık, ilerleme çubuğu, ses çubuğu ve tüm tuşlar.
 * Parmakla aşağı kaydırınca (ya da aşağı ok / geri tuşu) kapanır.
 */
@Composable
fun FullPlayer(
    cast: CastUi,
    tvName: String,
    library: MediaLibrary,
    onClose: () -> Unit,
    onToggle: () -> Unit,
    onSeek: (Double) -> Unit,
    onStop: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onSkip: (Double) -> Unit,
    onVolume: (Float) -> Unit,
    onShowInFolder: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val drag = remember { Animatable(0f) }
    var height by remember { mutableStateOf(1) }
    BackHandler(onBack = onClose)

    Box(
        Modifier
            .fillMaxSize()
            .onSizeChanged { height = it.height }
            .graphicsLayer { translationY = drag.value }
            .background(Brush.verticalGradient(listOf(Pal.Btn, Pal.Bg)))
            // Aşağı kaydırma: ekran parmağı takip eder; yeterince çekilirse kapanır
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onVerticalDrag = { ch, dy ->
                        ch.consume()
                        scope.launch { drag.snapTo((drag.value + dy).coerceAtLeast(0f)) }
                    },
                    onDragEnd = {
                        scope.launch {
                            if (drag.value > height * 0.22f) {
                                drag.animateTo(height.toFloat(), tween(180))
                                onClose()
                            } else drag.animateTo(0f, tween(200))
                        }
                    },
                    onDragCancel = { scope.launch { drag.animateTo(0f, tween(200)) } }
                )
            }
            .padding(horizontal = 20.dp, vertical = 12.dp)
    ) {
        Column(Modifier.fillMaxSize()) {
            // Üst çubuk: kapat + TV adı
            Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onClose),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(AppIcons.ArrowBack, "Kapat", tint = Pal.Text,
                        modifier = Modifier.size(26.dp).graphicsLayer { rotationZ = -90f })
                }
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    Icon(AppIcons.Cast, null, tint = Pal.Blue, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(tvName.ifBlank { "TV" }, color = Pal.Text, fontSize = 17.sp, fontWeight = FontWeight.Medium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.width(44.dp))
            }
            Spacer(Modifier.height(10.dp))

            // Büyük görsel + durum etiketi
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Box(Modifier.fillMaxHeight().aspectRatio(1f, matchHeightConstraintsFirst = true).clip(RoundedCornerShape(28.dp))) {
                    val it = cast.item
                    if (it != null) {
                        Thumb(
                            it, library, Modifier.fillMaxSize(), placeholderFor(it.kind),
                            px = 1080, placeholderSize = 96.dp,
                            scale = if (it.kind == MediaKind.PHOTO) ContentScale.Fit else ContentScale.Crop
                        )
                    } else {
                        Box(Modifier.fillMaxSize().background(Pal.Btn), contentAlignment = Alignment.Center) {
                            Icon(placeholderFor(cast.kind), null, tint = Pal.Sub, modifier = Modifier.size(96.dp))
                        }
                    }
                    Text(
                        statusText(cast),
                        color = Pal.Text, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                        modifier = Modifier.align(Alignment.TopEnd).padding(12.dp)
                            .clip(RoundedCornerShape(14.dp)).background(Pal.Blue.copy(alpha = 0.85f))
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
            }
            Spacer(Modifier.height(18.dp))

            // Başlık + alt başlık + "klasörde göster"
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(cast.title.ifBlank { cast.kind.label }, color = Pal.Text, fontSize = 22.sp,
                        fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val sub = cast.item?.subtitle.orEmpty()
                    if (sub.isNotBlank()) Text(sub, color = Pal.Sub, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (cast.phase == CastPhase.ERROR) Text(cast.error, color = Pal.Red, fontSize = 13.sp)
                }
                if (cast.albumId != null) {
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "Klasörde göster", color = Pal.Text, fontSize = 13.sp,
                        modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(Pal.BtnPressed)
                            .clickable(onClick = onShowInFolder).padding(horizontal = 12.dp, vertical = 8.dp)
                    )
                }
            }

            // İlerleme
            if (cast.kind != MediaKind.PHOTO && cast.duration > 0) {
                Spacer(Modifier.height(10.dp))
                var dragging by remember { mutableStateOf<Float?>(null) }
                val fraction = dragging ?: (cast.position / cast.duration).toFloat().coerceIn(0f, 1f)
                Slider(
                    value = fraction,
                    onValueChange = { dragging = it },
                    onValueChangeFinished = { dragging?.let { onSeek(it * cast.duration) }; dragging = null },
                    colors = SliderDefaults.colors(thumbColor = Pal.Text, activeTrackColor = Pal.Blue, inactiveTrackColor = Pal.BtnPressed)
                )
                Row(Modifier.fillMaxWidth()) {
                    Text(timeText(fraction * cast.duration), color = Pal.Sub, fontSize = 12.sp)
                    Spacer(Modifier.weight(1f))
                    Text(timeText(cast.duration), color = Pal.Sub, fontSize = 12.sp)
                }
            } else Spacer(Modifier.height(12.dp))

            // Ses (TV'nin Chromecast sesi)
            if (cast.volume >= 0f) {
                var vol by remember { mutableStateOf<Float?>(null) }
                val v = vol ?: cast.volume
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(AppIcons.VolumeUp, null, tint = Pal.Text, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(10.dp))
                    Slider(
                        value = v,
                        onValueChange = { vol = it; onVolume(it) },
                        onValueChangeFinished = { vol?.let(onVolume); vol = null },
                        colors = SliderDefaults.colors(thumbColor = Pal.Text, activeTrackColor = Pal.Blue, inactiveTrackColor = Pal.BtnPressed),
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(10.dp))
                    Text("${(v * 100).toInt()}%", color = Pal.Sub, fontSize = 13.sp, modifier = Modifier.width(40.dp))
                }
            }

            // Tuşlar
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RoundAction(AppIcons.SkipPrevious, "Önceki", size = 52.dp, enabled = cast.hasList, onClick = onPrev)
                if (cast.kind != MediaKind.PHOTO) {
                    RoundAction(AppIcons.Replay10, "10 saniye geri", size = 52.dp, onClick = { onSkip(-10.0) })
                    val playing = cast.phase == CastPhase.PLAYING || cast.phase == CastPhase.BUFFERING
                    RoundAction(if (playing) AppIcons.Pause else AppIcons.PlayArrow, if (playing) "Duraklat" else "Oynat",
                        size = 72.dp, iconSize = 36.dp, primary = true, onClick = onToggle)
                    RoundAction(AppIcons.Forward10, "10 saniye ileri", size = 52.dp, onClick = { onSkip(10.0) })
                } else Spacer(Modifier.width(48.dp))
                RoundAction(AppIcons.SkipNext, "Sonraki", size = 52.dp, enabled = cast.hasList, onClick = onNext)
            }
            Spacer(Modifier.height(14.dp))
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                RoundAction(AppIcons.Stop, "Durdur", size = 48.dp, iconSize = 22.dp, onClick = onStop)
            }
            Spacer(Modifier.height(6.dp))
        }
    }
}

private fun statusText(cast: CastUi) = when (cast.phase) {
    CastPhase.PREPARING -> "Hazırlanıyor"
    CastPhase.BUFFERING -> "Yükleniyor"
    CastPhase.PLAYING -> if (cast.kind == MediaKind.PHOTO) "Gösteriliyor" else "Oynatılıyor"
    CastPhase.PAUSED -> "Duraklatıldı"
    CastPhase.ENDED -> "Bitti"
    CastPhase.ERROR -> "Hata"
    CastPhase.NONE -> ""
}
