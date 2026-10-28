package com.serif.tvkumanda

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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
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

// ---------- Renkler ----------
object Pal {
    val Bg = Color(0xFF1C1C1E)
    val Btn = Color(0xFF2C2C2E)
    val BtnPressed = Color(0xFF3A3A3D)
    val Pad = Color(0xFF2A2A2D)
    val Text = Color(0xFFF5F5F7)
    val Sub = Color(0xFF9A9AA2)
    val Purple = Color(0xFF8E5CF7)
    val Blue = Color(0xFF4A7CF5)
    val Green = Color(0xFF4CD964)
    val Amber = Color(0xFFFFC542)
    val Red = Color(0xFFFF5A5F)
    val Level = Color(0xFF4A7FB5)
}

val RingBrush = Brush.linearGradient(listOf(Pal.Purple, Pal.Blue))

@Composable
fun RemoteTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Pal.Blue,
            background = Pal.Bg,
            surface = Pal.Bg,
            surfaceContainerLow = Color(0xFF242426),
            onSurface = Pal.Text,
            onBackground = Pal.Text
        ),
        content = content
    )
}

private enum class Sheet { NONE, CONNECT, KEYBOARD, NUMPAD }

// ---------- Ana ekran ----------
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemoteScreen(
    ui: UiState,
    initialIp: String,
    onConnect: (String) -> Unit,
    onKey: (Int) -> Unit,
    onText: (String) -> Unit,
    onMouse: (String) -> Unit,
    onLaunch: (TvApp) -> Unit,
    onMethod: (ConnMethod) -> Unit,
    onPairCode: (String) -> Unit,
    onPairCancel: () -> Unit,
    onPickSaved: (SavedTv) -> Unit,
    onForgetSaved: (SavedTv) -> Unit,
    onMic: () -> Unit
) {
    var sheet by remember { mutableStateOf(if (initialIp.isBlank()) Sheet.CONNECT else Sheet.NONE) }
    val pager = rememberPagerState(pageCount = { 3 })
    val scope = rememberCoroutineScope()

    LaunchedEffect(ui.state) {
        if (ui.state == ConnState.ERROR) sheet = Sheet.CONNECT
        if (ui.state == ConnState.CONNECTED && sheet == Sheet.CONNECT) sheet = Sheet.NONE
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Pal.Bg)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        TopBar(ui, onSettings = { sheet = Sheet.CONNECT }, onPower = { onKey(Keys.POWER) })

        Spacer(Modifier.height(12.dp))
        AppsRow(ui.apps, ui.state, ui.method, onLaunch)

        // Yön tuşu kalan boşluğa göre boyutlanır; ekran ne olursa olsun kaydırma gerekmez
        BoxWithConstraints(
            Modifier.fillMaxWidth().weight(1f).padding(vertical = 12.dp),
            contentAlignment = Alignment.Center
        ) {
            val padSize = minOf(maxWidth * 0.88f, maxHeight)
            // Sağa-sola kaydırarak modlar arasında geçilir
            HorizontalPager(state = pager, modifier = Modifier.fillMaxSize()) { p ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    when (p) {
                        0 -> DPad(padSize, onKey)
                        1 -> TouchPad(padSize, onKey)
                        else -> TrackPad(padSize, ui.fastMode, ui.method, onMouse)
                    }
                }
            }
        }

        PageDots(pager.currentPage) { i -> scope.launch { pager.animateScrollToPage(i) } }
        Spacer(Modifier.height(10.dp))

        BottomControls(ui, onKey, onMic,
            onKeyboard = { sheet = Sheet.KEYBOARD },
            onNumpad = { sheet = Sheet.NUMPAD }
        )
        Spacer(Modifier.height(4.dp))
    }

    if (sheet != Sheet.NONE) {
        ModalBottomSheet(
            onDismissRequest = { sheet = Sheet.NONE },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = Color(0xFF242426)
        ) {
            Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
                when (sheet) {
                    Sheet.CONNECT -> ConnectSheet(initialIp, ui, onConnect, onMethod, onPickSaved, onForgetSaved)
                    Sheet.KEYBOARD -> KeyboardSheet(onText, onKey)
                    Sheet.NUMPAD -> NumpadSheet(onKey)
                    Sheet.NONE -> {}
                }
            }
        }
    }

    if (ui.pairing) PairingDialog(ui.pairingError, onPairCode, onPairCancel)
}

/** Standart bağlantıda TV'nin gösterdiği 6 haneli kodu isteyen küçük pencere. */
@Composable
private fun PairingDialog(error: String, onCode: (String) -> Unit, onCancel: () -> Unit) {
    var code by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onCancel,
        containerColor = Color(0xFF242426),
        title = { Text("TV ile eşleştir", color = Pal.Text) },
        text = {
            Column {
                Text("TV ekranında görünen 6 haneli kodu gir.", color = Pal.Sub, fontSize = 14.sp)
                Spacer(Modifier.height(14.dp))
                OutlinedTextField(
                    value = code,
                    onValueChange = { v -> code = v.uppercase().filter { it.isDigit() || it in 'A'..'F' }.take(6) },
                    placeholder = { Text("ör. 1A2B3C") },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    colors = fieldColors(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (code.length == 6) onCode(code) })
                )
                if (error.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(error, color = Pal.Red, fontSize = 13.sp)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onCode(code) }, enabled = code.length == 6) { Text("Eşleştir") }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text("İptal", color = Pal.Sub) }
        }
    )
}

// ---------- Üst çubuk ----------
@Composable
private fun TopBar(ui: UiState, onSettings: () -> Unit, onPower: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(56.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconTap(Icons.Filled.Settings, Pal.Text, 30.dp, onSettings)
        Row(
            Modifier
                .weight(1f)
                .clip(RoundedCornerShape(12.dp))
                .clickable(onClick = onSettings)
                .padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            StatusDot(ui.state)
            Spacer(Modifier.width(10.dp))
            Text(
                when (ui.state) {
                    ConnState.CONNECTED -> ui.tvName.ifBlank { "TV" }
                    ConnState.CONNECTING -> "Bağlanıyor…"
                    else -> "TV seç"
                },
                color = Pal.Text,
                fontSize = 24.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 200.dp)
            )
            if (ui.fastMode) {
                Spacer(Modifier.width(4.dp))
                Text("⚡", fontSize = 14.sp)
            }
            Icon(Icons.Filled.ArrowDropDown, null, tint = Pal.Text, modifier = Modifier.size(30.dp))
        }
        IconTap(
            AppIcons.Power,
            if (ui.state == ConnState.CONNECTED) Pal.Green else Pal.Red,
            32.dp,
            onPower
        )
    }
}

@Composable
private fun IconTap(icon: ImageVector, tint: Color, size: Dp, onClick: () -> Unit) {
    val inter = remember { MutableInteractionSource() }
    val scale = pressScale(inter)
    Box(
        Modifier
            .size(52.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(CircleShape)
            .pressable(inter, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(size))
    }
}

@Composable
fun StatusDot(state: ConnState) {
    val color = when (state) {
        ConnState.CONNECTED -> Pal.Green
        ConnState.CONNECTING -> Pal.Amber
        ConnState.ERROR -> Pal.Red
        ConnState.IDLE -> Pal.Sub
    }
    val pulse = rememberInfiniteTransition(label = "pulse")
    val a by pulse.animateFloat(
        0.3f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "alpha"
    )
    Box(
        Modifier
            .size(10.dp)
            .clip(CircleShape)
            .background(color.copy(alpha = if (state == ConnState.CONNECTING) a else 1f))
    )
}

// ---------- Uygulamalar ----------
private val tileColors = listOf(
    Color(0xFF6E56CF), Color(0xFF3E7BFA), Color(0xFF0FA3B1), Color(0xFF2FA36B),
    Color(0xFFE5484D), Color(0xFFF76B15), Color(0xFFD6409F), Color(0xFF8D6E63)
)

@Composable
private fun AppsRow(apps: List<TvApp>, state: ConnState, method: ConnMethod, onLaunch: (TvApp) -> Unit) {
    if (apps.isEmpty()) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(96.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(Pal.Btn.copy(alpha = 0.5f)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                when {
                    method == ConnMethod.STANDARD -> "Uygulama listesi ADB bağlantısında görünür"
                    state == ConnState.CONNECTED -> "Uygulamalar yükleniyor…"
                    else -> "Bağlanınca TV'deki uygulamalar burada görünür"
                },
                color = Pal.Sub,
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(16.dp)
            )
        }
        return
    }
    LazyRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        items(apps, key = { it.pkg }) { app -> AppTile(app) { onLaunch(app) } }
    }
}

@Composable
private fun AppTile(app: TvApp, onClick: () -> Unit) {
    val inter = remember { MutableInteractionSource() }
    val scale = pressScale(inter)
    val base = tileColors[abs(app.pkg.hashCode()) % tileColors.size]
    // TV'den gelen gerçek simge (ADB); yoksa renkli baş harf
    val iconBitmap = remember(app.pkg, app.icon) {
        app.icon?.let { runCatching { android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }.getOrNull() }
    }
    // Serbest düzen: simge kendi biçiminde gösterilir (kare kare, geniş afiş geniş);
    // yükseklik sabit, genişlik görselin oranına göre
    val tileH = 72.dp
    val tileW = if (iconBitmap != null && iconBitmap.height > 0)
        (tileH * (iconBitmap.width.toFloat() / iconBitmap.height)).coerceIn(56.dp, 128.dp)
    else tileH
    Column(
        Modifier.width(maxOf(tileW, 76.dp)).graphicsLayer { scaleX = scale; scaleY = scale },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier
                .size(tileW, tileH)
                .clip(RoundedCornerShape(14.dp))
                .then(
                    if (iconBitmap != null) Modifier
                    else Modifier.background(Brush.linearGradient(listOf(base, base.copy(alpha = 0.65f))))
                )
                .pressable(inter, deferMs = 500, onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            if (iconBitmap != null) {
                Image(
                    iconBitmap,
                    contentDescription = app.name,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Text(
                    app.name.take(1).uppercase(),
                    color = Color.White,
                    fontSize = 30.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            app.name,
            color = Pal.Text,
            fontSize = 12.sp,
            lineHeight = 14.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 2,                       // uzun adlar ("Prime Video") kesilmeden iki satıra sığar
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.height(30.dp)
        )
    }
}

// ---------- Yön tuşları ----------
private enum class Dir { UP, DOWN, LEFT, RIGHT }

@Composable
fun DPad(size: Dp, onKey: (Int) -> Unit) {
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(Brush.radialGradient(listOf(Color(0xFF323235), Pal.Pad)))
            .border(2.dp, RingBrush, CircleShape)
    ) {
        DirKey(Dir.UP, Modifier.align(Alignment.TopCenter).fillMaxWidth(0.4f).fillMaxHeight(0.33f)) { onKey(Keys.UP) }
        DirKey(Dir.DOWN, Modifier.align(Alignment.BottomCenter).fillMaxWidth(0.4f).fillMaxHeight(0.33f)) { onKey(Keys.DOWN) }
        DirKey(Dir.LEFT, Modifier.align(Alignment.CenterStart).fillMaxWidth(0.33f).fillMaxHeight(0.4f)) { onKey(Keys.LEFT) }
        DirKey(Dir.RIGHT, Modifier.align(Alignment.CenterEnd).fillMaxWidth(0.33f).fillMaxHeight(0.4f)) { onKey(Keys.RIGHT) }
        OkButton(Modifier.align(Alignment.Center).fillMaxSize(0.3f)) { onKey(Keys.OK) }
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
        Chevron(dir, color = lerpColor(Pal.Text, Pal.Blue, p), modifier = Modifier.size(30.dp))
    }
}

/** İnce ok işareti. */
@Composable
private fun Chevron(dir: Dir, color: Color, modifier: Modifier) {
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
private fun OkButton(modifier: Modifier, onClick: () -> Unit) {
    val inter = remember { MutableInteractionSource() }
    val scale = pressScale(inter)
    val pressed by inter.collectIsPressedAsState()
    Box(
        modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .shadow(14.dp, CircleShape, ambientColor = Color.Black, spotColor = Color.Black)
            .clip(CircleShape)
            .background(
                Brush.verticalGradient(
                    if (pressed) listOf(Color(0xFF3A3A3E), Color(0xFF333336))
                    else listOf(Color(0xFF38383B), Color(0xFF2B2B2E))
                )
            )
            .border(3.dp, RingBrush, CircleShape)
            .pressable(inter, deferMs = 70, onClick = onClick)
    )
}

// ---------- Dokunmatik yüzeyler ----------
private val EdgeZone = 30.dp

/** Kenar şeritlerinden başlayan hareketler bırakılır; böylece kenardan kaydırınca mod değişir. */
private fun inEdge(x: Float, width: Int, edgePx: Float) = x < edgePx || x > width - edgePx

@Composable
private fun PadSurface(
    size: Dp,
    hint: List<String>,
    gesture: Modifier,
    icon: (@Composable () -> Unit)? = null
) {
    val shape = RoundedCornerShape(36.dp)
    Box(
        Modifier
            .size(size)
            .clip(shape)
            .background(Brush.radialGradient(listOf(Color(0xFF323235), Pal.Pad)))
            .border(2.dp, RingBrush, shape)
            .then(gesture),
        contentAlignment = Alignment.Center
    ) {
        // Kenar şeridi ipucu
        Box(Modifier.align(Alignment.CenterStart).padding(start = 8.dp).size(width = 3.dp, height = 40.dp).clip(CircleShape).background(Color(0x33FFFFFF)))
        Box(Modifier.align(Alignment.CenterEnd).padding(end = 8.dp).size(width = 3.dp, height = 40.dp).clip(CircleShape).background(Color(0x33FFFFFF)))
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
fun TouchPad(size: Dp, onKey: (Int) -> Unit) {
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val step = with(density) { 48.dp.toPx() }
    val edge = with(density) { EdgeZone.toPx() }
    val slop = with(density) { 8.dp.toPx() }
    val latest by rememberUpdatedState(onKey)

    PadSurface(
        size,
        listOf("Kaydır: yön", "Dokun: OK", "Mod değiştirmek için kenardan kaydır"),
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

/** Fare modu: TV'de imleç. Tek parmak imleç, dokunma tık, iki parmak sayfa kaydırma. */
@Composable
fun TrackPad(size: Dp, fastMode: Boolean, method: ConnMethod, onMouse: (String) -> Unit) {
    if (method == ConnMethod.STANDARD) {
        PadSurface(size, listOf("Bu özellik", "ADB bağlantısında çalışır"), Modifier)
        return
    }
    if (!fastMode) {
        PadSurface(size, listOf("Fare modu için", "⚡ Hızlı mod gerekli", "Bağlantıyı yenilemeyi dene"), Modifier)
        return
    }
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val edge = with(density) { EdgeZone.toPx() }
    val slop = with(density) { 8.dp.toPx() }
    val wheelStep = with(density) { 28.dp.toPx() }
    val latest by rememberUpdatedState(onMouse)

    PadSurface(
        size,
        emptyList(),
        Modifier.pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                if (inEdge(down.position.x, this.size.width, edge)) return@awaitEachGesture
                down.consume()
                var travelled = 0f
                var multi = false
                var rx = 0f
                var ry = 0f
                var wheelAcc = 0f
                while (true) {
                    val ev = awaitPointerEvent()
                    val pressed = ev.changes.filter { it.pressed }
                    if (pressed.isEmpty()) {
                        if (!multi && travelled < slop) {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            latest("c")
                        }
                        break
                    }
                    if (pressed.size >= 2) multi = true

                    // Ortalama hareket
                    var dx = 0f
                    var dy = 0f
                    pressed.forEach { val d = it.positionChange(); dx += d.x; dy += d.y }
                    dx /= pressed.size
                    dy /= pressed.size
                    ev.changes.forEach { it.consume() }
                    travelled += kotlin.math.sqrt(dx * dx + dy * dy)

                    if (multi) {
                        wheelAcc += dy
                        val notches = (wheelAcc / wheelStep).toInt()
                        if (notches != 0) {
                            latest("w $notches")
                            wheelAcc -= notches * wheelStep
                        }
                    } else {
                        // Hafif hızlanma: hızlı hareket daha uzağa gider
                        val speed = kotlin.math.sqrt(dx * dx + dy * dy)
                        val gain = 0.9f + (speed / 40f).coerceAtMost(1.6f)
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
            }
        },
        icon = { TrackpadIcon(Modifier.size(size * 0.3f)) }
    )
}

/** Trackpad simgesi: dokunmatik yüzey + işaret eden el. */
@Composable
private fun TrackpadIcon(modifier: Modifier) {
    val line = Color(0xFFCFCBDC)
    val cut = Color(0xFF2E2E31)
    Canvas(modifier) {
        val u = size.width / 48f
        fun p(x: Float, y: Float) = Offset(x * u, y * u)
        val stroke = Stroke(width = 3f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)

        // Yüzey
        drawRoundRect(
            color = line,
            topLeft = p(3f, 7f),
            size = androidx.compose.ui.geometry.Size(36f * u, 27f * u),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(5f * u),
            style = stroke
        )

        // El
        val hand = Path().apply {
            moveTo(26f * u, 36f * u)
            lineTo(26f * u, 21f * u)
            cubicTo(26f * u, 17f * u, 32f * u, 17f * u, 32f * u, 21f * u)
            lineTo(32f * u, 28f * u)
            lineTo(40f * u, 29.5f * u)
            quadraticBezierTo(44f * u, 30.5f * u, 43.5f * u, 34.5f * u)
            lineTo(42.5f * u, 40.5f * u)
            quadraticBezierTo(41.5f * u, 44.5f * u, 37.5f * u, 44.5f * u)
            lineTo(30.5f * u, 44.5f * u)
            quadraticBezierTo(27.5f * u, 44.5f * u, 25.5f * u, 42.5f * u)
            lineTo(20.5f * u, 37.5f * u)
            quadraticBezierTo(19f * u, 35.5f * u, 21f * u, 34.3f * u)
            quadraticBezierTo(23f * u, 33.2f * u, 26f * u, 36f * u)
            close()
        }
        // Elin altındaki çizgiyi kes, sonra eli çiz
        drawPath(hand, cut)
        drawPath(hand, cut, style = Stroke(width = 8f * u, join = StrokeJoin.Round))
        drawPath(hand, line, style = stroke)
        // Parmak boğumları
        drawLine(line, p(35.5f, 32f), p(35.5f, 37f), 2.5f * u, StrokeCap.Round)
        drawLine(line, p(39.5f, 33f), p(39.5f, 37f), 2.5f * u, StrokeCap.Round)
    }
}

private val modeNames = listOf("Yön tuşları", "Kaydırma", "Fare")

/**
 * Yüzen mod çubuğu: gölgeli kapsül içinde üç eşit çubuk ve seçili moda kayan renkli gösterge.
 * Dokunarak ya da kapsül üzerinde sağa-sola kaydırarak mod değiştirilir.
 * Mod değişince adı kapsülün üstünde kısa süre belirir.
 */
@Composable
private fun PageDots(page: Int, onSelect: (Int) -> Unit) {
    val haptic = LocalHapticFeedback.current
    val step = with(LocalDensity.current) { 36.dp.toPx() }
    val currentPage by rememberUpdatedState(page)
    val select by rememberUpdatedState(onSelect)

    val seg = 40.dp
    val bar = 24.dp
    val thumbX by animateDpAsState(
        seg * page + (seg - bar) / 2,
        spring(dampingRatio = 0.7f, stiffness = 500f),
        label = "thumb"
    )

    var showLabel by remember { mutableStateOf(false) }
    LaunchedEffect(page) {
        showLabel = true
        delay(1300)
        showLabel = false
    }
    val labelAlpha by animateFloatAsState(if (showLabel) 1f else 0f, tween(220), label = "label")

    val capsule = RoundedCornerShape(50)
    Box(contentAlignment = Alignment.Center) {
        // Kısa süre görünen mod adı (çubuğun üstünde yüzer)
        Box(
            Modifier
                .offset(y = (-38).dp)
                .graphicsLayer { alpha = labelAlpha; translationY = (1f - labelAlpha) * 12f }
                .shadow(8.dp, capsule)
                .clip(capsule)
                .background(Color(0xFF34343A))
                .padding(horizontal = 12.dp, vertical = 5.dp)
        ) {
            Text(modeNames.getOrElse(page) { "" }, color = Pal.Text, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        }

        // Kapsül
        Box(
            Modifier
                .shadow(12.dp, capsule, ambientColor = Color.Black, spotColor = Color.Black)
                .clip(capsule)
                .background(Color(0xFF29292D))
                .border(1.dp, Color(0x14FFFFFF), capsule)
                .pointerInput(Unit) {
                    var acc = 0f
                    var target = 0
                    detectHorizontalDragGestures(
                        onDragStart = { acc = 0f; target = currentPage },
                        onHorizontalDrag = { change, drag ->
                            change.consume()
                            acc += drag
                            val moveBy = when {
                                acc >= step -> 1
                                acc <= -step -> -1
                                else -> 0
                            }
                            if (moveBy != 0) {
                                acc -= moveBy * step
                                val next = (target + moveBy).coerceIn(0, modeNames.lastIndex)
                                if (next != target) {
                                    target = next
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    select(next)
                                }
                            }
                        }
                    )
                }
                .padding(horizontal = 10.dp, vertical = 4.dp)
        ) {
            Box(Modifier.size(width = seg * modeNames.size, height = 28.dp)) {
                Row {
                    for (i in modeNames.indices) {
                        Box(
                            Modifier
                                .size(width = seg, height = 28.dp)
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null
                                ) { onSelect(i) },
                            contentAlignment = Alignment.Center
                        ) {
                            Box(
                                Modifier
                                    .size(width = bar, height = 5.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF4A4A50))
                            )
                        }
                    }
                }
                // Kayan seçili gösterge
                Box(
                    Modifier
                        .align(Alignment.CenterStart)
                        .offset(x = thumbX)
                        .size(width = bar, height = 5.dp)
                        .clip(CircleShape)
                        .background(RingBrush)
                )
            }
        }
    }
}

// ---------- Alt kontroller ----------
/**
 * Alt kontroller. Tüm ölçüler tek bir "b" (düğme çapı) değerinden türetilir:
 * VOL/CH sürgüsü düğme genişliğinde ve 3 düğme yüksekliğinde, aralarda eşit boşluk.
 */
@Composable
private fun BottomControls(
    ui: UiState,
    onKey: (Int) -> Unit,
    onMic: () -> Unit,
    onKeyboard: () -> Unit,
    onNumpad: () -> Unit
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val b = minOf(76.dp, maxWidth / 5.8f)   // 5 sütun + 4 boşluk
        val gap = b * 0.2f
        val icon = b * 0.42f
        Row(
            Modifier.fillMaxWidth().height(b * 3f),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Rocker(
                width = b,
                label = "VOL",
                value = ui.volume?.toString(),
                level = ui.volume?.let { it.toFloat() / ui.volumeMax.coerceAtLeast(1) },
                topIsPlus = true,
                onUp = { onKey(Keys.VOL_UP) },
                onDown = { onKey(Keys.VOL_DOWN) }
            )
            Column(verticalArrangement = Arrangement.spacedBy(b * 0.38f)) {
                Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                    CircleBtn(b, { Icon(AppIcons.Undo, null, tint = Pal.Text, modifier = Modifier.size(icon)) }) { onKey(Keys.BACK) }
                    CircleBtn(b, { Icon(AppIcons.HomeOutline, null, tint = Pal.Text, modifier = Modifier.size(icon)) }) { onKey(Keys.HOME) }
                    CircleBtn(b, { DotsIcon(icon * 0.92f) }, onNumpad)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                    CircleBtn(b, { Icon(AppIcons.VolumeOff, null, tint = Pal.Text, modifier = Modifier.size(icon)) }) { onKey(Keys.MUTE) }
                    MicButton(b, icon, ui.micActive, ui.micLevel, onMic)
                    CircleBtn(b, { Icon(AppIcons.Keyboard, null, tint = Pal.Text, modifier = Modifier.size(icon)) }, onKeyboard)
                }
            }
            Rocker(
                width = b,
                label = "CH",
                value = null,
                level = null,
                topIsPlus = false,
                onUp = { onKey(Keys.CH_UP) },
                onDown = { onKey(Keys.CH_DOWN) }
            )
        }
    }
}

@Composable
private fun CircleBtn(size: Dp, content: @Composable () -> Unit, onClick: () -> Unit) {
    val inter = remember { MutableInteractionSource() }
    val scale = pressScale(inter)
    val pressed by inter.collectIsPressedAsState()
    Box(
        Modifier
            .size(size)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(CircleShape)
            .background(if (pressed) Pal.BtnPressed else Pal.Btn)
            .pressable(inter, onClick = onClick),
        contentAlignment = Alignment.Center
    ) { content() }
}

/** Mikrofon düğmesi: dinlerken kırmızıya döner, etrafında genişleyen halkalar ve sese göre büyüyen ışık. */
@Composable
private fun MicButton(btnSize: Dp, iconSize: Dp, active: Boolean, level: Float, onClick: () -> Unit) {
    val inter = remember { MutableInteractionSource() }
    val scale = pressScale(inter)
    val pulse = rememberInfiniteTransition(label = "mic")
    val p by pulse.animateFloat(
        0f, 1f, infiniteRepeatable(tween(1300, easing = LinearEasing)), label = "ring"
    )
    val lvl by animateFloatAsState(if (active) level else 0f, tween(120), label = "lvl")
    val bg by animateColorAsState(if (active) Color(0xFF4A2327) else Pal.Btn, tween(200), label = "bg")
    val tint by animateColorAsState(if (active) Pal.Red else Pal.Text, tween(200), label = "tint")
    Box(
        Modifier
            .size(btnSize)
            .drawBehind {
                if (active) {
                    val r = size.minDimension / 2
                    // Sese göre büyüyen yumuşak ışık
                    drawCircle(Pal.Red.copy(alpha = 0.22f), radius = r * (1f + 0.35f * lvl))
                    // Sürekli dışa doğru yayılan iki halka
                    for (k in 0..1) {
                        val f = (p + k * 0.5f) % 1f
                        drawCircle(
                            Pal.Red.copy(alpha = 0.45f * (1f - f)),
                            radius = r * (1f + 0.55f * f),
                            style = Stroke(width = 2.dp.toPx())
                        )
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { scaleX = scale; scaleY = scale }
                .clip(CircleShape)
                .background(bg)
                .pressable(inter, onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Icon(AppIcons.Mic, null, tint = tint, modifier = Modifier.size(iconSize))
        }
    }
}

/** Sayı tuş takımı simgesi (3x3 + 1 nokta). */
@Composable
private fun DotsIcon(iconSize: Dp) {
    Canvas(Modifier.size(iconSize)) {
        val r = size.width * 0.09f
        val gap = size.width * 0.3f
        val x0 = size.width * 0.2f
        val y0 = size.height * 0.14f
        for (row in 0..2) for (col in 0..2) {
            drawCircle(Pal.Text, r, Offset(x0 + col * gap, y0 + row * gap * 0.8f))
        }
        drawCircle(Pal.Text, r, Offset(x0 + gap, y0 + 3 * gap * 0.8f))
    }
}

/** VOL / CH dikey sürgüsü: üst yarı artı, alt yarı eksi. */
@Composable
private fun Rocker(
    width: Dp,
    label: String,
    value: String?,
    level: Float?,
    topIsPlus: Boolean,
    onUp: () -> Unit,
    onDown: () -> Unit
) {
    val shape = RoundedCornerShape(width / 2)
    val animLevel by animateFloatAsState((level ?: 0f).coerceIn(0f, 1f), tween(250), label = "lvl")
    Box(
        Modifier
            .width(width)
            .fillMaxHeight()
            .clip(shape)
            .background(Pal.Btn)
    ) {
        if (level != null) {
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .fillMaxHeight(animLevel)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Pal.Level)))
            )
        }
        Column(Modifier.fillMaxSize()) {
            RockerHalf(Modifier.weight(1f), up = true, plus = topIsPlus, onClick = onUp)
            RockerHalf(Modifier.weight(1f), up = false, plus = topIsPlus, onClick = onDown)
        }
        Column(
            Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(label, color = Pal.Text, fontSize = 15.sp, letterSpacing = 1.sp)
            if (value != null) Text(value, color = Pal.Text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun RockerHalf(modifier: Modifier, up: Boolean, plus: Boolean, onClick: () -> Unit) {
    val inter = remember { MutableInteractionSource() }
    val pressed by inter.collectIsPressedAsState()
    Box(
        modifier
            .fillMaxWidth()
            .background(if (pressed) Color.White.copy(alpha = 0.07f) else Color.Transparent)
            .pressable(inter, repeat = true, onClick = onClick),
        contentAlignment = if (up) Alignment.TopCenter else Alignment.BottomCenter
    ) {
        Box(Modifier.padding(vertical = 14.dp)) {
            if (plus) {
                Text(if (up) "+" else "−", color = Pal.Text, fontSize = 28.sp, fontWeight = FontWeight.Light)
            } else {
                Chevron(if (up) Dir.UP else Dir.DOWN, Pal.Text, Modifier.size(20.dp))
            }
        }
    }
}

// ---------- Alt sayfalar ----------
@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
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

@Composable
private fun ConnectSheet(
    initialIp: String,
    ui: UiState,
    onConnect: (String) -> Unit,
    onMethod: (ConnMethod) -> Unit,
    onPickSaved: (SavedTv) -> Unit,
    onForgetSaved: (SavedTv) -> Unit
) {
    var ip by remember { mutableStateOf(initialIp) }
    // Ağ taraması: kayıtlı TV yoksa panel açılınca kendiliğinden, varsa yalnızca
    // "Yeni TV ara" ile başlar ve 15 sn sonra durur.
    val context = LocalContext.current
    val discovery = remember { TvDiscovery(context) }
    var found by remember { mutableStateOf<List<FoundTv>>(emptyList()) }
    var scanning by remember { mutableStateOf(ui.savedTvs.isEmpty()) }
    var scannedOnce by remember { mutableStateOf(false) }
    LaunchedEffect(scanning) {
        if (scanning) {
            discovery.start { found = it }
            delay(15_000)
            discovery.stop()
            scanning = false
            scannedOnce = true
        }
    }
    DisposableEffect(Unit) { onDispose { discovery.stop() } }
    // Kayıtlı TV'lerin açık olup olmadığını panel açılınca hızlıca kontrol et
    var reachable by remember { mutableStateOf<Set<String>>(emptySet()) }
    LaunchedEffect(ui.savedTvs) {
        val ips = ui.savedTvs.map { it.ip }
        reachable = kotlinx.coroutines.coroutineScope {
            ips.map { ip ->
                async(kotlinx.coroutines.Dispatchers.IO) { if (TvPing.isOnline(ip)) ip else null }
            }.mapNotNull { it.await() }.toSet()
        }
    }
    SheetTitle("TV'ye bağlan", "TV ve telefon aynı Wi-Fi ağında olmalı.")
    MethodSelector(ui.method, onMethod)
    Spacer(Modifier.height(12.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = ip,
            onValueChange = { ip = it },
            placeholder = { Text("192.168.1.50") },
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
            colors = fieldColors(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { onConnect(ip.trim()) }),
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(10.dp))
        GradientButton(
            if (ui.state == ConnState.CONNECTING) "…" else "Bağlan",
            enabled = ui.state != ConnState.CONNECTING
        ) { onConnect(ip.trim()) }
    }
    SavedTvList(ui, reachable + found.map { it.ip }, onPick = { tv -> ip = tv.ip; onPickSaved(tv) }, onForget = onForgetSaved)
    FoundTvList(
        found.filter { f -> ui.savedTvs.none { it.ip == f.ip } }, ui.state,
        scanning = scanning, scannedOnce = scannedOnce, onScan = { scanning = true }
    ) { tv ->
        ip = tv.ip
        onConnect(tv.ip)
    }
    Spacer(Modifier.height(14.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        StatusDot(ui.state)
        Spacer(Modifier.width(8.dp))
        Text(
            when (ui.state) {
                ConnState.CONNECTED -> "Bağlı: ${ui.tvName} (${ui.message})" + if (ui.fastMode) " · ⚡ Hızlı mod" else ""
                else -> ui.message
            },
            color = when (ui.state) {
                ConnState.ERROR -> Pal.Red
                ConnState.CONNECTING -> Pal.Amber
                else -> Pal.Sub
            },
            fontSize = 14.sp
        )
    }
    val version = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
    }
    if (version != null) {
        Spacer(Modifier.height(18.dp))
        Text("TV Kumanda · Sürüm $version", color = Pal.Sub, fontSize = 12.sp)
    }
}

/** Kayıtlı TV'ler: her satır bir TV + yöntem; dokununca o yöntemle bağlanır, × ile kaldırılır. */
@Composable
private fun SavedTvList(ui: UiState, online: Set<String>, onPick: (SavedTv) -> Unit, onForget: (SavedTv) -> Unit) {
    if (ui.savedTvs.isEmpty()) return
    Spacer(Modifier.height(16.dp))
    Text("KAYITLI TV'LER", color = Pal.Sub, fontSize = 12.sp, letterSpacing = 1.sp, fontWeight = FontWeight.Medium)
    Spacer(Modifier.height(8.dp))
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (tv in ui.savedTvs) {
            val current = ui.state == ConnState.CONNECTED && ui.message == tv.ip && ui.method == tv.method
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Pal.Btn)
                    .then(if (current) Modifier.border(1.dp, RingBrush, RoundedCornerShape(14.dp)) else Modifier)
                    .clickable(enabled = ui.state != ConnState.CONNECTING && !current) { onPick(tv) }
                    .padding(start = 14.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(AppIcons.Tv, null, tint = if (current) Pal.Blue else Pal.Text, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(tv.name, color = Pal.Text, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (tv.ip in online) {
                            Box(Modifier.size(7.dp).clip(CircleShape).background(Pal.Green))
                            Spacer(Modifier.width(5.dp))
                        }
                        Text(
                            "${tv.ip} · ${if (tv.method == ConnMethod.STANDARD) "Standart" else "ADB"}",
                            color = Pal.Sub, fontSize = 12.sp
                        )
                    }
                }
                if (current) {
                    Text("Bağlı", color = Pal.Green, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.width(10.dp))
                }
                Box(
                    Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .clickable { onForget(tv) },
                    contentAlignment = Alignment.Center
                ) {
                    Text("×", color = Pal.Sub, fontSize = 20.sp)
                }
            }
        }
    }
}

/** Ağda otomatik bulunan (henüz kayıtlı olmayan) TV'ler. */
@Composable
private fun FoundTvList(
    tvs: List<FoundTv>,
    state: ConnState,
    scanning: Boolean,
    scannedOnce: Boolean,
    onScan: () -> Unit,
    onPick: (FoundTv) -> Unit
) {

    Spacer(Modifier.height(16.dp))
    Text(
        "BULUNAN TV'LER",
        color = Pal.Sub,
        fontSize = 12.sp,
        letterSpacing = 1.sp,
        fontWeight = FontWeight.Medium
    )
    Spacer(Modifier.height(8.dp))
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (tv in tvs) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Pal.Btn)
                    .clickable(enabled = state != ConnState.CONNECTING) { onPick(tv) }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(AppIcons.Tv, null, tint = Pal.Blue, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(tv.name, color = Pal.Text, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(tv.ip, color = Pal.Sub, fontSize = 12.sp)
                }
                Text("Bağlan", color = Pal.Blue, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
    Spacer(Modifier.height(if (tvs.isEmpty()) 0.dp else 8.dp))
    if (scanning) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 6.dp)) {
            CircularProgressIndicator(Modifier.size(16.dp), color = Pal.Sub, strokeWidth = 2.dp)
            Spacer(Modifier.width(10.dp))
            Text("Aranıyor…", color = Pal.Sub, fontSize = 13.sp)
        }
    } else {
        if (scannedOnce && tvs.isEmpty()) {
            Text("Yeni TV bulunamadı. IP'yi elle de girebilirsin.", color = Pal.Sub, fontSize = 13.sp)
            Spacer(Modifier.height(8.dp))
        }
        Row(
            Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(Pal.Btn)
                .clickable(onClick = onScan)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("⟳", color = Pal.Blue, fontSize = 16.sp)
            Spacer(Modifier.width(8.dp))
            Text("Yeni TV ara", color = Pal.Blue, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        }
    }
}

/** Bağlantı yöntemi seçimi: Standart (ADB'siz) | ADB. */
@Composable
private fun MethodSelector(method: ConnMethod, onMethod: (ConnMethod) -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(shape)
            .background(Pal.Btn)
            .padding(4.dp)
    ) {
        for ((m, label) in listOf(ConnMethod.STANDARD to "Standart", ConnMethod.ADB to "ADB")) {
            val selected = m == method
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(10.dp))
                    .then(if (selected) Modifier.background(RingBrush) else Modifier)
                    .clickable { onMethod(m) },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    label,
                    color = if (selected) Color.White else Pal.Sub,
                    fontSize = 14.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                )
            }
        }
    }
}

@Composable
private fun KeyboardSheet(onText: (String) -> Unit, onKey: (Int) -> Unit) {
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
private fun NumpadSheet(onKey: (Int) -> Unit) {
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

// ---------- Ortak yardımcılar ----------
private fun lerpColor(a: Color, b: Color, t: Float) = androidx.compose.ui.graphics.lerp(a, b, t)

@Composable
fun pressScale(inter: MutableInteractionSource): Float {
    val pressed by inter.collectIsPressedAsState()
    val s by animateFloatAsState(
        if (pressed) 0.93f else 1f,
        spring(dampingRatio = 0.5f, stiffness = 800f),
        label = "scale"
    )
    return s
}

/**
 * Basma davranışı: dokunulduğu anda gönderir (gecikmesiz),
 * basılı tutunca (repeat=true) tekrar tekrar gönderir.
 * deferMs > 0 ise gönderim o kadar ertelenir; bu sürede parmak kaydırılırsa
 * (ör. mod değiştirmek için) tuş gönderilmez. Hızlı dokunuşta bırakınca hemen gönderilir.
 */
@Composable
fun Modifier.pressable(
    inter: MutableInteractionSource,
    repeat: Boolean = false,
    deferMs: Long = 0,
    onClick: () -> Unit
): Modifier {
    val haptic = LocalHapticFeedback.current
    val latest by rememberUpdatedState(onClick)
    return this.pointerInput(repeat, deferMs) {
        coroutineScope {
            detectTapGestures(onPress = { offset ->
                val press = PressInteraction.Press(offset)
                inter.emit(press)
                var fired = false
                val fire = {
                    fired = true
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    latest()
                }
                if (deferMs == 0L) fire()
                val job = launch {
                    if (deferMs > 0) { delay(deferMs); fire() }
                    if (repeat) {
                        delay(380 - deferMs)
                        while (isActive) {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            latest()
                            delay(90)
                        }
                    }
                }
                val released = tryAwaitRelease()
                job.cancel()
                if (released && !fired) fire()
                inter.emit(if (released) PressInteraction.Release(press) else PressInteraction.Cancel(press))
            })
        }
    }
}
