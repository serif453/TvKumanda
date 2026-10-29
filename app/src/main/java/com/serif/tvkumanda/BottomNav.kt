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
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private class NavEntry(val tab: Tab, val icon: ImageVector, val label: String)

/**
 * Alt menü: Kumanda / Cast / Araçlar / Kısayollar.
 *
 * Seçili sekmenin arkasında yarı saydam, parlak kenarlı bir "cam" kapsül durur.
 * Dokununca kapsül o sekmeye kayar; parmak bar üzerinde sürüklenirse kapsül parmağı
 * takip eder ve bırakılan sekme açılır.
 */
@Composable
fun BottomNav(tab: Tab, onTab: (Tab) -> Unit) {
    val entries = remember {
        listOf(
            NavEntry(Tab.REMOTE, AppIcons.Tv, "Kumanda"),
            NavEntry(Tab.CAST, AppIcons.Cast, "Cast"),
            NavEntry(Tab.TOOLS, Icons.Outlined.Build, "Araçlar"),
            NavEntry(Tab.SHORTCUTS, Icons.Outlined.Bolt, "Kısayollar")
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

    Box(
        Modifier
            .fillMaxWidth()
            .height(64.dp)
            .padding(top = 6.dp)
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
            val inset = with(density) { 6.dp.toPx() }
            val pillWidth = with(density) { (slot - 2 * inset).toDp() }
            Box(
                Modifier
                    .offset { IntOffset((pillX.value + inset).roundToInt(), 0) }
                    .width(pillWidth)
                    .fillMaxHeight()
                    .graphicsLayer { scaleX = if (dragging) 1.06f else 1f; scaleY = if (dragging) 1.06f else 1f }
                    .background(
                        Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.16f), Color.White.copy(alpha = 0.05f))),
                        RoundedCornerShape(22.dp)
                    )
                    .border(
                        1.dp,
                        Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.45f), Color.White.copy(alpha = 0.08f))),
                        RoundedCornerShape(22.dp)
                    )
            )
        }
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            entries.forEachIndexed { i, e ->
                val lit = i == litIndex
                Column(
                    Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(e.icon, e.label, tint = if (lit) Pal.Text else Pal.Sub, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.height(2.dp))
                    Text(e.label, color = if (lit) Pal.Text else Pal.Sub, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                }
            }
        }
    }
}
