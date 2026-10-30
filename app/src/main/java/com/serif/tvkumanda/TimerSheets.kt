package com.serif.tvkumanda

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

// ---------------- Ortak parçalar ----------------

@Composable
private fun <T> ChoiceGrid(options: List<Pair<T, String>>, selected: T, enabled: Boolean = true, perRow: Int = 3, onSelect: (T) -> Unit) {
    for (row in options.chunked(perRow)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((value, label) in row) {
                val sel = value == selected
                Box(
                    Modifier.weight(1f).height(42.dp).clip(RoundedCornerShape(12.dp))
                        .background(if (sel) Pal.Blue else Pal.Btn)
                        .clickable(enabled = enabled) { onSelect(value) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(label, color = if (sel) Color.White else Pal.Text, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                }
            }
            repeat(perRow - row.size) { Spacer(Modifier.weight(1f)) }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Pal.Btn)
            .clickable(enabled = enabled) { onChange(!checked) }.padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = Pal.Text, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Switch(
            checked = checked, onCheckedChange = onChange, enabled = enabled,
            colors = SwitchDefaults.colors(checkedTrackColor = Pal.Blue, checkedThumbColor = Color.White, uncheckedTrackColor = Pal.Bg, uncheckedThumbColor = Pal.Sub)
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, color = Pal.Sub, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun Note(text: String) {
    Text(text, color = Pal.Sub, fontSize = 12.sp, lineHeight = 16.sp)
}

/** TV'deki nöbetçinin durumu. */
@Composable
private fun WatcherLine(status: TvWatcher.Status?) {
    if (status == null) return
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(if (status.running) Pal.Green else Pal.Sub))
        Spacer(Modifier.width(8.dp))
        Text(
            if (status.running) "TV'deki nöbetçi çalışıyor" else "Nöbetçi şu an çalışmıyor (açık bir ayar yok)",
            color = Pal.Sub, fontSize = 12.sp
        )
    }
}

/** Android 12+ alarm izni kapalıysa uyarı ve izin ekranına giden düğme. */
@Composable
private fun ExactAlarmNote(context: Context) {
    Text("Alarm izni kapalı: zamanlayıcı birkaç dakika gecikebilir.", color = Pal.Amber, fontSize = 12.sp)
    Spacer(Modifier.height(8.dp))
    SecondaryButton("Alarm iznini aç", Modifier.fillMaxWidth()) {
        PhoneTimers.exactSettingsIntent(context)?.let { runCatching { context.startActivity(it) } }
    }
    Spacer(Modifier.height(12.dp))
}

/** Telefondaki alarm izni; izin ekranından dönülünce kendiliğinden güncellenir. */
@Composable
private fun rememberExactAlarm(context: Context): Boolean {
    var exact by remember { mutableStateOf(PhoneTimers.canExact(context)) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            exact = PhoneTimers.canExact(context)
        }
    }
    return exact
}

// ---------------- 1. Uyku zamanlayıcısı ----------------

@Composable
internal fun SleepTimerTool(adb: Boolean) {
    val context = LocalContext.current
    val run = rememberRunner()
    val ip = remember { TvTools.currentIp() }
    var pending by remember { mutableStateOf<Long?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var minutes by remember { mutableStateOf(30) }
    var fade by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<ToolResult?>(null) }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    val exact = rememberExactAlarm(context)

    LaunchedEffect(Unit) {
        if (adb) {
            io { TvWatcher.read() }
                .onSuccess { s ->
                    val p = s.config.pendingSleep(System.currentTimeMillis(), s.state?.sleepDone ?: 0)
                    pending = p
                    if (p != null) fade = s.config.fade
                }
                .onFailure { result = ToolResult(false, errorText(it)) }
        } else {
            pending = PhoneTimers.sleep(context)?.takeIf { it.first == ip }?.second
        }
        loaded = true
    }
    // Geri sayım
    LaunchedEffect(pending) {
        val p = pending ?: return@LaunchedEffect
        while (true) {
            now = System.currentTimeMillis()
            if (now >= p) { pending = null; break }
            delay(1000)
        }
    }

    fun start() {
        val at = System.currentTimeMillis() + minutes * 60_000L
        busy = true; result = null
        run {
            if (adb) {
                io { TvWatcher.update { it.copy(sleepAt = at, fade = fade) } }
                    .onSuccess { pending = at; result = ToolResult(true, "Kuruldu: TV ${TimerText.clockOf(at)} olunca uyuyacak") }
                    .onFailure { result = ToolResult(false, errorText(it)) }
            } else {
                runCatching { PhoneTimers.scheduleSleep(context, ip, at) }
                    .onSuccess {
                        pending = at
                        result = ToolResult(true, "Kuruldu: TV ${TimerText.clockOf(at)} olunca uyuyacak")
                        DiagLog.log("Uyku zamanlayıcısı (telefon) kuruldu: ${TimerText.clockOf(at)}")
                    }
                    .onFailure { result = ToolResult(false, errorText(it)) }
            }
            busy = false
        }
    }

    fun cancel() {
        busy = true; result = null
        run {
            if (adb) {
                io { TvWatcher.update { it.copy(sleepAt = 0) } }
                    .onSuccess { pending = null; result = ToolResult(true, "Zamanlayıcı iptal edildi") }
                    .onFailure { result = ToolResult(false, errorText(it)) }
            } else {
                PhoneTimers.cancelSleep(context)
                pending = null
                result = ToolResult(true, "Zamanlayıcı iptal edildi")
            }
            busy = false
        }
    }

    Column(Modifier.verticalScroll(rememberScrollState())) {
        if (!loaded) Loading()
        val p = pending
        if (p != null) {
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Pal.Btn).padding(vertical = 18.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(TimerText.remaining(p - now), color = Pal.Text, fontSize = 34.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text("Uyku saati: ${TimerText.clockOf(p)}", color = Pal.Sub, fontSize = 13.sp)
            }
            Spacer(Modifier.height(10.dp))
            SecondaryButton("İptal et", Modifier.fillMaxWidth(), enabled = !busy) { cancel() }
            Spacer(Modifier.height(20.dp))
            SectionTitle("Yeniden kur")
        } else {
            SectionTitle("Ne kadar sonra uyusun?")
        }
        ChoiceGrid(
            listOf(15 to "15 dk", 30 to "30 dk", 45 to "45 dk", 60 to "1 sa", 90 to "1,5 sa", 120 to "2 sa"),
            minutes, enabled = !busy
        ) { minutes = it }
        Spacer(Modifier.height(4.dp))
        if (adb) {
            SwitchRow("Son dakikada sesi yavaşça kıs", fade, enabled = !busy) { fade = it }
            Spacer(Modifier.height(10.dp))
            Note("İşi TV'deki nöbetçi yapar; telefonu kapatsan ya da evden çıksan da çalışır.")
        } else {
            if (!exact) ExactAlarmNote(context)
            Note("Standart bağlantıda süre telefonda tutulur: telefon o saatte TV ile aynı Wi-Fi ağında olmalı. ADB ile bağlanırsan iş TV'ye devredilir ve sesi yavaşça kısma da açılır.")
        }
        Spacer(Modifier.height(16.dp))
        MainButton(if (busy) "Bekle…" else "Başlat", enabled = !busy && loaded && ip.isNotBlank()) { start() }
        ResultLine(result)
    }
}

// ---------------- 2. Zamanlı açma ----------------

@Composable
private fun Stepper(label: String, value: String, onMinus: () -> Unit, onPlus: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.clip(RoundedCornerShape(16.dp)).background(Pal.Btn).padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = Pal.Sub, fontSize = 12.sp)
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            StepKey("−", onMinus)
            Text(value, color = Pal.Text, fontSize = 26.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 14.dp))
            StepKey("+", onPlus)
        }
    }
}

@Composable
private fun StepKey(text: String, onClick: () -> Unit) {
    Box(
        Modifier.size(40.dp).clip(CircleShape).background(Pal.BtnPressed).pressable(
            remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, repeat = true, onClick = onClick
        ),
        contentAlignment = Alignment.Center
    ) { Text(text, color = Pal.Text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold) }
}

@Composable
internal fun WakeTimeTool() {
    val context = LocalContext.current
    val tvs = remember { TvTools.savedTvs() }
    val wakeable = remember { tvs.filter { it.mac.isNotBlank() } }
    var existing by remember { mutableStateOf(PhoneTimers.wake(context)) }
    var ip by remember {
        mutableStateOf(
            existing?.ip?.takeIf { e -> wakeable.any { it.ip == e } }
                ?: wakeable.firstOrNull { it.ip == TvTools.currentIp() }?.ip
                ?: wakeable.firstOrNull()?.ip.orEmpty()
        )
    }
    var hour by remember { mutableStateOf(existing?.hour ?: 7) }
    var minute by remember { mutableStateOf(existing?.minute ?: 30) }
    var daily by remember { mutableStateOf(existing?.daily ?: true) }
    var result by remember { mutableStateOf<ToolResult?>(null) }
    val exact = rememberExactAlarm(context)

    if (wakeable.isEmpty()) {
        Note(
            "Kapalı TV'yi açmak için telefon TV'ye Wi-Fi ile uyandırma sinyali gönderir. Bunun için TV'nin ağ adresinin " +
                "bilinmesi gerekir; bu adres TV'ye ilk kez ADB ile bağlanınca öğrenilir. Bir kez ADB ile bağlanıp tekrar dene."
        )
        return
    }

    Column(Modifier.verticalScroll(rememberScrollState())) {
        existing?.let { w ->
            val name = tvs.firstOrNull { it.ip == w.ip }?.display ?: w.ip
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Pal.Btn).padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "${if (w.daily) "Her gün" else "Bir kez"} ${TimerText.clock(w.hour * 60 + w.minute)}",
                        color = Pal.Text, fontSize = 18.sp, fontWeight = FontWeight.Bold
                    )
                    Text(name, color = Pal.Sub, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                SecondaryButton("İptal", Modifier.width(96.dp)) {
                    PhoneTimers.cancelWake(context)
                    existing = null
                    result = ToolResult(true, "Zamanlı açma iptal edildi")
                    DiagLog.log("Zamanlı açma iptal edildi")
                }
            }
            Spacer(Modifier.height(18.dp))
        }

        SectionTitle("Hangi TV?")
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (tv in wakeable) {
                val sel = tv.ip == ip
                Text(
                    tv.display, color = if (sel) Color.White else Pal.Text, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(if (sel) Pal.Blue else Pal.Btn)
                        .clickable { ip = tv.ip; result = null }
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                )
            }
        }
        if (tvs.size > wakeable.size) {
            Spacer(Modifier.height(6.dp))
            Note("Listede olmayan TV'ler için önce bir kez ADB ile bağlan.")
        }
        Spacer(Modifier.height(16.dp))
        SectionTitle("Saat kaçta?")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Stepper("Saat", hour.toString().padStart(2, '0'), { hour = (hour + 23) % 24; result = null }, { hour = (hour + 1) % 24; result = null }, Modifier.weight(1f))
            Stepper("Dakika", minute.toString().padStart(2, '0'), { minute = (minute + 55) % 60; result = null }, { minute = (minute + 5) % 60; result = null }, Modifier.weight(1f))
        }
        Spacer(Modifier.height(12.dp))
        SwitchRow("Her gün tekrarla", daily) { daily = it; result = null }
        Spacer(Modifier.height(12.dp))
        if (!exact) ExactAlarmNote(context)
        Note(
            "Telefon o saatte TV ile aynı Wi-Fi ağında olmalı. TV'nin ağ ayarlarında \"Wi-Fi ile aç\" (Wake on LAN) açık olmalı. " +
                "TV açılınca kumanda tuşlarıyla kullanılır; uygulama kendiliğinden açılmaz."
        )
        Spacer(Modifier.height(16.dp))
        MainButton(if (existing == null) "Kaydet" else "Güncelle", enabled = ip.isNotBlank()) {
            runCatching { PhoneTimers.scheduleWake(context, ip, hour, minute, daily) }
                .onSuccess { at ->
                    existing = PhoneTimers.wake(context)
                    val left = TimerText.remaining(at - System.currentTimeMillis())
                    result = ToolResult(true, "Kuruldu: TV ${TimerText.clockOf(at)} olunca açılacak ($left sonra)")
                    DiagLog.log("Zamanlı açma kuruldu: ${TimerText.clockOf(at)}${if (daily) ", her gün" else ""}")
                }
                .onFailure { result = ToolResult(false, errorText(it)) }
        }
        ResultLine(result)
    }
}

// ---------------- 3. Ses sınırı ----------------

@Composable
internal fun VolumeCapTool() {
    val run = rememberRunner()
    var status by remember { mutableStateOf<TvWatcher.Status?>(null) }
    var vol by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var on by remember { mutableStateOf(false) }
    var cap by remember { mutableStateOf(20f) }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<ToolResult?>(null) }
    LaunchedEffect(Unit) {
        io { TvWatcher.read() to TvTools.adb?.volume() }
            .onSuccess { (s, v) ->
                status = s
                vol = v
                on = s.config.volMax >= 0
                cap = (if (s.config.volMax >= 0) s.config.volMax else (v?.first ?: 20)).toFloat()
            }
            .onFailure { result = ToolResult(false, errorText(it)) }
    }
    val max = (vol?.second ?: 100).coerceAtLeast(1)

    Column(Modifier.verticalScroll(rememberScrollState())) {
        if (status == null && result == null) Loading()
        SwitchRow("Ses sınırı", on, enabled = !busy && status != null) { on = it; result = null }
        if (on) {
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("En yüksek ses", color = Pal.Sub, fontSize = 13.sp, modifier = Modifier.weight(1f))
                Text("${cap.toInt()} / $max", color = Pal.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
            Slider(
                value = cap.coerceIn(0f, max.toFloat()), onValueChange = { cap = it; result = null },
                valueRange = 0f..max.toFloat(), enabled = !busy,
                colors = SliderDefaults.colors(thumbColor = Pal.Blue, activeTrackColor = Pal.Blue, inactiveTrackColor = Pal.Btn)
            )
        }
        vol?.let {
            Note("Şu anki ses: ${it.first}")
            Spacer(Modifier.height(10.dp))
        }
        WatcherLine(status)
        Spacer(Modifier.height(10.dp))
        Note("Ses sınırın üstüne çıkarsa (TV'nin kendi kumandasıyla açılsa bile) birkaç saniye içinde geri kısılır. İşi TV'deki nöbetçi yapar; telefon kapalıyken de geçerlidir.")
        Spacer(Modifier.height(16.dp))
        MainButton(if (busy) "Uygulanıyor…" else "Uygula", enabled = !busy && status != null) {
            busy = true; result = null
            val v = if (on) cap.toInt() else -1
            run {
                io { TvWatcher.update { it.copy(volMax = v) } }
                    .onSuccess { status = it; result = ToolResult(true, if (v >= 0) "Ses en fazla $v olacak" else "Ses sınırı kapatıldı") }
                    .onFailure { result = ToolResult(false, errorText(it)) }
                busy = false
            }
        }
        ResultLine(result)
    }
}

// ---------------- 4. Ekran süresi sınırı ----------------

@Composable
internal fun ScreenTimeTool() {
    val run = rememberRunner()
    var status by remember { mutableStateOf<TvWatcher.Status?>(null) }
    var limit by remember { mutableStateOf(0) }
    var night by remember { mutableStateOf(-1) }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<ToolResult?>(null) }
    LaunchedEffect(Unit) {
        io { TvWatcher.read() }
            .onSuccess { s -> status = s; limit = s.config.limitMin; night = s.config.nightFrom }
            .onFailure { result = ToolResult(false, errorText(it)) }
    }
    val today = remember { Watcher.dayOf(System.currentTimeMillis()) }
    val used = status?.state?.takeIf { it.day == today }?.usedSec ?: 0

    Column(Modifier.verticalScroll(rememberScrollState())) {
        if (status == null && result == null) Loading()
        status?.let { s ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Pal.Btn).padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Bugün ekran açık", color = Pal.Sub, fontSize = 13.sp, modifier = Modifier.weight(1f))
                Text(
                    TimerText.duration(used) + if (s.config.limitMin > 0) " / ${TimerText.duration(s.config.limitMin * 60L)}" else "",
                    color = Pal.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold
                )
            }
            Spacer(Modifier.height(16.dp))
        }
        SectionTitle("Günlük sınır")
        ChoiceGrid(
            listOf(0 to "Kapalı", 60 to "1 sa", 120 to "2 sa", 180 to "3 sa", 240 to "4 sa", 360 to "6 sa"),
            limit, enabled = !busy && status != null
        ) { limit = it; result = null }
        Spacer(Modifier.height(8.dp))
        SectionTitle("Gece kapalı (sabah 7'ye kadar)")
        ChoiceGrid(
            listOf(-1 to "Kapalı", 21 * 60 to "21:00", 22 * 60 to "22:00", 23 * 60 to "23:00", 0 to "00:00"),
            night, enabled = !busy && status != null
        ) { night = it; result = null }
        Spacer(Modifier.height(8.dp))
        WatcherLine(status)
        Spacer(Modifier.height(10.dp))
        Note(
            "Sınır dolunca ya da gece saatlerinde TV uyku moduna geçer; yeniden açılırsa yarım dakika içinde yine kapanır. " +
                "Süre yalnızca ekran açıkken ve bu ayar açıkken sayılır, her gece sıfırlanır. İşi TV'deki nöbetçi yapar."
        )
        Spacer(Modifier.height(16.dp))
        MainButton(if (busy) "Uygulanıyor…" else "Uygula", enabled = !busy && status != null) {
            busy = true; result = null
            val l = limit
            val n = night
            run {
                io { TvWatcher.update { it.copy(limitMin = l, nightFrom = n, nightTo = if (n >= 0) 7 * 60 else -1) } }
                    .onSuccess {
                        status = it
                        result = ToolResult(true, if (l > 0 || n >= 0) "Sınırlar kuruldu" else "Sınırlar kapatıldı")
                    }
                    .onFailure { result = ToolResult(false, errorText(it)) }
                busy = false
            }
        }
        ResultLine(result)
    }
}
