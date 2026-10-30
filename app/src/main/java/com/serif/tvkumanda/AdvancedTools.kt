package com.serif.tvkumanda

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

// ---------------- Özel DNS (pencere) ----------------

@Composable
internal fun DnsTool() {
    val run = rememberRunner()
    var current by remember { mutableStateOf<String?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var choice by remember { mutableStateOf<String?>(null) }   // seçilen adres (null = otomatik)
    var custom by remember { mutableStateOf("") }
    var customSel by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var stage by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<ToolResult?>(null) }
    LaunchedEffect(Unit) {
        io { TvAdvanced.currentDns() }.onSuccess { h ->
            current = h; choice = h
            if (h != null && TvAdvanced.dnsOptions.none { it.host == h }) { customSel = true; custom = h }
        }.onFailure { result = ToolResult(false, errorText(it)) }
        loaded = true
    }
    if (!loaded) { Loading(); return }
    Text("Şu an: ${current ?: "Otomatik"}", color = Pal.Sub, fontSize = 13.sp)
    Spacer(Modifier.height(10.dp))
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (o in TvAdvanced.dnsOptions) {
            OptionRow(o.label, o.note, selected = !customSel && choice == o.host) { customSel = false; choice = o.host; result = null }
        }
        OptionRow("Özel adres", "Kendi DNS adresini yaz", selected = customSel) { customSel = true; result = null }
        if (customSel) {
            OutlinedTextField(
                value = custom, onValueChange = { custom = it.trim() }, singleLine = true,
                placeholder = { Text("ör. dns.nextdns.io", color = Pal.Sub) },
                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Pal.Text, unfocusedTextColor = Pal.Text,
                    focusedBorderColor = Pal.Blue, unfocusedBorderColor = Pal.Sub.copy(alpha = 0.5f), cursorColor = Pal.Blue
                )
            )
        }
    }
    Spacer(Modifier.height(8.dp))
    Text("Uyguladıktan sonra TV internete çıkamazsa eski ayara kendiliğinden döner.", color = Pal.Sub, fontSize = 12.sp)
    Spacer(Modifier.height(14.dp))
    val target = if (customSel) custom.ifBlank { null } else choice
    val canApply = !busy && !(customSel && custom.isBlank()) && target != current
    if (busy) { Progress(stage, -1f); Spacer(Modifier.height(10.dp)) }
    ConfirmButton(if (busy) "Uygulanıyor…" else "Uygula", enabled = canApply) {
        busy = true; result = null; stage = "Uygulanıyor…"
        run {
            io { TvAdvanced.applyDns(target) { s -> stage = s } }
                .onSuccess { ok ->
                    if (ok) { current = target; result = ToolResult(true, "DNS: ${target ?: "Otomatik"}") }
                    else result = ToolResult(false, "Bu DNS ile TV internete çıkamadı, eski ayara dönüldü")
                }
                .onFailure { result = ToolResult(false, errorText(it)) }
            busy = false
        }
    }
    ResultLine(result)
}

@Composable
private fun OptionRow(title: String, note: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape).background(if (selected) Pal.Blue.copy(alpha = 0.16f) else Pal.Btn)
            .border(1.dp, if (selected) Pal.Blue else Color.Transparent, shape).clickable(onClick = onClick).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Pal.Text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text(note, color = Pal.Sub, fontSize = 12.sp)
        }
        Icon(if (selected) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked, null, tint = if (selected) Pal.Blue else Pal.Sub)
    }
}

// ---------------- Ana ekran değiştir (pencere) ----------------

@Composable
internal fun HomeTool() {
    val run = rememberRunner()
    var data by remember { mutableStateOf<Result<Pair<List<TvAdvanced.Launcher>, String?>>?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var result by remember { mutableStateOf<ToolResult?>(null) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(reload) {
        val labels = io { TvFiles.packages().associate { it.pkg to it.label } }.getOrDefault(emptyMap())
        data = io { TvAdvanced.launchers(labels) }
    }
    val d = data
    when {
        d == null -> Loading()
        d.isFailure -> ResultLine(ToolResult(false, errorText(d.exceptionOrNull()!!)))
        else -> {
            val (list, current) = d.getOrThrow()
            val currentPkg = current?.substringBefore('/')
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (l in list) {
                    OptionRow(
                        l.label + if (TvAdvanced.isStock(l.pkg)) " (fabrika)" else "",
                        l.pkg, selected = l.pkg == currentPkg
                    ) {
                        if (l.pkg == currentPkg || busy) return@OptionRow
                        busy = true; result = null
                        run {
                            io { TvAdvanced.setHome(l.component) }
                                .onSuccess { result = ToolResult(true, "Ana ekran: ${l.label}"); reload++ }
                                .onFailure { result = ToolResult(false, errorText(it)) }
                            busy = false
                        }
                    }
                }
            }
            if (list.count { !TvAdvanced.isStock(it.pkg) } == 0) {
                Spacer(Modifier.height(10.dp))
                Text("TV'de başka bir ana ekran uygulaması kurulu değil.", color = Pal.Sub, fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
                SecondaryButton("TV'de Play Store'da ana ekranları aç", Modifier.fillMaxWidth()) {
                    run { io { TvAdvanced.openLauncherStore() }.onSuccess { result = ToolResult(true, "TV'de Play Store açıldı") } }
                }
            }
            val stock = list.firstOrNull { TvAdvanced.isStock(it.pkg) }
            if (stock != null && stock.pkg != currentPkg) {
                Spacer(Modifier.height(12.dp))
                MainButton("Fabrika ana ekranına dön", enabled = !busy) {
                    busy = true
                    run {
                        io { TvAdvanced.setHome(stock.component) }.onSuccess { result = ToolResult(true, "Fabrika ana ekranına dönüldü"); reload++ }
                            .onFailure { result = ToolResult(false, errorText(it)) }
                        busy = false
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Text("Bazı TV'ler Ana Sayfa tuşunu kendi ana ekranına kilitler; seçim o TV'lerde işe yaramayabilir.", color = Pal.Sub, fontSize = 12.sp)
            ResultLine(result)
        }
    }
}

// ---------------- Gereksiz uygulamaları kapat (tam ekran) ----------------

@Composable
internal fun DebloatPage(onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    val context = LocalContext.current
    val run = rememberRunner()
    var reload by remember { mutableIntStateOf(0) }
    var state by remember { mutableStateOf<Result<TvAdvanced.BloatState>?>(null) }
    var ram by remember { mutableStateOf<List<TvAdvanced.RamApp>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<ToolResult?>(null) }
    LaunchedEffect(reload) {
        state = io { TvAdvanced.bloatState() }
        ram = io { TvAdvanced.topRam() }.getOrDefault(emptyList())
    }
    val labels = remember(reload) { mutableStateMapOf<String, String>() }
    LaunchedEffect(Unit) { io { TvFiles.packages() }.onSuccess { l -> l.forEach { labels[it.pkg] = it.label } } }

    fun toggle(pkg: String, off: Boolean, okMsg: String) {
        busy = true; result = null
        run {
            io { TvAdvanced.setDisabled(context, pkg, off) }.onSuccess { result = ToolResult(true, okMsg); reload++ }
                .onFailure { result = ToolResult(false, errorText(it)) }
            busy = false
        }
    }

    PageScaffold("Gereksiz uygulamaları kapat", onClose) {
        val s = state
        when {
            s == null -> Loading()
            s.isFailure -> ResultLine(ToolResult(false, errorText(s.exceptionOrNull()!!)))
            else -> {
                val items = s.getOrThrow().items
                val pending = items.filter { (b, off) -> b.recommended && !off }
                val disabledByUs = TvAdvanced.DisabledLog.get(context)
                Text("Kapatılan uygulamalar silinmez, yalnızca devre dışı kalır; istediğin an geri açılır.", color = Pal.Sub, fontSize = 13.sp)
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.weight(1.4f)) {
                        ConfirmButton(if (pending.isEmpty()) "Önerilenler kapalı" else "Önerilenleri kapat (${pending.size})", enabled = !busy && pending.isNotEmpty()) {
                            busy = true; result = null
                            run {
                                val r = io { pending.forEach { (b, _) -> TvAdvanced.setDisabled(context, b.pkg, true) } }
                                result = r.fold({ ToolResult(true, "${pending.size} uygulama kapatıldı") }, { ToolResult(false, errorText(it)) })
                                busy = false; reload++
                            }
                        }
                    }
                    SecondaryButton("Hepsini geri aç", Modifier.weight(1f), enabled = !busy && disabledByUs.isNotEmpty()) {
                        busy = true; result = null
                        run {
                            val r = io { disabledByUs.forEach { TvAdvanced.setDisabled(context, it, false) } }
                            result = r.fold({ ToolResult(true, "Hepsi geri açıldı") }, { ToolResult(false, errorText(it)) })
                            busy = false; reload++
                        }
                    }
                }
                ResultLine(result)
                Spacer(Modifier.height(12.dp))
                LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (items.isEmpty()) item { Text("Bu TV'de listedeki uygulamalardan hiçbiri kurulu değil.", color = Pal.Sub, fontSize = 14.sp) }
                    items(items, key = { it.first.pkg }) { (b, off) ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Pal.Btn).padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(b.label, color = if (off) Pal.Sub else Pal.Text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                                    if (b.recommended) {
                                        Spacer(Modifier.width(6.dp))
                                        Text("önerilen", color = Pal.Green, fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
                                            modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(Pal.Green.copy(alpha = 0.15f)).padding(horizontal = 5.dp, vertical = 1.dp))
                                    }
                                }
                                Text(b.note, color = Pal.Sub, fontSize = 12.sp)
                            }
                            Switch(
                                checked = !off, enabled = !busy,
                                onCheckedChange = { on -> toggle(b.pkg, !on, if (on) "${b.label} açıldı" else "${b.label} kapatıldı") },
                                colors = SwitchDefaults.colors(checkedTrackColor = Pal.Green, checkedThumbColor = Color.White, uncheckedTrackColor = Pal.Bg, uncheckedThumbColor = Pal.Sub)
                            )
                        }
                    }
                    if (ram.isNotEmpty()) {
                        item {
                            Spacer(Modifier.height(10.dp))
                            Text("Şu an en çok bellek kullananlar", color = Pal.Sub, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            Text("Kapatmak yerine durdurabilirsin; açınca yeniden çalışırlar.", color = Pal.Sub, fontSize = 12.sp)
                        }
                        items(ram, key = { "ram_" + it.pkg }) { r ->
                            Row(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Pal.Btn).padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(labels[r.pkg] ?: appName(r.pkg), color = Pal.Text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(TvTools.mb(r.kb), color = Pal.Sub, fontSize = 12.sp)
                                }
                                Text(
                                    "Durdur", color = Pal.Amber, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable(enabled = !busy) {
                                        run { io { TvFiles.forceStop(TvPackage(r.pkg, r.pkg, false, false, "", "", 0)) }; reload++ }
                                    }.padding(horizontal = 10.dp, vertical = 6.dp)
                                )
                            }
                        }
                    }
                    item { Spacer(Modifier.height(24.dp)) }
                }
            }
        }
    }
}

// ---------------- ADB komut satırı (tam ekran) ----------------

private val quickCommands = listOf(
    "Sistem" to "getprop ro.product.model; getprop ro.build.version.release",
    "Boş alan" to "df -h /data",
    "İşlemler" to "top -n 1 -b | head -20",
    "Uygulamalar" to "pm list packages -3",
    "Ağ" to "ip -4 addr show"
)

@Composable
internal fun TerminalPage(onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var cmd by remember { mutableStateOf("") }
    val log = remember { mutableStateListOf<Pair<String, String>>() }        // komut → çıktı
    val history = remember { mutableStateListOf<String>() }
    var hIndex by remember { mutableIntStateOf(-1) }
    var busy by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    fun exec(c: String) {
        val t = c.trim(); if (t.isEmpty() || busy) return
        busy = true; cmd = ""; hIndex = -1
        if (history.lastOrNull() != t) history += t
        scope.launch {
            val out = io { TvAdvanced.run(t) }.getOrElse { "Hata: ${it.message}" }
            log += t to out
            busy = false
            listState.animateScrollToItem(log.size)
        }
    }

    PageScaffold("ADB komut satırı", onClose, actions = {
        Box(Modifier.size(44.dp).clip(CircleShape).clickable {
            clipboard.setText(AnnotatedString(log.joinToString("\n") { "$ ${it.first}\n${it.second}" }))
            android.widget.Toast.makeText(context, "Kopyalandı", android.widget.Toast.LENGTH_SHORT).show()
        }, contentAlignment = Alignment.Center) { Icon(Icons.Outlined.ContentCopy, "Kopyala", tint = Pal.Sub, modifier = Modifier.size(22.dp)) }
        Box(Modifier.size(44.dp).clip(CircleShape).clickable { log.clear() }, contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.DeleteSweep, "Temizle", tint = Pal.Sub, modifier = Modifier.size(22.dp))
        }
    }) {
        Text("İleri kullanıcılar için. Komutlar TV'de ADB kabuğunda çalışır (en fazla 30 sn).", color = Pal.Amber, fontSize = 12.sp)
        Spacer(Modifier.height(8.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((label, c) in quickCommands) {
                Text(label, color = Pal.Text, fontSize = 13.sp,
                    modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(Pal.Btn).clickable(enabled = !busy) { exec(c) }.padding(horizontal = 12.dp, vertical = 7.dp))
            }
        }
        Spacer(Modifier.height(8.dp))
        Box(Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0xFF0E0E10)).padding(10.dp)) {
            SelectionContainer {
                LazyColumn(state = listState) {
                    if (log.isEmpty()) item { Text("Komut yaz ya da yukarıdan bir hızlı komut seç.", color = Pal.Sub, fontSize = 12.sp, fontFamily = FontFamily.Monospace) }
                    items(log.size) { i ->
                        val (c, o) = log[i]
                        Text("$ $c", color = Pal.Green, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                        Text(o.ifBlank { "(çıktı yok)" }, color = Pal.Text, fontSize = 12.sp, fontFamily = FontFamily.Monospace, lineHeight = 15.sp)
                        Spacer(Modifier.height(8.dp))
                    }
                    if (busy) item { Text("Çalışıyor…", color = Pal.Sub, fontSize = 12.sp, fontFamily = FontFamily.Monospace) }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(CircleShape).clickable(enabled = history.isNotEmpty()) {
                hIndex = if (hIndex < 0) history.lastIndex else (hIndex - 1).coerceAtLeast(0); cmd = history[hIndex]
            }, contentAlignment = Alignment.Center) { Icon(Icons.Outlined.KeyboardArrowUp, "Önceki", tint = Pal.Sub) }
            Box(Modifier.size(40.dp).clip(CircleShape).clickable(enabled = hIndex >= 0) {
                hIndex = (hIndex + 1); if (hIndex > history.lastIndex) { hIndex = -1; cmd = "" } else cmd = history[hIndex]
            }, contentAlignment = Alignment.Center) { Icon(Icons.Outlined.KeyboardArrowDown, "Sonraki", tint = Pal.Sub) }
            OutlinedTextField(
                value = cmd, onValueChange = { cmd = it }, singleLine = true,
                placeholder = { Text("komut", color = Pal.Sub, fontFamily = FontFamily.Monospace) },
                textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go, autoCorrectEnabled = false),
                keyboardActions = KeyboardActions(onGo = { exec(cmd) }),
                modifier = Modifier.weight(1f), shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Pal.Text, unfocusedTextColor = Pal.Text,
                    focusedBorderColor = Pal.Blue, unfocusedBorderColor = Pal.Sub.copy(alpha = 0.4f), cursorColor = Pal.Blue
                )
            )
            Spacer(Modifier.width(8.dp))
            Box(Modifier.size(48.dp).clip(CircleShape).background(if (busy) Pal.Btn else Pal.Blue).clickable(enabled = !busy) { exec(cmd) }, contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.PlayArrow, "Çalıştır", tint = Color.White)
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

// ---------------- Sistem kayıtları (tam ekran) ----------------

@Composable
internal fun LogsPage(onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    val context = LocalContext.current
    var level by remember { mutableIntStateOf(0) }             // 0 hepsi, 1 uyarılar, 2 hatalar
    var appFilter by remember { mutableStateOf<String?>(null) }
    var appMenu by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var reload by remember { mutableIntStateOf(0) }
    var lines by remember { mutableStateOf<Result<List<TvAdvanced.LogLine>>?>(null) }
    val apps = remember { TvTools.appIcons().keys.toList() }
    LaunchedEffect(level, appFilter, reload) {
        lines = null
        lines = io { TvAdvanced.logs(when (level) { 1 -> 'W'; 2 -> 'E'; else -> 'V' }, appFilter) }
    }
    PageScaffold("Sistem kayıtları", onClose, actions = {
        Box(Modifier.size(44.dp).clip(CircleShape).clickable { reload++ }, contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.Refresh, "Yenile", tint = Pal.Sub, modifier = Modifier.size(22.dp))
        }
        Box(Modifier.size(44.dp).clip(CircleShape).clickable {
            val text = lines?.getOrNull()?.joinToString("\n") { it.text } ?: return@clickable
            runCatching {
                val dir = java.io.File(context.cacheDir, "shots").apply { mkdirs() }
                val f = java.io.File(dir, "tv_kayitlari.txt").apply { writeText(text) }
                val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.files", f)
                share(context, uri, "text/plain")
            }
        }, contentAlignment = Alignment.Center) { Icon(Icons.Outlined.Share, "Paylaş", tint = Pal.Sub, modifier = Modifier.size(22.dp)) }
    }) {
        FilterChipRow(listOf("Hepsi", "Uyarılar", "Hatalar"), level) { level = it }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { SearchBox(query, { query = it }, "Kayıtlarda ara") }
            Spacer(Modifier.width(8.dp))
            Box {
                Text(
                    appFilter?.let { TvTools.appIcons().keys.firstOrNull { k -> k == it }?.let { p -> appName(p) } ?: it } ?: "Tüm uygulamalar",
                    color = Pal.Text, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 130.dp).clip(RoundedCornerShape(14.dp)).background(Pal.Btn).clickable { appMenu = true }.padding(horizontal = 12.dp, vertical = 14.dp)
                )
                DropdownMenu(expanded = appMenu, onDismissRequest = { appMenu = false }, containerColor = Color(0xFF2C2C2E)) {
                    DropdownMenuItem(text = { Text("Tüm uygulamalar", color = Pal.Text) }, onClick = { appFilter = null; appMenu = false })
                    for (p in apps) DropdownMenuItem(text = { Text(appName(p), color = Pal.Text) }, onClick = { appFilter = p; appMenu = false })
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        val r = lines
        Box(Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0xFF0E0E10)).padding(8.dp)) {
            when {
                r == null -> Loading("Kayıtlar okunuyor…")
                r.isFailure -> ResultLine(ToolResult(false, errorText(r.exceptionOrNull()!!)))
                else -> {
                    val shown = r.getOrThrow().filter { query.isBlank() || it.text.contains(query, true) }
                    val state = rememberLazyListState()
                    LaunchedEffect(shown.size) { if (shown.isNotEmpty()) state.scrollToItem(shown.lastIndex) }
                    SelectionContainer {
                        LazyColumn(state = state) {
                            if (shown.isEmpty()) item { Text("Kayıt yok", color = Pal.Sub, fontSize = 12.sp) }
                            items(shown.size) { i ->
                                val l = shown[i]
                                Text(
                                    l.text, fontSize = 11.sp, fontFamily = FontFamily.Monospace, lineHeight = 14.sp,
                                    color = when (l.level) { 'E', 'F' -> Pal.Red; 'W' -> Pal.Amber; 'D', 'V' -> Pal.Sub; else -> Pal.Text }
                                )
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}
