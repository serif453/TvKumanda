package com.serif.tvkumanda

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * irdb'den çevrimiçi kod arama: dizin ve kumanda dosyaları kullanıcı isteyince GitHub'dan indirilir, APK'da irdb verisi
 * yoktur (lisans; bkz. [IrdbCodes]). Kaydedilen kumanda tuş kodlarını kaydın içinde tutar, sonra internet gerekmez.
 */
object IrdbOnline {
    @Volatile private var index: List<IrdbCodes.Brand>? = null

    /** Bir markadan en fazla bu kadar cihaz indirilir (TV'ler önce); bazı markalarda yüzü aşkın cihaz var. */
    const val MAX_DEVICES = 40

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 10_000
            c.readTimeout = 15_000
            c.setRequestProperty("User-Agent", "TvKumanda")
            if (c.responseCode != 200) error("sunucu ${c.responseCode} döndü")
            val bytes = c.inputStream.use { s ->
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(16 * 1024)
                while (true) {
                    val n = s.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > 4_000_000) error("dosya beklenenden büyük")
                }
                out.toByteArray()
            }
            return String(bytes, Charsets.UTF_8)
        } finally {
            c.disconnect()
        }
    }

    suspend fun brands(): List<IrdbCodes.Brand> = index ?: withContext(Dispatchers.IO) {
        IrdbCodes.parseIndex(get(IrdbCodes.BASE + "index")).also { index = it }
    }

    class Result(val sets: List<IrDb.CodeSet>, val skipped: Int, val failed: Int)

    /** Markanın cihazlarını indirir (4'ü aynı anda); aynı güç + ses kodu bir kez. [progress] (biten, toplam). */
    suspend fun download(brand: IrdbCodes.Brand, progress: (Int, Int) -> Unit): Result = withContext(Dispatchers.IO) {
        val devices = brand.devices.take(MAX_DEVICES)
        val total = devices.sumOf { it.paths.size }
        var done = 0
        var failed = 0
        val lock = Any()
        val gate = Semaphore(4)
        val parts = coroutineScope {
            devices.map { d ->
                async {
                    d to d.paths.map { p ->
                        gate.withPermit {
                            val r = runCatching { IrdbCodes.parseCsv(get(IrdbCodes.url(p))) }.getOrNull()
                            synchronized(lock) { done++; if (r == null) failed++; progress(done, total) }
                            r
                        }
                    }.filterNotNull()
                }
            }.awaitAll()
        }
        val seen = HashSet<Pair<String?, String?>>()
        var skipped = 0
        val sets = ArrayList<IrDb.CodeSet>()
        for ((d, files) in parts) {
            if (files.isEmpty()) continue
            val m = IrdbCodes.merge(files)
            skipped += m.skipped
            // Denenebilmesi için en az iki tuş, biri deneme tuşu olmalı
            if (m.keys.size < 2 || IrDb.testKey(IrDb.CodeSet(1, m.keys)) == null) continue
            val set = IrDb.CodeSet(1, m.keys, device = d.label)
            if (seen.add(IrDb.signature(set))) sets.add(set)
        }
        DiagLog.log("irdb ${brand.devices.size} cihazdan ${sets.size} kod seti (${failed} dosya inmedi, $skipped tuş desteklenmiyor)")
        Result(sets, skipped, failed)
    }
}

/** Çevrimiçi (irdb) marka seçimi: dizini indirir, marka seçilince kodlarını indirip [onPick] ile denemeye geçer. */
@Composable
internal fun IrdbPicker(onPick: (IrDb.Brand) -> Unit) {
    val scope = rememberCoroutineScope()
    var brands by remember { mutableStateOf<List<IrdbCodes.Brand>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableStateOf(0f) }
    LaunchedEffect(attempt) {
        error = null
        runCatching { IrdbOnline.brands() }
            .onSuccess { brands = it }
            .onFailure { error = "Liste indirilemedi (${it.message ?: it.javaClass.simpleName}). İnternet bağlantını kontrol et."; DiagLog.log("irdb dizini indirilemedi: ${it.message.orEmpty()}") }
    }
    val list = brands
    when {
        error != null -> {
            ResultLine(ToolResult(false, error!!))
            Spacer(Modifier.height(14.dp))
            SecondaryButton("Tekrar dene", Modifier.fillMaxWidth()) { attempt++ }
        }
        list == null -> Loading("irdb marka listesi indiriliyor…")
        busy != null -> {
            Text("$busy kodları indiriliyor", color = Pal.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(10.dp))
            LinearProgressIndicator(
                progress = { progress }, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(3.dp)),
                color = Navy.Accent, trackColor = Navy.Btn
            )
        }
        else -> {
            var q by remember { mutableStateOf("") }
            var pickError by remember { mutableStateOf<String?>(null) }
            OutlinedTextField(
                value = q, onValueChange = { q = it }, singleLine = true,
                placeholder = { Text("Marka ara (${list.size} marka)", color = Navy.Sub) },
                leadingIcon = { Icon(Icons.Outlined.Search, null, tint = Navy.Sub) },
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Pal.Text, unfocusedTextColor = Pal.Text,
                    focusedBorderColor = Navy.Accent, unfocusedBorderColor = Navy.Border, cursorColor = Navy.Accent
                ),
                modifier = Modifier.fillMaxWidth()
            )
            pickError?.let { ResultLine(ToolResult(false, it)) }
            Spacer(Modifier.height(10.dp))
            val query = q.trim()
            val shown = if (query.isEmpty()) list else list.filter { it.name.contains(query, ignoreCase = true) }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxSize()) {
                if (shown.isEmpty()) item { Text("Bu ad irdb'de yok.", color = Navy.Sub, fontSize = 13.sp) }
                items(shown, key = { it.name }) { b ->
                    val tv = b.devices.count { it.tv }
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Navy.Btn).clickable {
                            pickError = null
                            busy = b.name; progress = 0f
                            scope.launch {
                                val r = runCatching { IrdbOnline.download(b) { d, t -> progress = d.toFloat() / maxOf(t, 1) } }
                                busy = null
                                r.onFailure { pickError = errorText(it) }
                                r.onSuccess { res ->
                                    if (res.sets.isEmpty()) {
                                        pickError = if (res.failed > 0) "${b.name} kodları indirilemedi; internet bağlantını kontrol et."
                                        else "${b.name} kumandalarının kodları uygulamanın gönderebildiği biçimde değil."
                                    } else onPick(IrDb.Brand(b.name, res.sets))
                                }
                            }
                        }.padding(horizontal = 14.dp, vertical = 13.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(b.name, color = Pal.Text, fontSize = 15.sp, modifier = Modifier.weight(1f))
                        Text(if (tv > 0) "$tv TV · ${b.devices.size} cihaz" else "${b.devices.size} cihaz", color = Navy.Sub, fontSize = 12.sp)
                    }
                }
                item {
                    Text(
                        "irdb'de cihaz türü yazmaz: TV, DVD, uydu alıcısı karışıktır. Adında TV geçenler önce denenir. " +
                            "Kodlar internetten indirilir; kaydedilen kumanda internetsiz çalışır.",
                        color = Navy.Sub, fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp)
                    )
                    Text(IrdbCodes.NOTICE, color = Navy.Sub, fontSize = 11.sp, modifier = Modifier.padding(vertical = 10.dp))
                }
            }
        }
    }
}
