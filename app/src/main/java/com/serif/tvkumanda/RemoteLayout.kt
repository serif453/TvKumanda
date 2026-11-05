package com.serif.tvkumanda

import android.content.Context
import android.widget.Toast
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.zIndex
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Input
import androidx.compose.material.icons.outlined.Mouse
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ---------------- Üst şerit (tüm sekmeler; renkleri Kumanda'da lacivert) ----------------

@Composable
fun TopBarV2(
    ui: UiState, navy: Boolean, onSettings: () -> Unit, onPicker: () -> Unit, onPower: () -> Unit, showPower: Boolean = true,
    controlMode: Int? = null, onControl: (Int) -> Unit = {}, onDisconnect: () -> Unit = {}
) {
    if (navy) { GlassTopBar(ui, onSettings, onPicker, onPower, showPower, controlMode, onControl, onDisconnect); return }
    val btn = if (navy) Navy.Btn else Pal.Btn
    val border = if (navy) Navy.Border else Color.White.copy(alpha = 0.06f)
    Row(Modifier.fillMaxWidth().height(64.dp), verticalAlignment = Alignment.CenterVertically) {
        SquareButton(btn, border, onClick = onSettings) {
            Icon(Icons.Outlined.Settings, "Ayarlar", tint = Pal.Text, modifier = Modifier.size(24.dp))
        }
        // Ortada TV adı (dokununca TV seçici) ve bağlantı durumu
        Column(
            Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).clickable(onClick = onPicker).padding(vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    when (ui.state) {
                        ConnState.CONNECTED -> ui.tvName.ifBlank { "TV" }
                        ConnState.CONNECTING -> "Bağlanıyor…"
                        else -> "TV seç"
                    },
                    color = Pal.Text, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 210.dp)
                )
                Icon(Icons.Filled.KeyboardArrowDown, null, tint = if (navy) Navy.Sub else Pal.Sub, modifier = Modifier.size(22.dp))
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
                    color = if (navy) Navy.Sub else Pal.Sub, fontSize = 13.sp, fontWeight = FontWeight.Medium
                )
            }
        }
        val on = ui.state == ConnState.CONNECTED
        SquareButton(if (navy) Navy.PowerBg else Pal.Btn, if (navy) Pal.Red.copy(alpha = 0.25f) else border, onClick = onPower) {
            Icon(AppIcons.Power, "Güç", tint = if (on) (if (navy) Color(0xFFFF6B6B) else Pal.Green) else Pal.Red, modifier = Modifier.size(26.dp))
        }
    }
}

@Composable
private fun SquareButton(bg: Color, border: Color, onClick: () -> Unit, content: @Composable () -> Unit) {
    val inter = remember { MutableInteractionSource() }
    val scale = pressScale(inter)
    Box(
        Modifier.size(50.dp).graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(16.dp)).background(bg).border(1.dp, border, RoundedCornerShape(16.dp))
            .pressable(inter, onClick = onClick),
        contentAlignment = Alignment.Center
    ) { content() }
}

// ---------------- Uygulama satırı (küçük simgeler + Düzenle) ----------------

/** Uygulama satırında hangi uygulamaların, hangi sırayla duracağı (boşsa hepsi, varsayılan sırada). */
object AppRowPrefs {
    private fun prefs(c: Context) = c.getSharedPreferences("ayarlar", Context.MODE_PRIVATE)
    fun get(c: Context): List<String> = prefs(c).getString("uygulama_satiri", "").orEmpty().split(',').filter { it.isNotBlank() }
    fun set(c: Context, pkgs: List<String>) = prefs(c).edit().putString("uygulama_satiri", pkgs.joinToString(",")).apply()
}

fun orderedApps(all: List<TvApp>, pinned: List<String>): List<TvApp> {
    if (pinned.isEmpty()) return all
    val byPkg = all.associateBy { it.pkg }
    return pinned.mapNotNull { byPkg[it] }
}

@Composable
fun RemoteAppsRow(apps: List<TvApp>, state: ConnState, method: ConnMethod, tick: Int, onLaunch: (TvApp) -> Unit, onEdit: () -> Unit) {
    val context = LocalContext.current
    if (apps.isEmpty()) {
        GlassAppsEmpty(
            when {
                method == ConnMethod.STANDARD -> "Uygulama listesi ADB bağlantısında görünür"
                state == ConnState.CONNECTED -> "Uygulamalar yükleniyor…"
                else -> "Bağlanınca TV'deki uygulamalar burada görünür"
            }
        )
        return
    }
    val shown = remember(apps, tick) { orderedApps(apps, AppRowPrefs.get(context)) }
    LazyRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items(shown, key = { it.pkg }) { app -> SmallAppTile(app) { onLaunch(app) } }
        item(key = "_duzenle") {
            Column(Modifier.width(60.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier.size(52.dp).clip(RoundedCornerShape(14.dp))
                        .drawBehind {
                            drawRoundRect(
                                color = Navy.Sub.copy(alpha = 0.5f), cornerRadius = CornerRadius(14.dp.toPx()),
                                style = Stroke(width = 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)))
                            )
                        }
                        .clickable(onClick = onEdit),
                    contentAlignment = Alignment.Center
                ) { Icon(Icons.Outlined.Add, "Düzenle", tint = Navy.Sub, modifier = Modifier.size(24.dp)) }
                Spacer(Modifier.height(5.dp))
                Text("Düzenle", color = Navy.Sub, fontSize = 11.sp, maxLines = 1)
            }
        }
    }
}

/** Küçültülmüş uygulama kutucuğu: yükseklik 52 dp, genişlik simgenin oranına göre. */
@Composable
private fun SmallAppTile(app: TvApp, onClick: () -> Unit) {
    val inter = remember { MutableInteractionSource() }
    val scale = pressScale(inter)
    val bmp = app.icon
    val h = 52.dp
    val w = if (bmp != null && bmp.height > 0) (h * (bmp.width.toFloat() / bmp.height)).coerceIn(44.dp, 96.dp) else h
    Column(Modifier.width(maxOf(w, 60.dp)).graphicsLayer { scaleX = scale; scaleY = scale }, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(w, h).clip(RoundedCornerShape(12.dp))
                .then(if (bmp == null) Modifier.background(Navy.AccentSoft) else Modifier)
                .pressable(inter, deferMs = 500, onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            if (bmp != null) Image(bmp, app.name, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            else Text(app.name.take(2).uppercase(), color = Color(0xFFAFC0FF), fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(5.dp))
        Text(app.name, color = Pal.Text, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
    }
}

/** Uygulama satırını düzenleme: satırdakiler (basılı tutup sürükleyerek sırala, çıkar) ve eklenebilecekler. */
@Composable
fun EditAppsSheet(apps: List<TvApp>, onChanged: () -> Unit) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    var pinned by remember { mutableStateOf(AppRowPrefs.get(context).ifEmpty { apps.map { it.pkg } }.filter { p -> apps.any { it.pkg == p } }) }
    fun save(list: List<String>) { pinned = list; AppRowPrefs.set(context, list); onChanged() }
    val byPkg = apps.associateBy { it.pkg }

    // Sürükleme durumu: hangi satır tutuluyor, parmak ne kadar kaydı.
    val listState = rememberLazyListState()
    var dragKey by remember { mutableStateOf<String?>(null) }
    var dragStartOffset by remember { mutableIntStateOf(0) }
    var dragDelta by remember { mutableFloatStateOf(0f) }
    val edgePx = with(density) { 56.dp.toPx() }

    fun dragged() = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == dragKey }

    // Tutulan satırın ortası başka bir satırın üstüne gelince yer değiştirir.
    fun swapIfNeeded() {
        val cur = dragged() ?: return
        val top = dragStartOffset + dragDelta
        val mid = top + cur.size / 2f
        val target = listState.layoutInfo.visibleItemsInfo.firstOrNull {
            it.key != dragKey && (it.key as? String)?.startsWith("p_") == true &&
                mid > it.offset && mid < it.offset + it.size
        } ?: return
        val from = pinned.indexOf((cur.key as String).removePrefix("p_"))
        val to = pinned.indexOf((target.key as String).removePrefix("p_"))
        if (from < 0 || to < 0) return
        pinned = pinned.toMutableList().apply { add(to, removeAt(from)) }
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    }

    // Parmak listenin üst/alt kenarındayken liste kendiliğinden kayar.
    LaunchedEffect(dragKey) {
        if (dragKey == null) return@LaunchedEffect
        while (dragKey != null) {
            val cur = dragged()
            if (cur != null) {
                val info = listState.layoutInfo
                val top = dragStartOffset + dragDelta
                val bottom = top + cur.size
                val speed = when {
                    top < info.viewportStartOffset + edgePx -> -((info.viewportStartOffset + edgePx - top) / edgePx).coerceIn(0f, 1f) * 18f
                    bottom > info.viewportEndOffset - edgePx -> ((bottom - (info.viewportEndOffset - edgePx)) / edgePx).coerceIn(0f, 1f) * 18f
                    else -> 0f
                }
                if (speed != 0f) { listState.scrollBy(speed); swapIfNeeded() }
            }
            kotlinx.coroutines.delay(16)
        }
    }

    fun endDrag() {
        if (dragKey != null) save(pinned)
        dragKey = null; dragDelta = 0f
    }

    Column {
        Text("Uygulama satırını düzenle", color = Pal.Text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text("Basılı tutup sürükle, × ile çıkar, + ile ekle", color = Pal.Sub, fontSize = 13.sp)
        Spacer(Modifier.height(12.dp))
        LazyColumn(
            Modifier.heightIn(max = 520.dp).pointerInput(Unit) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { pos ->
                        val hit = listState.layoutInfo.visibleItemsInfo.firstOrNull {
                            (it.key as? String)?.startsWith("p_") == true && pos.y.toInt() in it.offset until it.offset + it.size
                        }
                        if (hit != null) {
                            dragKey = hit.key as String
                            dragStartOffset = hit.offset
                            dragDelta = 0f
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        }
                    },
                    onDrag = { change, amount ->
                        if (dragKey != null) {
                            change.consume()
                            dragDelta += amount.y
                            swapIfNeeded()
                        }
                    },
                    onDragEnd = { endDrag() },
                    onDragCancel = { endDrag() }
                )
            },
            state = listState,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            item(key = "h_pinned") { Text("Satırda (${pinned.size})", color = Pal.Sub, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(vertical = 4.dp)) }
            items(pinned, key = { "p_$it" }) { p ->
                val app = byPkg[p] ?: return@items
                val isDragged = dragKey == "p_$p"
                val lift by animateFloatAsState(if (isDragged) 1f else 0f, label = "lift")
                val mod = if (isDragged) {
                    Modifier.zIndex(1f).graphicsLayer {
                        val cur = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == dragKey }
                        translationY = if (cur != null) dragStartOffset + dragDelta - cur.offset else 0f
                        scaleX = 1f + 0.03f * lift; scaleY = 1f + 0.03f * lift
                        shadowElevation = 16f * lift
                        shape = RoundedCornerShape(14.dp); clip = true
                    }
                } else Modifier.animateItem()
                Box(mod) {
                    EditRow(app, highlight = lift) {
                        RowIcon(Icons.Filled.Close, true) { save(pinned - p) }
                        Box(Modifier.size(38.dp), contentAlignment = Alignment.Center) {
                            Icon(Icons.Filled.DragHandle, null, tint = Pal.Sub, modifier = Modifier.size(22.dp))
                        }
                    }
                }
            }
            val rest = apps.filter { it.pkg !in pinned }
            if (rest.isNotEmpty()) {
                item(key = "h_rest") { Text("Eklenebilir", color = Pal.Sub, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)) }
                items(rest, key = { "r_${it.pkg}" }) { app ->
                    Box(Modifier.animateItem()) {
                        EditRow(app) { RowIcon(Icons.Filled.Add, true) { save(pinned + app.pkg) } }
                    }
                }
            }
            item(key = "f_reset") {
                Spacer(Modifier.height(12.dp))
                Box(
                    Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(24.dp)).background(Pal.Btn)
                        .clickable { AppRowPrefs.set(context, emptyList()); pinned = apps.map { it.pkg }; onChanged() },
                    contentAlignment = Alignment.Center
                ) { Text("Varsayılana dön", color = Pal.Text, fontSize = 15.sp) }
            }
        }
    }
}

@Composable
private fun EditRow(app: TvApp, highlight: Float = 0f, actions: @Composable RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .background(androidx.compose.ui.graphics.lerp(Pal.Btn, Color(0xFF3A3A3E), highlight)).padding(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val bmp = app.icon
        Box(Modifier.size(44.dp, 36.dp).clip(RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
            if (bmp != null) Image(bmp, null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            else Text(app.name.take(2).uppercase(), color = Pal.Text, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(10.dp))
        Text(app.name, color = Pal.Text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        actions()
    }
}

@Composable
private fun RowIcon(icon: ImageVector, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(38.dp).clip(CircleShape).clickable(enabled = enabled, onClick = onClick).alpha(if (enabled) 1f else 0.3f),
        contentAlignment = Alignment.Center
    ) { Icon(icon, null, tint = Pal.Text, modifier = Modifier.size(22.dp)) }
}

// ---------------- Yön tuşu kartı ----------------

/** Ses çubuğunu MainActivity'ye bağlar (sürükleyip bırakınca sesi o seviyeye getirir). */
object VolumeCtl {
    var setTo: (Int) -> Unit = {}
}

