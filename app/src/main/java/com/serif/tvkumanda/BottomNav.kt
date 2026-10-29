package com.serif.tvkumanda

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Icon
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.PathParser
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeChild
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private class NavEntry(val tab: Tab, val icon: ImageVector, val selectedIcon: ImageVector, val label: String)

/** Alt menünün kapladığı yükseklik (kapsül + alt boşluk + nefes payı); içerikler en alta bu kadar boşluk bırakır. */
val BarSpace = 86.dp

/** Alt menünün arkasından akan listelerin en altta bırakacağı boşluk. */
val LocalListBottom = compositionLocalOf { 0.dp }

/** Seçili Kumanda simgesi: ekranı dolu televizyon. */
private val TvFilled: ImageVector by lazy {
    ImageVector.Builder("TvFilled", 24.dp, 24.dp, 24f, 24f).apply {
        addPath(
            PathParser().parsePathString(
                "M21,3H3c-1.1,0 -2,0.9 -2,2v12c0,1.1 0.9,2 2,2h5v2h8v-2h5c1.1,0 1.99,-0.9 1.99,-2L23,5c0,-1.1 -0.9,-2 -2,-2z"
            ).toNodes(),
            fill = SolidColor(Color.White)
        )
    }.build()
}

/**
 * Alt menü: Kumanda / Cast / Araçlar / Kısayollar.
 *
 * Seçili sekmenin arkasında yarı saydam, parlak kenarlı bir "cam" kapsül durur.
 * Dokununca kapsül o sekmeye kayar; parmak bar üzerinde sürüklenirse kapsül parmağı
 * takip eder ve bırakılan sekme açılır.
 */
@Composable
fun BottomNav(tab: Tab, hazeState: HazeState, modifier: Modifier = Modifier, onTab: (Tab) -> Unit) {
    val entries = remember {
        listOf(
            NavEntry(Tab.REMOTE, AppIcons.Tv, TvFilled, "Kumanda"),
            NavEntry(Tab.CAST, AppIcons.Cast, Icons.Filled.CastConnected, "Cast"),
            NavEntry(Tab.TOOLS, Icons.Outlined.Build, Icons.Filled.Build, "Araçlar"),
            NavEntry(Tab.SHORTCUTS, Icons.Outlined.Bolt, Icons.Filled.Bolt, "Kısayollar")
        )
    }
    val count = entries.size
    val selectedIndex = entries.indexOfFirst { it.tab == tab }.coerceAtLeast(0)
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current

    var widthPx by remember { mutableFloatStateOf(0f) }
    val slot = if (widthPx > 0) widthPx / count else 0f
    // Kapsülün sol kenarı (piksel); sürüklerken parmağı, bırakınca yay animasyonuyla sekmeyi izler
    val pillX = remember { Animatable(0f) }
    var dragging by remember { mutableStateOf(false) }
    // Kapsülün altındaki sekme (sürüklerken yazı ve simge buna göre parlar)
    val hoverIndex = if (slot > 0) ((pillX.value + slot / 2) / slot).toInt().coerceIn(0, count - 1) else selectedIndex
    val litIndex = if (dragging) hoverIndex else selectedIndex

    val springSpec = spring<Float>(dampingRatio = 0.78f, stiffness = Spring.StiffnessMediumLow)

    // Sekme dışarıdan değişirse (ör. paylaşımdan Cast'e geçiş) kapsül oraya kayar
    var placed by remember { mutableStateOf(false) }
    LaunchedEffect(selectedIndex, slot) {
        if (slot > 0 && !dragging) {
            // İlk açılışta kaymadan yerine konur
            if (!placed) { pillX.snapTo(selectedIndex * slot); placed = true }
            else pillX.animateTo(selectedIndex * slot, springSpec)
        }
    }
    // Sürüklerken her yeni sekmenin üstünden geçişte hafif titreşim
    LaunchedEffect(hoverIndex, dragging) {
        if (dragging) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    }

    fun settle(index: Int) {
        val i = index.coerceIn(0, count - 1)
        scope.launch { pillX.animateTo(i * slot, springSpec) }
        if (entries[i].tab != tab) onTab(entries[i].tab)
    }

    val barShape = RoundedCornerShape(32.dp)
    // Yüzen cam bar: arkasındaki içerik bulanıklaşıp hafifçe içeri sızar
    Box(
        modifier
            .fillMaxWidth()
            .height(68.dp)
            .shadow(10.dp, barShape, ambientColor = Color.Black, spotColor = Color.Black)
            .clip(barShape)
            .hazeChild(
                hazeState,
                HazeStyle(
                    backgroundColor = Pal.Bg,
                    tint = HazeTint(Color(0xFF2C2C30).copy(alpha = 0.62f)),
                    blurRadius = 22.dp,
                    noiseFactor = 0.04f
                )
            )
            .border(
                1.dp,
                Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.22f), Color.White.copy(alpha = 0.05f))),
                barShape
            )
            .padding(5.dp)
    ) {
    Box(
        Modifier
            .fillMaxSize()
            .onSizeChanged { widthPx = it.width.toFloat() }
            .pointerInput(slot, selectedIndex) {
                detectTapGestures { pos -> if (slot > 0) settle((pos.x / slot).toInt()) }
            }
            .pointerInput(slot, selectedIndex) {
                detectHorizontalDragGestures(
                    onDragStart = { pos ->
                        if (slot > 0) {
                            dragging = true
                            scope.launch { pillX.snapTo((pos.x - slot / 2).coerceIn(0f, slot * (count - 1))) }
                        }
                    },
                    onHorizontalDrag = { change, dx ->
                        change.consume()
                        scope.launch { pillX.snapTo((pillX.value + dx).coerceIn(0f, slot * (count - 1))) }
                    },
                    onDragEnd = {
                        val target = ((pillX.value + slot / 2) / slot).toInt()
                        dragging = false
                        settle(target)
                    },
                    onDragCancel = { dragging = false; settle(selectedIndex) }
                )
            }
    ) {
        // Cam kapsül
        if (slot > 0) {
            val inset = with(density) { 1.dp.toPx() }
            val pillWidth = with(density) { (slot - 2 * inset).toDp() }
            Box(
                Modifier
                    .offset { IntOffset((pillX.value + inset).roundToInt(), 0) }
                    .width(pillWidth)
                    .fillMaxHeight()
                    .graphicsLayer { scaleX = if (dragging) 1.06f else 1f; scaleY = if (dragging) 1.06f else 1f }
                    .background(
                        Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.20f), Color.White.copy(alpha = 0.07f))),
                        RoundedCornerShape(27.dp)
                    )
                    .border(
                        1.dp,
                        Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.50f), Color.White.copy(alpha = 0.10f))),
                        RoundedCornerShape(27.dp)
                    )
            )
        }
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            entries.forEachIndexed { i, e ->
                val lit = i == litIndex
                Column(
                    // Göz, altta yazı olduğu için tam ortadaki ikiliyi biraz yukarıda algılar; 1 dp aşağı alınır
                    Modifier.weight(1f).offset(y = 1.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(if (lit) e.selectedIcon else e.icon, e.label, tint = if (lit) Pal.Text else Pal.Sub, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.height(2.dp))
                    Text(e.label, color = if (lit) Pal.Text else Pal.Sub, fontSize = 11.sp, fontWeight = FontWeight.Medium, maxLines = 1,
                        // Yazı tipinin üst-alt fazladan boşluğu kırpılır, ikili kapsülde tam ortalanır
                        style = TextStyle(lineHeight = 13.sp, lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both)))
                }
            }
        }
    }
    }
}
