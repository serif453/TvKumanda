package com.serif.tvkumanda

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs

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
    onLaunch: (TvApp) -> Unit
) {
    var sheet by remember { mutableStateOf(if (initialIp.isBlank()) Sheet.CONNECT else Sheet.NONE) }
    var page by remember { mutableIntStateOf(0) }

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
        AppsRow(ui.apps, ui.state, onLaunch)

        // Yön tuşu kalan boşluğa göre boyutlanır; ekran ne olursa olsun kaydırma gerekmez
        BoxWithConstraints(
            Modifier.fillMaxWidth().weight(1f).padding(vertical = 12.dp),
            contentAlignment = Alignment.Center
        ) {
            val padSize = minOf(maxWidth * 0.88f, maxHeight)
            Crossfade(targetState = page, label = "page") { p ->
                if (p == 0) DPad(padSize, onKey) else TouchPad(padSize, onKey)
            }
        }

        PageDots(page) { page = it }
        Spacer(Modifier.height(10.dp))

        BottomControls(ui, onKey,
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
                    Sheet.CONNECT -> ConnectSheet(initialIp, ui, onConnect)
                    Sheet.KEYBOARD -> KeyboardSheet(onText, onKey)
                    Sheet.NUMPAD -> NumpadSheet(onKey)
                    Sheet.NONE -> {}
                }
            }
        }
    }
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
private fun AppsRow(apps: List<TvApp>, state: ConnState, onLaunch: (TvApp) -> Unit) {
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
                if (state == ConnState.CONNECTED) "Uygulamalar yükleniyor…" else "Bağlanınca TV'deki uygulamalar burada görünür",
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
    Column(
        Modifier.width(76.dp).graphicsLayer { scaleX = scale; scaleY = scale },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier
                .size(72.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(Brush.linearGradient(listOf(base, base.copy(alpha = 0.65f))))
                .pressable(inter, onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Text(
                app.name.take(1).uppercase(),
                color = Color.White,
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            app.name,
            color = Pal.Text,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
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
        modifier.pressable(inter, repeat = true, onClick = onClick),
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
            .pressable(inter, onClick = onClick)
    )
}

// ---------- Dokunmatik yüzey ----------
@Composable
fun TouchPad(size: Dp, onKey: (Int) -> Unit) {
    val haptic = LocalHapticFeedback.current
    val step = with(LocalDensity.current) { 48.dp.toPx() }
    val latest by rememberUpdatedState(onKey)
    Box(
        Modifier
            .size(size)
            .clip(RoundedCornerShape(36.dp))
            .background(Brush.radialGradient(listOf(Color(0xFF323235), Pal.Pad)))
            .border(2.dp, RingBrush, RoundedCornerShape(36.dp))
            .pointerInput(Unit) {
                detectTapGestures(onTap = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    latest(Keys.OK)
                })
            }
            .pointerInput(Unit) {
                var acc = Offset.Zero
                detectDragGestures(
                    onDragStart = { acc = Offset.Zero },
                    onDrag = { change, drag ->
                        change.consume()
                        acc += drag
                        while (abs(acc.x) >= step || abs(acc.y) >= step) {
                            if (abs(acc.x) > abs(acc.y)) {
                                latest(if (acc.x > 0) Keys.RIGHT else Keys.LEFT)
                                acc = acc.copy(x = acc.x - step * kotlin.math.sign(acc.x), y = 0f)
                            } else {
                                latest(if (acc.y > 0) Keys.DOWN else Keys.UP)
                                acc = acc.copy(y = acc.y - step * kotlin.math.sign(acc.y), x = 0f)
                            }
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        }
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Kaydır: yön", color = Pal.Sub, fontSize = 15.sp)
            Spacer(Modifier.height(4.dp))
            Text("Dokun: OK", color = Pal.Sub, fontSize = 15.sp)
        }
    }
}

@Composable
private fun PageDots(page: Int, onSelect: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        for (i in 0..1) {
            Box(
                Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .clickable { onSelect(i) },
                contentAlignment = Alignment.Center
            ) {
                Box(
                    Modifier
                        .size(if (i == page) 11.dp else 9.dp)
                        .clip(CircleShape)
                        .background(if (i == page) Color(0xFFDDDDE2) else Color(0xFF55555C))
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
                    CircleBtn(b, { Icon(AppIcons.Keyboard, null, tint = Pal.Text, modifier = Modifier.size(icon)) }, onKeyboard)
                    CircleBtn(b, { Icon(AppIcons.HomeOutline, null, tint = Pal.Text, modifier = Modifier.size(icon)) }) { onKey(Keys.HOME) }
                    CircleBtn(b, { DotsIcon(icon * 0.92f) }, onNumpad)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                    CircleBtn(b, { Icon(AppIcons.VolumeOff, null, tint = Pal.Text, modifier = Modifier.size(icon)) }) { onKey(Keys.MUTE) }
                    CircleBtn(b, { Icon(AppIcons.Mic, null, tint = Pal.Text, modifier = Modifier.size(icon)) }) { onKey(Keys.ASSIST) }
                    CircleBtn(b, { Icon(AppIcons.Undo, null, tint = Pal.Text, modifier = Modifier.size(icon)) }) { onKey(Keys.BACK) }
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
private fun ConnectSheet(initialIp: String, ui: UiState, onConnect: (String) -> Unit) {
    var ip by remember { mutableStateOf(initialIp) }
    SheetTitle("TV'ye bağlan", "TV ve telefon aynı Wi-Fi ağında olmalı.")
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
    val context = LocalContext.current
    val version = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
    }
    if (version != null) {
        Spacer(Modifier.height(18.dp))
        Text("TV Kumanda · Sürüm $version", color = Pal.Sub, fontSize = 12.sp)
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
            .pressable(inter, repeat, onClick),
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
 */
@Composable
fun Modifier.pressable(
    inter: MutableInteractionSource,
    repeat: Boolean = false,
    onClick: () -> Unit
): Modifier {
    val haptic = LocalHapticFeedback.current
    val latest by rememberUpdatedState(onClick)
    return this.pointerInput(repeat) {
        coroutineScope {
            detectTapGestures(onPress = { offset ->
                val press = PressInteraction.Press(offset)
                inter.emit(press)
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                latest()
                val job = if (repeat) launch {
                    delay(380)
                    while (isActive) {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        latest()
                        delay(90)
                    }
                } else null
                val released = tryAwaitRelease()
                job?.cancel()
                inter.emit(if (released) PressInteraction.Release(press) else PressInteraction.Cancel(press))
            })
        }
    }
}
