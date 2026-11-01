package com.serif.tvkumanda

import android.widget.Toast
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Dialpad
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Input
import androidx.compose.material.icons.outlined.Mouse
import androidx.compose.material.icons.outlined.OndemandVideo
import androidx.compose.material.icons.outlined.QueueMusic
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material.icons.outlined.Tv
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Kumanda sekmesindeki yazılar Inter ile; diğer sekmeler değişmez. */
@Composable
fun GlassText(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalTextStyle provides LocalTextStyle.current.copy(fontFamily = Glass.Inter), content = content)
}

// ---------------- Üst şerit ----------------

@Composable
fun GlassTopBar(ui: UiState, onSettings: () -> Unit, onPicker: () -> Unit, onPower: () -> Unit) {
    GlassText {
        Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
            GlassSquare(Modifier.size(52.dp), RoundedCornerShape(18.dp), onClick = onSettings) {
                Icon(Icons.Outlined.Settings, "Ayarlar", tint = Glass.Text, modifier = Modifier.size(26.dp))
            }
            Spacer(Modifier.width(12.dp))
            // Ortada kutu içinde TV adı (dokununca TV seçici) ve bağlantı durumu
            val pillShape = RoundedCornerShape(26.dp)
            val inter = remember { MutableInteractionSource() }
            val pressed by inter.collectIsPressedAsState()
            Column(
                Modifier.weight(1f).height(54.dp).glass(pillShape, pressed, strength = 0.8f)
                    .clickable(interactionSource = inter, indication = null, onClick = onPicker)
                    .padding(horizontal = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center
            ) {
                // Uzun TV adı kutudan taşmasın: simge ve ok hep görünür, ad sığmazsa "…" ile kısalır
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Tv, null, tint = Glass.Text, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        when (ui.state) {
                            ConnState.CONNECTED -> ui.tvName.ifBlank { "TV" }
                            ConnState.CONNECTING -> "Bağlanıyor…"
                            else -> "TV Seç"
                        },
                        color = Glass.Text, fontSize = 17.sp, lineHeight = 21.sp, fontWeight = FontWeight.Bold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false)
                    )
                    Spacer(Modifier.width(4.dp))
                    Icon(Icons.Filled.KeyboardArrowDown, null, tint = Glass.Text, modifier = Modifier.size(22.dp))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(ui.state)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        when (ui.state) {
                            ConnState.CONNECTED -> "Bağlı · " + (if (ui.method == ConnMethod.STANDARD) "Standart mod" else "ADB" + if (ui.fastMode) " ⚡" else "")
                            ConnState.CONNECTING -> "Bağlanıyor…"
                            ConnState.ERROR -> "Bağlantı yok"
                            ConnState.IDLE -> "Bağlı değil"
                        },
                        color = Glass.Sub, fontSize = 12.sp, lineHeight = 15.sp, fontWeight = FontWeight.Medium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            // Güç: kırmızı cam
            val on = ui.state == ConnState.CONNECTED
            val pInter = remember { MutableInteractionSource() }
            val pScale = pressScale(pInter)
            val pPressed by pInter.collectIsPressedAsState()
            val shape = RoundedCornerShape(18.dp)
            Box(
                Modifier.size(52.dp).graphicsLayer { scaleX = pScale; scaleY = pScale }
                    .glow(Glass.Red.copy(alpha = 0.6f), 10.dp, shape)
                    .clip(shape)
                    .background(Brush.verticalGradient(if (pPressed) listOf(Color(0xFF8A2C40), Color(0xFF55192C)) else listOf(Color(0xFF6E2234), Color(0xFF431625))))
                    .border(1.dp, Brush.verticalGradient(listOf(Color(0xFFFF7A88).copy(alpha = 0.55f), Color(0xFFFF4D5E).copy(alpha = 0.15f))), shape)
                    .pressable(pInter, onClick = onPower),
                contentAlignment = Alignment.Center
            ) {
                Icon(AppIcons.Power, "Güç", tint = if (on) Color(0xFFFF8A95) else Glass.Red, modifier = Modifier.size(26.dp))
            }
        }
    }
}

/** Uygulama satırı boşken: cam kutu, solda ekran simgesi, ortada açıklama, sağda ok. */
@Composable
fun GlassAppsEmpty(text: String) {
    val shape = RoundedCornerShape(28.dp)
    GlassText {
        Row(
            Modifier.fillMaxWidth().height(58.dp).glass(shape, strength = 0.75f).padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Outlined.OndemandVideo, null, tint = Glass.Text, modifier = Modifier.size(30.dp))
            Spacer(Modifier.width(16.dp))
            Text(text, color = Glass.Text, fontSize = 14.sp, lineHeight = 18.sp, maxLines = 2, modifier = Modifier.weight(1f))
            Icon(Icons.Outlined.ChevronRight, null, tint = Glass.Lavender, modifier = Modifier.size(24.dp))
        }
    }
}

// ---------------- Tuşlar ----------------

/** Yumuşak köşeli cam tuş. */
@Composable
fun GlassSquare(
    modifier: Modifier,
    shape: RoundedCornerShape = RoundedCornerShape(16.dp),
    selected: Boolean = false,
    enabled: Boolean = true,
    repeat: Boolean = false,
    onClick: () -> Unit,
    content: @Composable () -> Unit
) {
    val inter = remember { MutableInteractionSource() }
    val scale = pressScale(inter)
    val pressed by inter.collectIsPressedAsState()
    Box(
        modifier.alpha(if (enabled) 1f else 0.4f).graphicsLayer { scaleX = scale; scaleY = scale }
            .then(
                if (selected) Modifier.clip(shape)
                    .background(Brush.verticalGradient(listOf(Glass.Purple.copy(alpha = 0.45f), Glass.Blue.copy(alpha = 0.25f))))
                    .border(1.dp, Glass.Lavender.copy(alpha = 0.8f), shape)
                else Modifier.glass(shape, pressed)
            )
            .pressable(inter, repeat = repeat, onClick = onClick),
        contentAlignment = Alignment.Center
    ) { content() }
}

@Composable
private fun SideIcon(icon: ImageVector, label: String, size: Dp = 26.dp, selected: Boolean = false) =
    Icon(icon, label, tint = if (selected) Color.White else Glass.Text, modifier = Modifier.size(size))

/** Mikrofon: mavi-mor dolu kare; dinlerken kırmızıya döner ve etrafında halkalar yayılır. */
@Composable
private fun GlassMic(size: Dp, active: Boolean, onClick: () -> Unit) {
    val level = LiveUi.micLevel     // yalnızca bu tuş yeniden çizilir
    val inter = remember { MutableInteractionSource() }
    val scale = pressScale(inter)
    val shape = RoundedCornerShape(16.dp)
    val p = if (active) {
        val pulse = rememberInfiniteTransition(label = "mic")
        pulse.animateFloat(0f, 1f, infiniteRepeatable(tween(1300, easing = LinearEasing)), label = "ring")
    } else null
    val lvl by animateFloatAsState(if (active) level else 0f, tween(120), label = "lvl")
    val tint by animateColorAsState(if (active) Pal.Red else Color.White, tween(200), label = "tint")
    Box(
        Modifier.size(size).drawBehind {
            if (active) {
                val r = this.size.minDimension / 2
                drawCircle(Pal.Red.copy(alpha = 0.22f), radius = r * (1f + 0.35f * lvl))
                for (k in 0..1) {
                    val f = ((p?.value ?: 0f) + k * 0.5f) % 1f
                    drawCircle(Pal.Red.copy(alpha = 0.45f * (1f - f)), radius = r * (1f + 0.55f * f), style = Stroke(width = 2.dp.toPx()))
                }
            }
        },
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier.fillMaxSize().graphicsLayer { scaleX = scale; scaleY = scale }
                .then(if (active) Modifier else Modifier.glow(Color(0xFF5B5CF0), 12.dp, shape))
                .clip(shape)
                .background(if (active) SolidBrush(Color(0xFF4A2327)) else Glass.Mic)
                .border(1.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.35f), Color.White.copy(alpha = 0.06f))), shape)
                .pressable(inter, onClick = onClick),
            contentAlignment = Alignment.Center
        ) { Icon(AppIcons.Mic, "Sesli Ara", tint = tint, modifier = Modifier.size(size * 0.5f)) }
    }
}

private fun SolidBrush(c: Color) = Brush.verticalGradient(listOf(c, c))

// ---------------- Yön kartı ----------------

/**
 * Cam kart: üstte büyük ses çubuğu; altında solda 5 (Kaynak, Bilgi, Kaydırma, fn, Klavye), sağda 5 (123, Rehber,
 * Fare, Medya, Ayarlar) tuş; ortada daire ve altında Geri · Ana Sayfa · Mikrofon. Kaydırma / fare modunda üstteki 8 yan
 * tuş solar, yüzey kartın iki kenarına kadar genişler; sağ üstteki ✕ ile normal kumandaya dönülür.
 * fn = Araçlar'daki "Sistem kısayolları", Medya = "Medya tuşları" penceresi.
 */
@Composable
fun GlassPadCard(
    ui: UiState,
    mode: Int,
    onMode: (Int) -> Unit,
    onKey: (Int) -> Unit,
    onMouse: (String) -> Unit,
    onMic: () -> Unit,
    onNumpad: () -> Unit,
    onKeyboard: () -> Unit,
    onTvSettings: () -> Unit,
    onSystemKeys: () -> Unit,
    onMediaKeys: () -> Unit,
    modifier: Modifier
) {
    val context = LocalContext.current
    val adb = ui.method == ConnMethod.ADB
    val shape = RoundedCornerShape(32.dp)
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val pad = 12.dp
        val pill = 58.dp
        val inner = maxWidth - pad * 2
        val key = if (inner < 300.dp) 44.dp else 50.dp           // dar ekranlarda tuşlar biraz küçülür
        val col = key + 4.dp
        val rowGap = 18.dp                                        // daire ile alt sıra arası
        val fixedH = pad * 2 + pill + 14.dp + rowGap + key
        val circle = minOf(inner - col * 2 - 8.dp, maxHeight - fixedH).coerceIn(120.dp, 260.dp)
        val bodyH = maxOf(circle + rowGap + key, key * 5 + 8.dp * 4)
        val cardH = pad * 2 + pill + 14.dp + bodyH
        GlassText {
            Column(
                Modifier.fillMaxWidth().height(cardH).glass(shape, strength = 0.55f).padding(pad)
            ) {
                GlassVolumePill(ui, onKey, Modifier.fillMaxWidth().height(pill))
                Spacer(Modifier.height(14.dp))
                // Kaydırma / fare modunda üstteki 8 yan tuş solar, yüzey kartın iki kenarına kadar genişler (Serif'in isteği)
                val padMode = mode == 1 || mode == 2
                val lastPad = remember { intArrayOf(1) }                 // çıkarken küçülen yüzey son modu göstermeye devam eder
                if (padMode) lastPad[0] = mode
                val shownPad = lastPad[0]
                val exp by animateFloatAsState(if (padMode) 1f else 0f, tween(250), label = "padExpand")
                // Yüzey alt sıradaki Klavye tuşunun ölçülen gerçek yerinden 14 dp önce biter (ses çubuğu ile aynı boşluk);
                // hesapla bulunan yükseklik bazı ekranlarda alt sıranın altına giriyordu (Serif'in v0.132 ekran görüntüsü)
                val density = LocalDensity.current
                var bottomRowTop by remember { mutableStateOf(-1f) }
                val wideH = if (bottomRowTop > 0f) with(density) { bottomRowTop.toDp() } - 14.dp
                            else bodyH - key - 14.dp
                val wideW = inner - (col - key)                          // tuşların dış kenarlarıyla aynı hiza
                val sideAlpha = Modifier.graphicsLayer { alpha = 1f - exp }
                Box(Modifier.fillMaxWidth().height(bodyH)) {
                    Row(Modifier.fillMaxSize()) {
                        Column(Modifier.width(col).fillMaxHeight(), verticalArrangement = Arrangement.SpaceBetween, horizontalAlignment = Alignment.CenterHorizontally) {
                            GlassSquare(Modifier.size(key).then(sideAlpha), enabled = adb, onClick = {
                                if (adb) onKey(Keys.INPUT) else Toast.makeText(context, "Kaynak tuşu yalnızca ADB bağlantısında çalışır", Toast.LENGTH_SHORT).show()
                            }) { SideIcon(Icons.Outlined.Input, "Kaynak") }
                            GlassSquare(Modifier.size(key).then(sideAlpha), onClick = { onKey(KEY_INFO) }) { SideIcon(Icons.Outlined.Info, "Bilgi") }
                            GlassSquare(Modifier.size(key).then(sideAlpha), selected = mode == 1, onClick = { onMode(if (mode == 1) 0 else 1) }) {
                                SideIcon(Icons.Outlined.TouchApp, "Kaydırma", selected = mode == 1)
                            }
                            GlassSquare(Modifier.size(key).then(sideAlpha), onClick = onSystemKeys) {
                                Text("fn", color = Glass.Text, fontSize = 22.sp, fontWeight = FontWeight.Medium)
                            }
                            GlassSquare(Modifier.size(key).onGloballyPositioned { bottomRowTop = it.positionInParent().y }, onClick = onKeyboard) { SideIcon(Icons.Filled.Keyboard, "Klavye") }
                        }
                        Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(Modifier.size(circle), contentAlignment = Alignment.Center) {
                                if (exp < 0.999f) {
                                    Box(Modifier.graphicsLayer { alpha = 1f - exp }) { DPad(circle, onKey, navy = true) }
                                }
                            }
                            Spacer(Modifier.weight(1f))
                            Row(horizontalArrangement = Arrangement.spacedBy(if (inner < 300.dp) 6.dp else 9.dp)) {
                                GlassSquare(Modifier.size(key), onClick = { onKey(Keys.BACK) }) { SideIcon(AppIcons.Undo, "Geri") }
                                GlassSquare(Modifier.size(key), onClick = { onKey(Keys.HOME) }) { SideIcon(AppIcons.HomeOutline, "Ana Sayfa") }
                                GlassMic(key, ui.micActive, onMic)
                            }
                        }
                        Column(Modifier.width(col).fillMaxHeight(), verticalArrangement = Arrangement.SpaceBetween, horizontalAlignment = Alignment.CenterHorizontally) {
                            GlassSquare(Modifier.size(key).then(sideAlpha), onClick = onNumpad) { SideIcon(Icons.Filled.Dialpad, "123") }
                            GlassSquare(Modifier.size(key).then(sideAlpha), onClick = { onKey(KEY_GUIDE) }) { SideIcon(GlassIcons.Guide, "Rehber", 28.dp) }
                            GlassSquare(Modifier.size(key).then(sideAlpha), selected = mode == 2, enabled = adb, onClick = {
                                if (adb) onMode(if (mode == 2) 0 else 2) else Toast.makeText(context, "Fare yalnızca ADB bağlantısında çalışır", Toast.LENGTH_SHORT).show()
                            }) { SideIcon(Icons.Outlined.Mouse, "Fare", selected = mode == 2) }
                            GlassSquare(Modifier.size(key).then(sideAlpha), onClick = onMediaKeys) { SideIcon(Icons.Outlined.QueueMusic, "Medya tuşları") }
                            GlassSquare(Modifier.size(key), onClick = onTvSettings) { SideIcon(Icons.Outlined.Settings, "Ayarlar") }
                        }
                    }
                    // Geniş yüzey: yan tuşların üstüne biner (gizli tuşlara dokunulamaz). Çıkarken küçülüp kaybolur.
                    if (exp > 0.001f) {
                        val w = circle + (wideW - circle) * exp
                        val h = circle + (wideH - circle) * exp
                        Box(Modifier.align(Alignment.TopCenter).size(w, h)) {
                            when (shownPad) {
                                1 -> TouchPad(Modifier.fillMaxSize(), onKey)
                                else -> TrackPad(Modifier.fillMaxSize(), ui.fastMode, ui.method, onMouse) { if (padMode) onMode(0) }
                            }
                            // Sol üst: hangi modda olunduğu. Fare modunda "Fare ▾": dokununca hız ve şerit ayarı açılır
                            if (shownPad == 1) {
                                Row(
                                    Modifier.align(Alignment.TopStart).padding(start = 18.dp, top = 16.dp).graphicsLayer { alpha = exp },
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Outlined.TouchApp, null, tint = Glass.Text.copy(alpha = 0.7f), modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(5.dp))
                                    Text("Kaydırma", color = Glass.Text.copy(alpha = 0.7f), fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                }
                            } else if (ui.method != ConnMethod.STANDARD && ui.fastMode) {
                                // Şerit soldaysa etiket sağ üste geçer, üst üste binmesinler
                                MouseModeLabel(
                                    Modifier.align(if (MouseSettings.stripLeft) Alignment.TopEnd else Alignment.TopStart)
                                        .padding(start = 12.dp, end = 12.dp, top = 12.dp).graphicsLayer { alpha = exp }
                                )
                            } else {
                                Row(
                                    Modifier.align(Alignment.TopStart).padding(start = 18.dp, top = 16.dp).graphicsLayer { alpha = exp },
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Outlined.Mouse, null, tint = Glass.Text.copy(alpha = 0.7f), modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(5.dp))
                                    Text("Fare", color = Glass.Text.copy(alpha = 0.7f), fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                }
                            }
                            // Sağ üst: ✕ ile normal kumandaya dönülür (çalışan fare modunda ✕ kaydırma şeridinin tepesinde)
                            if (shownPad == 1 || ui.method == ConnMethod.STANDARD || !ui.fastMode) {
                                Box(
                                    Modifier.align(Alignment.TopEnd).padding(top = 10.dp, end = 10.dp)
                                        .graphicsLayer { alpha = exp }
                                        .size(36.dp).clip(CircleShape)
                                        .background(Color.Black.copy(alpha = 0.28f))
                                        .clickable(enabled = padMode) { onMode(0) },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(Icons.Filled.Close, "Moddan çık", tint = Glass.Text, modifier = Modifier.size(20.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private const val KEY_INFO = 165   // KEYCODE_INFO
private const val KEY_GUIDE = 172  // KEYCODE_GUIDE

// ---------------- Ses çubuğu ----------------

/**
 * Büyük cam ses çubuğu: Sessiz · − · çubuk · +. − / + birer adım (basılı tutunca tekrar), çubuğa dokunmak ya da
 * sürükleyip bırakmak sesi o seviyeye getirir. TV'nin sesi henüz bilinmiyorsa çubuk soluk ve kapalıdır.
 */
@Composable
private fun GlassVolumePill(ui: UiState, onKey: (Int) -> Unit, modifier: Modifier) {
    val shape = RoundedCornerShape(29.dp)
    Row(
        // Sessiz tuşu sol kenara yakın; kazanılan alanın yarısı çubuğa, yarısı çubuğun iki yanına boşluk (Serif'in isteği)
        modifier.glass(shape, strength = 0.8f).padding(start = 0.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val isMuted = ui.muted == true
        PillButton(GlassIcons.Mute, if (isMuted) "Sesi aç" else "Sessiz", active = isMuted) { onKey(Keys.MUTE) }
        PillButton(Icons.Filled.Remove, "Ses kıs", repeat = true) { onKey(Keys.VOL_DOWN) }
        GlassVolumeTrack(ui.volume, ui.volumeMax.coerceAtLeast(1), isMuted, Modifier.weight(1f).fillMaxHeight().padding(horizontal = 8.5.dp))
        PillButton(Icons.Filled.Add, "Ses aç", repeat = true) { onKey(Keys.VOL_UP) }
    }
}

@Composable
private fun PillButton(icon: ImageVector, label: String, repeat: Boolean = false, active: Boolean = false, onClick: () -> Unit) {
    val inter = remember { MutableInteractionSource() }
    val scale = pressScale(inter)
    val pressed by inter.collectIsPressedAsState()
    // active: TV sessizde — tuş kırmızımsı parıltıyla yanar
    val on by animateFloatAsState(if (active) 1f else 0f, label = "active")
    Box(
        Modifier.size(44.dp).graphicsLayer { scaleX = scale; scaleY = scale }
            .then(if (active) Modifier.glow(Glass.Red, 10.dp) else Modifier)
            .clip(CircleShape)
            .background(
                when {
                    on > 0f -> Glass.Red.copy(alpha = 0.22f * on + if (pressed) 0.08f else 0f)
                    pressed -> Color.White.copy(alpha = 0.10f)
                    else -> Color.Transparent
                }
            )
            .pressable(inter, repeat = repeat, onClick = onClick),
        contentAlignment = Alignment.Center
    ) { Icon(icon, label, tint = androidx.compose.ui.graphics.lerp(Glass.Text, Glass.Red, on), modifier = Modifier.size(26.dp)) }
}

/** Sessizken çubuğun dolu kısmı: gri. */
private val MutedFill = Brush.horizontalGradient(listOf(Color(0xFF5A5A68), Color(0xFF7A7A88)))

@Composable
private fun GlassVolumeTrack(volume: Int?, max: Int, muted: Boolean, modifier: Modifier) {
    var drag by remember { mutableStateOf<Float?>(null) }
    val known = volume != null
    val frac = (drag ?: ((volume ?: (max / 2)).toFloat() / max)).coerceIn(0f, 1f)
    val shown = drag?.let { Math.round(it * max) } ?: volume
    val thumb = 34.dp
    BoxWithConstraints(
        modifier.alpha(if (known) 1f else 0.4f).padding(horizontal = 2.dp)
            .then(if (!known) Modifier else Modifier
                .pointerInput(max) {
                    val r = thumb.toPx() / 2
                    fun f(x: Float) = ((x - r) / (size.width - 2 * r)).coerceIn(0f, 1f)
                    detectTapGestures { o -> VolumeCtl.setTo(Math.round(f(o.x) * max)) }
                }
                .pointerInput(max) {
                    val r = thumb.toPx() / 2
                    fun f(x: Float) = ((x - r) / (size.width - 2 * r)).coerceIn(0f, 1f)
                    detectHorizontalDragGestures(
                        onDragStart = { o -> drag = f(o.x) },
                        onDragEnd = { drag?.let { VolumeCtl.setTo(Math.round(it * max)) }; drag = null },
                        onDragCancel = { drag = null }
                    ) { change, _ -> change.consume(); drag = f(change.position.x) }
                }),
        contentAlignment = Alignment.CenterStart
    ) {
        val w = maxWidth
        val track = 14.dp
        Box(Modifier.fillMaxWidth().height(track).clip(RoundedCornerShape(7.dp)).background(Color(0xFF0C0D1E).copy(alpha = 0.75f)))
        Box(
            Modifier.width((w - thumb) * frac + thumb / 2).height(track).clip(RoundedCornerShape(7.dp))
                .background(if (muted) MutedFill else Glass.VolumeFill)
        )
        Box(
            Modifier.offset(x = (w - thumb) * frac).size(thumb)
                .glow(Glass.Lavender, 12.dp)
                .clip(CircleShape).background(Brush.verticalGradient(listOf(Color.White, Color(0xFFE6E4F5)))),
            contentAlignment = Alignment.Center
        ) {
            if (shown != null) Text("$shown", color = Color(0xFF1B1840).copy(alpha = if (muted) 0.4f else 1f), fontSize = 14.sp, fontWeight = FontWeight.Bold, lineHeight = 14.sp, textAlign = TextAlign.Center)
        }
    }
}

// ---------------- Medya çubuğu ----------------

@Composable
fun GlassMediaBar(onKey: (Int) -> Unit, header: (@Composable () -> Unit)? = null, leading: (@Composable () -> Unit)? = null) {
    // TV'de bir şey çalarken oynatıcı (başlık + sarma çubuğu) aynı cam kartın üstüne girer: tek kart
    if (header == null) {
        MediaKeysRow(Modifier.fillMaxWidth().height(68.dp).glass(RoundedCornerShape(34.dp), strength = 0.65f), onKey)
    } else {
        Column(Modifier.fillMaxWidth().glass(RoundedCornerShape(28.dp), strength = 0.65f)) {
            header()
            MediaKeysRow(Modifier.fillMaxWidth().height(64.dp), onKey, leading)
        }
    }
}

@Composable
private fun MediaKeysRow(modifier: Modifier, onKey: (Int) -> Unit, leading: (@Composable () -> Unit)? = null) {
    Row(
        modifier.padding(horizontal = 10.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Video açıkken oynatılan uygulamanın simgesi tuşların solunda
        leading?.invoke()
        BarKey(AppIcons.SkipPrevious, "Önceki") { onKey(Keys.PREV) }
        BarKey(Icons.Filled.FastRewind, "Geri sar", repeat = true) { onKey(89) }
        val inter = remember { MutableInteractionSource() }
        val scale = pressScale(inter)
        val pressed by inter.collectIsPressedAsState()
        Box(
            Modifier.size(54.dp).graphicsLayer { scaleX = scale; scaleY = scale }
                .glow(Glass.Purple, 14.dp)
                .clip(CircleShape).background(if (pressed) Color(0xFF2A2856) else Color(0xFF1E1D42))
                .border(2.5.dp, Glass.PlayRing, CircleShape)
                .pressable(inter, onClick = { onKey(Keys.PLAY_PAUSE) }),
            contentAlignment = Alignment.Center
        ) { Icon(AppIcons.PlayPause, "Oynat/Duraklat", tint = Color.White, modifier = Modifier.size(28.dp)) }
        BarKey(Icons.Filled.FastForward, "İleri sar", repeat = true) { onKey(90) }
        BarKey(AppIcons.SkipNext, "Sonraki") { onKey(Keys.NEXT) }
    }
}

@Composable
private fun BarKey(icon: ImageVector, label: String, repeat: Boolean = false, onClick: () -> Unit) {
    val inter = remember { MutableInteractionSource() }
    val pressed by inter.collectIsPressedAsState()
    Box(
        Modifier.size(48.dp).clip(CircleShape).background(if (pressed) Color.White.copy(alpha = 0.10f) else Color.Transparent)
            .pressable(inter, repeat = repeat, onClick = onClick),
        contentAlignment = Alignment.Center
    ) { Icon(icon, label, tint = Color.White, modifier = Modifier.size(28.dp)) }
}
