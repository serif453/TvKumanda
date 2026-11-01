package com.serif.tvkumanda

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Mouse
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Fare modunun ayarları: imleç hızı (1–5, 3 = eski davranış) ve kaydırma şeridinin hangi kenarda durduğu.
 * Telefonda "ayarlar" tercihlerinde saklanır, uygulama kapanıp açılınca hatırlanır.
 */
object MouseSettings {
    var speed by mutableIntStateOf(3)
        private set
    var stripLeft by mutableStateOf(false)
        private set
    private var loaded = false

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences("ayarlar", Context.MODE_PRIVATE)

    fun load(c: Context) {
        if (loaded) return
        loaded = true
        val p = prefs(c)
        speed = p.getInt("fare_hiz", 3).coerceIn(1, 5)
        stripLeft = p.getBoolean("fare_serit_sol", false)
    }

    fun updateSpeed(c: Context, v: Int) {
        speed = v.coerceIn(1, 5)
        prefs(c).edit().putInt("fare_hiz", speed).apply()
    }

    fun updateStripLeft(c: Context, left: Boolean) {
        stripLeft = left
        prefs(c).edit().putBoolean("fare_serit_sol", left).apply()
    }

    /** Hız kademesine göre imleç çarpanı (3 = 1.0, eski hız). */
    fun gain(): Float = when (speed) {
        1 -> 0.5f
        2 -> 0.75f
        4 -> 1.4f
        5 -> 1.9f
        else -> 1f
    }

    val speedNames = listOf("Çok yavaş", "Yavaş", "Normal", "Hızlı", "Çok hızlı")
}

/**
 * Sol üstteki "Fare ▾" etiketi: dokununca küçük ayar penceresi açılır (imleç hızı, şerit sol/sağ).
 */
@Composable
fun MouseModeLabel(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { MouseSettings.load(context) }
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        Row(
            Modifier.clip(RoundedCornerShape(16.dp))
                .background(Color.White.copy(alpha = 0.08f))
                .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(16.dp))
                .clickable { open = true }.padding(start = 10.dp, end = 6.dp, top = 5.dp, bottom = 5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Outlined.Mouse, null, tint = Glass.Text.copy(alpha = 0.85f), modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(5.dp))
            Text("Fare", color = Glass.Text.copy(alpha = 0.85f), fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Icon(Icons.Filled.ArrowDropDown, "Fare ayarları", tint = Glass.Text.copy(alpha = 0.85f), modifier = Modifier.size(18.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = Navy.Btn) {
            Column(Modifier.width(260.dp).padding(horizontal = 16.dp, vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("İmleç hızı", color = Glass.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Text(MouseSettings.speedNames[MouseSettings.speed - 1], color = Glass.Lavender, fontSize = 13.sp)
                }
                Slider(
                    value = MouseSettings.speed.toFloat(),
                    onValueChange = { MouseSettings.updateSpeed(context, Math.round(it)) },
                    valueRange = 1f..5f,
                    steps = 3,
                    colors = SliderDefaults.colors(
                        thumbColor = Color.White,
                        activeTrackColor = Glass.Purple,
                        inactiveTrackColor = Color.White.copy(alpha = 0.15f),
                        activeTickColor = Color.White.copy(alpha = 0.5f),
                        inactiveTickColor = Color.White.copy(alpha = 0.3f)
                    )
                )
                Row {
                    Text("Yavaş", color = Glass.Sub, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    Text("Hızlı", color = Glass.Sub, fontSize = 12.sp)
                }
                Spacer(Modifier.height(14.dp))
                Text("Kaydırma şeridi", color = Glass.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SideChip("Sol", MouseSettings.stripLeft, Modifier.weight(1f)) { MouseSettings.updateStripLeft(context, true) }
                    SideChip("Sağ", !MouseSettings.stripLeft, Modifier.weight(1f)) { MouseSettings.updateStripLeft(context, false) }
                }
                Spacer(Modifier.height(6.dp))
            }
        }
    }
}

@Composable
private fun SideChip(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier.height(38.dp).clip(shape)
            .background(if (selected) Glass.Purple.copy(alpha = 0.35f) else Color.White.copy(alpha = 0.06f))
            .border(1.dp, if (selected) Glass.Lavender else Color.White.copy(alpha = 0.12f), shape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = if (selected) Glass.Text else Glass.Sub, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

/**
 * Fare yüzeyinin kenarındaki kaydırma şeridi (Serif'in işaretlediği örnek gibi): en üstte ✕ (normal kumandaya döner),
 * altında ▲, eşit aralıklı çizgili sürükleme alanı ve ▼. ▲ / ▼ bir adım kaydırır (basılı tutunca tekrarlar),
 * çizgili alanda parmak yukarı-aşağı sürüklenince sayfa kayar. Kaydırma yönü iki parmakla kaydırmayla aynıdır.
 * Şeridin hangi kenarda durduğu "Fare ▾" penceresinden seçilir.
 */
@Composable
fun ScrollStrip(modifier: Modifier, onMouse: (String) -> Unit, onClose: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    val latest by rememberUpdatedState(onMouse)
    val step = with(LocalDensity.current) { 22.dp.toPx() }
    // Çizgiler parmakla birlikte kayar; dokunulduğu belli olsun
    var shift by remember { mutableFloatStateOf(0f) }
    val shape = RoundedCornerShape(24.dp)

    Column(
        modifier.clip(shape).background(Color(0xFF16172A).copy(alpha = 0.85f)),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier.padding(top = 6.dp).size(36.dp).clip(CircleShape)
                .background(Color.White.copy(alpha = 0.07f))
                .clickable(onClick = onClose),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Filled.Close, "Moddan çık", tint = Glass.Text, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.height(4.dp))
        StripArrow(up = true) { latest("w 1"); haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
        Box(
            Modifier.weight(1f).fillMaxWidth().pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    var acc = 0f
                    while (true) {
                        val ev = awaitPointerEvent()
                        val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                        if (!ch.pressed) break
                        val dy = ch.positionChange().y
                        ch.consume()
                        acc += dy
                        shift += dy
                        val notches = (acc / step).toInt()
                        if (notches != 0) {
                            latest("w $notches")
                            acc -= notches * step
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        }
                    }
                }
            }
        ) {
            // Eşit aralıklı çizgiler; sayısı şeridin boyuna göre kendiliğinden ayarlanır
            Canvas(Modifier.fillMaxSize().padding(vertical = 4.dp)) {
                val gap = 26.dp.toPx()
                val w = 13.dp.toPx()
                val cx = size.width / 2
                val count = (size.height / gap).toInt().coerceAtLeast(1)
                val space = size.height / count
                val off = ((shift % space) + space) % space
                for (i in 0..count) {
                    val y = space / 2 + i * space + off - space
                    if (y < 0f || y > size.height) continue
                    drawLine(
                        Color.White.copy(alpha = 0.28f),
                        Offset(cx - w, y), Offset(cx + w, y),
                        strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round
                    )
                }
            }
        }
        StripArrow(up = false) { latest("w -1"); haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
        Spacer(Modifier.height(4.dp))
    }
}

/** ▲ / ▼: dokununca bir adım, basılı tutunca tekrarlar. */
@Composable
private fun StripArrow(up: Boolean, onStep: () -> Unit) {
    val scope = rememberCoroutineScope()
    val latest by rememberUpdatedState(onStep)
    Box(
        Modifier.fillMaxWidth().height(44.dp).pointerInput(Unit) {
            detectTapGestures(onPress = {
                latest()
                val job = scope.launch {
                    delay(400)
                    while (true) { latest(); delay(110) }
                }
                tryAwaitRelease()
                job.cancel()
            })
        },
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.size(20.dp, 13.dp)) {
            val p = Path().apply {
                if (up) {
                    moveTo(size.width / 2, 0f); lineTo(size.width, size.height); lineTo(0f, size.height)
                } else {
                    moveTo(0f, 0f); lineTo(size.width, 0f); lineTo(size.width / 2, size.height)
                }
                close()
            }
            drawPath(p, Color(0xFFD6D6E6))
        }
    }
}
