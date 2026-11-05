package com.serif.tvkumanda

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ScreenRotation
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Air Mouse (5 Kasım 2026, Serif'in isteği): telefonu havada oynatarak TV'deki imleci yönetmek.
 * Fare moduyla aynı sanal fareyi kullanır (ADB hızlı mod, `KeyServer` "m / c / d / u / w").
 * Büyük alana basılı tutulurken jiroskop imleci yürütür, bırakınca imleç durur; kısa dokunma = tık.
 * Altta "Tıkla" (basılı tutup telefonu oynatınca sürükler), yanda fare modunun kaydırma şeridi.
 * Sensör yalnızca basılıyken açıktır (pil yemesin); mod açıkken telefon ekranı kararmaz.
 */
@Composable
fun AirMousePad(modifier: Modifier, fastMode: Boolean, method: ConnMethod, onMouse: (String) -> Unit, onClose: () -> Unit) {
    if (method == ConnMethod.STANDARD) {
        PadSurface(modifier, listOf("Air Mouse", "ADB bağlantısında çalışır"), Modifier)
        return
    }
    if (!fastMode) {
        PadSurface(modifier, listOf("Air Mouse için", "⚡ Hızlı mod gerekli", "Bağlantıyı yenilemeyi dene"), Modifier)
        return
    }
    val context = LocalContext.current
    val sm = remember { context.getSystemService(Context.SENSOR_SERVICE) as SensorManager }
    val gyro = remember { sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE) }
    if (gyro == null) {
        PadSurface(modifier, listOf("Bu telefonda", "jiroskop sensörü yok", "Fare kontrolünü kullanabilirsin"), Modifier)
        return
    }
    val gravitySensor = remember { sm.getDefaultSensor(Sensor.TYPE_GRAVITY) }
    val haptic = LocalHapticFeedback.current
    val latest by rememberUpdatedState(onMouse)
    LaunchedEffect(Unit) { latest("M"); MouseSettings.load(context) }

    // Mod açıkken ekran kararmasın
    val view = LocalView.current
    DisposableEffect(Unit) {
        val before = view.keepScreenOn
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = before }
    }

    var holding by remember { mutableStateOf(false) }
    var buttonDown by remember { mutableStateOf(false) }

    // Sensör yalnızca basılıyken dinlenir
    DisposableEffect(holding) {
        if (!holding) return@DisposableEffect onDispose { }
        val motion = AirMotion()
        val grav = floatArrayOf(0f, 0f, 1f)
        var last = 0L
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                if (e.sensor.type == Sensor.TYPE_GRAVITY) {
                    grav[0] = e.values[0]; grav[1] = e.values[1]; grav[2] = e.values[2]
                    return
                }
                val dt = if (last == 0L) 0f else (e.timestamp - last) / 1e9f
                last = e.timestamp
                val (dx, dy) = motion.step(
                    e.values[0], e.values[1], e.values[2],
                    grav[0], grav[1], grav[2], dt, MouseSettings.gain()
                )
                if (dx != 0 || dy != 0) latest("m $dx $dy")
            }
            override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        }
        gravitySensor?.let { sm.registerListener(listener, it, SensorManager.SENSOR_DELAY_GAME) }
        sm.registerListener(listener, gyro, SensorManager.SENSOR_DELAY_GAME)
        onDispose { sm.unregisterListener(listener) }
    }
    // Mod kapanırken basılı kalan tuş TV'de takılı kalmasın
    DisposableEffect(Unit) { onDispose { if (buttonDown) latest("u") } }

    val glow by animateFloatAsState(if (holding) 1f else 0f, tween(if (holding) 90 else 260), label = "air")
    val shape = RoundedCornerShape(36.dp)
    val left = MouseSettings.stripLeft
    BoxWithConstraints(modifier.glass(shape, strength = 0.75f).clip(shape)) {
        // Dar alanda (küçük ekran, uzun üst kısım) Tıkla alçalır, boşluklar azalır; hareket alanı büyük kalsın
        val tight = maxHeight < 260.dp
        Column(
            Modifier.fillMaxSize()
                .padding(
                    start = if (left) 72.dp else 12.dp, end = if (left) 12.dp else 72.dp,
                    top = if (tight) 48.dp else 52.dp, bottom = if (tight) 10.dp else 12.dp
                ),
            verticalArrangement = Arrangement.spacedBy(if (tight) 8.dp else 10.dp)
        ) {
            // Basılı tut ve telefonu oynat
            val holdShape = RoundedCornerShape(26.dp)
            Box(
                Modifier.weight(1f).fillMaxWidth().clip(holdShape)
                    .background(Glass.Blue.copy(alpha = 0.06f + 0.16f * glow))
                    .border(1.5.dp, Glass.Blue.copy(alpha = 0.18f + 0.6f * glow), holdShape)
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            down.consume()
                            val start = System.currentTimeMillis()
                            holding = true
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            try {
                                val up = waitForUpOrCancellation()
                                if (up != null && System.currentTimeMillis() - start < 220) {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    latest("c")
                                }
                            } finally {
                                holding = false
                            }
                        }
                    }
                    .described("Air Mouse hareket alanı"),
                contentAlignment = Alignment.Center
            ) {
                val iconTint = Glass.Text.copy(alpha = 0.55f + 0.45f * glow)
                val label = if (holding) "Telefonu oynat" else "Basılı tut, telefonu oynat"
                BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    if (maxHeight < 130.dp) {
                        // Dar: simge ve yazı yan yana tek satır, alt yazı gizli
                        Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.ScreenRotation, null, tint = iconTint, modifier = Modifier.size(26.dp))
                            Spacer(Modifier.width(10.dp))
                            Text(
                                label, color = Glass.Text, fontSize = 15.sp, fontWeight = FontWeight.Medium,
                                maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                        }
                    } else {
                        Column(Modifier.padding(horizontal = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Outlined.ScreenRotation, null, tint = iconTint, modifier = Modifier.size(46.dp))
                            Spacer(Modifier.height(10.dp))
                            Text(
                                label, color = Glass.Text, fontSize = 16.sp, fontWeight = FontWeight.Medium,
                                textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "Kısa dokunma = tıkla",
                                color = Glass.Text.copy(alpha = 0.55f), fontSize = 13.sp, textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            }
            // Tıkla: dokununca tık, basılı tutup alanla birlikte oynatınca sürükler
            val btnShape = RoundedCornerShape(22.dp)
            Box(
                Modifier.fillMaxWidth().height(if (tight) 46.dp else 58.dp).clip(btnShape)
                    .background(Color.White.copy(alpha = if (buttonDown) 0.18f else 0.08f))
                    .pointerInput(Unit) {
                        detectTapGestures(onPress = {
                            buttonDown = true
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            latest("d")
                            try { tryAwaitRelease() } finally {
                                latest("u")
                                buttonDown = false
                            }
                        })
                    }
                    .described("Tıkla"),
                contentAlignment = Alignment.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.TouchApp, null, tint = Glass.Text, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Tıkla", color = Glass.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        ScrollStrip(
            Modifier.align(if (left) Alignment.TopStart else Alignment.TopEnd)
                .padding(top = 10.dp, bottom = 10.dp, start = if (left) 10.dp else 0.dp, end = if (left) 0.dp else 10.dp)
                .width(48.dp).fillMaxHeight(),
            onMouse,
            onClose
        )
    }
}

private fun Modifier.described(label: String): Modifier = semantics { contentDescription = label }
