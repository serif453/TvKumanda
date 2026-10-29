package com.serif.tvkumanda

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Tam ekran oynatıcının konumu (ekranın üstünden aşağı uzaklık, piksel).
 * 0 = tamamen açık, [height] = tamamen kapalı. Hem tam ekrandan aşağı kaydırma hem de
 * küçük karttan yukarı kaydırma bu tek değeri değiştirir; ekran parmağı takip eder.
 */
class PlayerSheet(private val scope: CoroutineScope) {
    val y = Animatable(HIDDEN)
    var height by mutableStateOf(0f)
    /** Küçük oynatıcı kartının üst kenarı: oynatıcı buradan açılır ve buraya kapanır. */
    var cardTop by mutableStateOf(0f)

    /** Kapalı konum: kart görünüyorsa kartın üst kenarı, değilse ekranın altı. */
    private val closedPos: Float get() = if (cardTop > 0f) cardTop else height

    val visible: Boolean get() = height > 0f && y.value < closedPos - 1f

    /** Karttan yukarı doğru açılır (dokununca da, kaydırınca da aynı yerden). */
    fun open() = scope.launch {
        if (y.value > closedPos) y.snapTo(closedPos)
        y.animateTo(0f, tween(260))
    }

    /** Kartın olduğu yere inip içinde kaybolur. */
    fun close() = scope.launch {
        y.animateTo(closedPos, tween(220))
        y.snapTo(HIDDEN)
    }

    fun hideNow() = scope.launch { y.snapTo(HIDDEN) }

    /** Karttan sürükleme başlarken oynatıcının üst kenarı kartın üst kenarına yerleşir. */
    fun startFromCard() = scope.launch { if (y.value > closedPos) y.snapTo(closedPos) }

    fun dragBy(dy: Float) = scope.launch { y.snapTo((y.value + dy).coerceIn(0f, closedPos)) }

    /**
     * Parmak kalkınca: karttan çekiliyorsa yolun üçte biri geçildiyse açılır;
     * tam ekrandan itiliyorsa biraz aşağı inildiyse kapanır.
     */
    fun settle(fromMini: Boolean) {
        val openIt = if (fromMini) y.value < closedPos * 0.67f else y.value < closedPos * 0.22f
        if (openIt) open() else close()
    }

    private companion object { const val HIDDEN = 100_000f }
}

@Composable
fun rememberPlayerSheet(): PlayerSheet {
    val scope = rememberCoroutineScope()
    return remember { PlayerSheet(scope) }
}

/** Küçük karttaki başlık bölgesi: yukarı kaydırınca tam ekran oynatıcı parmağı takip ederek açılır. */
fun Modifier.swipeUpToOpen(sheet: PlayerSheet): Modifier = pointerInput(sheet) {
    detectVerticalDragGestures(
        onDragStart = { sheet.startFromCard() },
        onVerticalDrag = { ch, dy -> ch.consume(); sheet.dragBy(dy) },
        onDragEnd = { sheet.settle(fromMini = true) },
        onDragCancel = { sheet.close() }
    )
}

/**
 * Tam ekran oynatıcı: büyük görsel, başlık, ilerleme ve ses çubuğu, tuşlar; fotoğraf ve
 * videoda altta "Bu klasörden" önizleme şeridi. Aşağı kaydırınca (ya da aşağı ok / geri) kapanır.
 */
@Composable
fun FullPlayer(
    sheet: PlayerSheet,
    cast: CastUi,
    tvName: String,
    library: MediaLibrary,
    onToggle: () -> Unit,
    onSeek: (Double) -> Unit,
    onStop: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onSkip: (Double) -> Unit,
    onVolume: (Float) -> Unit,
    onPlayAt: (Int) -> Unit,
    onShowInFolder: () -> Unit,
    onRepeat: () -> Unit = {},
    onShuffle: () -> Unit = {}
) {
    BackHandler(enabled = sheet.visible) { sheet.close() }

    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer { translationY = sheet.y.value }
            .background(Brush.verticalGradient(listOf(Pal.Btn, Pal.Bg)))
            // Aşağı kaydırma: ekran parmağı takip eder; yeterince çekilirse kapanır
            .pointerInput(sheet) {
                detectVerticalDragGestures(
                    onVerticalDrag = { ch, dy -> ch.consume(); sheet.dragBy(dy) },
                    onDragEnd = { sheet.settle(fromMini = false) },
                    onDragCancel = { sheet.open() }
                )
            }
            .padding(horizontal = 20.dp, vertical = 12.dp)
    ) {
        Column(Modifier.fillMaxSize()) {
            // Üst çubuk: kapat + TV adı
            Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(44.dp).clip(CircleShape).clickable { sheet.close() },
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

            // Büyük görsel + durum etiketi (kalan alana göre boyutlanır)
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

            // Üst satır: dosya adı + "Klasörde göster"
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(cast.title.ifBlank { cast.kind.label }, color = Pal.Text, fontSize = 22.sp,
                    fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f))
                if (cast.albumId != null) {
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "Klasörde göster", color = Pal.Text, fontSize = 13.sp,
                        modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(Pal.BtnPressed)
                            .clickable(onClick = onShowInFolder).padding(horizontal = 12.dp, vertical = 8.dp)
                    )
                }
            }
            // Alt satır: sanatçı/klasör + küçük durdur tuşu
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                Column(Modifier.weight(1f)) {
                    val sub = cast.item?.subtitle.orEmpty()
                    if (sub.isNotBlank()) Text(sub, color = Pal.Sub, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (cast.phase == CastPhase.ERROR) Text(cast.error, color = Pal.Red, fontSize = 13.sp)
                }
                RoundAction(AppIcons.Stop, "Durdur", size = 40.dp, iconSize = 20.dp, onClick = onStop)
            }

            // İlerleme
            if (cast.kind != MediaKind.PHOTO && cast.duration > 0) {
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
            } else Spacer(Modifier.height(10.dp))

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

            // Müzik: karıştır ve tekrar
            if (cast.kind == MediaKind.MUSIC) {
                Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    ModeToggle(AppIcons.Shuffle, if (cast.shuffle) "Karıştır: açık" else "Karıştır: kapalı", cast.shuffle, onShuffle)
                    Spacer(Modifier.weight(1f))
                    ModeToggle(
                        if (cast.repeat == MusicRepeat.ONE) AppIcons.RepeatOne else AppIcons.Repeat,
                        when (cast.repeat) { MusicRepeat.OFF -> "Tekrar: kapalı"; MusicRepeat.ALL -> "Listeyi tekrarla"; MusicRepeat.ONE -> "Bu şarkıyı tekrarla" },
                        cast.repeat != MusicRepeat.OFF, onRepeat
                    )
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

            // Fotoğraf ve videoda: "Bu klasörden" önizleme şeridi
            if (cast.kind != MediaKind.MUSIC && cast.list.size > 1) {
                Spacer(Modifier.height(16.dp))
                Text("Bu klasörden", color = Pal.Sub, fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
                PreviewStrip(cast, library, onPlayAt)
            }
            Spacer(Modifier.height(6.dp))
        }
    }
}

/** Yatay küçük resim şeridi: oynatılan öğe çerçeveli ve ortada; dokununca o dosya açılır. */
@Composable
private fun PreviewStrip(cast: CastUi, library: MediaLibrary, onPlayAt: (Int) -> Unit) {
    val state = rememberLazyListState()
    val index = cast.list.indexOfFirst { it.id == cast.currentId }
    LaunchedEffect(index) {
        if (index < 0) return@LaunchedEffect
        // Oynatılan öğeyi şeridin ortasına getir
        val viewport = state.layoutInfo.viewportSize.width
        val itemPx = state.layoutInfo.visibleItemsInfo.firstOrNull()?.size ?: 0
        state.animateScrollToItem(index, -((viewport - itemPx) / 2).coerceAtLeast(0))
    }
    LazyRow(state = state, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().height(76.dp)) {
        itemsIndexed(cast.list, key = { _, it -> it.id }) { i, item ->
            val playing = item.id == cast.currentId
            Box(
                Modifier.size(76.dp).clip(RoundedCornerShape(12.dp)).clickable { if (!playing) onPlayAt(i) }
            ) {
                Thumb(item, library, Modifier.fillMaxSize(), placeholderFor(item.kind))
                if (item.kind == MediaKind.VIDEO && item.durationMs > 0) {
                    Text(
                        timeText(item.durationMs / 1000.0), color = Pal.Text, fontSize = 10.sp,
                        modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp)
                            .clip(RoundedCornerShape(5.dp)).background(Pal.Bg.copy(alpha = 0.7f))
                            .padding(horizontal = 4.dp)
                    )
                }
                if (playing) Box(Modifier.fillMaxSize().border(3.dp, Pal.Blue, RoundedCornerShape(12.dp)))
            }
        }
    }
}

@Composable
private fun ModeToggle(icon: androidx.compose.ui.graphics.vector.ImageVector, desc: String, active: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(44.dp).clip(CircleShape)
            .background(if (active) Pal.Blue.copy(alpha = 0.18f) else Pal.Bg.copy(alpha = 0f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) { Icon(icon, desc, tint = if (active) Pal.Blue else Pal.Sub, modifier = Modifier.size(24.dp)) }
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
