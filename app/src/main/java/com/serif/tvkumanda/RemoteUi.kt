package com.serif.tvkumanda

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

// ---------- Renkler ----------
object Pal {
    val Bg = Color(0xFF070A14)
    val Card = Color(0x12FFFFFF)
    val CardBorder = Color(0x1FFFFFFF)
    val Text = Color(0xFFF1F5F9)
    val Sub = Color(0xFF94A3B8)
    val Cyan = Color(0xFF22D3EE)
    val Indigo = Color(0xFF6366F1)
    val Red = Color(0xFFF43F5E)
    val Green = Color(0xFF34D399)
    val Amber = Color(0xFFFBBF24)
}

val AccentBrush = Brush.linearGradient(listOf(Color(0xFF4F46E5), Color(0xFF06B6D4)))

@Composable
fun RemoteTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Pal.Cyan,
            background = Pal.Bg,
            surface = Pal.Bg,
            onSurface = Pal.Text,
            onBackground = Pal.Text
        ),
        content = content
    )
}

// ---------- Ana ekran ----------
@Composable
fun RemoteScreen(
    initialIp: String,
    state: ConnState,
    message: String,
    tvName: String,
    onConnect: (String) -> Unit,
    onKey: (Int) -> Unit,
    onText: (String) -> Unit,
    onCommand: (String) -> Unit
) {
    var ip by remember { mutableStateOf(initialIp) }
    var showSetup by remember { mutableStateOf(initialIp.isBlank()) }
    var showKeyboard by remember { mutableStateOf(false) }

    LaunchedEffect(state) {
        if (state == ConnState.CONNECTED) showSetup = false
        if (state == ConnState.ERROR) showSetup = true
    }

    Box(Modifier.fillMaxSize().background(Pal.Bg)) {
        // Arka plandaki yumuşak ışık
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .offset(y = (-140).dp)
                .size(460.dp)
                .background(Brush.radialGradient(listOf(Color(0x404F46E5), Color.Transparent)))
        )

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Header(state, message, tvName) { showSetup = !showSetup }

            AnimatedVisibility(
                visible = showSetup,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                ConnectCard(ip, { ip = it }, state, message) { onConnect(ip.trim()) }
            }

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                RoundIconButton(AppIcons.Power, tint = Pal.Red, container = Color(0x26F43F5E)) {
                    onKey(Keys.POWER)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    RoundIconButton(AppIcons.Keyboard, active = showKeyboard) {
                        showKeyboard = !showKeyboard
                    }
                    RoundIconButton(Icons.Filled.Menu) { onKey(Keys.MENU) }
                }
            }

            AnimatedVisibility(
                visible = showKeyboard,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                KeyboardCard(onText, onKey)
            }

            DPad(onKey)

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PillButton(Icons.Filled.ArrowBack, "Geri", Modifier.weight(1f)) { onKey(Keys.BACK) }
                PillButton(Icons.Filled.Home, "Ana ekran", Modifier.weight(1f)) { onKey(Keys.HOME) }
            }

            Segmented(
                Modifier.fillMaxWidth(),
                listOf(
                    Seg(AppIcons.VolumeDown, repeat = true) { onKey(Keys.VOL_DOWN) },
                    Seg(AppIcons.VolumeOff) { onKey(Keys.MUTE) },
                    Seg(AppIcons.VolumeUp, repeat = true) { onKey(Keys.VOL_UP) }
                )
            )

            Segmented(
                Modifier.fillMaxWidth(),
                listOf(
                    Seg(AppIcons.SkipPrevious) { onKey(Keys.PREV) },
                    Seg(AppIcons.PlayPause) { onKey(Keys.PLAY_PAUSE) },
                    Seg(AppIcons.SkipNext) { onKey(Keys.NEXT) }
                )
            )

            Text(
                "UYGULAMALAR",
                color = Pal.Sub,
                fontSize = 12.sp,
                letterSpacing = 1.5.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                AppChip("YouTube", Color(0xFFFF0033), Modifier.weight(1f)) {
                    onCommand(launchApp("com.google.android.youtube.tv"))
                }
                AppChip("Netflix", Color(0xFFE50914), Modifier.weight(1f)) {
                    onCommand(launchApp("com.netflix.ninja"))
                }
                AppChip("Ayarlar", Pal.Sub, Modifier.weight(1f)) {
                    onCommand("am start -a android.settings.SETTINGS")
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

// ---------- Üst bilgi ----------
@Composable
fun Header(state: ConnState, message: String, tvName: String, onSettings: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(46.dp).clip(RoundedCornerShape(14.dp)).background(AccentBrush),
            contentAlignment = Alignment.Center
        ) {
            Icon(AppIcons.Tv, null, tint = Color.White, modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                if (state == ConnState.CONNECTED && tvName.isNotBlank()) tvName else "TV Kumanda",
                color = Pal.Text,
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(state)
                Spacer(Modifier.width(6.dp))
                Text(
                    when (state) {
                        ConnState.CONNECTED -> "Bağlı · $message"
                        ConnState.CONNECTING -> "Bağlanıyor…"
                        ConnState.ERROR -> "Bağlantı yok"
                        ConnState.IDLE -> "Bağlı değil"
                    },
                    color = Pal.Sub,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        RoundIconButton(Icons.Filled.Settings, size = 46.dp, onClick = onSettings)
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
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "alpha"
    )
    Box(
        Modifier
            .size(8.dp)
            .clip(CircleShape)
            .background(color.copy(alpha = if (state == ConnState.CONNECTING) a else 1f))
    )
}

// ---------- Kartlar ----------
@Composable
fun GlassCard(content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(24.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Pal.Card)
            .border(1.dp, Pal.CardBorder, shape)
            .padding(18.dp),
        content = content
    )
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Pal.Cyan,
    unfocusedBorderColor = Pal.CardBorder,
    focusedTextColor = Pal.Text,
    unfocusedTextColor = Pal.Text,
    cursorColor = Pal.Cyan,
    focusedContainerColor = Color(0x0AFFFFFF),
    unfocusedContainerColor = Color(0x0AFFFFFF),
    focusedPlaceholderColor = Pal.Sub,
    unfocusedPlaceholderColor = Pal.Sub
)

@Composable
fun ConnectCard(
    ip: String,
    onIp: (String) -> Unit,
    state: ConnState,
    message: String,
    onConnect: () -> Unit
) {
    GlassCard {
        Text("TV'ye bağlan", color = Pal.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Text("TV ve telefon aynı Wi-Fi ağında olmalı.", color = Pal.Sub, fontSize = 13.sp)
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = ip,
                onValueChange = onIp,
                placeholder = { Text("192.168.1.50") },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                colors = fieldColors(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { onConnect() }),
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(10.dp))
            GradientButton(
                if (state == ConnState.CONNECTING) "…" else "Bağlan",
                enabled = state != ConnState.CONNECTING,
                onClick = onConnect
            )
        }
        if (state == ConnState.ERROR || state == ConnState.CONNECTING) {
            Spacer(Modifier.height(10.dp))
            Text(
                message,
                color = if (state == ConnState.ERROR) Pal.Red else Pal.Amber,
                fontSize = 13.sp
            )
        }
    }
}

@Composable
fun KeyboardCard(onText: (String) -> Unit, onKey: (Int) -> Unit) {
    var text by remember { mutableStateOf("") }
    val send = { if (text.isNotEmpty()) { onText(text); text = "" } }
    GlassCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text("TV'ye yazılacak metin") },
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
            PillButton(AppIcons.Backspace, "Sil", Modifier.weight(1f), repeat = true) { onKey(Keys.DEL) }
            PillButton(AppIcons.Enter, "Enter", Modifier.weight(1f)) { onKey(Keys.ENTER) }
        }
    }
}

// ---------- Yön tuşları ----------
@Composable
fun DPad(onKey: (Int) -> Unit) {
    Box(
        Modifier
            .size(290.dp)
            .clip(CircleShape)
            .background(Brush.radialGradient(listOf(Color(0xFF1B2238), Color(0xFF0C1120))))
            .border(
                1.5.dp,
                Brush.linearGradient(listOf(Color(0x806366F1), Color(0x3322D3EE))),
                CircleShape
            )
    ) {
        DirKey(Icons.Filled.KeyboardArrowUp, Modifier.align(Alignment.TopCenter)) { onKey(Keys.UP) }
        DirKey(Icons.Filled.KeyboardArrowDown, Modifier.align(Alignment.BottomCenter)) { onKey(Keys.DOWN) }
        DirKey(Icons.Filled.KeyboardArrowLeft, Modifier.align(Alignment.CenterStart)) { onKey(Keys.LEFT) }
        DirKey(Icons.Filled.KeyboardArrowRight, Modifier.align(Alignment.CenterEnd)) { onKey(Keys.RIGHT) }
        OkButton(Modifier.align(Alignment.Center)) { onKey(Keys.OK) }
    }
}

@Composable
fun DirKey(icon: ImageVector, modifier: Modifier, onClick: () -> Unit) {
    val inter = remember { MutableInteractionSource() }
    val pressed by inter.collectIsPressedAsState()
    val p by animateFloatAsState(if (pressed) 1f else 0f, tween(120), label = "dir")
    Box(
        modifier
            .padding(6.dp)
            .size(88.dp)
            .clip(CircleShape)
            .background(Pal.Cyan.copy(alpha = 0.16f * p))
            .pressable(inter, repeat = true, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, null, tint = lerp(Pal.Sub, Pal.Cyan, p), modifier = Modifier.size(46.dp))
    }
}

@Composable
fun OkButton(modifier: Modifier, onClick: () -> Unit) {
    val inter = remember { MutableInteractionSource() }
    val scale = pressScale(inter)
    Box(
        modifier
            .size(112.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .shadow(22.dp, CircleShape, ambientColor = Pal.Cyan, spotColor = Pal.Cyan)
            .clip(CircleShape)
            .background(AccentBrush)
            .pressable(inter, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text("OK", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
    }
}

// ---------- Düğmeler ----------
@Composable
fun pressScale(inter: MutableInteractionSource): Float {
    val pressed by inter.collectIsPressedAsState()
    val s by animateFloatAsState(
        if (pressed) 0.92f else 1f,
        spring(dampingRatio = 0.5f, stiffness = 700f),
        label = "scale"
    )
    return s
}

/**
 * Basma davranışı: kısa dokunuşta bir kez, basılı tutunca (repeat=true) tekrar tekrar gönderir.
 * Kaydırma hareketinde tetiklenmez.
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
                var fired = false
                val job = if (repeat) launch {
                    delay(380)
                    while (isActive) {
                        fired = true
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        latest()
                        delay(110)
                    }
                } else null
                val released = tryAwaitRelease()
                job?.cancel()
                if (released && !fired) {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    latest()
                }
                inter.emit(if (released) PressInteraction.Release(press) else PressInteraction.Cancel(press))
            })
        }
    }
}

@Composable
fun RoundIconButton(
    icon: ImageVector,
    tint: Color = Pal.Text,
    container: Color = Pal.Card,
    active: Boolean = false,
    size: Dp = 56.dp,
    onClick: () -> Unit
) {
    val inter = remember { MutableInteractionSource() }
    val scale = pressScale(inter)
    Box(
        Modifier
            .size(size)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(CircleShape)
            .then(if (active) Modifier.background(AccentBrush) else Modifier.background(container))
            .border(1.dp, Pal.CardBorder, CircleShape)
            .pressable(inter, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, null, tint = if (active) Color.White else tint, modifier = Modifier.size(24.dp))
    }
}

@Composable
fun PillButton(
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    repeat: Boolean = false,
    onClick: () -> Unit
) {
    val inter = remember { MutableInteractionSource() }
    val scale = pressScale(inter)
    val shape = RoundedCornerShape(20.dp)
    Row(
        modifier
            .height(56.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(shape)
            .background(Pal.Card)
            .border(1.dp, Pal.CardBorder, shape)
            .pressable(inter, repeat, onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Icon(icon, null, tint = Pal.Text, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, color = Pal.Text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
    }
}

class Seg(val icon: ImageVector, val repeat: Boolean = false, val onClick: () -> Unit)

@Composable
fun Segmented(modifier: Modifier, items: List<Seg>) {
    val shape = RoundedCornerShape(30.dp)
    Row(
        modifier
            .height(60.dp)
            .clip(shape)
            .background(Pal.Card)
            .border(1.dp, Pal.CardBorder, shape),
        verticalAlignment = Alignment.CenterVertically
    ) {
        items.forEachIndexed { i, s ->
            if (i > 0) Box(Modifier.width(1.dp).height(26.dp).background(Pal.CardBorder))
            val inter = remember { MutableInteractionSource() }
            val pressed by inter.collectIsPressedAsState()
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(if (pressed) Color(0x1A22D3EE) else Color.Transparent)
                    .pressable(inter, s.repeat, s.onClick),
                contentAlignment = Alignment.Center
            ) {
                Icon(s.icon, null, tint = if (pressed) Pal.Cyan else Pal.Text, modifier = Modifier.size(26.dp))
            }
        }
    }
}

@Composable
fun AppChip(label: String, dot: Color, modifier: Modifier, onClick: () -> Unit) {
    val inter = remember { MutableInteractionSource() }
    val scale = pressScale(inter)
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier
            .height(52.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(shape)
            .background(Pal.Card)
            .border(1.dp, Pal.CardBorder, shape)
            .pressable(inter, onClick = onClick)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Box(Modifier.size(9.dp).clip(CircleShape).background(dot))
        Spacer(Modifier.width(8.dp))
        Text(label, color = Pal.Text, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

@Composable
fun GradientButton(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier
            .height(56.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(AccentBrush, alpha = if (enabled) 1f else 0.5f)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 20.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}
