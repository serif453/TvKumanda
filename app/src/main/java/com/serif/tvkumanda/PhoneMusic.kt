package com.serif.tvkumanda

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Telefonda müzik çalma (Serif, 2 Kasım 2026): Cast → Müzik'te bağlı değilken şarkıya dokununca telefonda çalar.
 * Sade: yalnızca uygulama açıkken çalar (arka plana geçince duraklar), karıştır/tekrarla yok, albüm sonunda durur.
 * "TV'de çal" şarkıyı kaldığı saniyeden TV'ye geçirir; TV'de müzik başlayınca telefon susar (MainActivity.castMedia).
 */
object PhonePlayer {
    var list by mutableStateOf<List<MediaItem>>(emptyList())
        private set
    var index by mutableStateOf(0)
        private set
    var albumId = 0L
        private set
    var playing by mutableStateOf(false)
        private set
    var durationMs by mutableStateOf(0L)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    val current: MediaItem? get() = list.getOrNull(index)
    val active: Boolean get() = current != null

    private var mp: MediaPlayer? = null
    private var prepared = false
    private var app: Context? = null
    private var focus: AudioFocusRequest? = null

    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()

    fun play(context: Context, items: List<MediaItem>, at: Int, album: Long) {
        if (items.isEmpty() || at !in items.indices) return
        app = context.applicationContext
        list = items
        albumId = album
        start(at)
    }

    private fun start(i: Int) {
        val ctx = app ?: return
        releasePlayer()
        index = i
        error = null
        val item = list[i]
        durationMs = item.durationMs
        playing = false
        val p = MediaPlayer()
        mp = p
        runCatching {
            p.setAudioAttributes(attrs)
            p.setDataSource(ctx, item.uri)
            p.setOnPreparedListener {
                if (mp !== p) return@setOnPreparedListener
                prepared = true
                if (it.duration > 0) durationMs = it.duration.toLong()
                if (requestFocus()) { it.start(); playing = true }
            }
            p.setOnCompletionListener { if (mp === p) next(auto = true) }
            p.setOnErrorListener { _, _, _ ->
                if (mp === p) { playing = false; error = "Bu şarkı çalınamadı" }
                true
            }
            p.prepareAsync()
        }.onFailure { playing = false; error = "Bu şarkı çalınamadı" }
    }

    /** Şu anki konum (ms). */
    fun positionMs(): Long =
        mp?.takeIf { prepared }?.let { runCatching { it.currentPosition.toLong() }.getOrDefault(0L) } ?: 0L

    fun toggle() {
        val p = mp ?: return
        if (!prepared) return
        if (p.isPlaying) { p.pause(); playing = false }
        else if (requestFocus()) { p.start(); playing = true }
    }

    fun pause() {
        val p = mp ?: return
        if (prepared && p.isPlaying) p.pause()
        playing = false
    }

    fun seek(ms: Long) { mp?.takeIf { prepared }?.seekTo(ms.toInt()) }

    /** [deltaMs] kadar ileri (artı) ya da geri (eksi). */
    fun skip(deltaMs: Long) {
        val end = if (durationMs > 0) durationMs - 500 else Long.MAX_VALUE
        seek((positionMs() + deltaMs).coerceIn(0L, maxOf(0L, end)))
    }

    /** Sonraki şarkı; albüm bitince durur (kendiliğinden geçişte) ya da başa döner (düğmeyle). */
    fun next(auto: Boolean = false) {
        if (list.isEmpty()) return
        if (index + 1 < list.size) start(index + 1)
        else if (auto) { pause(); seek(0) }
        else start(0)
    }

    /** Önceki: şarkının 3. saniyesinden sonra başa sarar, değilse önceki şarkıya geçer. */
    fun prev() {
        if (list.isEmpty()) return
        if (positionMs() > 3000) seek(0)
        else start(if (index > 0) index - 1 else list.lastIndex)
    }

    /** Tamamen durdurur; oynatıcı kartı kaybolur. */
    fun stop() {
        releasePlayer()
        list = emptyList()
        index = 0
        playing = false
        error = null
        abandonFocus()
    }

    private fun releasePlayer() {
        val p = mp ?: return
        mp = null
        prepared = false
        runCatching { p.stop() }
        runCatching { p.release() }
    }

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        // Arama, başka bir uygulamanın müziği vb.: duraklat (kendiliğinden devam etmez; sade)
        if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) pause()
    }

    private fun requestFocus(): Boolean {
        val am = app?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return true
        val req = focus ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attrs)
            .setOnAudioFocusChangeListener(focusListener)
            .build().also { focus = it }
        return am.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun abandonFocus() {
        val am = app?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        focus?.let { am.abandonAudioFocusRequest(it) }
    }
}

/**
 * Tam ekran müzik oynatıcısının yeri: 0 = tamamen açık, 1 = aşağıda (mini kart). Mini kart yukarı sürüklenince,
 * oynatıcı aşağı çekilince parmağı takip eder; bırakınca en yakın uca yaylanır.
 */
class MusicSheet(private val scope: CoroutineScope) {
    var frac by mutableStateOf(1f)
        private set
    var visible by mutableStateOf(false)
        private set
    private var anim: Job? = null
    /** Mini kartın üst kenarının ekrandaki yeri (px): oynatıcı buradan yükselir, buraya iner. */
    var anchor by mutableStateOf(0f)

    /** Sürükleme: [d] kapalı konuma olan uzaklığa oranla (aşağı artı). */
    fun drag(d: Float) { anim?.cancel(); visible = true; frac = (frac + d).coerceIn(0f, 1f) }
    /** Bırakınca: [limit]'ten yukarıdaysa açılır, değilse kapanır. */
    fun settle(limit: Float) { if (frac < limit) open() else close() }
    fun open() { visible = true; to(0f) }
    fun close() { to(1f) { visible = false } }
    /** Animasyonsuz kapat (TV'ye geçiş, şarkı durdurma). */
    fun hide() { anim?.cancel(); frac = 1f; visible = false }

    private fun to(target: Float, end: () -> Unit = {}) {
        anim?.cancel()
        anim = scope.launch {
            animate(frac, target, animationSpec = tween(260)) { v, _ -> frac = v }
            end()
        }
    }
}

@Composable
fun rememberMusicSheet(): MusicSheet {
    val scope = rememberCoroutineScope()
    return remember { MusicSheet(scope) }
}

/** Ekrandaki konum: çalarken yarım saniyede bir güncellenir. */
@Composable
private fun rememberPhonePosition(): Long {
    var pos by remember { mutableStateOf(PhonePlayer.positionMs()) }
    LaunchedEffect(PhonePlayer.playing, PhonePlayer.index, PhonePlayer.list) {
        while (true) { pos = PhonePlayer.positionMs(); if (!PhonePlayer.playing) break; delay(500) }
    }
    return pos
}

/** Cast ekranının altındaki küçük "telefonda çalıyor" kartı; dokununca tam ekran oynatıcı açılır. */
@Composable
fun PhoneMiniCard(library: MediaLibrary, sheet: MusicSheet) {
    val item = PhonePlayer.current ?: return
    val pos = rememberPhonePosition()
    val dur = PhonePlayer.durationMs
    val screenPx = with(LocalDensity.current) { LocalConfiguration.current.screenHeightDp.dp.toPx() }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Pal.Btn)
            .clickable { sheet.open() }
            // Yukarı sürükleyince tam ekran oynatıcı parmakla birlikte yükselir; çeyrekten fazla çekilirse açılır
            .onGloballyPositioned { sheet.anchor = it.positionInWindow().y }
            .pointerInput(screenPx) {
                detectVerticalDragGestures(
                    onDragEnd = { sheet.settle(0.75f) },
                    onDragCancel = { sheet.settle(0.75f) }
                ) { ch, dy -> ch.consume(); sheet.drag(dy / sheet.anchor.takeIf { it > 0f }.let { it ?: screenPx }) }
            }
    ) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Thumb(item, library, Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)), AppIcons.MusicNote)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(item.title, color = Pal.Text, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    PhonePlayer.error ?: "Telefonda çalıyor", color = if (PhonePlayer.error != null) Pal.Red else Pal.Sub,
                    fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.width(8.dp))
            RoundAction(
                if (PhonePlayer.playing) AppIcons.Pause else AppIcons.PlayArrow,
                if (PhonePlayer.playing) "Duraklat" else "Çal",
                size = 44.dp, iconSize = 24.dp, primary = true
            ) { PhonePlayer.toggle() }
            Spacer(Modifier.width(6.dp))
            RoundAction(AppIcons.Close, "Kapat", size = 40.dp, iconSize = 20.dp) { PhonePlayer.stop() }
        }
        if (dur > 0) {
            Box(Modifier.fillMaxWidth().height(3.dp).background(Pal.BtnPressed)) {
                Box(Modifier.fillMaxWidth((pos.toFloat() / dur).coerceIn(0f, 1f)).fillMaxHeight().background(Pal.Blue))
            }
        }
    }
}

/** Tam ekran telefon oynatıcısı: kapak, ilerleme, önceki / çal / sonraki ve "TV'de çal". */
@Composable
fun PhoneMusicPlayer(
    library: MediaLibrary,
    cc: CcUi,
    dlna: DlnaUi,
    onCcPick: (FoundTv?) -> Unit,
    onDlnaPick: (DlnaDevice) -> Unit,
    sheet: MusicSheet
) {
    val item = PhonePlayer.current
    // TV'ye geçilince (ya da kart kapatılınca) oynatıcı da kapanır
    LaunchedEffect(item == null) { if (item == null) sheet.hide() }
    if (item == null || !sheet.visible) return
    val pos = rememberPhonePosition()
    val dur = PhonePlayer.durationMs
    val onClose = { sheet.close() }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        // Arkadaki ekran karartılmaz: oynatıcı aşağı inerken Cast ekranı görünsün
        val view = LocalView.current
        SideEffect { (view.parent as? DialogWindowProvider)?.window?.setDimAmount(0f) }
        Column(
            Modifier.fillMaxSize()
                // Kapalıyken üst kenarı mini kartın üstünde durur; sona doğru kartın içinde kaybolur
                .graphicsLayer {
                    val dist = sheet.anchor.takeIf { it > 0f } ?: size.height.toFloat()
                    translationY = sheet.frac * dist
                    alpha = 1f - ((sheet.frac - 0.85f) / 0.15f).coerceIn(0f, 1f)
                }
                // Aşağı çekince parmakla birlikte iner; çeyrekten fazla çekilirse mini karta küçülür
                .pointerInput(Unit) {
                    detectVerticalDragGestures(
                        onDragEnd = { sheet.settle(0.25f) },
                        onDragCancel = { sheet.settle(0.25f) }
                    ) { ch, dy -> ch.consume(); sheet.drag(dy / (sheet.anchor.takeIf { it > 0f } ?: size.height.toFloat())) }
                }
                .background(Pal.Bg).systemBarsPadding().padding(horizontal = 20.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Üst: kapat + cihaz düğmesi
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onClose),
                    contentAlignment = Alignment.Center
                ) { Icon(AppIcons.ArrowBack, "Küçült", tint = Pal.Text, modifier = Modifier.size(24.dp).graphicsLayer { rotationZ = -90f }) }
                Spacer(Modifier.weight(1f))
                CastButton(cc, dlna)
            }
            Spacer(Modifier.weight(0.5f))
            Thumb(
                item, library, Modifier.fillMaxWidth(0.85f).aspectRatio(1f).clip(RoundedCornerShape(20.dp)),
                AppIcons.MusicNote, px = 800, placeholderSize = 72.dp, scale = ContentScale.Crop
            )
            Spacer(Modifier.height(24.dp))
            Text(
                item.title, color = Pal.Text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center
            )
            if (item.subtitle.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(item.subtitle, color = Pal.Sub, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            PhonePlayer.error?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, color = Pal.Red, fontSize = 13.sp)
            }
            Spacer(Modifier.height(16.dp))

            // İlerleme
            var dragging by remember(item.id) { mutableStateOf<Float?>(null) }
            val fraction = dragging ?: if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f
            Slider(
                value = fraction,
                enabled = dur > 0,
                onValueChange = { dragging = it },
                onValueChangeFinished = { dragging?.let { PhonePlayer.seek((it * dur).toLong()) }; dragging = null },
                colors = SliderDefaults.colors(thumbColor = Pal.Text, activeTrackColor = Pal.Blue, inactiveTrackColor = Pal.BtnPressed)
            )
            Row(Modifier.fillMaxWidth()) {
                Text(timeText(fraction * dur / 1000.0), color = Pal.Sub, fontSize = 12.sp)
                Spacer(Modifier.weight(1f))
                Text(timeText(dur / 1000.0), color = Pal.Sub, fontSize = 12.sp)
            }
            Spacer(Modifier.height(12.dp))

            // Önceki / 10 sn geri / çal-duraklat / 10 sn ileri / sonraki
            Row(verticalAlignment = Alignment.CenterVertically) {
                RoundAction(AppIcons.SkipPrevious, "Önceki", size = 48.dp, iconSize = 24.dp) { PhonePlayer.prev() }
                Spacer(Modifier.width(12.dp))
                RoundAction(AppIcons.Replay10, "10 saniye geri", size = 52.dp, iconSize = 26.dp) { PhonePlayer.skip(-10_000) }
                Spacer(Modifier.width(12.dp))
                RoundAction(
                    if (PhonePlayer.playing) AppIcons.Pause else AppIcons.PlayArrow,
                    if (PhonePlayer.playing) "Duraklat" else "Çal",
                    size = 72.dp, iconSize = 36.dp, primary = true
                ) { PhonePlayer.toggle() }
                Spacer(Modifier.width(12.dp))
                RoundAction(AppIcons.Forward10, "10 saniye ileri", size = 52.dp, iconSize = 26.dp) { PhonePlayer.skip(10_000) }
                Spacer(Modifier.width(12.dp))
                RoundAction(AppIcons.SkipNext, "Sonraki", size = 48.dp, iconSize = 24.dp) { PhonePlayer.next() }
            }
            Spacer(Modifier.height(20.dp))

            // Telefonun medya sesi (yan tuşlarla değişince çubuk da güncellenir)
            PhoneVolumeRow()
            Spacer(Modifier.weight(1f))
        }
        // Oynatıcı açıkken cihaz paneli onun üstünde açılır
        CastDevicePanel(cc, dlna, onCcPick, onDlnaPick)
    }
}

/** Telefonun medya ses düzeyi çubuğu. */
@Composable
private fun PhoneVolumeRow() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val am = remember { ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val max = remember { am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1) }
    var vol by remember { mutableStateOf(am.getStreamVolume(AudioManager.STREAM_MUSIC)) }
    var dragging by remember { mutableStateOf<Float?>(null) }
    DisposableEffect(Unit) {
        val obs = object : android.database.ContentObserver(android.os.Handler(android.os.Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { vol = am.getStreamVolume(AudioManager.STREAM_MUSIC) }
        }
        ctx.contentResolver.registerContentObserver(android.provider.Settings.System.CONTENT_URI, true, obs)
        onDispose { ctx.contentResolver.unregisterContentObserver(obs) }
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Icon(AppIcons.VolumeUp, "Ses", tint = Pal.Text, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(10.dp))
        Slider(
            value = dragging ?: vol.toFloat(),
            valueRange = 0f..max.toFloat(),
            onValueChange = { v ->
                dragging = v
                val n = kotlin.math.round(v).toInt()
                if (n != vol) { vol = n; runCatching { am.setStreamVolume(AudioManager.STREAM_MUSIC, n, 0) } }
            },
            onValueChangeFinished = { dragging = null },
            colors = SliderDefaults.colors(thumbColor = Pal.Text, activeTrackColor = Pal.Blue, inactiveTrackColor = Pal.BtnPressed),
            modifier = Modifier.weight(1f)
        )
    }
}
