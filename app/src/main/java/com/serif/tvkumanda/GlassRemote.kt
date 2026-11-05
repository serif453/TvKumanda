package com.serif.tvkumanda

import android.widget.Toast
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sign
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.DesktopAccessDisabled
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.Gamepad
import androidx.compose.material.icons.outlined.Swipe
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
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

/**
 * controlSwipe null değilse (yalnızca Kumanda sekmesi) TV adının sağındaki ⌄ ayrı bir yuvarlak tuştur ve küçük bir menü
 * açar: Cihaz seçin · Bağlantıyı kes · Kaydırma kontrolü · D-pad kontrolü (Serif'in örneği: Google TV uygulaması).
 */
@Composable
fun GlassTopBar(
    ui: UiState, onSettings: () -> Unit, onPicker: () -> Unit, onPower: () -> Unit, showPower: Boolean = true,
    controlSwipe: Boolean? = null, onControl: (Boolean) -> Unit = {}, onDisconnect: () -> Unit = {}
) {
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
            Row(
                Modifier.weight(1f).height(54.dp).glass(pillShape, pressed, strength = 0.8f),
                verticalAlignment = Alignment.CenterVertically
            ) {
            Column(
                Modifier.weight(1f).fillMaxHeight()
                    .clickable(interactionSource = inter, indication = null, onClick = onPicker)
                    .padding(start = 12.dp, end = if (controlSwipe != null) 4.dp else 12.dp),
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
                    if (controlSwipe == null) {
                        Spacer(Modifier.width(4.dp))
                        Icon(Icons.Filled.KeyboardArrowDown, null, tint = Glass.Text, modifier = Modifier.size(22.dp))
                    }
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
            if (controlSwipe != null) ControlMenuButton(ui, controlSwipe, onPicker, onDisconnect, onControl)
            }
            // Güç: kırmızı cam (yalnızca Kumanda sekmesinde; diğerlerinde TV kutusu o alana uzanır)
            if (showPower) {
            Spacer(Modifier.width(12.dp))
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
}

@Composable
private fun ControlMenuButton(ui: UiState, swipe: Boolean, onPicker: () -> Unit, onDisconnect: () -> Unit, onControl: (Boolean) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val turn by animateFloatAsState(if (open) 180f else 0f, tween(200), label = "chev")
    Box(Modifier.padding(end = 6.dp)) {
        val inter = remember { MutableInteractionSource() }
        val pressed by inter.collectIsPressedAsState()
        Box(
            Modifier.size(42.dp).clip(CircleShape)
                .background(Color.White.copy(alpha = if (pressed || open) 0.16f else 0.08f))
                .clickable(interactionSource = inter, indication = null) { open = !open },
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Filled.KeyboardArrowDown, "Kumanda seçenekleri", tint = Glass.Text,
                modifier = Modifier.size(22.dp).graphicsLayer { rotationZ = turn })
        }
        DropdownMenu(
            expanded = open, onDismissRequest = { open = false },
            containerColor = GlassSheet.Menu, shape = RoundedCornerShape(16.dp),
            offset = androidx.compose.ui.unit.DpOffset(0.dp, 6.dp)
        ) {
            GlassText {
                MenuRow(Icons.Outlined.Devices, "Cihaz seçin") { open = false; onPicker() }
                MenuRow(Icons.Outlined.DesktopAccessDisabled, "Bağlantıyı kes", enabled = ui.state == ConnState.CONNECTED) {
                    open = false; onDisconnect()
                }
                HorizontalDivider(Modifier.padding(vertical = 4.dp, horizontal = 8.dp), color = Color.White.copy(alpha = 0.10f))
                MenuRow(Icons.Outlined.Swipe, "Kaydırma kontrolü", checked = swipe) { open = false; onControl(true) }
                MenuRow(Icons.Outlined.Gamepad, "D-pad kontrolü", checked = !swipe) { open = false; onControl(false) }
            }
        }
    }
}

@Composable
private fun MenuRow(icon: ImageVector, label: String, checked: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label, color = Glass.Text, fontSize = 15.sp) },
        leadingIcon = { Icon(icon, null, tint = Glass.Text, modifier = Modifier.size(22.dp)) },
        trailingIcon = { if (checked) Icon(Icons.Filled.Check, "Seçili", tint = Glass.Lavender, modifier = Modifier.size(20.dp)) else Spacer(Modifier.size(20.dp)) },
        enabled = enabled,
        onClick = onClick,
        modifier = Modifier.widthIn(min = 230.dp)
    )
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
private fun SideIcon(icon: ImageVector, label: String?, size: Dp = 26.dp, selected: Boolean = false) =
    Icon(icon, label, tint = if (selected) Color.White else Glass.Text, modifier = Modifier.size(size))

/** Mikrofon: koyu yuvarlak cam (Google TV kumandası gibi); dinlerken kırmızıya döner ve etrafında halkalar yayılır. */
@Composable
private fun GlassMic(size: Dp, active: Boolean, onClick: () -> Unit) {
    val level = LiveUi.micLevel     // yalnızca bu tuş yeniden çizilir
    val inter = remember { MutableInteractionSource() }
    val scale = pressScale(inter)
    val shape = CircleShape
    val pressed by inter.collectIsPressedAsState()
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
                .then(
                    if (active) Modifier.clip(shape).background(SolidBrush(Color(0xFF4A2327)))
                        .border(1.dp, Pal.Red.copy(alpha = 0.5f), shape)
                    else Modifier.glass(shape, pressed)
                )
                .pressable(inter, onClick = onClick),
            contentAlignment = Alignment.Center
        ) { Icon(AppIcons.Mic, "Sesli Ara", tint = tint, modifier = Modifier.size(size * 0.42f)) }
    }
}

private fun SolidBrush(c: Color) = Brush.verticalGradient(listOf(c, c))

// ---------------- Yön kartı ----------------

/**
 * Cam kart (5 Kasım 2026, Serif'in onayladığı önizleme): üstte ses çubuğu; altında solda 3 (Kaynak, ⋯ Daha fazla,
 * Klavye), sağda 3 (123, Fare, Medya) tuş, ortada yön dairesi ya da kaydırma yüzeyi; en altta Google TV kumandası gibi
 * yuvarlak Geri · geniş Ana Sayfa · yuvarlak Mikrofon. ⋯ = Bilgi, Rehber, fn (Sistem kısayolları), TV ayarları.
 * Fare modunda 6 yan tuş solar, yüzey kartın iki kenarına kadar genişler; sağ üstteki ✕ ile çıkılır.
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
    onMore: () -> Unit,
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
        val bottomKey = key + 14.dp                               // alt sıradaki yuvarlak tuşlar
        val fixedH = pad * 2 + pill + 14.dp + rowGap + bottomKey
        val circle = minOf(inner - col * 2 - 8.dp, maxHeight - fixedH).coerceIn(140.dp, 280.dp)
        val bodyH = maxOf(circle, key * 3 + 20.dp)
        val cardH = fixedH + bodyH
        GlassText {
            Column(
                Modifier.fillMaxWidth().height(cardH).glass(shape, strength = 0.55f).padding(pad)
            ) {
                GlassVolumePill(ui, onKey, Modifier.fillMaxWidth().height(pill))
                Spacer(Modifier.height(14.dp))
                // Fare modunda 6 yan tuş solar, yüzey kartın iki kenarına kadar genişler
                val wide = mode == 2
                val exp by animateFloatAsState(if (wide) 1f else 0f, tween(250), label = "padExpand")
                val wideW = inner - (col - key)                          // tuşların dış kenarlarıyla aynı hiza
                val sideAlpha = Modifier.graphicsLayer { alpha = 1f - exp }
                Box(Modifier.fillMaxWidth().height(bodyH)) {
                    Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.width(col).fillMaxHeight(), verticalArrangement = Arrangement.SpaceBetween, horizontalAlignment = Alignment.CenterHorizontally) {
                            GlassSquare(Modifier.size(key).then(sideAlpha), enabled = adb, onClick = {
                                if (adb) onKey(Keys.INPUT) else Toast.makeText(context, "Kaynak tuşu yalnızca ADB bağlantısında çalışır", Toast.LENGTH_SHORT).show()
                            }) { SideIcon(Icons.Outlined.Input, "Kaynak") }
                            GlassSquare(Modifier.size(key).then(sideAlpha), selected = true, onClick = onMore) { SideIcon(Icons.Filled.MoreHoriz, "Daha fazla", selected = true) }
                            GlassSquare(Modifier.size(key).then(sideAlpha), onClick = onKeyboard) { SideIcon(Icons.Filled.Keyboard, "Klavye") }
                        }
                        Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                            if (exp < 0.999f) {
                                Box(Modifier.size(circle).graphicsLayer { alpha = 1f - exp }) {
                                    if (mode == 1 || (mode == 2 && AppSettings.swipeControl)) SwipePad(Modifier.fillMaxSize(), onKey)
                                    else FlatDPad(circle, onKey)
                                }
                            }
                        }
                        Column(Modifier.width(col).fillMaxHeight(), verticalArrangement = Arrangement.SpaceBetween, horizontalAlignment = Alignment.CenterHorizontally) {
                            GlassSquare(Modifier.size(key).then(sideAlpha), onClick = onNumpad) { SideIcon(Icons.Filled.Dialpad, "123") }
                            GlassSquare(Modifier.size(key).then(sideAlpha), selected = mode == 2, enabled = adb, onClick = {
                                if (adb) onMode(if (mode == 2) 0 else 2) else Toast.makeText(context, "Fare yalnızca ADB bağlantısında çalışır", Toast.LENGTH_SHORT).show()
                            }) { SideIcon(Icons.Outlined.Mouse, "Fare", selected = mode == 2) }
                            GlassSquare(Modifier.size(key).then(sideAlpha), onClick = onMediaKeys) { SideIcon(Icons.Outlined.QueueMusic, "Medya tuşları") }
                        }
                    }
                    // Fare yüzeyi: yan tuşların üstüne biner (gizli tuşlara dokunulamaz). Çıkarken küçülüp kaybolur.
                    if (exp > 0.001f) {
                        val w = circle + (wideW - circle) * exp
                        val h = circle + (bodyH - circle) * exp
                        Box(Modifier.align(Alignment.Center).size(w, h)) {
                            TrackPad(Modifier.fillMaxSize(), ui.fastMode, ui.method, onMouse) { if (wide) onMode(0) }
                            // Sol üst: "Fare ▾" (hız ve şerit ayarı) ya da düz "Fare" etiketi
                            if (ui.method != ConnMethod.STANDARD && ui.fastMode) {
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
                                // Sağ üst: ✕ ile normal kumandaya dönülür (çalışan fare modunda ✕ kaydırma şeridinin tepesinde)
                                Box(
                                    Modifier.align(Alignment.TopEnd).padding(top = 10.dp, end = 10.dp)
                                        .graphicsLayer { alpha = exp }
                                        .size(36.dp).clip(CircleShape)
                                        .background(Color.Black.copy(alpha = 0.28f))
                                        .clickable(enabled = wide) { onMode(0) },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(Icons.Filled.Close, "Moddan çık", tint = Glass.Text, modifier = Modifier.size(20.dp))
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(rowGap))
                // Alt sıra (Serif'in örneği: Google TV kumandası): yuvarlak Geri · geniş Ana Sayfa · yuvarlak Mikrofon
                Row(Modifier.fillMaxWidth().height(bottomKey), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        GlassSquare(Modifier.size(bottomKey), CircleShapeR, onClick = { onKey(Keys.BACK) }) {
                            SideIcon(Icons.AutoMirrored.Filled.ArrowBack, "Geri", 26.dp)
                        }
                    }
                    GlassSquare(Modifier.weight(1.6f).height(bottomKey), RoundedCornerShape(bottomKey / 2), onClick = { onKey(Keys.HOME) }) {
                        SideIcon(Icons.Filled.Home, "Ana Sayfa", 28.dp)
                    }
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        GlassMic(bottomKey, ui.micActive, onMic)
                    }
                }
            }
        }
    }
}

/** GlassSquare yuvarlak köşe ister; yarıçap %50 = daire. */
private val CircleShapeR = RoundedCornerShape(50)

// ---------------- Düz yön dairesi (Google TV kumandası gibi) ----------------

private val DPadRing = Color(0xFF1D1D21)
private val DPadEdge = Color(0xFF0E0E10)
private val DPadCenter = Color(0xFF444648)
private val DPadCenterPressed = Color(0xFFECECEF)
private val DPadArrow = Color(0xFF8A8A92)

/**
 * Tek parça daire: basılan yönün dilimi açık renkte parlar, ortaya basınca gri düğme beyazlaşır (Serif'in örneği).
 * Yön tuşları basılı tutulunca tekrarlar.
 */
@Composable
internal fun FlatDPad(size: Dp, onKey: (Int) -> Unit) {
    val haptic = LocalHapticFeedback.current
    val latest by rememberUpdatedState(onKey)
    var zone by remember { mutableStateOf<Dir?>(null) }
    var center by remember { mutableStateOf(false) }
    val glow by animateFloatAsState(if (zone != null) 1f else 0f, tween(if (zone != null) 40 else 180), label = "wedge")
    var shownZone by remember { mutableStateOf(Dir.UP) }
    zone?.let { shownZone = it }
    val centerColor by animateColorAsState(if (center) DPadCenterPressed else DPadCenter, tween(if (center) 40 else 160), label = "ok")
    Box(
        Modifier.size(size).clip(CircleShape).background(DPadRing).border(2.dp, DPadEdge, CircleShape)
            .drawBehind {
                if (glow > 0f) {
                    val start = when (shownZone) { Dir.RIGHT -> -45f; Dir.DOWN -> 45f; Dir.LEFT -> 135f; Dir.UP -> 225f }
                    drawArc(Color.White.copy(alpha = 0.13f * glow), start, 90f, useCenter = true)
                }
            }
            .pointerInput(Unit) {
                coroutineScope {
                    detectTapGestures(onPress = { o ->
                        val cx = this@pointerInput.size.width / 2f
                        val cy = this@pointerInput.size.height / 2f
                        val dx = o.x - cx
                        val dy = o.y - cy
                        val r = kotlin.math.hypot(dx, dy)
                        if (r < this@pointerInput.size.width * 0.2f) {
                            center = true
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            latest(Keys.OK)
                            tryAwaitRelease()
                            center = false
                            return@detectTapGestures
                        }
                        val d = if (kotlin.math.abs(dx) > kotlin.math.abs(dy)) (if (dx > 0) Dir.RIGHT else Dir.LEFT)
                                else (if (dy > 0) Dir.DOWN else Dir.UP)
                        val code = when (d) { Dir.UP -> Keys.UP; Dir.DOWN -> Keys.DOWN; Dir.LEFT -> Keys.LEFT; Dir.RIGHT -> Keys.RIGHT }
                        zone = d
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        latest(code)
                        val job = launch {
                            delay(380)
                            while (isActive) {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                latest(code)
                                delay(90)
                            }
                        }
                        tryAwaitRelease()
                        job.cancel()
                        zone = null
                    })
                }
            },
        contentAlignment = Alignment.Center
    ) {
        val arrow = size * 0.11f
        val edge = size * 0.09f
        Chevron(Dir.UP, DPadArrow, Modifier.align(Alignment.TopCenter).padding(top = edge).size(arrow))
        Chevron(Dir.DOWN, DPadArrow, Modifier.align(Alignment.BottomCenter).padding(bottom = edge).size(arrow))
        Chevron(Dir.LEFT, DPadArrow, Modifier.align(Alignment.CenterStart).padding(start = edge).size(arrow))
        Chevron(Dir.RIGHT, DPadArrow, Modifier.align(Alignment.CenterEnd).padding(end = edge).size(arrow))
        Box(Modifier.fillMaxSize(0.39f).clip(CircleShape).background(centerColor).border(2.dp, DPadEdge, CircleShape))
    }
}

// ---------------- Kaydırma yüzeyi ----------------

/**
 * Kaydırma kontrolü (Serif'in örneği: Google TV uygulaması): ortada bir nokta parmağı takip eder; her ~48 dp'lik
 * kaydırma bir yön tuşu, kısa dokunma OK. Yön gidince o kenar hafifçe parlar, dokununca halka yayılır.
 * Bırakınca nokta ortaya döner.
 */
@Composable
internal fun SwipePad(modifier: Modifier, onKey: (Int) -> Unit) {
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val step = with(density) { 48.dp.toPx() }
    val slop = with(density) { 8.dp.toPx() }
    val latest by rememberUpdatedState(onKey)
    val scope = rememberCoroutineScope()
    var active by remember { mutableStateOf(false) }
    var knob by remember { mutableStateOf(Offset.Unspecified) }
    var home by remember { mutableStateOf(Offset.Unspecified) }
    val back = remember { Animatable(1f) }                 // bırakınca noktanın ortaya dönüşü (0 → 1)
    var from by remember { mutableStateOf(Offset.Zero) }
    var flashDir by remember { mutableStateOf(Dir.UP) }
    val flash = remember { Animatable(0f) }
    var rippleAt by remember { mutableStateOf(Offset.Zero) }
    val ripple = remember { Animatable(1f) }
    val shape = RoundedCornerShape(30.dp)
    val knobR = with(density) { 23.dp.toPx() }
    val bg by animateColorAsState(if (active) Color(0xFF212127) else Color(0xFF1A1A1F), tween(150), label = "padBg")
    val knobColor by animateColorAsState(if (active) Glass.Lavender else Color(0xFF3C3C46), tween(150), label = "knob")
    Box(
        modifier.clip(shape).background(bg).border(1.dp, Color.White.copy(alpha = 0.08f), shape)
            .onSizeChanged { home = Offset(it.width / 2f, it.height * 0.42f) }
            .drawBehind {
                // Yön gidince o kenardan içeri doğru hafif parlama
                if (flash.value > 0f) {
                    val c = Glass.Lavender.copy(alpha = 0.30f * flash.value)
                    val t = Color.Transparent
                    val b = when (flashDir) {
                        Dir.UP -> Brush.verticalGradient(listOf(c, t), 0f, size.height * 0.45f)
                        Dir.DOWN -> Brush.verticalGradient(listOf(t, c), size.height * 0.55f, size.height)
                        Dir.LEFT -> Brush.horizontalGradient(listOf(c, t), 0f, size.width * 0.45f)
                        Dir.RIGHT -> Brush.horizontalGradient(listOf(t, c), size.width * 0.55f, size.width)
                    }
                    drawRect(b)
                }
                // Dokununca yayılan halka
                if (ripple.value < 1f) {
                    drawCircle(Glass.Lavender.copy(alpha = 1f - ripple.value), radius = knobR * (0.5f + 4f * ripple.value),
                        center = rippleAt, style = Stroke(2.dp.toPx()))
                }
                // Nokta: basılıyken parmağın altında, bırakınca ortaya kayar
                val h = if (home.isSpecified) home else center
                val p = if (active && knob.isSpecified) knob else Offset(from.x + (h.x - from.x) * back.value, from.y + (h.y - from.y) * back.value)
                drawCircle(knobColor, radius = if (active) knobR * 0.8f else knobR, center = p)
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    active = true
                    knob = down.position
                    var acc = Offset.Zero
                    var travelled = 0f
                    while (true) {
                        val ev = awaitPointerEvent()
                        val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                        if (!ch.pressed) {
                            if (travelled < slop) {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                latest(Keys.OK)
                                rippleAt = ch.position
                                scope.launch { ripple.snapTo(0f); ripple.animateTo(1f, tween(450)) }
                            }
                            break
                        }
                        val d = ch.positionChange()
                        ch.consume()
                        travelled += d.getDistance()
                        acc += d
                        knob = Offset(ch.position.x.coerceIn(0f, size.width.toFloat()), ch.position.y.coerceIn(0f, size.height.toFloat()))
                        while (abs(acc.x) >= step || abs(acc.y) >= step) {
                            val dir: Dir
                            if (abs(acc.x) > abs(acc.y)) {
                                dir = if (acc.x > 0) Dir.RIGHT else Dir.LEFT
                                acc = Offset(acc.x - step * sign(acc.x), 0f)
                            } else {
                                dir = if (acc.y > 0) Dir.DOWN else Dir.UP
                                acc = Offset(0f, acc.y - step * sign(acc.y))
                            }
                            latest(when (dir) { Dir.UP -> Keys.UP; Dir.DOWN -> Keys.DOWN; Dir.LEFT -> Keys.LEFT; Dir.RIGHT -> Keys.RIGHT })
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            flashDir = dir
                            scope.launch { flash.snapTo(1f); flash.animateTo(0f, tween(300)) }
                        }
                    }
                    from = knob
                    active = false
                    scope.launch { back.snapTo(0f); back.animateTo(1f, tween(250)) }
                }
            }
    ) {
        Column(
            Modifier.align(Alignment.BottomCenter).padding(bottom = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            HintLine(Icons.Outlined.Swipe, "Gezinmek için kaydırın")
            HintLine(Icons.Outlined.TouchApp, "Seçmek için dokunun")
        }
    }
}

@Composable
private fun HintLine(icon: ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = Glass.Sub, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, color = Glass.Sub, fontSize = 12.sp, lineHeight = 14.sp, maxLines = 1)
    }
}

// ---------------- Daha fazla ----------------

/** ⋯ tuşunun açtığı pencere: Bilgi, Rehber, fn (Sistem kısayolları), TV ayarları. */
@Composable
internal fun MoreKeysSheet(onKey: (Int) -> Unit, onSystemKeys: () -> Unit, onTvSettings: () -> Unit) {
    GlassText {
        Column {
            Text("Daha fazla", color = Glass.Text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MoreTile(Modifier.weight(1f), "Bilgi", { onKey(KEY_INFO) }) { SideIcon(Icons.Outlined.Info, null) }
                MoreTile(Modifier.weight(1f), "Rehber", { onKey(KEY_GUIDE) }) { SideIcon(GlassIcons.Guide, null, 28.dp) }
                MoreTile(Modifier.weight(1f), "Fn tuşları", onSystemKeys) {
                    Text("fn", color = Glass.Text, fontSize = 22.sp, lineHeight = 26.sp, fontWeight = FontWeight.Medium)
                }
                MoreTile(Modifier.weight(1f), "TV ayarları", onTvSettings) { SideIcon(Icons.Outlined.Settings, null) }
            }
        }
    }
}

@Composable
private fun MoreTile(modifier: Modifier, label: String, onClick: () -> Unit, icon: @Composable () -> Unit) {
    GlassSquare(modifier.height(92.dp), RoundedCornerShape(18.dp), onClick = onClick) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.height(28.dp), contentAlignment = Alignment.Center) { icon() }
            Spacer(Modifier.height(8.dp))
            Text(label, color = Glass.Text, fontSize = 12.sp, lineHeight = 14.sp, maxLines = 1, textAlign = TextAlign.Center)
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
internal fun GlassVolumePill(ui: UiState, onKey: (Int) -> Unit, modifier: Modifier) {
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

/** Önceki · geri sar · oynat/duraklat · ileri sar · sonraki; "Şimdi oynatılıyor" sayfasında kullanılır. */
@Composable
internal fun MediaKeysRow(modifier: Modifier, onKey: (Int) -> Unit) {
    Row(
        modifier.padding(horizontal = 10.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
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
