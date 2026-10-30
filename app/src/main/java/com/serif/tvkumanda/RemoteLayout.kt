package com.serif.tvkumanda

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ---------------- Üst şerit (tüm sekmeler; renkleri Kumanda'da lacivert) ----------------

@Composable
fun TopBarV2(ui: UiState, navy: Boolean, onSettings: () -> Unit, onPicker: () -> Unit, onPower: () -> Unit) {
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
        Box(
            Modifier.fillMaxWidth().height(64.dp).clip(RoundedCornerShape(18.dp)).background(Navy.Card).border(1.dp, Navy.Border, RoundedCornerShape(18.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                when {
                    method == ConnMethod.STANDARD -> "Uygulama listesi ADB bağlantısında görünür"
                    state == ConnState.CONNECTED -> "Uygulamalar yükleniyor…"
                    else -> "Bağlanınca TV'deki uygulamalar burada görünür"
                },
                color = Navy.Sub, fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(12.dp)
            )
        }
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

/** Uygulama satırını düzenleme: satırdakiler (sırala, çıkar) ve eklenebilecekler. */
@Composable
fun EditAppsSheet(apps: List<TvApp>, onChanged: () -> Unit) {
    val context = LocalContext.current
    var pinned by remember { mutableStateOf(AppRowPrefs.get(context).ifEmpty { apps.map { it.pkg } }.filter { p -> apps.any { it.pkg == p } }) }
    fun save(list: List<String>) { pinned = list; AppRowPrefs.set(context, list); onChanged() }
    val byPkg = apps.associateBy { it.pkg }
    Column {
        Text("Uygulama satırını düzenle", color = Pal.Text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text("Okla sırala, × ile çıkar, + ile ekle", color = Pal.Sub, fontSize = 13.sp)
        Spacer(Modifier.height(12.dp))
        LazyColumn(Modifier.heightIn(max = 520.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            item { Text("Satırda (${pinned.size})", color = Pal.Sub, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(vertical = 4.dp)) }
            itemsIndexed(pinned, key = { _, p -> "p_$p" }) { i, p ->
                val app = byPkg[p] ?: return@itemsIndexed
                EditRow(app) {
                    RowIcon(Icons.Filled.KeyboardArrowUp, i > 0) { save(pinned.toMutableList().apply { add(i - 1, removeAt(i)) }) }
                    RowIcon(Icons.Filled.KeyboardArrowDown, i < pinned.lastIndex) { save(pinned.toMutableList().apply { add(i + 1, removeAt(i)) }) }
                    RowIcon(Icons.Filled.Close, true) { save(pinned - p) }
                }
            }
            val rest = apps.filter { it.pkg !in pinned }
            if (rest.isNotEmpty()) {
                item { Text("Eklenebilir", color = Pal.Sub, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)) }
                items(rest, key = { "r_${it.pkg}" }) { app ->
                    EditRow(app) { RowIcon(Icons.Filled.Add, true) { save(pinned + app.pkg) } }
                }
            }
            item {
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
private fun EditRow(app: TvApp, actions: @Composable RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Pal.Btn).padding(8.dp),
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

/** Yön tuşu, Kaydırma ve Fare modları; yanlarda altı yuvarlak tuş. */
@Composable
fun PadCard(
    ui: UiState,
    mode: Int,
    onMode: (Int) -> Unit,
    onKey: (Int) -> Unit,
    onMouse: (String) -> Unit,
    onMic: () -> Unit,
    onNumpad: () -> Unit,
    onKeyboard: () -> Unit,
    onTvSettings: () -> Unit,
    modifier: Modifier
) {
    val context = LocalContext.current
    val adb = ui.method == ConnMethod.ADB
    val shape = RoundedCornerShape(30.dp)
    BoxWithConstraints(
        modifier.fillMaxWidth().clip(shape).background(Navy.Card).border(1.dp, Navy.Border, shape).padding(horizontal = 8.dp, vertical = 10.dp)
    ) {
        val side = 64.dp
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.width(side).fillMaxHeight(), verticalArrangement = Arrangement.SpaceBetween, horizontalAlignment = Alignment.CenterHorizontally) {
                SideKey(Icons.Outlined.Input, "Kaynak", enabled = adb) {
                    if (adb) onKey(Keys.INPUT) else toast(context, "Kaynak tuşu yalnızca ADB bağlantısında çalışır")
                }
                SideKey(Icons.Outlined.TouchApp, "Kaydırma", selected = mode == 1) { onMode(if (mode == 1) 0 else 1) }
                SideKey(Icons.Filled.Keyboard, "Klavye", onClick = onKeyboard)
            }
            // Orta: üstte Ses kıs · Sessiz · Ses aç, ortada daire, altta Geri · Ana Sayfa · Mikrofon
            BoxWithConstraints(Modifier.weight(1f).fillMaxHeight()) {
                val small = 42.dp
                val rows = (small + 8.dp) * 2
                val circle = minOf(maxWidth, maxHeight - rows)
                Row(Modifier.align(Alignment.TopCenter).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    SmallKey(Icons.Filled.VolumeDown, "Ses kıs", small, repeat = true) { onKey(Keys.VOL_DOWN) }
                    SmallKey(AppIcons.VolumeOff, "Sessiz", small) { onKey(Keys.MUTE) }
                    SmallKey(Icons.Filled.VolumeUp, "Ses aç", small, repeat = true) { onKey(Keys.VOL_UP) }
                }
                Box(Modifier.align(Alignment.Center), contentAlignment = Alignment.Center) {
                    when (mode) {
                        1 -> TouchPad(circle, onKey)
                        2 -> TrackPad(circle, ui.fastMode, ui.method, onMouse)
                        else -> DPad(circle, onKey, navy = true)
                    }
                }
                Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    SmallKey(AppIcons.Undo, "Geri", small) { onKey(Keys.BACK) }
                    SmallKey(AppIcons.HomeOutline, "Ana Sayfa", small) { onKey(Keys.HOME) }
                    MicSmall(ui, small, onMic)
                }
            }
            Column(Modifier.width(side).fillMaxHeight(), verticalArrangement = Arrangement.SpaceBetween, horizontalAlignment = Alignment.CenterHorizontally) {
                SideKey(null, "123", onClick = onNumpad, content = { DotsIcon(20.dp) })
                SideKey(Icons.Outlined.Mouse, "Fare", selected = mode == 2, enabled = adb) {
                    if (adb) onMode(if (mode == 2) 0 else 2) else toast(context, "Fare yalnızca ADB bağlantısında çalışır")
                }
                SideKey(Icons.Outlined.Settings, "Ayarlar", onClick = onTvSettings)
            }
        }
    }
}

private fun toast(c: Context, m: String) = Toast.makeText(c, m, Toast.LENGTH_SHORT).show()

@Composable
private fun SideKey(
    icon: ImageVector?, label: String, selected: Boolean = false, enabled: Boolean = true,
    content: (@Composable () -> Unit)? = null, onClick: () -> Unit
) {
    val inter = remember { MutableInteractionSource() }
    val scale = pressScale(inter)
    val pressed by inter.collectIsPressedAsState()
    Column(Modifier.alpha(if (enabled) 1f else 0.4f), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(50.dp).graphicsLayer { scaleX = scale; scaleY = scale }
                .clip(CircleShape)
                .background(if (selected) Navy.AccentSoft else if (pressed) Navy.BtnPressed else Navy.Btn)
                .border(1.dp, if (selected) Navy.Accent else Navy.Border, CircleShape)
                .pressable(inter, onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            if (content != null) content()
            else if (icon != null) Icon(icon, label, tint = if (selected) Color(0xFFAFC0FF) else Pal.Text, modifier = Modifier.size(22.dp))
        }
    }
}

// ---------------- Medya çubuğu ----------------

@Composable
fun MediaBar(onKey: (Int) -> Unit) {
    val shape = RoundedCornerShape(32.dp)
    Row(
        Modifier.fillMaxWidth().height(64.dp).clip(shape)
            .background(Navy.Card)
            .border(1.dp, Brush.horizontalGradient(listOf(Navy.Accent.copy(alpha = 0.55f), Pal.Purple.copy(alpha = 0.45f))), shape)
            .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        MediaKey(Icons.Filled.SkipPrevious, "Önceki") { onKey(Keys.PREV) }
        MediaKey(Icons.Filled.FastRewind, "Geri sar", repeat = true) { onKey(89) }
        // Ortada parlayan oynat/duraklat
        val inter = remember { MutableInteractionSource() }
        val scale = pressScale(inter)
        Box(
            Modifier.size(50.dp).graphicsLayer { scaleX = scale; scaleY = scale }
                .shadow(14.dp, CircleShape, ambientColor = Navy.Accent, spotColor = Navy.Accent)
                .clip(CircleShape).background(Navy.Btn)
                .border(2.dp, RingBrush, CircleShape)
                .pressable(inter, onClick = { onKey(Keys.PLAY_PAUSE) }),
            contentAlignment = Alignment.Center
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.PlayArrow, "Oynat/Duraklat", tint = Pal.Text, modifier = Modifier.size(20.dp))
                Icon(Icons.Filled.Pause, null, tint = Pal.Text, modifier = Modifier.size(18.dp).offset(x = (-4).dp))
            }
        }
        MediaKey(Icons.Filled.FastForward, "İleri sar", repeat = true) { onKey(90) }
        MediaKey(Icons.Filled.SkipNext, "Sonraki") { onKey(Keys.NEXT) }
    }
}

@Composable
private fun MediaKey(icon: ImageVector, label: String, repeat: Boolean = false, onClick: () -> Unit) {
    val inter = remember { MutableInteractionSource() }
    val pressed by inter.collectIsPressedAsState()
    Box(
        Modifier.size(46.dp).clip(CircleShape).background(if (pressed) Navy.BtnPressed else Color.Transparent)
            .pressable(inter, repeat = repeat, onClick = onClick),
        contentAlignment = Alignment.Center
    ) { Icon(icon, label, tint = Pal.Text, modifier = Modifier.size(26.dp)) }
}

// ---------------- Alt tuşlar ----------------

@Composable
fun RemoteBottomKeys(ui: UiState, onKey: (Int) -> Unit, onMic: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(148.dp), verticalAlignment = Alignment.CenterVertically) {
        Rocker(
            width = 70.dp, bg = Navy.Btn, label = "SES", value = ui.volume?.toString(),
            level = ui.volume?.let { it.toFloat() / ui.volumeMax.coerceAtLeast(1) },
            topIsPlus = true, onUp = { onKey(Keys.VOL_UP) }, onDown = { onKey(Keys.VOL_DOWN) }
        )
        Spacer(Modifier.weight(1f))
        Rocker(
            width = 70.dp, bg = Navy.Btn, label = "KANAL", value = null, level = null,
            topIsPlus = false, onUp = { onKey(Keys.CH_UP) }, onDown = { onKey(Keys.CH_DOWN) }
        )
    }
}

/** Yön tuşu kartındaki küçük yuvarlak tuş. */
@Composable
private fun SmallKey(icon: ImageVector, label: String, size: Dp, modifier: Modifier = Modifier, repeat: Boolean = false, onClick: () -> Unit) {
    val inter = remember { MutableInteractionSource() }
    val scale = pressScale(inter)
    val pressed by inter.collectIsPressedAsState()
    Box(
        modifier.size(size).graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(CircleShape).background(if (pressed) Navy.BtnPressed else Navy.Btn).border(1.dp, Navy.Border, CircleShape)
            .pressable(inter, repeat = repeat, onClick = onClick),
        contentAlignment = Alignment.Center
    ) { Icon(icon, label, tint = Pal.Text, modifier = Modifier.size(size * 0.48f)) }
}

@Composable
private fun MicSmall(ui: UiState, size: Dp, onMic: () -> Unit) {
    Box(Modifier.size(size)) {
        MicButton(btnSize = size, iconSize = size * 0.48f, active = ui.micActive, level = ui.micLevel, idle = Navy.AccentSoft, label = "Sesli Ara", onClick = onMic)
    }
}

// ---------------- TV'de çalan: oynatıcı çubuğu ----------------

private fun hms(ms: Long): String {
    val t = (ms / 1000).coerceAtLeast(0)
    return if (t >= 3600) "%d:%02d:%02d".format(t / 3600, (t % 3600) / 60, t % 60) else "%d:%02d".format(t / 60, t % 60)
}

@Composable
fun NowPlayingBar(np: NowPlaying, apps: List<TvApp>, onKey: (Int) -> Unit, onSeek: (Long) -> Unit) {
    // Çalarken konum her saniye ilerler
    var now by remember { mutableLongStateOf(android.os.SystemClock.elapsedRealtime()) }
    LaunchedEffect(np.playing, np.at) {
        while (np.playing) { kotlinx.coroutines.delay(500); now = android.os.SystemClock.elapsedRealtime() }
    }
    var dragging by remember { mutableStateOf<Float?>(null) }
    val pos = np.position(now)
    val dur = np.durationMs
    val app = apps.firstOrNull { it.pkg == np.pkg }
    val shape = RoundedCornerShape(24.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(Navy.Card).border(1.dp, Navy.Border, shape)
            .padding(start = 14.dp, end = 10.dp, top = 10.dp, bottom = 6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val bmp = app?.icon
            Box(Modifier.size(36.dp).clip(RoundedCornerShape(9.dp)).background(Navy.Btn), contentAlignment = Alignment.Center) {
                if (bmp != null) Image(bmp, null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                else Icon(Icons.Filled.PlayArrow, null, tint = Navy.Sub, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(np.title.ifBlank { app?.name ?: "Oynatılıyor" }, color = Pal.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(np.artist.ifBlank { app?.name ?: np.pkg }, color = Navy.Sub, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            MediaKey(Icons.Filled.SkipPrevious, "Önceki") { onKey(Keys.PREV) }
            val inter = remember { MutableInteractionSource() }
            val scale = pressScale(inter)
            Box(
                Modifier.size(44.dp).graphicsLayer { scaleX = scale; scaleY = scale }.clip(CircleShape).background(Navy.Accent)
                    .pressable(inter, onClick = { onKey(Keys.PLAY_PAUSE) }),
                contentAlignment = Alignment.Center
            ) { Icon(if (np.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, "Oynat/Duraklat", tint = Color.White, modifier = Modifier.size(24.dp)) }
            MediaKey(Icons.Filled.SkipNext, "Sonraki") { onKey(Keys.NEXT) }
        }
        if (dur > 0) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val shown = dragging?.let { (it * dur).toLong() } ?: pos
                Text(hms(shown), color = Navy.Sub, fontSize = 12.sp, modifier = Modifier.width(52.dp))
                androidx.compose.material3.Slider(
                    value = dragging ?: (pos.toFloat() / dur).coerceIn(0f, 1f),
                    onValueChange = { dragging = it },
                    onValueChangeFinished = { dragging?.let { onSeek((it * dur).toLong()) }; dragging = null },
                    enabled = np.canSeek,
                    colors = androidx.compose.material3.SliderDefaults.colors(
                        thumbColor = Color(0xFFAFC0FF), activeTrackColor = Navy.Accent, inactiveTrackColor = Navy.Btn,
                        disabledThumbColor = Navy.Sub, disabledActiveTrackColor = Navy.Sub, disabledInactiveTrackColor = Navy.Btn
                    ),
                    modifier = Modifier.weight(1f).height(28.dp)
                )
                Text(hms(dur), color = Navy.Sub, fontSize = 12.sp, textAlign = TextAlign.End, modifier = Modifier.width(52.dp))
            }
        } else Spacer(Modifier.height(6.dp))
    }
}

@Composable
private fun LabelKey(icon: ImageVector, label: String, modifier: Modifier, onClick: () -> Unit) {
    val inter = remember { MutableInteractionSource() }
    val scale = pressScale(inter)
    val pressed by inter.collectIsPressedAsState()
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier.fillMaxHeight().graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(shape).background(if (pressed) Navy.BtnPressed else Navy.Btn).border(1.dp, Navy.Border, shape)
            .pressable(inter, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center
    ) {
        Icon(icon, label, tint = Pal.Text, modifier = Modifier.size(26.dp))
    }
}
