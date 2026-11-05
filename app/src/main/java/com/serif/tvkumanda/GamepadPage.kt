package com.serif.tvkumanda

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.hypot
import kotlin.math.roundToInt

/*
 * Oyun kolu: "Daha fazla" penceresindeki kutucuktan açılan yatay, tam ekran sayfa.
 * ADB (hızlı mod) bağlantısında TV'de sanal bir oyun kolu kurulur (KeyServer, /dev/uhid): çubuklar analog,
 * birden çok tuşa aynı anda basılabilir. Standart bağlantıda tuşlar tek tek tuş kodu olarak gider; sol çubuk
 * yön tuşu gibi davranır, sağ çubuk çalışmaz. Kalem simgesiyle düzenleme: taşı, boyutlandır, kaldır, geri ekle.
 */

private val PadYellow = Color(0xFFF2C14E)
private val FaceColors = mapOf(
    "Y" to PadYellow, "X" to Color(0xFF6FA8FF), "B" to Color(0xFFFF7A7A), "A" to Color(0xFF5FD59A)
)

private const val PREFS = "oyun_kolu"
private const val PREF_LAYOUT = "duzen"

private fun loadLayout(c: Context) =
    GamepadLayout.decode(c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(PREF_LAYOUT, null))

private fun saveLayout(c: Context, items: List<PadItem>) =
    c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(PREF_LAYOUT, GamepadLayout.encode(items)).apply()

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** Tuş basışlarını TV'ye çevirir: sanal kol satırları (ADB) ya da tuş kodları (Standart). */
private class PadEngine(
    private val hid: Boolean,
    private val scope: CoroutineScope,
    private val onKey: (Int) -> Unit,
    private val onLine: (String) -> Unit,
    private val tap: () -> Unit
) {
    private var state = PadHidState()
    private var sent = ""
    private var stickDirty = false
    private val repeats = HashMap<String, Job>()
    private val repeatKeys = HashMap<String, Int>()

    fun press(d: PadDef) {
        if (hid && d.bit != 0) {
            tap()
            state = state.copy(buttons = state.buttons or d.bit)
            flush()
        } else onKey(d.key)
    }

    fun release(d: PadDef) {
        if (hid && d.bit != 0) {
            state = state.copy(buttons = state.buttons and d.bit.inv())
            flush()
        }
    }

    fun dpad(mask: Int) {
        if (hid) {
            if (mask == state.dpad) return
            if (mask != 0) tap()
            state = state.copy(dpad = mask)
            flush()
        } else repeat("DPAD", PadDir.keyOf(mask))
    }

    fun stick(left: Boolean, x: Float, y: Float) {
        if (hid) {
            state = if (left) state.copy(lx = x, ly = y) else state.copy(rx = x, ry = y)
            stickDirty = true
        } else if (left) repeat("LS", PadDir.key(x, y, 0.5f))
    }

    /** Çubuk hareketleri kare başına en fazla bir satır olarak gider. */
    fun tick() {
        if (stickDirty) { stickDirty = false; flush() }
    }

    private fun flush() {
        val line = state.line()
        if (line != sent) { sent = line; onLine(line) }
    }

    /** Standart'ta yön basılı tutulunca kumandadaki gibi tekrar eder. */
    private fun repeat(source: String, key: Int?) {
        if (repeatKeys[source] == key) return
        repeats.remove(source)?.cancel()
        if (key == null) { repeatKeys.remove(source); return }
        repeatKeys[source] = key
        repeats[source] = scope.launch {
            onKey(key)
            delay(380)
            while (isActive) { onKey(key); delay(110) }
        }
    }

    fun releaseAll() {
        repeats.values.forEach { it.cancel() }
        repeats.clear(); repeatKeys.clear()
        if (hid) { state = PadHidState(); stickDirty = false; flush() }
    }
}

@Composable
fun GamepadPage(ui: UiState, onKey: (Int) -> Unit, onLine: (String) -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    val view = LocalView.current
    val density = LocalDensity.current

    // Yatay, tam ekran; sayfa kapanınca eski hâline döner
    DisposableEffect(Unit) {
        val act = view.context.findActivity()
        val prevOrientation = act?.requestedOrientation
        act?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        val ctl = act?.window?.let { WindowCompat.getInsetsController(it, view) }
        ctl?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        ctl?.hide(WindowInsetsCompat.Type.systemBars())
        val keep = view.keepScreenOn
        view.keepScreenOn = true
        onDispose {
            ctl?.show(WindowInsetsCompat.Type.systemBars())
            if (act != null && prevOrientation != null) act.requestedOrientation = prevOrientation
            view.keepScreenOn = keep
        }
    }

    val connected = ui.state == ConnState.CONNECTED
    val hid = connected && ui.method == ConnMethod.ADB && ui.fastMode
    val key by rememberUpdatedState(onKey)
    val line by rememberUpdatedState(onLine)
    val scope = rememberCoroutineScope()
    val engine = remember(hid) {
        PadEngine(hid, scope, { key(it) }, { line(it) }, { AppSettings.tapIfEnabled(context) })
    }
    DisposableEffect(engine) {
        if (hid) line("G")
        onDispose {
            engine.releaseAll()
            if (hid) line("G0")
        }
    }
    LaunchedEffect(engine) {
        while (true) { delay(16); engine.tick() }
    }

    var items by remember { mutableStateOf(loadLayout(context)) }
    var editing by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<String?>(null) }
    var addOpen by remember { mutableStateOf(false) }
    val finishEdit = {
        saveLayout(context, items)
        editing = false; selected = null; addOpen = false
    }
    BackHandler { if (editing) finishEdit() else onClose() }

    fun update(id: String, f: (PadItem) -> PadItem) {
        items = items.map { if (it.id == id) f(it) else it }
    }

    GlassText {
        BoxWithConstraints(
            Modifier.fillMaxSize().glassBackdrop()
                // Arkadaki kumandaya dokunulmasın; düzenlerken boşluğa dokunmak seçimi kaldırır
                .pointerInput(editing) {
                    detectTapGestures { if (editing) { selected = null; addOpen = false } }
                }
        ) {
            val wPx = constraints.maxWidth.toFloat()
            val hPx = constraints.maxHeight.toFloat()

            items.filter { it.visible }.forEach { item ->
                val def = GamepadLayout.def(item.id) ?: return@forEach
                val w = (def.w * item.scale).dp
                val h = (def.h * item.scale).dp
                val halfW = with(density) { w.toPx() } / 2f
                val halfH = with(density) { h.toPx() } / 2f
                Box(
                    Modifier
                        .offset { IntOffset((item.x * wPx - halfW).roundToInt(), (item.y * hPx - halfH).roundToInt()) }
                        .size(w, h)
                        .editFrame(editing, selected == item.id, def.round, (12 * item.scale).dp)
                        .pointerInput(editing, item.id, item.scale, wPx, hPx) {
                            if (editing) editGesture(
                                onSelect = { selected = if (selected == item.id) null else item.id; addOpen = false },
                                onDragStart = { selected = item.id; addOpen = false },
                                onDrag = { d ->
                                    update(item.id) {
                                        it.copy(
                                            x = (it.x + d.x / wPx).coerceIn(halfW / wPx, 1f - halfW / wPx),
                                            y = (it.y + d.y / hPx).coerceIn(halfH / hPx, 1f - halfH / hPx)
                                        )
                                    }
                                }
                            )
                        }
                ) {
                    when (def.kind) {
                        PadKind.BUTTON -> PadButton(def, item.scale, editing, engine)
                        PadKind.STICK -> PadStick(def, editing, engine)
                        PadKind.DPAD -> PadDpad(item.scale, editing, engine)
                    }
                }
            }

            // Üst orta: ✕ ve kalem; düzenlerken Sıfırla · Tuş ekle · Bitti
            Column(
                Modifier.align(Alignment.TopCenter).padding(top = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (!editing) {
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        RoundTool(Icons.Filled.Close, "Kapat", onClose)
                        RoundTool(Icons.Outlined.Edit, "Düzenle") { editing = true; selected = null }
                    }
                    Text(
                        when {
                            hid -> "Sanal oyun kolu · ADB"
                            connected -> "Tuş olarak gidiyor · Standart"
                            else -> "TV'ye bağlı değil"
                        },
                        color = if (connected) Glass.Sub else Glass.Red, fontSize = 12.sp,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Pill(Icons.Outlined.RestartAlt, "Sıfırla") {
                            items = GamepadLayout.defaults(); selected = null; addOpen = false
                        }
                        Pill(Icons.Filled.Add, "Tuş ekle") { addOpen = !addOpen; selected = null }
                        Pill(Icons.Filled.Check, "Bitti", strong = true, onClick = finishEdit)
                    }
                    if (addOpen) {
                        val hidden = items.filter { !it.visible }
                        Column(
                            Modifier.padding(top = 8.dp).widthIn(max = 360.dp)
                                .clip(RoundedCornerShape(14.dp)).background(Color(0xFF231E45))
                                .border(1.dp, Glass.Lavender.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
                                .padding(10.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            if (hidden.isEmpty()) {
                                Text("Kaldırılan tuş yok", color = Glass.Sub, fontSize = 13.sp)
                            } else {
                                hidden.chunked(4).forEach { row ->
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 3.dp)) {
                                        row.forEach { h ->
                                            val d = GamepadLayout.def(h.id) ?: return@forEach
                                            Pill(null, d.label) {
                                                update(h.id) { it.copy(visible = true, x = 0.5f, y = 0.4f) }
                                                selected = h.id; addOpen = false
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Seçili tuş: boyut ve kaldırma
            val sel = selected?.let { id -> items.firstOrNull { it.id == id } }
            if (editing && sel != null) {
                val d = GamepadLayout.def(sel.id)
                Row(
                    Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Pill(Icons.Filled.Remove, null) {
                        update(sel.id) { it.copy(scale = GamepadLayout.clampScale(it.scale - GamepadLayout.STEP)) }
                    }
                    Text(
                        "${d?.label ?: sel.id} · %${(sel.scale * 100).roundToInt()}",
                        color = Glass.Text, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 4.dp)
                    )
                    Pill(Icons.Filled.Add, null) {
                        update(sel.id) { it.copy(scale = GamepadLayout.clampScale(it.scale + GamepadLayout.STEP)) }
                    }
                    Pill(Icons.Outlined.DeleteOutline, "Kaldır", danger = true) {
                        update(sel.id) { it.copy(visible = false) }
                        selected = null
                    }
                }
            }
        }
    }
}

/** Düzenleme: kıpırdamadan bırakmak seçer, sürüklemek taşır. Olaylar tüketilir, arka plan seçimi kaldırmasın. */
private suspend fun PointerInputScope.editGesture(onSelect: () -> Unit, onDragStart: () -> Unit, onDrag: (Offset) -> Unit) {
    awaitEachGesture {
        val down = awaitFirstDown()
        down.consume()
        var moved = false
        var total = Offset.Zero
        while (true) {
            val ev = awaitPointerEvent()
            val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
            if (!ch.pressed) { ch.consume(); break }
            val d = ch.positionChange()
            ch.consume()
            total += d
            if (!moved && total.getDistance() > viewConfiguration.touchSlop) { moved = true; onDragStart() }
            if (moved) onDrag(d)
        }
        if (!moved) onSelect()
    }
}

/** Düzenlerken kesikli çerçeve, seçiliyse sarı düz çerçeve. */
private fun Modifier.editFrame(editing: Boolean, selected: Boolean, round: Boolean, corner: Dp): Modifier =
    if (!editing) this else drawWithContent {
        drawContent()
        val r = if (round) size.minDimension / 2f else corner.toPx()
        if (selected) {
            drawRoundRect(PadYellow, cornerRadius = CornerRadius(r), style = Stroke(width = 2.5.dp.toPx()))
        } else {
            drawRoundRect(
                Glass.Lavender, cornerRadius = CornerRadius(r),
                style = Stroke(width = 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 7f)))
            )
        }
    }

@Composable
private fun PadButton(def: PadDef, scale: Float, editing: Boolean, engine: PadEngine) {
    var pressed by remember { mutableStateOf(false) }
    val shape: Shape = if (def.round) CircleShape else RoundedCornerShape((12 * scale).dp)
    Box(
        Modifier.fillMaxSize()
            .glass(shape, pressed = pressed, strength = 0.9f)
            .border(1.dp, if (pressed) Glass.Lavender else Color.White.copy(alpha = 0.13f), shape)
            .then(
                if (editing) Modifier else Modifier.pointerInput(engine) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        pressed = true
                        engine.press(def)
                        try {
                            do {
                                val ev = awaitPointerEvent()
                            } while (ev.changes.any { it.pressed })
                        } finally {
                            pressed = false
                            engine.release(def)
                        }
                    }
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        when (def.id) {
            "HOME" -> Icon(Icons.Filled.Home, "Ana Sayfa", tint = Glass.Text, modifier = Modifier.size((24 * scale).dp))
            "SELECT", "START" -> Text(def.label, color = Glass.Text, fontSize = (12 * scale).sp, maxLines = 1)
            else -> Text(
                def.label, color = FaceColors[def.id] ?: Glass.Text,
                fontSize = ((if (FaceColors.containsKey(def.id)) 22 else 15) * scale).sp,
                fontWeight = FontWeight.SemiBold, maxLines = 1
            )
        }
    }
}

@Composable
private fun PadStick(def: PadDef, editing: Boolean, engine: PadEngine) {
    val left = def.id == "LS"
    var knob by remember { mutableStateOf(Offset.Zero) }   // −1..1
    BoxWithConstraints(
        Modifier.fillMaxSize()
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.05f))
            .border(1.dp, Color.White.copy(alpha = 0.13f), CircleShape)
            .then(
                if (editing) Modifier else Modifier.pointerInput(engine) {
                    val travel = size.width / 2f * 0.62f
                    fun at(p: Offset) {
                        var x = (p.x - size.width / 2f) / travel
                        var y = (p.y - size.height / 2f) / travel
                        val d = hypot(x, y)
                        if (d > 1f) { x /= d; y /= d }
                        knob = Offset(x, y)
                        engine.stick(left, x, y)
                    }
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        try {
                            at(down.position)
                            while (true) {
                                val ch = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                                if (!ch.pressed) break
                                ch.consume()
                                at(ch.position)
                            }
                        } finally {
                            knob = Offset.Zero
                            engine.stick(left, 0f, 0f)
                        }
                    }
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        val k = maxWidth * 0.42f
        val travel = maxWidth / 2 * 0.62f
        Box(
            Modifier.offset(travel * knob.x, travel * knob.y).size(k)
                .clip(CircleShape).background(Glass.Lavender.copy(alpha = 0.45f))
                .border(1.dp, Glass.Lavender, CircleShape)
        )
    }
}

@Composable
private fun PadDpad(scale: Float, editing: Boolean, engine: PadEngine) {
    var mask by remember { mutableIntStateOf(0) }
    BoxWithConstraints(
        Modifier.fillMaxSize().then(
            if (editing) Modifier else Modifier.pointerInput(engine) {
                fun at(p: Offset) {
                    val m = PadDir.of((p.x - size.width / 2f) / (size.width / 2f), (p.y - size.height / 2f) / (size.height / 2f), 0.22f)
                    if (m != mask) { mask = m; engine.dpad(m) }
                }
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    try {
                        at(down.position)
                        while (true) {
                            val ch = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                            if (!ch.pressed) break
                            ch.consume()
                            at(ch.position)
                        }
                    } finally {
                        mask = 0
                        engine.dpad(0)
                    }
                }
            }
        )
    ) {
        val c = maxWidth / 3
        val shape = RoundedCornerShape((8 * scale).dp)
        @Composable
        fun arm(dx: Int, dy: Int, bit: Int, icon: ImageVector, label: String) {
            val on = mask and bit != 0
            Box(
                Modifier.offset(c * dx, c * dy).size(c)
                    .glass(shape, pressed = on, strength = 0.9f)
                    .border(1.dp, if (on) Glass.Lavender else Color.White.copy(alpha = 0.13f), shape),
                contentAlignment = Alignment.Center
            ) { Icon(icon, label, tint = Glass.Text, modifier = Modifier.size(c * 0.6f)) }
        }
        arm(1, 0, PadDir.UP, Icons.Filled.KeyboardArrowUp, "Yukarı")
        arm(0, 1, PadDir.LEFT, Icons.Filled.KeyboardArrowLeft, "Sol")
        arm(2, 1, PadDir.RIGHT, Icons.Filled.KeyboardArrowRight, "Sağ")
        arm(1, 2, PadDir.DOWN, Icons.Filled.KeyboardArrowDown, "Aşağı")
    }
}

@Composable
private fun RoundTool(icon: ImageVector, label: String, onClick: () -> Unit) {
    Box(
        Modifier.size(44.dp).glass(CircleShape, strength = 0.9f)
            .border(1.dp, Color.White.copy(alpha = 0.13f), CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) { Icon(icon, label, tint = Glass.Text, modifier = Modifier.size(22.dp)) }
}

@Composable
private fun Pill(icon: ImageVector?, label: String?, strong: Boolean = false, danger: Boolean = false, onClick: () -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    val edge = if (danger) Glass.Red else Glass.Lavender.copy(alpha = 0.55f)
    Row(
        Modifier.clip(shape)
            .background(if (strong) Glass.Purple else Color(0xFF2D2852))
            .border(1.dp, edge, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = if (label == null) 10.dp else 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        if (icon != null) Icon(icon, label, tint = if (danger) Color(0xFFFFB3B3) else Glass.Text, modifier = Modifier.size(18.dp))
        if (label != null) Text(label, color = if (danger) Color(0xFFFFB3B3) else Glass.Text, fontSize = 13.sp, maxLines = 1)
    }
}
