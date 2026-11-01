package com.serif.tvkumanda

import android.content.Context
import android.hardware.ConsumerIrManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SettingsRemote
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

/**
 * Kayıtlı bir IR kumanda. [keys] varsa tuş kodları kaydın içindedir (yeni kayıtlar, dosyadan eklenenler);
 * yoksa (eski kayıtlar) [brand] markasının [set] sıradaki kod setine bakılır.
 */
data class IrSaved(val id: String, val name: String, val brand: String, val set: Int, val keys: Map<String, String>? = null)

/** IR sayfaları ve kayıtlı IR kumandalar (TV seçimi penceresinden açılır). */
object IrUi {
    /** null: kapalı, "": yeni kumanda ekle, diğer: açık kumandanın kimliği. */
    var page by mutableStateOf<String?>(null)
    var saved by mutableStateOf<List<IrSaved>>(emptyList())
    private var loaded = false

    private fun prefs(c: Context) = c.getSharedPreferences("ayarlar", Context.MODE_PRIVATE)

    fun load(c: Context) {
        if (loaded) return
        loaded = true
        saved = runCatching {
            val arr = org.json.JSONArray(prefs(c).getString("ir_kumandalar", "[]"))
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                val k = o.optJSONObject("keys")?.let { j -> j.keys().asSequence().associateWith { j.getString(it) } }
                IrSaved(o.getString("id"), o.getString("name"), o.getString("brand"), o.optInt("set", 0), k)
            }
        }.getOrDefault(emptyList())
    }

    private fun store(c: Context) {
        val arr = org.json.JSONArray()
        saved.forEach { r ->
            val o = org.json.JSONObject().put("id", r.id).put("name", r.name).put("brand", r.brand).put("set", r.set)
            r.keys?.let { k -> o.put("keys", org.json.JSONObject(k as Map<*, *>)) }
            arr.put(o)
        }
        prefs(c).edit().putString("ir_kumandalar", arr.toString()).apply()
    }

    fun add(c: Context, r: IrSaved) { saved = saved + r; store(c) }
    fun rename(c: Context, id: String, name: String) { saved = saved.map { if (it.id == id) it.copy(name = name) else it }; store(c) }
    fun remove(c: Context, id: String) { saved = saved.filterNot { it.id == id }; store(c) }
}

/** Telefonun IR vericisi ve kod veritabanı. */
object IrSender {
    private val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    @Volatile private var db: List<IrDb.Brand>? = null
    private var toggle = false

    fun available(c: Context): Boolean = runCatching {
        (c.getSystemService(Context.CONSUMER_IR_SERVICE) as? ConsumerIrManager)?.hasIrEmitter() == true
    }.getOrDefault(false)

    suspend fun brands(c: Context): List<IrDb.Brand> = db ?: withContext(Dispatchers.IO) {
        IrDb.sorted(IrDb.withFamilies(IrDb.parse(c.assets.open("ir/tv.txt").bufferedReader().use { it.readText() }))).also { db = it }
    }

    suspend fun codeSet(c: Context, r: IrSaved): IrDb.CodeSet? =
        r.keys?.let { IrDb.CodeSet(1, it) } ?: brands(c).firstOrNull { it.name.equals(r.brand, true) }?.sets?.getOrNull(r.set)

    /** [newPress] yeni basış (RC5/RC6 değişim biti değişir); basılı tutulunca false gönderilir. */
    suspend fun send(c: Context, code: String, newPress: Boolean = true): Boolean = withContext(dispatcher) {
        runCatching {
            val ir = c.getSystemService(Context.CONSUMER_IR_SERVICE) as ConsumerIrManager
            val parsed = IrCodec.parseCode(code) ?: return@runCatching false
            if (newPress && IrCodec.usesToggle(parsed)) toggle = !toggle
            val s = IrCodec.encode(parsed, toggle)
            ir.transmit(supported(ir, s.carrierHz), s.pattern)
            true
        }.onFailure { DiagLog.log("IR gönderilemedi: ${it.javaClass.simpleName}: ${it.message.orEmpty()}") }.getOrDefault(false)
    }

    /** Telefon bu taşıyıcı frekansını desteklemiyorsa en yakın desteklenen değer. */
    private fun supported(ir: ConsumerIrManager, hz: Int): Int {
        val ranges = runCatching { ir.carrierFrequencies }.getOrNull()?.takeIf { it.isNotEmpty() } ?: return hz
        if (ranges.any { hz in it.minFrequency..it.maxFrequency }) return hz
        return ranges.flatMap { listOf(it.minFrequency, it.maxFrequency) }.minByOrNull { kotlin.math.abs(it - hz) } ?: hz
    }
}

private val keyNames = mapOf(
    "power" to "Güç", "vol_up" to "Sesi aç", "vol_dn" to "Sesi kıs", "mute" to "Sessiz", "ch_up" to "Kanal +", "ch_dn" to "Kanal −",
    "up" to "Yukarı", "down" to "Aşağı", "left" to "Sol", "right" to "Sağ", "ok" to "Tamam", "back" to "Geri", "exit" to "Çıkış",
    "home" to "Ana sayfa", "menu" to "Menü", "settings" to "Ayarlar", "info" to "Bilgi", "source" to "Kaynak", "guide" to "Rehber",
    "play" to "Oynat", "pause" to "Duraklat", "stop" to "Durdur", "play_pause" to "Oynat/Duraklat", "rewind" to "Geri sar",
    "ffwd" to "İleri sar", "netflix" to "Netflix", "youtube" to "YouTube", "subtitle" to "Altyazı"
)

// ---------------- Sayfa yöneticisi ----------------

@Composable
fun IrPageHost() {
    val p = IrUi.page ?: return
    if (p.isEmpty()) { IrAddPage(onClose = { IrUi.page = null }, onSaved = { IrUi.page = it }); return }
    val r = IrUi.saved.firstOrNull { it.id == p }
    if (r != null) IrRemotePage(r) { IrUi.page = null }
    else SideEffect { IrUi.page = null } // unutulan kumanda
}

@Composable
private fun PageFrame(
    title: String, subtitle: String?, onBack: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {}, content: @Composable ColumnScope.() -> Unit
) {
    BackHandler(onBack = onBack)
    Box(
        Modifier.fillMaxSize().background(Navy.Bg)
            // Arkadaki ekrana dokunulmasın
            .pointerInput(Unit) { detectTapGestures { } }
    ) {
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onBack), contentAlignment = Alignment.Center) {
                    Icon(AppIcons.ArrowBack, "Geri", tint = Pal.Text, modifier = Modifier.size(26.dp))
                }
                Spacer(Modifier.width(6.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, color = Pal.Text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (subtitle != null) Text(subtitle, color = Navy.Sub, fontSize = 12.sp)
                }
                actions()
            }
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

// ---------------- Yeni IR kumanda: marka → deneme → ad; tarama; dosyadan ----------------

/** Kaydedilmeyi bekleyen kod seti: [brand] kumandanın markası (ya da dosya adı), [note] ad adımında gösterilir. */
private class IrPending(val brand: String, val setIndex: Int, val keys: Map<String, String>, val note: String)

@Composable
private fun IrAddPage(onClose: () -> Unit, onSaved: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var brands by remember { mutableStateOf<List<IrDb.Brand>?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { runCatching { IrSender.brands(context) }.onSuccess { brands = it }.onFailure { loadError = errorText(it) } }
    var brand by remember { mutableStateOf<IrDb.Brand?>(null) }
    var setIndex by remember { mutableIntStateOf(0) }
    var pending by remember { mutableStateOf<IrPending?>(null) }
    var scanning by remember { mutableStateOf(false) }
    var exhausted by remember { mutableStateOf(false) }
    var sent by remember { mutableStateOf<String?>(null) }
    var fileError by remember { mutableStateOf<String?>(null) }

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            fileError = null
            val r = runCatching {
                withContext(Dispatchers.IO) {
                    val name = context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                        ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
                    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("dosya açılamadı")
                    if (bytes.size > 1_000_000) error("dosya çok büyük, bir kumanda dosyası seç")
                    (name ?: "Kumanda") to IrDb.parseFlipper(String(bytes, Charsets.UTF_8))
                }
            }
            r.onFailure { fileError = errorText(it); DiagLog.log("IR dosyası okunamadı: ${it.message.orEmpty()}") }
            r.onSuccess { (name, imp) ->
                if (imp.keys.isEmpty()) {
                    fileError = "Bu dosyada gönderilebilecek tuş bulunamadı. Flipper Zero'nun .ir kumanda dosyası olmalı."
                } else {
                    val extra = imp.keys.keys.count { it.startsWith(IrDb.EXTRA) }
                    val note = buildString {
                        append("${imp.keys.size} tuş okundu")
                        if (extra > 0) append(" ($extra tanesi \"Diğer tuşlar\"da)")
                        if (imp.skipped > 0) append(", ${imp.skipped} tuş desteklenmeyen biçimde olduğu için atlandı")
                        append('.')
                    }
                    DiagLog.log("IR dosyası eklendi: ${imp.keys.size} tuş, ${imp.skipped} atlandı")
                    pending = IrPending(name.substringBeforeLast('.').ifBlank { "Kumanda" }.take(30), 0, imp.keys, note)
                }
            }
        }
    }

    val b = brand
    val p = pending
    val back: () -> Unit = {
        when {
            p != null -> pending = null
            scanning -> scanning = false
            b != null -> { brand = null; exhausted = false; sent = null }
            else -> onClose()
        }
    }
    val subtitle = when {
        p != null -> "Son adım: adını ver"
        scanning -> "Markamı bilmiyorum"
        b == null -> "1. Markanı seç"
        else -> "2. Kodu dene · ${b.name}"
    }
    PageFrame("IR kumanda ekle", subtitle, back) {
        when {
            loadError != null -> ResultLine(ToolResult(false, loadError!!))
            brands == null -> Loading("Kod listesi açılıyor…")
            p != null -> NameStep(p) { name ->
                val r = IrSaved(System.currentTimeMillis().toString(36), name, p.brand, p.setIndex, p.keys)
                IrUi.add(context, r)
                onSaved(r.id)
            }
            scanning -> PowerScan(brands!!) { cand ->
                pending = IrPending(cand.brand, 0, cand.set.keys, "Güç kodu ${cand.brand} kumandasından. Diğer tuşlar da aynı kumandadan gelir.")
            }
            b == null -> BrandPicker(
                brands!!, fileError,
                onScan = { fileError = null; scanning = true },
                onFile = { fileError = null; runCatching { importer.launch(arrayOf("*/*")) }.onFailure { fileError = "Dosya seçici açılamadı." } }
            ) { brand = it; setIndex = 0; exhausted = false; sent = null; fileError = null }
            else -> {
                val set = b.sets[setIndex]
                val test = IrDb.testKey(set)
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                    Text(
                        "Telefonun üst kenarını TV'ye doğrult ve aşağıdaki tuşa bas. TV tepki verirse \"Çalıştı\"ya dokun.",
                        color = Navy.Sub, fontSize = 14.sp
                    )
                    Spacer(Modifier.height(14.dp))
                    Text("Kod seti ${setIndex + 1} / ${b.sets.size}", color = Pal.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    set.source?.let {
                        Text("$it kumandasından · aynı fabrikanın markası", color = Navy.Sub, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        for (k in listOfNotNull(test, "power".takeIf { it in set.keys && it != test })) {
                            TryButton(keyNames[k] ?: k, if (k == "power") Icons.Filled.PowerSettingsNew else Icons.Filled.VolumeUp, Modifier.weight(1f)) {
                                scope.launch {
                                    val ok = IrSender.send(context, set.keys.getValue(k))
                                    sent = if (ok) "\"${keyNames[k] ?: k}\" sinyali gönderildi" else "Sinyal gönderilemedi"
                                }
                            }
                        }
                    }
                    if (test == "vol_up") Text("Sesi açma TV'yi kapatmaz; güç tuşunu TV kapalıysa dene.", color = Navy.Sub, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                    sent?.let { Text(it, color = Navy.Sub, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp)) }
                    Spacer(Modifier.height(20.dp))
                    if (exhausted) {
                        Text("Bu markanın kodlarından hiçbiri tutmadıysa \"Markamı bilmiyorum\" taramasını dene; TV'ler çoğu zaman başka markanın kumanda dilini kullanır.",
                            color = Pal.Amber, fontSize = 13.sp)
                        Spacer(Modifier.height(10.dp))
                        MainButton("Markamı bilmiyorum, tara") { brand = null; exhausted = false; sent = null; scanning = true }
                        Spacer(Modifier.height(10.dp))
                        SecondaryButton("Başka marka seç", Modifier.fillMaxWidth()) { brand = null; exhausted = false; sent = null }
                        Spacer(Modifier.height(10.dp))
                        SecondaryButton("Baştan dene", Modifier.fillMaxWidth()) { setIndex = 0; exhausted = false; sent = null }
                    } else {
                        MainButton("Çalıştı") {
                            val note = "${b.name} · kod seti ${setIndex + 1}" + (set.source?.let { " ($it kumandası)" } ?: "")
                            pending = IrPending(b.name, setIndex, set.keys, note)
                        }
                        Spacer(Modifier.height(10.dp))
                        SecondaryButton(if (setIndex + 1 < b.sets.size) "Çalışmadı, sonrakini dene" else "Çalışmadı", Modifier.fillMaxWidth()) {
                            sent = null
                            if (setIndex + 1 < b.sets.size) setIndex++ else exhausted = true
                        }
                    }
                    Spacer(Modifier.height(18.dp))
                    Text("Telefon IR'ı yalnızca birkaç metre ve TV'ye bakarken çalışır, tıpkı gerçek kumanda gibi.", color = Navy.Sub, fontSize = 12.sp)
                }
            }
        }
    }
}

/**
 * "Markamı bilmiyorum": veritabanındaki her farklı güç kodunu 1,5 sn arayla gönderir. TV kapanınca kullanıcı durdurur;
 * insanın tepki süresi yüzünden doğru kod son gönderilen ya da bir öncekidir, bu yüzden durunca kodlar elle tek tek
 * denenir (TV kapalıyken doğru güç kodu TV'yi açar).
 */
@Composable
private fun PowerScan(brands: List<IrDb.Brand>, onFound: (IrDb.PowerCandidate) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val list = remember(brands) { IrDb.powerScan(brands) }
    var index by remember { mutableIntStateOf(0) }
    var running by remember { mutableStateOf(false) }
    var started by remember { mutableStateOf(false) }
    var finished by remember { mutableStateOf(false) }
    var sent by remember { mutableStateOf<String?>(null) }

    // Tarama sürerken ekran kararmasın
    val view = LocalView.current
    DisposableEffect(running) {
        view.keepScreenOn = running
        onDispose { view.keepScreenOn = false }
    }
    LaunchedEffect(running) {
        while (running) {
            IrSender.send(context, list[index].set.keys.getValue("power"))
            delay(1500)
            if (!running) break
            if (index + 1 >= list.size) { running = false; finished = true } else index++
        }
    }

    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        val c = list.getOrNull(index)
        when {
            c == null -> ResultLine(ToolResult(false, "Kod listesi boş."))
            !started -> {
                Text("TV açıkken başla ve telefonun üst kenarını TV'ye doğrult. Telefon 1,5 saniyede bir farklı bir " +
                    "markanın güç kodunu gönderir (${list.size} kod, en fazla ${(list.size * 3 + 1) / 2 / 60 + 1} dakika).",
                    color = Navy.Sub, fontSize = 14.sp)
                Spacer(Modifier.height(10.dp))
                Text("TV kapandığı anda \"TV kapandı\"ya bas.", color = Pal.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(20.dp))
                MainButton("Taramayı başlat") { started = true; finished = false; index = 0; running = true }
            }
            running -> {
                Text("Kod ${index + 1} / ${list.size}", color = Pal.Text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Text(c.brand, color = Navy.Sub, fontSize = 14.sp)
                Spacer(Modifier.height(10.dp))
                LinearProgressIndicator(
                    progress = { (index + 1f) / list.size }, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(3.dp)),
                    color = Navy.Accent, trackColor = Navy.Btn
                )
                Spacer(Modifier.height(24.dp))
                MainButton("TV kapandı", danger = true) { running = false; sent = null }
                Spacer(Modifier.height(10.dp))
                SecondaryButton("Durdur", Modifier.fillMaxWidth()) { running = false; sent = null }
            }
            finished -> {
                Text("Hiçbir kod TV'yi kapatmadı.", color = Pal.Amber, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Text("Telefonun TV'ye yakın ve dönük olduğundan emin ol. Bu TV'nin kodu veritabanında olmayabilir; " +
                    "kumandanın Flipper dosyası varsa \"Dosyadan ekle\" ile eklenebilir.", color = Navy.Sub, fontSize = 13.sp)
                Spacer(Modifier.height(16.dp))
                MainButton("Baştan tara") { finished = false; index = 0; running = true }
                Spacer(Modifier.height(10.dp))
                SecondaryButton("Durduğun yerdeki kodları elle dene", Modifier.fillMaxWidth()) { finished = false }
            }
            else -> {
                Text("Doğru kodu bulalım", color = Pal.Text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Text("TV'yi kapatan kod genelde son gönderilen ya da bir öncekidir. TV kapalıyken \"Gönder\"e bas: " +
                    "doğru kod TV'yi açar. Açmazsa \"Önceki\" ile bir geri git.", color = Navy.Sub, fontSize = 14.sp)
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SecondaryButton("Önceki", Modifier.weight(1f), enabled = index > 0) { index--; sent = null }
                    Column(Modifier.weight(1.3f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Kod ${index + 1} / ${list.size}", color = Pal.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Text(c.brand, color = Navy.Sub, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    SecondaryButton("Sonraki", Modifier.weight(1f), enabled = index + 1 < list.size) { index++; sent = null }
                }
                Spacer(Modifier.height(12.dp))
                TryButton("Gönder", Icons.Filled.PowerSettingsNew, Modifier.fillMaxWidth()) {
                    scope.launch {
                        val ok = IrSender.send(context, c.set.keys.getValue("power"))
                        sent = if (ok) "Kod ${index + 1} gönderildi" else "Sinyal gönderilemedi"
                    }
                }
                sent?.let { Text(it, color = Navy.Sub, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp)) }
                Spacer(Modifier.height(18.dp))
                MainButton("Çalıştı") { onFound(c) }
                Spacer(Modifier.height(10.dp))
                SecondaryButton("Taramaya buradan devam et", Modifier.fillMaxWidth()) { sent = null; running = true }
            }
        }
    }
}

@Composable
private fun TryButton(text: String, icon: ImageVector, modifier: Modifier, onClick: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    Column(
        modifier.height(96.dp).clip(RoundedCornerShape(20.dp)).background(Navy.AccentSoft)
            .border(1.dp, Navy.Accent, RoundedCornerShape(20.dp))
            .clickable { haptic.performHapticFeedback(HapticFeedbackType.LongPress); onClick() },
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center
    ) {
        Icon(icon, null, tint = Pal.Text, modifier = Modifier.size(30.dp))
        Spacer(Modifier.height(6.dp))
        Text(text, color = Pal.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun BrandPicker(
    brands: List<IrDb.Brand>, fileError: String?, onScan: () -> Unit, onFile: () -> Unit, onPick: (IrDb.Brand) -> Unit
) {
    var q by remember { mutableStateOf("") }
    OutlinedTextField(
        value = q, onValueChange = { q = it }, singleLine = true,
        placeholder = { Text("Marka ara", color = Navy.Sub) },
        leadingIcon = { Icon(Icons.Outlined.Search, null, tint = Navy.Sub) },
        shape = RoundedCornerShape(14.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = Pal.Text, unfocusedTextColor = Pal.Text,
            focusedBorderColor = Navy.Accent, unfocusedBorderColor = Navy.Border, cursorColor = Navy.Accent
        ),
        modifier = Modifier.fillMaxWidth()
    )
    Spacer(Modifier.height(10.dp))
    val query = q.trim()
    val list = if (query.isEmpty()) brands else brands.filter { it.name.contains(query, ignoreCase = true) }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxSize()) {
        if (query.isEmpty()) {
            item(key = "_tara") { OptionRow(Icons.Filled.Search, "Markamı bilmiyorum", "Tüm güç kodlarını sırayla dener", onScan) }
            item(key = "_dosya") { OptionRow(Icons.Outlined.FileOpen, "Dosyadan ekle", "Flipper Zero kumanda dosyası (.ir)", onFile) }
            if (fileError != null) item(key = "_hata") { ResultLine(ToolResult(false, fileError)) }
        }
        if (list.isEmpty()) item {
            Column(Modifier.padding(vertical = 8.dp)) {
                Text("Bu marka listede yok. \"Markamı bilmiyorum\" taraması TV'nin kullandığı kodu bulabilir.",
                    color = Navy.Sub, fontSize = 13.sp)
                Spacer(Modifier.height(10.dp))
                SecondaryButton("Markamı bilmiyorum, tara", Modifier.fillMaxWidth(), onClick = onScan)
            }
        }
        items(list, key = { it.name }) { b ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Navy.Btn).clickable { onPick(b) }
                    .padding(horizontal = 14.dp, vertical = 13.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(b.name, color = Pal.Text, fontSize = 15.sp, modifier = Modifier.weight(1f))
                val n = if (b.sets.size == 1) "1 kod" else "${b.sets.size} kod"
                Text(if (b.familyOnly) "$n · benzer markalardan" else n, color = Navy.Sub, fontSize = 12.sp)
            }
        }
        item {
            Text("Kodlar: Flipper-IRDB (serbest lisans). Aynı fabrikadan çıkan markalar (ör. Vestel, Telefunken, Finlux; " +
                "Arçelik, Beko, Grundig) birbirinin kodlarını da dener.",
                color = Navy.Sub, fontSize = 11.sp, modifier = Modifier.padding(vertical = 10.dp))
        }
    }
}

@Composable
private fun OptionRow(icon: ImageVector, title: String, sub: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Navy.AccentSoft).clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = Navy.Accent, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = Pal.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(sub, color = Navy.Sub, fontSize = 12.sp)
        }
    }
}

@Composable
private fun NameStep(p: IrPending, onSave: (String) -> Unit) {
    var name by remember { mutableStateOf("${p.brand} TV") }
    Text("Kumanda listesinde bu ad görünür, örneğin \"Yatak odası TV\".", color = Navy.Sub, fontSize = 14.sp)
    Spacer(Modifier.height(12.dp))
    OutlinedTextField(
        value = name, onValueChange = { name = it }, singleLine = true, shape = RoundedCornerShape(14.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = Pal.Text, unfocusedTextColor = Pal.Text,
            focusedBorderColor = Navy.Accent, unfocusedBorderColor = Navy.Border, cursorColor = Navy.Accent
        ),
        modifier = Modifier.fillMaxWidth()
    )
    Spacer(Modifier.height(8.dp))
    Text(p.note, color = Navy.Sub, fontSize = 12.sp)
    Spacer(Modifier.height(16.dp))
    MainButton("Kaydet ve aç", enabled = name.isNotBlank()) { onSave(name.trim()) }
}

// ---------------- IR kumanda ekranı ----------------

@Composable
private fun IrRemotePage(r: IrSaved, onClose: () -> Unit) {
    val context = LocalContext.current
    var set by remember(r.id) { mutableStateOf<IrDb.CodeSet?>(null) }
    var missing by remember(r.id) { mutableStateOf(false) }
    LaunchedEffect(r.id) { val s = runCatching { IrSender.codeSet(context, r) }.getOrNull(); set = s; missing = s == null }
    var digits by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var forgetAsk by remember { mutableStateOf(false) }

    PageFrame(r.name, "IR kumanda · ${r.brand}", onClose, actions = {
        Box {
            Box(Modifier.size(44.dp).clip(CircleShape).clickable { menu = true }, contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.MoreVert, "Diğer", tint = Navy.Sub)
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = Navy.Btn) {
                DropdownMenuItem(text = { Text("Yeniden adlandır", color = Pal.Text) }, onClick = { menu = false; renaming = true })
                DropdownMenuItem(text = { Text("Unut", color = Pal.Red) }, onClick = { menu = false; forgetAsk = true })
            }
        }
    }) {
        val s = set
        when {
            missing -> ResultLine(ToolResult(false, "Bu kumandanın kodları bulunamadı; silip yeniden ekle."))
            s == null -> Loading()
            else -> IrKeys(s, digits) { digits = !digits }
        }
    }
    if (renaming) {
        var text by remember { mutableStateOf(r.name) }
        AlertDialog(
            onDismissRequest = { renaming = false }, containerColor = Navy.Card,
            title = { Text("Kumandanın adı", color = Pal.Text) },
            text = {
                OutlinedTextField(
                    value = text, onValueChange = { text = it }, singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Pal.Text, unfocusedTextColor = Pal.Text,
                        focusedBorderColor = Navy.Accent, unfocusedBorderColor = Navy.Border, cursorColor = Navy.Accent)
                )
            },
            confirmButton = { TextButton(onClick = { if (text.isNotBlank()) IrUi.rename(context, r.id, text.trim()); renaming = false }) { Text("Kaydet", color = Navy.Accent) } },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("Vazgeç", color = Navy.Sub) } }
        )
    }
    if (forgetAsk) AlertDialog(
        onDismissRequest = { forgetAsk = false }, containerColor = Navy.Card,
        title = { Text("Kumanda unutulsun mu?", color = Pal.Text) },
        text = { Text("\"${r.name}\" IR kumandalardan kaldırılır.", color = Navy.Sub) },
        confirmButton = { TextButton(onClick = { forgetAsk = false; onClose(); IrUi.remove(context, r.id) }) { Text("Unut", color = Pal.Red) } },
        dismissButton = { TextButton(onClick = { forgetAsk = false }) { Text("Vazgeç", color = Navy.Sub) } }
    )
}

@Composable
private fun IrKeys(set: IrDb.CodeSet, digits: Boolean, onDigits: () -> Unit) {
    val k = set.keys
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
        // Güç, kaynak, sessiz
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            IrRound(k, "power", Icons.Filled.PowerSettingsNew, tint = Pal.Red)
            IrRound(k, "source", Icons.Filled.Input)
            IrRound(k, "mute", Icons.Filled.VolumeOff)
        }
        Spacer(Modifier.height(18.dp))
        // Ses | yön tuşları | kanal
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            IrRocker(k, "vol_up", "vol_dn", "SES")
            IrDpad(k)
            IrRocker(k, "ch_up", "ch_dn", "KANAL")
        }
        Spacer(Modifier.height(18.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            IrRound(k, "back", AppIcons.ArrowBack, small = true)
            IrRound(k, "home", Icons.Filled.Home, small = true)
            IrRound(k, "menu", Icons.Filled.Menu, small = true)
            IrRound(k, if ("exit" in k || "settings" !in k) "exit" else "settings", if ("exit" in k || "settings" !in k) Icons.Filled.Close else Icons.Filled.Settings, small = true)
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            IrRound(k, "info", Icons.Filled.Info, small = true)
            IrRound(k, "guide", Icons.Filled.ViewList, small = true)
            IrRound(k, "subtitle", Icons.Filled.Subtitles, small = true)
            Box(
                Modifier.size(52.dp).clip(CircleShape).background(if (digits) Navy.AccentSoft else Navy.Btn).clickable(onClick = onDigits),
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Filled.Dialpad, "Rakamlar", tint = Pal.Text) }
        }
        if (digits) {
            Spacer(Modifier.height(14.dp))
            for (row in listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("", "0", ""))) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    for (d in row) if (d.isEmpty()) Spacer(Modifier.size(64.dp, 52.dp)) else IrText(k, d, d, Modifier.size(64.dp, 52.dp))
                }
                Spacer(Modifier.height(8.dp))
            }
        }
        // Oynatma tuşları (varsa)
        val media = listOf("rewind" to Icons.Filled.FastRewind, "play_pause" to Icons.Filled.PlayArrow, "play" to Icons.Filled.PlayArrow,
            "pause" to Icons.Filled.Pause, "stop" to Icons.Filled.Stop, "ffwd" to Icons.Filled.FastForward)
            .filter { it.first in k }.let { l -> if ("play_pause" in k) l.filter { it.first != "play" && it.first != "pause" } else l }
        if (media.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                for ((name, icon) in media) IrRound(k, name, icon, small = true)
            }
        }
        // Renkli tuşlar
        val colors = listOf("red" to Color(0xFFE5484D), "green" to Color(0xFF30A46C), "yellow" to Color(0xFFF5D90A), "blue" to Color(0xFF3E63DD)).filter { it.first in k }
        if (colors.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                for ((name, c) in colors) IrKey(k, name, Modifier.size(56.dp, 26.dp).clip(RoundedCornerShape(13.dp)).background(c)) {}
            }
        }
        // Uygulama tuşları
        val apps = listOf("netflix", "youtube").filter { it in k }
        if (apps.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                for (a in apps) IrText(k, a, keyNames[a] ?: a, Modifier.weight(1f).height(48.dp))
            }
        }
        // Dosyadan eklenen kumandada uygulamanın tanımadığı tuşlar, dosyadaki adlarıyla
        val extras = k.keys.filter { it.startsWith(IrDb.EXTRA) }
        if (extras.isNotEmpty()) {
            Spacer(Modifier.height(18.dp))
            Text("DİĞER TUŞLAR", color = Navy.Sub, fontSize = 12.sp, letterSpacing = 1.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            for (row in extras.chunked(3)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (x in row) IrText(k, x, x.removePrefix(IrDb.EXTRA).replace('_', ' '), Modifier.weight(1f).height(48.dp), small = true)
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
                Spacer(Modifier.height(8.dp))
            }
        }
        Spacer(Modifier.height(18.dp))
        Text("Soluk tuşlar bu kod setinde yok. Telefonun üst kenarını TV'ye doğrult.", color = Navy.Sub, fontSize = 12.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(24.dp))
    }
}

/**
 * Tek IR tuşu: dokununca bir kez gönderir; basılı tutulursa (ses, kanal, yön) aynı basış olarak tekrar eder.
 * Kod setinde olmayan tuş soluk görünür ve çalışmaz.
 */
@Composable
private fun IrKey(keys: Map<String, String>, name: String, modifier: Modifier, repeat: Boolean = false, content: @Composable BoxScope.() -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val code = keys[name]
    var pressed by remember { mutableStateOf(false) }
    Box(
        modifier.alpha(if (code == null) 0.3f else if (pressed) 0.6f else 1f)
            .then(if (code == null) Modifier else Modifier.pointerInput(code) {
                detectTapGestures(onPress = {
                    pressed = true
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    var job: Job? = null
                    scope.launch {
                        IrSender.send(context, code, newPress = true)
                    }
                    if (repeat) job = scope.launch {
                        delay(450)
                        while (isActive) { IrSender.send(context, code, newPress = false); delay(120) }
                    }
                    tryAwaitRelease()
                    job?.cancel()
                    pressed = false
                })
            }),
        contentAlignment = Alignment.Center,
        content = content
    )
}

@Composable
private fun IrRound(keys: Map<String, String>, name: String, icon: ImageVector, tint: Color = Pal.Text, small: Boolean = false) {
    val size = if (small) 52.dp else 64.dp
    IrKey(keys, name, Modifier.size(size).clip(CircleShape).background(Navy.Btn).border(1.dp, Navy.Border, CircleShape)) {
        Icon(icon, keyNames[name] ?: name, tint = tint, modifier = Modifier.size(if (small) 24.dp else 28.dp))
    }
}

@Composable
private fun IrText(keys: Map<String, String>, name: String, label: String, modifier: Modifier, small: Boolean = false) {
    IrKey(keys, name, modifier.clip(RoundedCornerShape(14.dp)).background(Navy.Btn).border(1.dp, Navy.Border, RoundedCornerShape(14.dp))) {
        Text(label, color = Pal.Text, fontSize = if (small) 13.sp else 18.sp, fontWeight = FontWeight.Medium,
            maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 4.dp))
    }
}

/** Dikey + / − tuşu (ses, kanal). */
@Composable
private fun IrRocker(keys: Map<String, String>, up: String, down: String, label: String) {
    Column(
        Modifier.width(64.dp).clip(RoundedCornerShape(32.dp)).background(Navy.Btn).border(1.dp, Navy.Border, RoundedCornerShape(32.dp)),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        IrKey(keys, up, Modifier.size(64.dp), repeat = true) { Icon(Icons.Filled.Add, keyNames[up], tint = Pal.Text, modifier = Modifier.size(28.dp)) }
        Text(label, color = Navy.Sub, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        IrKey(keys, down, Modifier.size(64.dp), repeat = true) { Icon(Icons.Filled.Remove, keyNames[down], tint = Pal.Text, modifier = Modifier.size(28.dp)) }
    }
}

@Composable
private fun IrDpad(keys: Map<String, String>) {
    Box(Modifier.size(180.dp).clip(CircleShape).background(Navy.Btn).border(1.dp, Navy.Border, CircleShape)) {
        IrKey(keys, "up", Modifier.align(Alignment.TopCenter).size(64.dp, 56.dp), repeat = true) { Icon(Icons.Filled.KeyboardArrowUp, "Yukarı", tint = Pal.Text, modifier = Modifier.size(32.dp)) }
        IrKey(keys, "down", Modifier.align(Alignment.BottomCenter).size(64.dp, 56.dp), repeat = true) { Icon(Icons.Filled.KeyboardArrowDown, "Aşağı", tint = Pal.Text, modifier = Modifier.size(32.dp)) }
        IrKey(keys, "left", Modifier.align(Alignment.CenterStart).size(56.dp, 64.dp), repeat = true) { Icon(Icons.Filled.KeyboardArrowLeft, "Sol", tint = Pal.Text, modifier = Modifier.size(32.dp)) }
        IrKey(keys, "right", Modifier.align(Alignment.CenterEnd).size(56.dp, 64.dp), repeat = true) { Icon(Icons.Filled.KeyboardArrowRight, "Sağ", tint = Pal.Text, modifier = Modifier.size(32.dp)) }
        IrKey(keys, "ok", Modifier.align(Alignment.Center).size(68.dp).clip(CircleShape).background(Navy.AccentSoft)) {
            Text("OK", color = Pal.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

// ---------------- TV seçimi penceresindeki bölüm ----------------

/** "IR KUMANDALAR" bölümü: kayıtlı IR kumandalar ve "IR kumanda ekle". Telefonda IR yoksa hiç görünmez. */
@Composable
fun IrSection(onOpen: (String) -> Unit) {
    val context = LocalContext.current
    val available = remember { IrSender.available(context) }
    if (!available) return
    LaunchedEffect(Unit) { IrUi.load(context) }
    Spacer(Modifier.height(18.dp))
    Text("IR KUMANDALAR", color = Navy.Sub, fontSize = 12.sp, letterSpacing = 1.sp, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(8.dp))
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        for (r in IrUi.saved) {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Navy.Btn)
                    .border(1.dp, Navy.Border, RoundedCornerShape(18.dp)).clickable { onOpen(r.id) }
                    .padding(horizontal = 14.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Outlined.SettingsRemote, null, tint = Pal.Text, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(r.name, color = Pal.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("IR · ${r.brand} · Wi-Fi gerekmez", color = Navy.Sub, fontSize = 12.sp)
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Navy.AccentSoft).clickable { onOpen("") }
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Filled.Add, null, tint = Navy.Accent, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text("IR kumanda ekle", color = Navy.Accent, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}
