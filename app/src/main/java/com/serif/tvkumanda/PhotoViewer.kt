package com.serif.tvkumanda

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CastConnected
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.SyncDisabled
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Cast → Fotoğraf albümünde bir fotoğrafa dokununca açılan tam ekran görüntüleyici (Serif, 2 Kasım 2026).
 * Telefonda galeri gibi gezilir: kaydırma, iki parmakla / çift dokunarak yakınlaştırma, altta küçük resim şeridi.
 * TV denetimi üst çubukta: bağlı değilken "Bağlan" → cihaz seçilince o an bakılan foto TV'ye gider; bağlıyken "Takip"
 * açıksa telefonda kaydırdıkça TV de değişir, kapalıyken serbestçe gezilir. TV'deki fotoğraf başka yoldan değişirse
 * (slayt, oynatıcı kartı) takip açıkken görüntüleyici de o fotoğrafa kayar.
 */
@Composable
fun PhotoViewer(
    album: MediaAlbum,
    startIndex: Int,
    library: MediaLibrary,
    cast: CastUi,
    cc: CcUi,
    dlna: DlnaUi,
    onCast: (MediaItem) -> Unit,
    onCcPick: (FoundTv?) -> Unit,
    onDlnaPick: (DlnaDevice) -> Unit,
    onClose: () -> Unit
) {
    val items = album.items
    if (items.isEmpty()) return
    val connected = castDeviceName(cc, dlna) != null
    val pager = rememberPagerState(startIndex.coerceIn(0, items.lastIndex)) { items.size }
    // Takip başta açık: bağlıysa foto hemen TV'ye gider; bağlı değilken "Bağlan"dan cihaz seçilince o an bakılan foto gider
    var follow by remember { mutableStateOf(true) }
    var chrome by remember { mutableStateOf(true) }
    var zoomed by remember { mutableStateOf(false) }
    // Bizim gönderdiğimiz son fotoğraf: TV'den gelen değişiklikle karışmasın
    var lastSent by remember { mutableStateOf<Long?>(null) }
    val currentCast by rememberUpdatedState(cast)
    val scope = rememberCoroutineScope()
    // Aşağı çekerek kapatma: fotoğrafın aşağı kayma miktarı (px)
    var dragY by remember { mutableStateOf(0f) }
    var dragJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    val screenPx = with(androidx.compose.ui.platform.LocalDensity.current) {
        androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp.toPx()
    }
    val onCastNow by rememberUpdatedState(onCast)

    fun sendCurrent() {
        val item = items.getOrNull(pager.currentPage) ?: return
        lastSent = item.id
        onCastNow(item)
    }

    // Kaydırma durunca (takip açık ve cihaz bağlıysa) yeni fotoğraf TV'ye gider; hızlı kaydırmada ara fotoğraflar atlanır
    var first by remember { mutableStateOf(true) }
    LaunchedEffect(pager.settledPage, follow, connected) {
        if (!first) delay(350)
        first = false
        if (!follow || !connected) return@LaunchedEffect
        val item = items.getOrNull(pager.settledPage) ?: return@LaunchedEffect
        val c = currentCast
        val onTv = c.phase != CastPhase.NONE && c.kind == MediaKind.PHOTO && c.currentId == item.id
        if (!onTv && lastSent != item.id) sendCurrent()
    }

    // TV'deki fotoğraf başka yoldan değişti (slayt gösterisi, önceki/sonraki): görüntüleyici de oraya kayar
    LaunchedEffect(cast.currentId, cast.stepTick) {
        val id = cast.currentId ?: return@LaunchedEffect
        if (!follow || cast.kind != MediaKind.PHOTO || id == lastSent) return@LaunchedEffect
        val i = items.indexOfFirst { it.id == id }
        if (i >= 0 && i != pager.currentPage) { lastSent = id; pager.animateScrollToPage(i) }
    }

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        // Arkadaki ekran karartılmaz: aşağı çekerken albüm arkadan görünsün
        val view = androidx.compose.ui.platform.LocalView.current
        SideEffect { (view.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window?.setDimAmount(0f) }
        val progress = (dragY / screenPx).coerceIn(0f, 1f)
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = (1f - progress * 2.2f).coerceIn(0f, 1f)))) {
            HorizontalPager(
                state = pager,
                beyondViewportPageCount = 1,
                userScrollEnabled = !zoomed && dragY == 0f,
                key = { items[it].id },
                modifier = Modifier.fillMaxSize().graphicsLayer {
                    translationY = dragY
                    val k = 1f - progress * 0.35f
                    scaleX = k; scaleY = k
                }
            ) { page ->
                ZoomablePhoto(
                    item = items[page],
                    library = library,
                    isCurrent = page == pager.currentPage,
                    onZoomChange = { if (page == pager.currentPage) zoomed = it },
                    onTap = { chrome = !chrome },
                    onDismissDrag = { dy -> dragJob?.cancel(); dragY = (dragY + dy).coerceAtLeast(0f) },
                    onDismissEnd = {
                        // Ekranın ~%15'inden fazla çekildiyse kapanır, değilse yerine yaylanır
                        val close = dragY > screenPx * 0.15f
                        dragJob = scope.launch {
                            androidx.compose.animation.core.animate(dragY, if (close) screenPx else 0f,
                                animationSpec = androidx.compose.animation.core.tween(if (close) 180 else 220)) { v, _ -> dragY = v }
                            if (close) onClose()
                        }
                    }
                )
            }

            // Üst: kapat, sıra, cihaz düğmesi
            AnimatedVisibility(chrome && dragY == 0f, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopCenter)) {
                Row(
                    Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.45f))
                        .statusBarsPadding().padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onClose),
                        contentAlignment = Alignment.Center
                    ) { Icon(AppIcons.Close, "Kapat", tint = Pal.Text, modifier = Modifier.size(24.dp)) }
                    Spacer(Modifier.width(6.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            items.getOrNull(pager.currentPage)?.title ?: "", color = Pal.Text, fontSize = 15.sp,
                            fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                        Text("${pager.currentPage + 1} / ${items.size}", color = Pal.Sub, fontSize = 12.sp)
                    }
                    Spacer(Modifier.width(8.dp))
                    // Bağlıyken: TV takibi aç/kapa (kapalıyken telefonda serbestçe gezilir, TV'deki foto değişmez)
                    if (connected) {
                        val shape = RoundedCornerShape(20.dp)
                        Row(
                            Modifier.clip(shape)
                                .then(
                                    if (follow) Modifier.background(Pal.Blue.copy(alpha = 0.18f)).border(1.dp, Pal.Blue.copy(alpha = 0.7f), shape)
                                    else Modifier.background(Pal.Btn)
                                )
                                .clickable { follow = !follow }
                                .padding(horizontal = 10.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                if (follow) Icons.Filled.Sync else Icons.Filled.SyncDisabled, null,
                                tint = if (follow) Color(0xFF9DB8FF) else Pal.Sub, modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(5.dp))
                            Text(
                                "Takip", color = if (follow) Color(0xFFCFDBFF) else Pal.Sub,
                                fontSize = 13.sp, fontWeight = FontWeight.Medium
                            )
                        }
                        Spacer(Modifier.width(6.dp))
                    }
                    CastButton(cc, dlna)
                }
            }

            // Alt: önceki / sıradaki fotoların küçük resim şeridi
            AnimatedVisibility(chrome && dragY == 0f, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.BottomCenter)) {
                val tvId = if (cast.phase != CastPhase.NONE && cast.kind == MediaKind.PHOTO) cast.currentId else null
                Filmstrip(
                    items = items, library = library, current = pager.currentPage, tvId = tvId,
                    modifier = Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.45f))
                        .navigationBarsPadding().padding(vertical = 10.dp)
                ) { i ->
                    scope.launch {
                        if (kotlin.math.abs(i - pager.currentPage) > 3) pager.scrollToPage(i) else pager.animateScrollToPage(i)
                    }
                }
            }
        }
        // Görüntüleyici açıkken cihaz paneli onun üstünde açılır (Cast ekranı kendi panelini bu sırada çizmez)
        CastDevicePanel(cc, dlna, onCcPick, onDlnaPick)
    }
}

/** Tek fotoğraf: önce küçük resim, sonra tam boy; iki parmakla ya da çift dokunarak yakınlaşır. */
@Composable
private fun ZoomablePhoto(
    item: MediaItem,
    library: MediaLibrary,
    isCurrent: Boolean,
    onZoomChange: (Boolean) -> Unit,
    onTap: () -> Unit,
    onDismissDrag: (Float) -> Unit,
    onDismissEnd: () -> Unit
) {
    val dismissDrag by rememberUpdatedState(onDismissDrag)
    val dismissEnd by rememberUpdatedState(onDismissEnd)
    val thumb = remember(item.id) { library.cachedThumbnail(item)?.asImageBitmap() }
    val full by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, item.id) {
        value = withContext(Dispatchers.IO) { library.fullImage(item)?.asImageBitmap() }
    }
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var box by remember { mutableStateOf(IntSize.Zero) }

    fun clamp(o: Offset, s: Float): Offset {
        val mx = box.width * (s - 1f) / 2f
        val my = box.height * (s - 1f) / 2f
        return Offset(o.x.coerceIn(-mx, mx), o.y.coerceIn(-my, my))
    }
    fun set(s: Float, o: Offset) {
        scale = s.coerceIn(1f, 5f)
        offset = if (scale <= 1.01f) Offset.Zero else clamp(o, scale)
        if (scale <= 1.01f) scale = 1f
        onZoomChange(scale > 1f)
    }
    // Başka fotoğrafa geçilince bu fotoğrafın yakınlaştırması sıfırlanır
    LaunchedEffect(isCurrent) { if (!isCurrent && scale != 1f) set(1f, Offset.Zero) }

    Box(
        Modifier.fillMaxSize()
            .onSizeChanged { box = it }
            .pointerInput(item.id) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = { p ->
                        if (scale > 1f) set(1f, Offset.Zero)
                        else {
                            // Dokunulan nokta merkeze yakın kalsın
                            val s = 2.5f
                            val c = Offset(box.width / 2f, box.height / 2f)
                            set(s, (c - p) * (s - 1f))
                        }
                    }
                )
            }
            .pointerInput(item.id) {
                // İki parmak ya da yakınlaştırılmışken: kaydırmayı biz alırız; aksi hâlde sayfa geçişi çalışır
                // Tek parmakla belirgin şekilde aşağı çekilirse (yakın değilken): kapatma hareketi
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    var dismissing = false
                    var decided = false
                    var total = Offset.Zero
                    do {
                        val ev = awaitPointerEvent()
                        val fingers = ev.changes.count { it.pressed }
                        if (dismissing) {
                            if (fingers == 1) dismissDrag(ev.calculatePan().y)
                            ev.changes.forEach { if (it.positionChanged()) it.consume() }
                        } else if (fingers >= 2 || scale > 1f) {
                            decided = true
                            val z = ev.calculateZoom()
                            val p = ev.calculatePan()
                            set(scale * z, offset * z + p)
                            ev.changes.forEach { if (it.positionChanged()) it.consume() }
                        } else if (!decided && fingers == 1) {
                            total += ev.calculatePan()
                            if (total.getDistance() > viewConfiguration.touchSlop) {
                                decided = true
                                val consumed = ev.changes.any { it.isConsumed }
                                if (!consumed && total.y > 0 && total.y > kotlin.math.abs(total.x) * 1.5f) {
                                    dismissing = true
                                    dismissDrag(total.y)
                                    ev.changes.forEach { it.consume() }
                                }
                            }
                        }
                    } while (ev.changes.any { it.pressed })
                    if (dismissing) dismissEnd()
                }
            },
        contentAlignment = Alignment.Center
    ) {
        val img = full ?: thumb
        if (img != null) {
            Image(
                img, item.title, contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().graphicsLayer {
                    scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y
                }
            )
        } else {
            Icon(placeholderFor(item.kind), null, tint = Pal.Sub, modifier = Modifier.size(40.dp))
        }
    }
}

/**
 * Alttaki küçük resim şeridi: baktığın foto ortada ve çerçeveli; dokununca o fotoya atlanır.
 * TV'de o an gösterilen fotonun köşesinde mavi cast işareti.
 */
@Composable
private fun Filmstrip(
    items: List<MediaItem>, library: MediaLibrary, current: Int, tvId: Long?,
    modifier: Modifier, onPick: (Int) -> Unit
) {
    val state = rememberLazyListState(initialFirstVisibleItemIndex = current)
    val density = androidx.compose.ui.platform.LocalDensity.current
    val itemPx = with(density) { 60.dp.roundToPx() }   // 56 dp kutu + 4 dp aralık
    // Baktığın foto şeridin ortasına gelsin
    LaunchedEffect(current) {
        val view = state.layoutInfo.viewportSize.width
        val off = -(view / 2 - itemPx / 2)
        if (view == 0) state.scrollToItem(current) else state.animateScrollToItem(current, off)
    }
    LazyRow(
        state = state,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        contentPadding = PaddingValues(horizontal = 8.dp),
        modifier = modifier
    ) {
        itemsIndexed(items, key = { _, it -> it.id }) { i, item ->
            val sel = i == current
            val shape = RoundedCornerShape(8.dp)
            Box(
                Modifier.size(56.dp).clip(shape)
                    .then(if (sel) Modifier.border(2.dp, Color.White, shape) else Modifier)
                    .clickable { onPick(i) }
            ) {
                Thumb(
                    item, library,
                    Modifier.fillMaxSize().graphicsLayer { alpha = if (sel) 1f else 0.6f },
                    placeholderFor(item.kind), placeholderSize = 20.dp
                )
                if (item.id == tvId) {
                    Box(
                        Modifier.align(Alignment.TopEnd).padding(3.dp).size(18.dp).clip(CircleShape).background(Pal.Blue),
                        contentAlignment = Alignment.Center
                    ) { Icon(Icons.Filled.CastConnected, "TV'de", tint = Color.White, modifier = Modifier.size(11.dp)) }
                }
            }
        }
    }
}
