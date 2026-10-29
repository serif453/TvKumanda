package com.serif.tvkumanda

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

enum class MediaKind(val label: String, val mimeFilter: String, val fallbackMime: String) {
    PHOTO("Fotoğraf", "image/*", "image/jpeg"),
    VIDEO("Video", "video/*", "video/mp4"),
    MUSIC("Müzik", "audio/*", "audio/mpeg")
}

/** Müzikte tekrar modu. */
enum class MusicRepeat { OFF, ALL, ONE }

enum class CastPhase { NONE, PREPARING, BUFFERING, PLAYING, PAUSED, ENDED, ERROR }

/** Cast ekranının durumu. Süreler saniye cinsinden. */
data class CastUi(
    val phase: CastPhase = CastPhase.NONE,
    val title: String = "",
    val kind: MediaKind = MediaKind.VIDEO,
    val position: Double = 0.0,
    val duration: Double = 0.0,
    val error: String = "",
    /** Hazırlık aşaması ("TV'ye bağlanılıyor…" vb.). */
    val stage: String = "",
    /** Klasör/albüm sırasıyla oynatılıyor mu (önceki/sonraki tuşları için). */
    val hasList: Boolean = false,
    /** Oynatılan dosyanın kimliği (galeride vurgulamak için). */
    val currentId: Long? = null,
    /** Oynatmanın başlatıldığı klasör/albüm. */
    val albumId: Long? = null,
    /** Önceki/sonraki/otomatik geçişte artar: liste oynatılanı takip etsin. */
    val stepTick: Int = 0,
    /** Oynatılan dosya (tam ekran oynatıcıdaki görsel ve alt başlık için). */
    val item: MediaItem? = null,
    /** Chromecast ses düzeyi 0..1 (bilinmiyorsa -1). */
    val volume: Float = -1f,
    /** Oynatmanın başladığı klasörün öğeleri (önizleme şeridi için). */
    val list: List<MediaItem> = emptyList(),
    val repeat: MusicRepeat = MusicRepeat.ALL,
    val shuffle: Boolean = false,
    /** Fotoğraf slayt gösterisi açık mı ve kaç saniyede bir geçiliyor. */
    val slideshow: Boolean = false,
    val slideSec: Int = 5,
    /** Bu videoya altyazı eklenmiş mi. */
    val hasSubtitle: Boolean = false
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

/**
 * Cast sekmesi: tür seçimi, uygulama içi galeri (albümler → öğeler) ve oynatıcı kartı.
 * Galeri izni verilmezse telefonun dosya seçicisiyle devam edilebilir.
 */
@Composable
fun CastScreen(
    cast: CastUi,
    connected: Boolean,
    onPick: (MediaKind) -> Unit,
    onCastItem: (MediaItem, List<MediaItem>, Long) -> Unit,
    onToggle: () -> Unit,
    onSeek: (Double) -> Unit,
    onStop: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onSkip: (Double) -> Unit,
    onWarmup: () -> Unit,
    library: MediaLibrary,
    showInFolderTick: Int,
    sheet: PlayerSheet,
    onSlideshow: () -> Unit,
    onSlideSec: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Sekme açılınca TV'nin Chromecast'ine arka planda (görünmeden) bağlanılır
    LaunchedEffect(connected) { if (connected) onWarmup() }
    var browse by remember { mutableStateOf<MediaKind?>(null) }
    var album by remember { mutableStateOf<MediaAlbum?>(null) }
    var permTick by remember { mutableStateOf(0) }
    // Oynatıcı kartından "oynatılan klasöre dön": klasör açılınca liste öğeye kaydırılır
    var jumpAlbum by remember { mutableStateOf<Long?>(null) }
    var jumpTick by remember { mutableStateOf(0) }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permTick++ }
    // İzin telefonun Ayarlar ekranından verilip uygulamaya dönülürse de yeniden kontrol edilir
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) permTick++ }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }

    // Tam ekran oynatıcıdaki "Klasörde göster": oynatmanın başladığı klasöre dön, öğeyi göster
    LaunchedEffect(showInFolderTick) {
        if (showInFolderTick == 0) return@LaunchedEffect
        val target = cast.albumId ?: return@LaunchedEffect
        if (browse == cast.kind && album?.id == target) jumpTick++
        else { browse = cast.kind; album = null; jumpAlbum = target }
    }

    BackHandler(enabled = browse != null) {
        if (album != null) album = null else browse = null
    }

    Column(modifier.fillMaxWidth().padding(top = 12.dp)) {
        val kind = browse
        if (kind == null) {
            Text("TV'de aç", color = Pal.Text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(
                if (connected) "Telefondaki fotoğraf, video ya da müziği TV'de oynat" else "Önce bir TV'ye bağlan",
                color = Pal.Sub, fontSize = 14.sp
            )
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                for ((k, icon) in listOf(MediaKind.PHOTO to AppIcons.Photo, MediaKind.VIDEO to AppIcons.Movie, MediaKind.MUSIC to AppIcons.MusicNote)) {
                    KindButton(icon, k.label, Modifier.weight(1f), connected) {
                        browse = k
                        album = null
                        if (!library.hasPermission(k)) { library.markAsked(k); permLauncher.launch(library.permissionsFor(k)) }
                    }
                }
            }
            Spacer(Modifier.weight(1f))
        } else {
            // Başlık: geri + albüm/tür adı
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(44.dp).clip(CircleShape).clickable { if (album != null) album = null else browse = null },
                    contentAlignment = Alignment.Center
                ) { Icon(AppIcons.ArrowBack, "Geri", tint = Pal.Text, modifier = Modifier.size(26.dp)) }
                Spacer(Modifier.width(6.dp))
                Text(
                    album?.name ?: kind.label,
                    color = Pal.Text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.height(10.dp))
            Box(Modifier.weight(1f).fillMaxWidth()) {
                // permTick'e bağlı: izin cevabı gelince ya da uygulamaya dönülünce yeniden kontrol edilir
                val granted = remember(kind, permTick) { library.hasPermission(kind) }
                if (!granted) {
                    PermissionNeeded(
                        onAsk = { library.markAsked(kind); permLauncher.launch(library.permissionsFor(kind)) },
                        onPicker = { onPick(kind) }
                    )
                } else {
                    val albums by produceState<List<MediaAlbum>?>(null, kind, permTick) {
                        value = withContext(Dispatchers.IO) { runCatching { library.albums(kind) }.getOrDefault(emptyList()) }
                    }
                    val list = albums
                    // "Oynatılan klasöre dön" isteği: klasör listesi yüklenince o klasör açılır
                    LaunchedEffect(list, jumpAlbum) {
                        val target = jumpAlbum
                        if (list != null && target != null) {
                            list.firstOrNull { it.id == target }?.let { album = it; jumpTick++ }
                            jumpAlbum = null
                        }
                    }
                    val open = album
                    val current = if (cast.phase != CastPhase.NONE && cast.kind == kind) cast.currentId else null
                    // Liste yalnızca oynatmanın başladığı klasörde kendiliğinden kayar
                    val follow = open != null && open.id == cast.albumId && cast.kind == kind
                    val scrollKey = if (follow) cast.stepTick * 1000 + jumpTick else -1
                    when {
                        list == null -> CenterText("Yükleniyor…")
                        list.isEmpty() -> CenterText("Bu telefonda ${kind.label.lowercase()} bulunamadı")
                        open == null -> AlbumGrid(list, library) { album = it }
                        kind == MediaKind.MUSIC -> TrackList(open.items, library, connected, current, scrollKey) { onCastItem(it, open.items, open.id) }
                        else -> ItemGrid(open.items, library, connected, current, scrollKey) { onCastItem(it, open.items, open.id) }
                    }
                }
            }
        }
        if (cast.phase != CastPhase.NONE) {
            Spacer(Modifier.height(8.dp))
            // Kart başlığına dokununca tam ekran oynatıcı açılır
            PlayerCard(cast, onToggle, onSeek, onStop, onPrev, onNext, onSkip, sheet, onSlideshow, onSlideSec)
        }
        Spacer(Modifier.height(6.dp))
    }
}

@Composable
private fun CenterText(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, color = Pal.Sub, fontSize = 15.sp, textAlign = TextAlign.Center)
    }
}

@Composable
private fun PermissionNeeded(onAsk: () -> Unit, onPicker: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            "Galeriyi göstermek için telefondaki medyaya erişim izni gerekiyor. " +
                "Dosyalar yalnızca seçtiğinde ve yalnızca TV'ne gönderilir.",
            color = Pal.Sub, fontSize = 14.sp, textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(16.dp))
        PillButton("İzin ver", primary = true, onClick = onAsk)
        Spacer(Modifier.height(10.dp))
        PillButton("Dosya seçiciyle seç", primary = false, onClick = onPicker)
    }
}

@Composable
private fun PillButton(label: String, primary: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(22.dp))
            .background(if (primary) Pal.Blue else Pal.Btn)
            .clickable(onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 12.dp)
    ) { Text(label, color = Pal.Text, fontSize = 15.sp, fontWeight = FontWeight.Medium) }
}

/** Küçük resim: arka planda yüklenir ve önbelleğe alınır. */
@Composable
internal fun Thumb(
    item: MediaItem, library: MediaLibrary, modifier: Modifier, placeholder: ImageVector,
    px: Int = 320, placeholderSize: Dp = 28.dp, scale: ContentScale = ContentScale.Crop
) {
    val bmp by produceState<ImageBitmap?>(null, item.kind, item.id, px) {
        value = withContext(Dispatchers.IO) { library.thumbnail(item, px)?.asImageBitmap() }
    }
    Box(modifier.background(Pal.Btn), contentAlignment = Alignment.Center) {
        val b = bmp
        if (b != null) Image(b, null, contentScale = scale, modifier = Modifier.fillMaxSize())
        else Icon(placeholder, null, tint = Pal.Sub, modifier = Modifier.size(placeholderSize))
    }
}

internal fun placeholderFor(kind: MediaKind) = when (kind) {
    MediaKind.PHOTO -> AppIcons.Photo
    MediaKind.VIDEO -> AppIcons.Movie
    MediaKind.MUSIC -> AppIcons.MusicNote
}

@Composable
private fun AlbumGrid(albums: List<MediaAlbum>, library: MediaLibrary, onOpen: (MediaAlbum) -> Unit) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        items(albums, key = { it.id }) { a ->
            Column(Modifier.clip(RoundedCornerShape(16.dp)).clickable { onOpen(a) }) {
                Thumb(a.cover, library, Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(16.dp)), placeholderFor(a.cover.kind))
                Spacer(Modifier.height(6.dp))
                Text(a.name, color = Pal.Text, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${a.count}", color = Pal.Sub, fontSize = 12.sp)
            }
        }
    }
}

/**
 * Liste oynatılanı takip eder: [scrollKey] değişince (önceki/sonraki/otomatik geçiş ya da
 * "klasöre dön") oynatılan öğe ekranda değilse yumuşakça ona kaydırılır; görünüyorsa kımıldamaz.
 */
@Composable
private fun FollowCurrent(state: androidx.compose.foundation.lazy.grid.LazyGridState?, listState: androidx.compose.foundation.lazy.LazyListState?, index: Int, scrollKey: Int) {
    LaunchedEffect(scrollKey) {
        if (scrollKey < 0 || index < 0) return@LaunchedEffect
        if (state != null) {
            val visible = state.layoutInfo.visibleItemsInfo
            val fully = visible.any { it.index == index && it.offset.y >= 0 &&
                it.offset.y + it.size.height <= state.layoutInfo.viewportEndOffset }
            if (!fully) state.animateScrollToItem(index)
        } else if (listState != null) {
            val visible = listState.layoutInfo.visibleItemsInfo
            val fully = visible.any { it.index == index && it.offset >= 0 &&
                it.offset + it.size <= listState.layoutInfo.viewportEndOffset }
            if (!fully) listState.animateScrollToItem(index)
        }
    }
}

@Composable
private fun ItemGrid(
    items: List<MediaItem>, library: MediaLibrary, enabled: Boolean,
    currentId: Long?, scrollKey: Int, onCast: (MediaItem) -> Unit
) {
    val state = rememberLazyGridState()
    FollowCurrent(state, null, items.indexOfFirst { it.id == currentId }, scrollKey)
    LazyVerticalGrid(
        state = state,
        columns = GridCells.Fixed(3),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        items(items, key = { it.id }) { item ->
            val playing = item.id == currentId
            Box(
                Modifier.aspectRatio(1f).clip(RoundedCornerShape(10.dp))
                    .clickable(enabled = enabled) { onCast(item) }
            ) {
                Thumb(item, library, Modifier.fillMaxSize(), placeholderFor(item.kind))
                if (item.kind == MediaKind.VIDEO && item.durationMs > 0) {
                    Text(
                        timeText(item.durationMs / 1000.0),
                        color = Pal.Text, fontSize = 11.sp, fontWeight = FontWeight.Medium,
                        modifier = Modifier.align(Alignment.BottomEnd).padding(5.dp)
                            .clip(RoundedCornerShape(6.dp)).background(Pal.Bg.copy(alpha = 0.7f))
                            .padding(horizontal = 5.dp, vertical = 1.dp)
                    )
                }
                if (playing) {
                    // Oynatılan: mavi çerçeve + köşede küçük "oynatılıyor" simgesi
                    Box(Modifier.fillMaxSize().border(3.dp, Pal.Blue, RoundedCornerShape(10.dp)))
                    PlayingBadge(Modifier.align(Alignment.TopEnd).padding(6.dp))
                }
            }
        }
    }
}

@Composable
private fun PlayingBadge(modifier: Modifier) {
    Box(
        modifier.size(24.dp).clip(CircleShape).background(Pal.Blue),
        contentAlignment = Alignment.Center
    ) { Icon(AppIcons.PlayArrow, "Oynatılıyor", tint = Pal.Text, modifier = Modifier.size(16.dp)) }
}

@Composable
private fun TrackList(
    items: List<MediaItem>, library: MediaLibrary, enabled: Boolean,
    currentId: Long?, scrollKey: Int, onCast: (MediaItem) -> Unit
) {
    val state = rememberLazyListState()
    FollowCurrent(null, state, items.indexOfFirst { it.id == currentId }, scrollKey)
    LazyColumn(state = state, verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxSize()) {
        items(items, key = { it.id }) { item ->
            val playing = item.id == currentId
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                    .background(if (playing) Pal.Blue.copy(alpha = 0.16f) else Pal.Bg)
                    .clickable(enabled = enabled) { onCast(item) }.padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.size(48.dp)) {
                    Thumb(item, library, Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp)), AppIcons.MusicNote)
                    if (playing) PlayingBadge(Modifier.align(Alignment.Center))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        item.title, color = if (playing) Pal.Blue else Pal.Text, fontSize = 15.sp,
                        fontWeight = if (playing) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    if (item.subtitle.isNotBlank()) Text(item.subtitle, color = Pal.Sub, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (item.durationMs > 0) Text(timeText(item.durationMs / 1000.0), color = Pal.Sub, fontSize = 12.sp)
            }
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
private fun PlayerCard(
    cast: CastUi,
    onToggle: () -> Unit,
    onSeek: (Double) -> Unit,
    onStop: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onSkip: (Double) -> Unit,
    sheet: PlayerSheet,
    onSlideshow: () -> Unit,
    onSlideSec: () -> Unit
) {
    Column(
        Modifier
            .fillMaxWidth()
            // Tam ekran oynatıcı bu kartın üst kenarından açılır ve buraya kapanır
            .onGloballyPositioned { sheet.cardTop = it.positionInRoot().y }
            .clip(RoundedCornerShape(24.dp))
            .background(Brush.verticalGradient(listOf(Pal.Btn, Pal.Pad)))
            .padding(18.dp)
    ) {
        // Başlık satırı: dosya adı + durum, sağ üstte küçük durdur tuşu
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Dosya adına dokununca ya da yukarı kaydırınca tam ekran oynatıcı açılır
            Column(
                Modifier.weight(1f).clip(RoundedCornerShape(10.dp))
                    .swipeUpToOpen(sheet)
                    .clickable { sheet.open() }
            ) {
                Text(
                    cast.title.ifBlank { cast.kind.label },
                    color = Pal.Text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    when (cast.phase) {
                        CastPhase.PREPARING -> cast.stage.ifBlank { "TV'ye gönderiliyor…" }
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
            }
            Spacer(Modifier.width(10.dp))
            RoundAction(AppIcons.Stop, "Durdur", size = 40.dp, iconSize = 20.dp, onClick = onStop)
        }

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
            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RoundAction(AppIcons.SkipPrevious, "Önceki", enabled = cast.hasList, onClick = onPrev)
            if (cast.kind != MediaKind.PHOTO) {
                RoundAction(AppIcons.Replay10, "10 saniye geri", onClick = { onSkip(-10.0) })
                val playing = cast.phase == CastPhase.PLAYING || cast.phase == CastPhase.BUFFERING
                RoundAction(
                    if (playing) AppIcons.Pause else AppIcons.PlayArrow, if (playing) "Duraklat" else "Oynat",
                    size = 64.dp, iconSize = 32.dp, primary = true, onClick = onToggle
                )
                RoundAction(AppIcons.Forward10, "10 saniye ileri", onClick = { onSkip(10.0) })
            } else {
                SlideshowControls(cast, onSlideshow, onSlideSec, big = 64.dp)
            }
            RoundAction(AppIcons.SkipNext, "Sonraki", enabled = cast.hasList, onClick = onNext)
        }
    }
}

@Composable
internal fun RoundAction(
    icon: ImageVector,
    desc: String,
    size: Dp = 48.dp,
    iconSize: Dp = 26.dp,
    primary: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Box(
        Modifier.size(size).clip(CircleShape)
            .background(if (primary) Pal.Blue else Pal.BtnPressed)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, desc, tint = if (enabled) Pal.Text else Pal.Sub.copy(alpha = 0.4f), modifier = Modifier.size(iconSize))
    }
}

/** Fotoğraf slayt gösterisi: oynat/duraklat ve süre seçimi (3 / 5 / 10 sn). */
@Composable
internal fun SlideshowControls(cast: CastUi, onSlideshow: () -> Unit, onSlideSec: () -> Unit, big: Dp) {
    if (cast.list.size <= 1) { Spacer(Modifier.width(40.dp)); return }
    RoundAction(
        if (cast.slideshow) AppIcons.Pause else AppIcons.PlayArrow,
        if (cast.slideshow) "Slaytı durdur" else "Slayt gösterisi",
        size = big, iconSize = big / 2, primary = true, onClick = onSlideshow
    )
    Text(
        "${cast.slideSec} sn", color = Pal.Text, fontSize = 13.sp, fontWeight = FontWeight.Medium,
        modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(Pal.BtnPressed)
            .clickable(onClick = onSlideSec).padding(horizontal = 10.dp, vertical = 8.dp)
    )
}

internal fun timeText(sec: Double): String {
    val s = sec.toLong().coerceAtLeast(0)
    val h = s / 3600; val m = (s % 3600) / 60; val r = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, r) else "%d:%02d".format(m, r)
}
