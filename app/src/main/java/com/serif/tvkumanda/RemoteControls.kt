package com.serif.tvkumanda

import androidx.compose.material.icons.outlined.Input

import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.haze

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.QueueMusic
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.blur
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.foundation.text.BasicTextField
import androidx.activity.compose.BackHandler
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.Image
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sign

/*
 * Kumanda sekmesinin parçaları: yön tuşları, dokunmatik yüzeyler (kaydırma, fare) ve alttan açılan
 * yazma/klavye/rakam pencereleri. Ekranın iskeleti, tema ve ortak yardımcılar RemoteUi.kt'de.
 */

// ---------- Yön tuşları ----------
internal enum class Dir { UP, DOWN, LEFT, RIGHT }

@Composable
fun DPad(size: Dp, onKey: (Int) -> Unit, navy: Boolean = false) {
    Box(
        Modifier
            .size(size)
            .then(
                // Kumanda sekmesi: cam daire (üstte daha parlak kenar)
                if (navy) Modifier.glass(CircleShape, strength = 0.75f)
                else Modifier.clip(CircleShape)
                    .background(Brush.radialGradient(listOf(Color(0xFF323235), Pal.Pad)))
                    .border(2.dp, RingBrush, CircleShape)
            )
    ) {
        DirKey(Dir.UP, Modifier.align(Alignment.TopCenter).fillMaxWidth(0.4f).fillMaxHeight(0.33f)) { onKey(Keys.UP) }
        DirKey(Dir.DOWN, Modifier.align(Alignment.BottomCenter).fillMaxWidth(0.4f).fillMaxHeight(0.33f)) { onKey(Keys.DOWN) }
        DirKey(Dir.LEFT, Modifier.align(Alignment.CenterStart).fillMaxWidth(0.33f).fillMaxHeight(0.4f)) { onKey(Keys.LEFT) }
        DirKey(Dir.RIGHT, Modifier.align(Alignment.CenterEnd).fillMaxWidth(0.33f).fillMaxHeight(0.4f)) { onKey(Keys.RIGHT) }
        OkButton(Modifier.align(Alignment.Center).fillMaxSize(if (navy) 0.43f else 0.3f), navy) { onKey(Keys.OK) }
    }
}

@Composable
private fun DirKey(dir: Dir, modifier: Modifier, onClick: () -> Unit) {
    val inter = remember { MutableInteractionSource() }
    val pressed by inter.collectIsPressedAsState()
    val p by animateFloatAsState(if (pressed) 1f else 0f, tween(90), label = "dir")
    Box(
        modifier.pressable(inter, repeat = true, deferMs = 70, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.08f * p))
        )
        Chevron(dir, color = lerpColor(Glass.Text, Glass.Lavender, p), modifier = Modifier.size(28.dp))
    }
}

/** İnce ok işareti. */
@Composable
internal fun Chevron(dir: Dir, color: Color, modifier: Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val path = Path()
        when (dir) {
            Dir.UP -> { path.moveTo(w * 0.1f, h * 0.7f); path.lineTo(w * 0.5f, h * 0.3f); path.lineTo(w * 0.9f, h * 0.7f) }
            Dir.DOWN -> { path.moveTo(w * 0.1f, h * 0.3f); path.lineTo(w * 0.5f, h * 0.7f); path.lineTo(w * 0.9f, h * 0.3f) }
            Dir.LEFT -> { path.moveTo(w * 0.7f, h * 0.1f); path.lineTo(w * 0.3f, h * 0.5f); path.lineTo(w * 0.7f, h * 0.9f) }
            Dir.RIGHT -> { path.moveTo(w * 0.3f, h * 0.1f); path.lineTo(w * 0.7f, h * 0.5f); path.lineTo(w * 0.3f, h * 0.9f) }
        }
        drawPath(
            path, color,
            style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
    }
}

@Composable
private fun OkButton(modifier: Modifier, navy: Boolean = false, onClick: () -> Unit) {
    val inter = remember { MutableInteractionSource() }
    val scale = pressScale(inter)
    val pressed by inter.collectIsPressedAsState()
    Box(
        modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .then(if (navy) Modifier.glow(Glass.Purple, 22.dp) else Modifier.shadow(14.dp, CircleShape, ambientColor = Color.Black, spotColor = Color.Black))
            .clip(CircleShape)
            .background(
                if (navy) Brush.radialGradient(if (pressed) listOf(Color(0xFF2C2A5A), Color(0xFF201E46)) else listOf(Color(0xFF242350), Color(0xFF191838)))
                else Brush.verticalGradient(if (pressed) listOf(Color(0xFF3A3A3E), Color(0xFF333336)) else listOf(Color(0xFF38383B), Color(0xFF2B2B2E)))
            )
            .border(if (navy) 4.dp else 3.dp, if (navy) Glass.OkRing else RingBrush, CircleShape)
            .pressable(inter, deferMs = 70, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (navy) Text("OK", color = Color(0xFFE9E6FF), fontSize = 30.sp, fontWeight = FontWeight.SemiBold, fontFamily = Glass.Inter)
    }
}

// ---------- Dokunmatik yüzeyler ----------
private val EdgeZone = 0.dp   // modlar artık düğmeyle değişiyor; kenar şeridi gerekmiyor

/** Kenar şeritlerinden başlayan hareketler bırakılır; böylece kenardan kaydırınca mod değişir. */
private fun inEdge(x: Float, width: Int, edgePx: Float) = x < edgePx || x > width - edgePx

@Composable
private fun PadSurface(
    modifier: Modifier,
    hint: List<String>,
    gesture: Modifier,
    icon: (@Composable () -> Unit)? = null
) {
    val shape = RoundedCornerShape(36.dp)
    Box(
        modifier
            .glass(shape, strength = 0.75f)
            .then(gesture),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            icon?.invoke()
            hint.forEach {
                Text(it, color = Pal.Sub, fontSize = 14.sp, textAlign = TextAlign.Center)
                Spacer(Modifier.height(4.dp))
            }
        }
    }
}

/** Kaydırma modu: parmak hareketi yön tuşu, dokunma OK. */
@Composable
fun TouchPad(modifier: Modifier, onKey: (Int) -> Unit) {
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val step = with(density) { 48.dp.toPx() }
    val edge = with(density) { EdgeZone.toPx() }
    val slop = with(density) { 8.dp.toPx() }
    val latest by rememberUpdatedState(onKey)

    PadSurface(
        modifier,
        listOf("Kaydır: yön", "Dokun: OK"),
        Modifier.pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                if (inEdge(down.position.x, this.size.width, edge)) return@awaitEachGesture
                down.consume()
                var acc = Offset.Zero
                var travelled = 0f
                while (true) {
                    val ev = awaitPointerEvent()
                    val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                    if (!ch.pressed) {
                        if (travelled < slop) {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            latest(Keys.OK)
                        }
                        break
                    }
                    val d = ch.positionChange()
                    ch.consume()
                    travelled += d.getDistance()
                    acc += d
                    while (abs(acc.x) >= step || abs(acc.y) >= step) {
                        if (abs(acc.x) > abs(acc.y)) {
                            latest(if (acc.x > 0) Keys.RIGHT else Keys.LEFT)
                            acc = Offset(acc.x - step * sign(acc.x), 0f)
                        } else {
                            latest(if (acc.y > 0) Keys.DOWN else Keys.UP)
                            acc = Offset(0f, acc.y - step * sign(acc.y))
                        }
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    }
                }
            }
        }
    )
}

/**
 * Fare modu: TV'de imleç. Tek parmak imleç, dokunma tık, basılı tutup hareket = sürükleme / seçme,
 * iki parmak ya da kenardaki şerit sayfa kaydırma. Parmağın altında yarı saydam mavi halka belirir.
 * İmleç hızı ve şeridin kenarı `MouseSettings`'te (sol üstteki "Fare ▾").
 */
@Composable
fun TrackPad(modifier: Modifier, fastMode: Boolean, method: ConnMethod, onMouse: (String) -> Unit, onClose: () -> Unit) {
    if (method == ConnMethod.STANDARD) {
        PadSurface(modifier, listOf("Bu özellik", "ADB bağlantısında çalışır"), Modifier)
        return
    }
    if (!fastMode) {
        PadSurface(modifier, listOf("Fare modu için", "⚡ Hızlı mod gerekli", "Bağlantıyı yenilemeyi dene"), Modifier)
        return
    }
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val slop = with(density) { 8.dp.toPx() }
    val wheelStep = with(density) { 28.dp.toPx() }
    val latest by rememberUpdatedState(onMouse)
    // Fare modu açılır açılmaz TV'deki sanal fare kurulur; TV onu tanırken (1-2 sn) hareketler kaybolmaz
    LaunchedEffect(Unit) { latest("M"); MouseSettings.load(context) }

    // Parmak halkası: yeri ve görünürlüğü
    var touch by remember { mutableStateOf(Offset.Zero) }
    var touching by remember { mutableStateOf(false) }
    var dragging by remember { mutableStateOf(false) }
    val ringAlpha by animateFloatAsState(if (touching) 1f else 0f, tween(if (touching) 90 else 260), label = "halka")
    val ringScale by animateFloatAsState(if (dragging) 1.25f else 1f, tween(160), label = "halkaBoy")

    val shape = RoundedCornerShape(36.dp)
    val left = MouseSettings.stripLeft
    Box(modifier.glass(shape, strength = 0.75f).clip(shape)) {
        Box(
            Modifier.fillMaxSize()
                .padding(start = if (left) 66.dp else 0.dp, end = if (left) 0.dp else 66.dp)
                .drawBehind {
                    if (ringAlpha > 0.01f) {
                        val r = 30.dp.toPx() * ringScale
                        drawCircle(Glass.Blue.copy(alpha = 0.22f * ringAlpha), r, touch)
                        drawCircle(Glass.Blue.copy(alpha = 0.85f * ringAlpha), r, touch, style = Stroke(2.5.dp.toPx()))
                        drawCircle(Glass.Blue.copy(alpha = 0.35f * ringAlpha), 7.dp.toPx(), touch)
                    }
                }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        touch = down.position
                        touching = true
                        dragging = false
                        val start = System.currentTimeMillis()
                        var travelled = 0f
                        var multi = false
                        var rx = 0f
                        var ry = 0f
                        var wheelAcc = 0f
                        try {
                            while (true) {
                                // Parmak ~0.4 sn kıpırdamadan durursa sürükleme başlar (TV'de sol tuş basılı kalır)
                                val waitHold = !dragging && !multi && travelled < slop
                                val ev = if (waitHold) {
                                    val left0 = 400L - (System.currentTimeMillis() - start)
                                    if (left0 <= 0) null else withTimeoutOrNull(left0) { awaitPointerEvent() }
                                } else awaitPointerEvent()
                                if (ev == null) {
                                    dragging = true
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    latest("d")
                                    continue
                                }
                                val pressed = ev.changes.filter { it.pressed }
                                if (pressed.isEmpty()) {
                                    if (!dragging && !multi && travelled < slop) {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        latest("c")
                                    }
                                    break
                                }
                                if (pressed.size >= 2 && !dragging) multi = true

                                // Ortalama hareket
                                var dx = 0f
                                var dy = 0f
                                pressed.forEach { val d = it.positionChange(); dx += d.x; dy += d.y }
                                dx /= pressed.size
                                dy /= pressed.size
                                ev.changes.forEach { it.consume() }
                                travelled += kotlin.math.sqrt(dx * dx + dy * dy)
                                (pressed.firstOrNull { it.id == down.id } ?: pressed.first()).let { touch = it.position }

                                if (multi) {
                                    wheelAcc += dy
                                    val notches = (wheelAcc / wheelStep).toInt()
                                    if (notches != 0) {
                                        latest("w $notches")
                                        wheelAcc -= notches * wheelStep
                                    }
                                } else {
                                    // Hafif hızlanma: hızlı hareket daha uzağa gider; çarpan kullanıcının hız ayarından
                                    val speed = kotlin.math.sqrt(dx * dx + dy * dy)
                                    val gain = (0.9f + (speed / 40f).coerceAtMost(1.6f)) * MouseSettings.gain()
                                    rx += dx * gain
                                    ry += dy * gain
                                    val ix = rx.toInt()
                                    val iy = ry.toInt()
                                    if (ix != 0 || iy != 0) {
                                        latest("m $ix $iy")
                                        rx -= ix
                                        ry -= iy
                                    }
                                }
                            }
                        } finally {
                            // Sürükleme biterken (ya da mod kapanırken) TV'de tuş bırakılır, takılı kalmaz
                            if (dragging) latest("u")
                            dragging = false
                            touching = false
                        }
                    }
                }
        )
        ScrollStrip(
            Modifier.align(if (left) Alignment.TopStart else Alignment.TopEnd)
                .padding(top = 10.dp, bottom = 10.dp, start = if (left) 10.dp else 0.dp, end = if (left) 0.dp else 10.dp)
                .width(48.dp).fillMaxHeight(),
            onMouse,
            onClose
        )
    }
}


// ---------- Alt sayfalar ----------
@Composable
internal fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Pal.Blue,
    unfocusedBorderColor = Color(0xFF3A3A3D),
    focusedTextColor = Pal.Text,
    unfocusedTextColor = Pal.Text,
    cursorColor = Pal.Blue,
    focusedContainerColor = Pal.Btn,
    unfocusedContainerColor = Pal.Btn,
    focusedPlaceholderColor = Pal.Sub,
    unfocusedPlaceholderColor = Pal.Sub
)

@Composable
private fun SheetTitle(title: String, subtitle: String? = null) {
    Text(title, color = Pal.Text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
    if (subtitle != null) {
        Spacer(Modifier.height(4.dp))
        Text(subtitle, color = Pal.Sub, fontSize = 14.sp)
    }
    Spacer(Modifier.height(16.dp))
}

/**
 * Standart modda tam ekran yazma: arkada bulanık kumanda, büyük yazı alanı, sağ üstte kapatma.
 * Yazılanlar canlı olarak TV'ye gider. Metnin yanındaki ✕ hepsini siler.
 */
@Composable
internal fun TypingScreen(
    seed: String,
    seedTick: Int,
    imeReady: Boolean,
    hint: String,
    onText: (String) -> Unit,
    onSubmit: () -> Unit,
    onClose: () -> Unit,
    onTvSearch: () -> Unit
) {
    var value by remember { mutableStateOf(TextFieldValue(seed, TextRange(seed.length))) }
    LaunchedEffect(seedTick) {
        if (seed != value.text) value = TextFieldValue(seed, TextRange(seed.length))
    }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(80); runCatching { focus.requestFocus() } }
    BackHandler(onBack = onClose)
    // Bulanıklık desteklenmeyen eski Android sürümlerinde perde daha koyu
    val scrim = if (android.os.Build.VERSION.SDK_INT >= 31) Color(0x99141416) else Color(0xEE141416)

    Box(
        Modifier
            .fillMaxSize()
            .background(scrim)
            .pointerInput(Unit) { detectTapGestures { } }   // arkadaki kumandaya dokunulmasın
            .systemBarsPadding()
    ) {
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .padding(top = 12.dp, end = 16.dp)
                .size(48.dp)
                .clip(CircleShape)
                .clickable(onClick = onClose),
            contentAlignment = Alignment.Center
        ) {
            Icon(AppIcons.Close, "Kapat", tint = Pal.Text, modifier = Modifier.size(30.dp))
        }

        Column(Modifier.fillMaxWidth().padding(start = 24.dp, end = 20.dp, top = 80.dp)) {
            if (!imeReady) {
                Row(
                    Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .clickable(onClick = onTvSearch)
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(AppIcons.Info, null, tint = Pal.Sub, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(14.dp))
                    Text(
                        "TV'de bir yazı kutusu seçin. Arama ekranını açmak için dokunun.",
                        color = Pal.Sub,
                        fontSize = 15.sp,
                        lineHeight = 20.sp,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Spacer(Modifier.width(10.dp))
                    Icon(AppIcons.Search, "TV'de ara", tint = Pal.Text, modifier = Modifier.size(26.dp))
                }
                Spacer(Modifier.height(22.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                BasicTextField(
                    value = value,
                    onValueChange = {
                        val changed = it.text != value.text
                        value = it
                        if (changed) onText(it.text)
                    },
                    textStyle = TextStyle(color = Pal.Text, fontSize = 30.sp, fontWeight = FontWeight.SemiBold),
                    cursorBrush = SolidColor(Pal.Blue),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { onSubmit() }, onDone = { onSubmit() }, onGo = { onSubmit() }),
                    modifier = Modifier.weight(1f).focusRequester(focus),
                    decorationBox = { inner ->
                        Box {
                            if (value.text.isEmpty()) {
                                // TV'deki kutunun kendi ipucu yazısı (ör. "Arama yapın") varsa o gösterilir
                                Text(hint.ifBlank { "Yazmaya başlayın…" }, color = Pal.Sub, fontSize = 30.sp, fontWeight = FontWeight.SemiBold)
                            }
                            inner()
                        }
                    }
                )
                if (value.text.isNotEmpty()) {
                    Spacer(Modifier.width(8.dp))
                    Box(
                        Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .clickable {
                                value = TextFieldValue("")
                                onText("")
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(AppIcons.Clear, "Tümünü sil", tint = Pal.Sub, modifier = Modifier.size(26.dp))
                    }
                }
            }
        }
    }
}

@Composable
internal fun KeyboardSheet(onText: (String) -> Unit, onKey: (Int) -> Unit) {
    var text by remember { mutableStateOf("") }
    val send = { if (text.isNotEmpty()) { onText(text); text = "" } }
    SheetTitle("TV'ye yaz")
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            placeholder = { Text("Aranacak metin…") },
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
            colors = fieldColors(),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { send() }),
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(10.dp))
        GradientButton("Gönder", onClick = send)
    }
    Spacer(Modifier.height(12.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        SheetKey("⌫ Sil", Modifier.weight(1f), repeat = true) { onKey(Keys.DEL) }
        SheetKey("Enter", Modifier.weight(1f)) { onKey(Keys.ENTER) }
    }
}

@Composable
internal fun NumpadSheet(onKey: (Int) -> Unit) {
    SheetTitle("Sayı tuşları")
    val rows = listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("⌫", "0", "OK"))
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        for (r in rows) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                for (k in r) {
                    SheetKey(k, Modifier.weight(1f), big = true, repeat = k == "⌫") {
                        when (k) {
                            "⌫" -> onKey(Keys.DEL)
                            "OK" -> onKey(Keys.OK)
                            else -> onKey(Keys.digit(k.toInt()))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SheetKey(
    label: String,
    modifier: Modifier,
    big: Boolean = false,
    repeat: Boolean = false,
    onClick: () -> Unit
) {
    val inter = remember { MutableInteractionSource() }
    val pressed by inter.collectIsPressedAsState()
    val scale = pressScale(inter)
    Box(
        modifier
            .height(if (big) 64.dp else 54.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(18.dp))
            .background(if (pressed) Pal.BtnPressed else Pal.Btn)
            .pressable(inter, repeat = repeat, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = Pal.Text, fontSize = if (big) 24.sp else 16.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun GradientButton(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier
            .height(56.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(RingBrush, alpha = if (enabled) 1f else 0.5f)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 20.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}
