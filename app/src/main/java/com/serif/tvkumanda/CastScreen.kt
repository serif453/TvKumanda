package com.serif.tvkumanda

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
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

/**
 * Cast sekmesi: tür seçimi, uygulama içi galeri (albümler → öğeler) ve oynatıcı kartı.
 * Galeri izni verilmezse telefonun dosya seçicisiyle devam edilebilir.
 */
@Composable
fun CastScreen(
    cast: CastUi,
    connected: Boolean,
    onPick: (MediaKind) -> Unit,
    onCastItem: (MediaItem) -> Unit,
    onToggle: () -> Unit,
    onSeek: (Double) -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val library = remember { MediaLibrary(context.applicationContext) }
    var browse by remember { mutableStateOf<MediaKind?>(null) }
    var album by remember { mutableStateOf<MediaAlbum?>(null) }
    var permTick by remember { mutableStateOf(0) }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permTick++ }
    // İzin telefonun Ayarlar ekranından verilip uygulamaya dönülürse de yeniden kontrol edilir
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) permTick++ }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
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
                        if (!library.hasPermission(k)) permLauncher.launch(library.permissionsFor(k))
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
                        onAsk = { permLauncher.launch(library.permissionsFor(kind)) },
                        onPicker = { onPick(kind) }
                    )
                } else {
                    val albums by produceState<List<MediaAlbum>?>(null, kind, permTick) {
                        value = withContext(Dispatchers.IO) { runCatching { library.albums(kind) }.getOrDefault(emptyList()) }
                    }
                    val list = albums
                    val open = album
                    when {
                        list == null -> CenterText("Yükleniyor…")
                        list.isEmpty() -> CenterText("Bu telefonda ${kind.label.lowercase()} bulunamadı")
                        open == null -> AlbumGrid(list, library) { album = it }
                        kind == MediaKind.MUSIC -> TrackList(open.items, library, connected, onCastItem)
                        else -> ItemGrid(open.items, library, connected, onCastItem)
                    }
                }
            }
        }
        if (cast.phase != CastPhase.NONE) {
            Spacer(Modifier.height(8.dp))
            PlayerCard(cast, onToggle, onSeek, onStop)
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
private fun Thumb(item: MediaItem, library: MediaLibrary, modifier: Modifier, placeholder: ImageVector) {
    val bmp by produceState<ImageBitmap?>(null, item.kind, item.id) {
        value = withContext(Dispatchers.IO) { library.thumbnail(item)?.asImageBitmap() }
    }
    Box(modifier.background(Pal.Btn), contentAlignment = Alignment.Center) {
        val b = bmp
        if (b != null) Image(b, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        else Icon(placeholder, null, tint = Pal.Sub, modifier = Modifier.size(28.dp))
    }
}

private fun placeholderFor(kind: MediaKind) = when (kind) {
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

@Composable
private fun ItemGrid(items: List<MediaItem>, library: MediaLibrary, enabled: Boolean, onCast: (MediaItem) -> Unit) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        items(items, key = { it.id }) { item ->
            Box(Modifier.aspectRatio(1f).clip(RoundedCornerShape(10.dp)).clickable(enabled = enabled) { onCast(item) }) {
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
            }
        }
    }
}

@Composable
private fun TrackList(items: List<MediaItem>, library: MediaLibrary, enabled: Boolean, onCast: (MediaItem) -> Unit) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxSize()) {
        items(items, key = { it.id }) { item ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(enabled = enabled) { onCast(item) }.padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Thumb(item, library, Modifier.size(48.dp).clip(RoundedCornerShape(10.dp)), AppIcons.MusicNote)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(item.title, color = Pal.Text, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
