package com.serif.tvkumanda

import android.os.SystemClock
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private val CardBg = Color(0xFF161618)
private val CardLine = Color(0xFF38383C)
private val Track = Color(0xFF3A3A3D)

private fun accent(f: Float) = if (f > 0.85f) Pal.Amber else Pal.Blue

internal enum class AppSort { RAM, CPU }

/**
 * Performans ve çalışan uygulamalar arasında paylaşılan bellek. Pencere kapanıp açılınca da durur, böylece liste
 * anında görünür; TV bağlantısı değişince sıfırlanır.
 */
private object PerfCache {
    private var owner: Any? = null
    private val pkgLock = Mutex()
    var packages: Map<String, TvPackage>? = null
    var launchable: Set<String>? = null
    var apps: List<PerfStats.AppUse>? = null
    var snap: PerfStats.ProcSnap? = null
    var snapAt = 0L
    var pss: Map<String, Long>? = null
    var foreground: String? = null
    val cpuHist = ArrayList<Int>()
    val memHist = ArrayList<Int>()
    const val HIST = 60

    fun check() {
        val now = TvTools.adb
        if (owner !== now) {
            owner = now; packages = null; launchable = null; apps = null; snap = null; pss = null; foreground = null
            cpuHist.clear(); memHist.clear()
        }
    }

    /** Son ölçüm 10 sn'den yeniyse işlemci farkı için kullanılabilir. */
    fun freshSnap(): PerfStats.ProcSnap? = snap?.takeIf { SystemClock.elapsedRealtime() - snapAt < 10_000 }

    fun store(s: PerfStats.ProcSnap, list: List<PerfStats.AppUse>) { snap = s; snapAt = SystemClock.elapsedRealtime(); apps = list }

    /** Uygulama adları: bağlantı başına bir kez okunur. */
    suspend fun ensurePackages() = pkgLock.withLock {
        if (launchable == null) io { TvTools.launchableApps() }.onSuccess { launchable = it }
        if (packages == null) io { TvFiles.packages() }.onSuccess { l -> packages = l.associateBy { it.pkg } }
    }

    /** Performans açılınca listeyi önceden hazırla; karta dokununca beklemeden açılsın. */
    suspend fun prewarm() {
        check()
        if (freshSnap() == null) io { TvTools.procSnap() }.onSuccess { s -> store(s, PerfStats.apps(null, s, pss)) }
        ensurePackages()
    }

    fun push(h: ArrayList<Int>, v: Int) { h.add(v); while (h.size > HIST) h.removeAt(0) }
}

/** Performans: bellek kartı + işlemci halkaları; kartlara dokununca çalışan uygulamalar. */
@Composable
internal fun PerformanceTool() {
    // Liste açıkken de son değerler korunur; geri dönünce boş ekran görünmez
    var view by remember { mutableStateOf<PerfStats.View?>(null) }
    var sort by remember { mutableStateOf<AppSort?>(null) }
    val s = sort
    if (s != null) RunningApps(s, onSort = { sort = it }, onBack = { sort = null })
    else PerfOverview(view, { view = it }, onOpen = { sort = it })
}

/** Sistem bölümündeki ayrı "Çalışan uygulamalar" kutucuğu. */
@Composable
internal fun RunningAppsTool() {
    var sort by remember { mutableStateOf(AppSort.RAM) }
    RunningApps(sort, onSort = { sort = it }, onBack = null)
}

@Composable
private fun PerfOverview(view: PerfStats.View?, setView: (PerfStats.View) -> Unit, onOpen: (AppSort) -> Unit) {
    var error by remember { mutableStateOf<String?>(null) }
    var hist by remember { mutableIntStateOf(0) } // grafiği yeniden çizdirmek için sayaç
    LaunchedEffect(Unit) { PerfCache.prewarm() }
    LaunchedEffect(Unit) {
        // Pencere kapanınca ya da liste açılınca bu döngü de kendiliğinden durur
        PerfCache.check()
        var prev: PerfStats.Sample? = null
        while (true) {
            val r = io { TvTools.perfSample() }
            r.onSuccess { s ->
                val v = PerfStats.view(prev, s)
                // Geri dönüşte ilk ölçümün boş yüzdeleri eski değerleri silmesin
                if (prev != null || view == null || view.cores.size != v.cores.size) setView(v)
                else setView(v.copy(cpu = view.cpu, cores = view.cores))
                v.cpu?.let { PerfCache.push(PerfCache.cpuHist, it) }
                if (prev != null) PerfCache.push(PerfCache.memHist, v.usedPercent)
                hist++
                error = null
                val first = prev == null
                prev = s
                delay(if (first) 400 else 1000)
            }.onFailure {
                error = errorText(it)
                delay(2000)
            }
        }
    }
    val v = view
    when {
        v == null && error != null -> ResultLine(ToolResult(false, error!!))
        v == null -> Loading("TV'den ölçülüyor…")
        else -> {
            val maxH = (LocalConfiguration.current.screenHeightDp * 0.72f).dp
            Column(Modifier.heightIn(max = maxH).verticalScroll(rememberScrollState())) {
                MemoryCard(v) { onOpen(AppSort.RAM) }
                Spacer(Modifier.height(12.dp))
                data class RingItem(val label: String, val text: String, val f: Float, val dim: Boolean, val icon: ImageVector, val sort: AppSort)
                fun pct(p: Int?) = if (p == null) "—" else "%$p"
                val cards = buildList {
                    add(RingItem("İşlemci kullanımı", pct(v.cpu), (v.cpu ?: 0) / 100f, false, Icons.Outlined.Memory, AppSort.CPU))
                    v.cores.forEachIndexed { i, p ->
                        val off = i in v.offline
                        add(RingItem("Çekirdek ${i + 1}", if (off) "Kapalı" else pct(p), (p ?: 0) / 100f, off, Icons.Outlined.Memory, AppSort.CPU))
                    }
                }
                cards.chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        row.forEach { c ->
                            RingCard(c.label, c.text, c.f, c.dim, c.icon, null, Modifier.weight(1f)) { onOpen(c.sort) }
                        }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(12.dp))
                }
                key(hist) { HistoryCard() }
                Spacer(Modifier.height(10.dp))
                Text("Kartlara dokun: çalışan uygulamalar", color = Pal.Sub, fontSize = 12.sp,
                    modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                error?.let { ResultLine(ToolResult(false, it)) }
            }
        }
    }
}

/** Çalışan uygulamalar: bellek ve işlemci kullanımı, durdurma; [onBack] null ise ayrı kutucuktan açılmıştır. */
@Composable
private fun RunningApps(sort: AppSort, onSort: (AppSort) -> Unit, onBack: (() -> Unit)?) {
    val run = rememberRunner()
    remember { PerfCache.check(); 0 }
    // Önbellekte liste varsa hemen göster, sonra tazele
    var apps by remember { mutableStateOf(PerfCache.apps) }
    var packages by remember { mutableStateOf(PerfCache.packages) }
    var launchable by remember { mutableStateOf(PerfCache.launchable) }
    var showSystem by remember { mutableStateOf(false) }
    var foreground by remember { mutableStateOf(PerfCache.foreground) }
    var error by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<ToolResult?>(null) }
    var expanded by remember { mutableStateOf<String?>(null) }
    var confirmAll by remember { mutableStateOf(false) }
    var busyAll by remember { mutableStateOf(false) }
    val stopped = remember { mutableStateMapOf<String, Boolean>() }
    val opened = remember { SystemClock.elapsedRealtime() }

    LaunchedEffect(Unit) { PerfCache.ensurePackages(); packages = PerfCache.packages; launchable = PerfCache.launchable }
    LaunchedEffect(Unit) {
        var prev = PerfCache.freshSnap()
        if (prev != null) delay(1000) // önbellekteki ölçümle işlemci farkı için kısa bekleme yeter
        while (true) {
            io { TvTools.procSnap() }.onSuccess { snap ->
                val list = PerfStats.apps(prev, snap, PerfCache.pss)
                apps = if (prev == null && apps != null) list.map { a -> a.copy(cpu = apps!!.firstOrNull { it.pkg == a.pkg }?.cpu) } else list
                PerfCache.store(snap, apps!!)
                error = null
                val first = prev == null
                prev = snap
                stopped.keys.toList().forEach { k -> if (list.none { it.pkg == k }) stopped.remove(k) }
                delay(if (first) 500 else 2000)
            }.onFailure { error = errorText(it); delay(2000) }
        }
    }
    // Ağır ölçümler sürekli değil (TV'yi yoruyor): liste ekrana gelince bir kez, sonra yalnızca gerektiğinde.
    // Ekrandaki uygulama: açılışta, durdurma/açmadan sonra ve "Yenile"de. Gerçek bellek (PSS): açılışta ve "Yenile"de.
    val ready = apps != null
    var fgTick by remember { mutableIntStateOf(0) }
    var pssTick by remember { mutableIntStateOf(0) }
    var measuring by remember { mutableStateOf(false) }
    LaunchedEffect(ready, fgTick) {
        if (!ready) return@LaunchedEffect
        if (fgTick > 0) delay(1500) // durdurulan/açılan uygulamanın yerleşmesi için
        io { TvTools.foregroundApp() }.onSuccess { foreground = it; PerfCache.foreground = it }
    }
    LaunchedEffect(ready, pssTick) {
        if (!ready) return@LaunchedEffect
        measuring = true
        io { TvTools.appPss() }.onSuccess { m ->
            PerfCache.pss = m
            apps = apps?.map { a -> m[a.pkg]?.let { a.copy(kb = it) } ?: a }
        }
        measuring = false
    }
    // Açılış süresi teşhis kaydına: yavaşlık sürerse nerede takıldığı görülsün
    LaunchedEffect(ready) {
        if (ready) DiagLog.log("Çalışan uygulamalar: liste ${SystemClock.elapsedRealtime() - opened} ms'de açıldı")
    }

    if (onBack != null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(36.dp).clip(CircleShape).background(Pal.Btn).clickable(onClick = onBack), contentAlignment = Alignment.Center) {
                Icon(AppIcons.ArrowBack, "Geri", tint = Pal.Text, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(10.dp))
            Text("Çalışan uygulamalar", color = Pal.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.height(12.dp))
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SortChip("Bellek", sort == AppSort.RAM) { onSort(AppSort.RAM) }
        SortChip("İşlemci", sort == AppSort.CPU) { onSort(AppSort.CPU) }
        Spacer(Modifier.weight(1f))
        SortChip(if (measuring) "Ölçülüyor…" else "Yenile", false) { if (!measuring && ready) { pssTick++; fgTick++ } }
    }
    Spacer(Modifier.height(12.dp))
    val list = apps
    when {
        list == null && error != null -> ResultLine(ToolResult(false, error!!))
        list == null -> Loading("Uygulamalar okunuyor…")
        else -> {
            val fg = foreground
            val sorted = if (sort == AppSort.RAM) list.sortedByDescending { it.kb }
                else list.sortedWith(compareByDescending<PerfStats.AppUse> { it.cpu ?: -1.0 }.thenByDescending { it.kb })
            fun label(pkg: String) = packages?.get(pkg)?.label ?: appName(pkg)
            fun stop(pkg: String) {
                stopped[pkg] = true; result = null; expanded = null
                run {
                    io { TvFiles.forceStop(TvPackage(pkg, label(pkg), false, false, "", "", 0)) }
                        .onSuccess { stopped.remove(pkg); result = ToolResult(true, "${label(pkg)} durduruldu"); fgTick++ }
                        .onFailure { stopped.remove(pkg); result = ToolResult(false, errorText(it)) }
                }
            }
            fun isUser(pkg: String) = PerfStats.isUserApp(pkg, launchable, packages?.get(pkg)?.let { !it.system }, TvAdvanced.isProtected(pkg))
            val (userApps, sysApps) = sorted.partition { isUser(it.pkg) }
            @Composable
            fun row(a: PerfStats.AppUse) {
                val protected = TvAdvanced.isProtected(a.pkg)
                AppRow(
                    name = label(a.pkg),
                    a = a,
                    system = false,
                    onScreen = a.pkg == fg,
                    canStop = !protected,
                    stopping = stopped[a.pkg] == true,
                    open = expanded == a.pkg,
                    onToggle = { expanded = if (expanded == a.pkg) null else a.pkg },
                    onStop = { stop(a.pkg) },
                    onLaunch = {
                        expanded = null; result = null
                        run {
                            io { TvFiles.open(TvPackage(a.pkg, label(a.pkg), false, false, "", "", 0)) }
                                .onSuccess { result = ToolResult(true, "${label(a.pkg)} TV'de açıldı"); fgTick++ }
                                .onFailure { result = ToolResult(false, errorText(it)) }
                        }
                    },
                    onInfo = {
                        expanded = null; result = null
                        run {
                            io { TvTools.openAppInfo(a.pkg) }
                                .onSuccess { result = ToolResult(true, "Uygulama bilgisi TV'de açıldı") }
                                .onFailure { result = ToolResult(false, errorText(it)) }
                        }
                    }
                )
            }
            val maxH = (LocalConfiguration.current.screenHeightDp * if (onBack != null) 0.52f else 0.56f).dp
            LazyColumn(Modifier.heightIn(max = maxH), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (userApps.isEmpty()) item("bos") {
                    Text("Şu an arka planda çalışan uygulama yok.", color = Pal.Sub, fontSize = 13.sp, modifier = Modifier.padding(vertical = 6.dp))
                }
                items(userApps, key = { it.pkg }) { row(it) }
                if (sysApps.isNotEmpty()) item("sistem_dugme") {
                    SecondaryButton(
                        if (showSystem) "Sistem uygulamalarını gizle" else "Sistem uygulamalarını göster (${sysApps.size})",
                        Modifier.fillMaxWidth()
                    ) { showSystem = !showSystem }
                }
                if (showSystem && sysApps.isNotEmpty()) {
                    item("sistem_baslik") {
                        Text("Sistem uygulamaları", color = Pal.Sub, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(top = 4.dp))
                    }
                    items(sysApps, key = { "s_" + it.pkg }) { row(it) }
                }
            }
            ResultLine(result)
            error?.let { ResultLine(ToolResult(false, it)) }
            Spacer(Modifier.height(12.dp))
            // Yalnızca kullanıcı uygulamaları; ekrandakine ve sistem uygulamalarına dokunulmaz
            val targets = userApps.filter { !TvAdvanced.isProtected(it.pkg) && it.pkg != fg && stopped[it.pkg] != true }.map { it.pkg }
            SecondaryButton(
                when {
                    busyAll -> "Durduruluyor…"
                    confirmAll -> "Emin misin? ${targets.size} uygulama durdurulacak"
                    else -> "Arka plandakilerin hepsini durdur"
                },
                Modifier.fillMaxWidth(), enabled = !busyAll && targets.isNotEmpty()
            ) {
                if (!confirmAll) { confirmAll = true; return@SecondaryButton }
                confirmAll = false; busyAll = true; result = null
                run {
                    io { TvTools.forceStopAll(targets) }
                        .onSuccess { result = ToolResult(true, "${targets.size} uygulama durduruldu"); fgTick++ }
                        .onFailure { result = ToolResult(false, errorText(it)) }
                    busyAll = false
                }
            }
            Spacer(Modifier.height(8.dp))
            Text("Durdurulan uygulama açınca yeniden çalışır. Toplu durdurma yalnızca üstteki uygulamalara dokunur; ekrandaki uygulama ve sistem uygulamaları hariç.",
                color = Pal.Sub, fontSize = 12.sp)
        }
    }
}

@Composable
private fun SortChip(text: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text, color = if (selected) Color.White else Pal.Sub, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.clip(RoundedCornerShape(18.dp)).background(if (selected) Pal.Blue else Pal.Btn)
            .clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp)
    )
}

@Composable
private fun Badge(text: String, color: Color) {
    Text(text, color = color, fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(color.copy(alpha = 0.15f)).padding(horizontal = 5.dp, vertical = 1.dp))
}

@Composable
private fun AppRow(
    name: String, a: PerfStats.AppUse, system: Boolean, onScreen: Boolean, canStop: Boolean, stopping: Boolean,
    open: Boolean, onToggle: () -> Unit, onStop: () -> Unit, onLaunch: () -> Unit, onInfo: () -> Unit
) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(CardBg)
            .border(1.dp, if (onScreen) Pal.Green.copy(alpha = 0.5f) else CardLine, RoundedCornerShape(16.dp))
            .clickable(onClick = onToggle).padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(name, color = Pal.Text, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (onScreen) { Spacer(Modifier.width(6.dp)); Badge("ekranda", Pal.Green) }
                    if (system) { Spacer(Modifier.width(6.dp)); Badge("sistem", Pal.Sub) }
                }
                Spacer(Modifier.height(2.dp))
                val cpu = a.cpu?.let { "%" + String.format(java.util.Locale.forLanguageTag("tr"), "%.1f", it) } ?: "—"
                Text("${SysInfo.mb(a.kb)}  ·  İşlemci $cpu", color = Pal.Sub, fontSize = 13.sp)
            }
            if (canStop) {
                Spacer(Modifier.width(8.dp))
                Text(
                    if (stopping) "Durduruluyor" else "Durdur",
                    color = if (stopping) Pal.Sub else Pal.Amber, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable(enabled = !stopping, onClick = onStop)
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                )
            }
        }
        if (open) {
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RowAction(Icons.Outlined.PlayArrow, "TV'de aç", Modifier.weight(1f), onLaunch)
                RowAction(Icons.Outlined.Info, "Uygulama bilgisi", Modifier.weight(1f), onInfo)
            }
            Spacer(Modifier.height(6.dp))
            Text("Önbellek temizleme ve izinler TV'deki uygulama bilgisi ekranında.", color = Pal.Sub, fontSize = 11.sp)
        }
    }
}

@Composable
private fun RowAction(icon: ImageVector, text: String, modifier: Modifier, onClick: () -> Unit) {
    Row(
        modifier.clip(RoundedCornerShape(12.dp)).background(Pal.Btn).clickable(onClick = onClick).padding(vertical = 9.dp),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = Pal.Text, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, color = Pal.Text, fontSize = 13.sp, maxLines = 1)
    }
}

@Composable
private fun PerfCard(modifier: Modifier = Modifier, onClick: (() -> Unit)?, content: @Composable ColumnScope.() -> Unit) {
    val base = modifier.clip(RoundedCornerShape(22.dp))
    Column(
        (if (onClick != null) base.clickable(onClick = onClick) else base).background(CardBg)
            .border(1.dp, CardLine, RoundedCornerShape(22.dp)).padding(18.dp),
        content = content
    )
}

@Composable
private fun MemoryCard(v: PerfStats.View, onClick: () -> Unit) {
    val f = if (v.totalKb > 0) (v.usedKb.toFloat() / v.totalKb).coerceIn(0f, 1f) else 0f
    val anim by animateFloatAsState(f, tween(600), label = "ram")
    PerfCard(Modifier.fillMaxWidth(), onClick) {
        Text("Bellek", color = Pal.Text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(10.dp))
        val free = 100 - v.usedPercent
        MemLine("Kullanılan", "${SysInfo.mb(v.usedKb)} (%${v.usedPercent})")
        MemLine("Boş", "${SysInfo.mb(v.availKb)} (%$free)")
        MemLine("Toplam", SysInfo.mb(v.totalKb))
        Spacer(Modifier.height(14.dp))
        SplitBar(anim)
    }
}

@Composable
private fun MemLine(k: String, value: String) {
    Row(Modifier.padding(vertical = 2.dp)) {
        Text(k, color = Pal.Sub, fontSize = 14.sp, modifier = Modifier.width(92.dp))
        Text(value, color = Pal.Text, fontSize = 14.sp)
    }
}

/** Dolu kısım ve boş kısım arasında küçük boşluk; boşun ucunda bitiş noktası. */
@Composable
private fun SplitBar(f: Float) {
    val color = accent(f)
    Canvas(Modifier.fillMaxWidth().height(10.dp)) {
        val h = size.height
        val gap = 6.dp.toPx()
        val usedW = (size.width * f).coerceIn(0f, size.width)
        val r = CornerRadius(h / 2, h / 2)
        if (usedW > 0f) drawRoundRect(color, Offset.Zero, Size(maxOf(usedW - gap / 2, h), h), r)
        val restX = if (usedW > 0f) usedW + gap / 2 else 0f
        if (size.width - restX > h) {
            drawRoundRect(Track, Offset(restX, 0f), Size(size.width - restX, h), r)
            drawCircle(color, h / 4, Offset(size.width - h / 2, h / 2))
        }
    }
}

@Composable
private fun RingCard(label: String, text: String, f: Float, dim: Boolean, icon: ImageVector, color: Color?, modifier: Modifier, onClick: () -> Unit) {
    val anim by animateFloatAsState(f.coerceIn(0f, 1f), tween(600), label = "ring")
    PerfCard(modifier, onClick) {
        Box(Modifier.fillMaxWidth().aspectRatio(1.35f), contentAlignment = Alignment.Center) {
            Ring(anim, color ?: accent(anim), Modifier.fillMaxHeight().aspectRatio(1f))
            Text(text, color = if (dim) Pal.Sub else Pal.Text, fontSize = 17.sp)
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = Pal.Sub, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(label, color = Pal.Text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Saat 12'den başlayan halka: dolu yay + aralıklı boş yay (ekran görüntüsündeki gibi). */
@Composable
private fun Ring(f: Float, color: Color, modifier: Modifier) {
    Canvas(modifier.padding(4.dp)) {
        val w = 9.dp.toPx()
        val d = size.minDimension - w
        val tl = Offset((size.width - d) / 2, (size.height - d) / 2)
        val sz = Size(d, d)
        val sweep = 360f * f.coerceIn(0f, 1f)
        // Yuvarlak uçlar yaydan taşar; boşluk bunu da karşılasın
        val gap = Math.toDegrees((w * 1.6 / (d / 2)).toDouble()).toFloat()
        when {
            sweep < 0.5f -> drawArc(Track, 0f, 360f, false, tl, sz, style = Stroke(w))
            sweep > 360f - 2 * gap -> drawArc(color, 0f, 360f, false, tl, sz, style = Stroke(w))
            else -> {
                drawArc(color, -90f, sweep, false, tl, sz, style = Stroke(w, cap = StrokeCap.Round))
                drawArc(Track, -90f + sweep + gap, 360f - sweep - 2 * gap, false, tl, sz, style = Stroke(w, cap = StrokeCap.Round))
            }
        }
    }
}

/** Son 1 dakika: işlemci ve bellek yüzdesi, ince çizgiler. */
@Composable
private fun HistoryCard() {
    val cpu = PerfCache.cpuHist.toList()
    val mem = PerfCache.memHist.toList()
    PerfCard(Modifier.fillMaxWidth(), null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Son 1 dakika", color = Pal.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Legend("İşlemci", Pal.Blue)
            Spacer(Modifier.width(12.dp))
            Legend("Bellek", Pal.Green)
        }
        Spacer(Modifier.height(12.dp))
        if (cpu.size < 2 && mem.size < 2) {
            Text("Ölçümler birikiyor…", color = Pal.Sub, fontSize = 12.sp)
        } else {
            Canvas(Modifier.fillMaxWidth().height(64.dp)) {
                // Arka plan çizgileri: %0, %50, %100
                for (y in listOf(0f, 0.5f, 1f)) drawLine(Track, Offset(0f, size.height * y), Offset(size.width, size.height * y), 1f)
                fun line(h: List<Int>, c: Color) {
                    if (h.size < 2) return
                    val step = size.width / (PerfCache.HIST - 1)
                    // Sağa yaslı: en yeni ölçüm sağ kenarda
                    val x0 = size.width - step * (h.size - 1)
                    val p = Path()
                    h.forEachIndexed { i, v ->
                        val x = x0 + step * i
                        val y = size.height * (1f - v.coerceIn(0, 100) / 100f)
                        if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
                    }
                    drawPath(p, c, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
                line(mem, Pal.Green)
                line(cpu, Pal.Blue)
            }
        }
    }
}

@Composable
private fun Legend(text: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(5.dp))
        Text(text, color = Pal.Sub, fontSize = 12.sp)
    }
}
