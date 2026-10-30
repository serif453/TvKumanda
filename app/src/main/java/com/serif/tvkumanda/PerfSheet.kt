package com.serif.tvkumanda

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

private val CardBg = Color(0xFF161618)
private val CardLine = Color(0xFF38383C)
private val Track = Color(0xFF3A3A3D)

private fun accent(f: Float) = if (f > 0.85f) Pal.Amber else Pal.Blue

private enum class AppSort { RAM, CPU }

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

@Composable
private fun PerfOverview(view: PerfStats.View?, setView: (PerfStats.View) -> Unit, onOpen: (AppSort) -> Unit) {
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        // Pencere kapanınca ya da liste açılınca bu döngü de kendiliğinden durur
        var prev: PerfStats.Sample? = null
        while (true) {
            val r = io { TvTools.perfSample() }
            r.onSuccess { s ->
                val v = PerfStats.view(prev, s)
                // Geri dönüşte ilk ölçümün boş yüzdeleri eski değerleri silmesin
                if (prev != null || view == null || view.cores.size != v.cores.size) setView(v)
                else setView(v.copy(cpu = view.cpu, cores = view.cores))
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
                val cards = listOf(Triple("İşlemci kullanımı", v.cpu, false)) +
                    v.cores.mapIndexed { i, p -> Triple("Çekirdek ${i + 1}", p, i in v.offline) }
                cards.chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        row.forEach { (label, p, off) -> CpuCard(label, p, off, Modifier.weight(1f)) { onOpen(AppSort.CPU) } }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(12.dp))
                }
                Text("Kartlara dokun: çalışan uygulamalar", color = Pal.Sub, fontSize = 12.sp,
                    modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                error?.let { ResultLine(ToolResult(false, it)) }
            }
        }
    }
}

/** Çalışan uygulamalar: bellek ve işlemci kullanımı, durdurma. */
@Composable
private fun RunningApps(sort: AppSort, onSort: (AppSort) -> Unit, onBack: () -> Unit) {
    val run = rememberRunner()
    var apps by remember { mutableStateOf<List<PerfStats.AppUse>?>(null) }
    var pss by remember { mutableStateOf<Map<String, Long>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<ToolResult?>(null) }
    val stopped = remember { mutableStateMapOf<String, Boolean>() }
    val packages = remember { mutableStateMapOf<String, TvPackage>() }
    LaunchedEffect(Unit) { io { TvFiles.packages() }.onSuccess { l -> l.forEach { packages[it.pkg] = it } } }
    // Gerçek bellek ölçümü TV'yi biraz yorar: 5 sn'de bir
    LaunchedEffect(Unit) {
        while (true) { io { TvTools.appPss() }.onSuccess { pss = it }; delay(5000) }
    }
    LaunchedEffect(Unit) {
        var prev: PerfStats.ProcSnap? = null
        while (true) {
            io { TvTools.procSnap() }.onSuccess { snap ->
                val list = PerfStats.apps(prev, snap, pss)
                // İlk ölçümde işlemci yok; yarım saniye sonra ikinci ölçüm gelir
                apps = if (prev == null && apps != null) list.map { a -> a.copy(cpu = apps!!.firstOrNull { it.pkg == a.pkg }?.cpu) } else list
                error = null
                val first = prev == null
                prev = snap
                // Durdurulan uygulama gerçekten kapandıysa işareti kaldır
                stopped.keys.toList().forEach { k -> if (list.none { it.pkg == k }) stopped.remove(k) }
                delay(if (first) 500 else 2000)
            }.onFailure { error = errorText(it); delay(2000) }
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(36.dp).clip(CircleShape).background(Pal.Btn).clickable(onClick = onBack), contentAlignment = Alignment.Center) {
            Icon(AppIcons.ArrowBack, "Geri", tint = Pal.Text, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(10.dp))
        Text("Çalışan uygulamalar", color = Pal.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
    }
    Spacer(Modifier.height(12.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SortChip("Bellek", sort == AppSort.RAM) { onSort(AppSort.RAM) }
        SortChip("İşlemci", sort == AppSort.CPU) { onSort(AppSort.CPU) }
    }
    Spacer(Modifier.height(12.dp))
    val list = apps
    when {
        list == null && error != null -> ResultLine(ToolResult(false, error!!))
        list == null -> Loading("Uygulamalar okunuyor…")
        else -> {
            val sorted = if (sort == AppSort.RAM) list.sortedByDescending { it.kb }
                else list.sortedWith(compareByDescending<PerfStats.AppUse> { it.cpu ?: -1.0 }.thenByDescending { it.kb })
            val maxH = (LocalConfiguration.current.screenHeightDp * 0.62f).dp
            LazyColumn(Modifier.heightIn(max = maxH), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(sorted, key = { it.pkg }) { a ->
                    val pkg = packages[a.pkg]
                    val protected = TvAdvanced.isProtected(a.pkg)
                    AppRow(
                        name = pkg?.label ?: appName(a.pkg),
                        a = a,
                        system = pkg?.system ?: protected,
                        canStop = !protected,
                        stopping = stopped[a.pkg] == true
                    ) {
                        stopped[a.pkg] = true; result = null
                        val label = pkg?.label ?: appName(a.pkg)
                        run {
                            io { TvFiles.forceStop(TvPackage(a.pkg, label, false, false, "", "", 0)) }
                                .onSuccess { stopped.remove(a.pkg); result = ToolResult(true, "$label durduruldu") }
                                .onFailure { stopped.remove(a.pkg); result = ToolResult(false, errorText(it)) }
                        }
                    }
                }
            }
            ResultLine(result)
            error?.let { ResultLine(ToolResult(false, it)) }
            Spacer(Modifier.height(8.dp))
            Text("Durdurulan uygulama açınca yeniden çalışır. TV'nin çalışması için gereken sistem parçaları durdurulamaz.",
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
private fun AppRow(name: String, a: PerfStats.AppUse, system: Boolean, canStop: Boolean, stopping: Boolean, onStop: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(CardBg)
            .border(1.dp, CardLine, RoundedCornerShape(16.dp)).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(name, color = Pal.Text, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (system) {
                    Spacer(Modifier.width(6.dp))
                    Text("sistem", color = Pal.Sub, fontSize = 10.sp,
                        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(Pal.Btn).padding(horizontal = 5.dp, vertical = 1.dp))
                }
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
}

@Composable
private fun PerfCard(modifier: Modifier = Modifier, onClick: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.clip(RoundedCornerShape(22.dp)).clickable(onClick = onClick).background(CardBg)
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
        val r = androidx.compose.ui.geometry.CornerRadius(h / 2, h / 2)
        if (usedW > 0f) drawRoundRect(color, Offset.Zero, Size(maxOf(usedW - gap / 2, h), h), r)
        val restX = if (usedW > 0f) usedW + gap / 2 else 0f
        if (size.width - restX > h) {
            drawRoundRect(Track, Offset(restX, 0f), Size(size.width - restX, h), r)
            drawCircle(color, h / 4, Offset(size.width - h / 2, h / 2))
        }
    }
}

@Composable
private fun CpuCard(label: String, percent: Int?, offline: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val f = (percent ?: 0) / 100f
    val anim by animateFloatAsState(f, tween(600), label = "cpu")
    PerfCard(modifier, onClick) {
        Box(Modifier.fillMaxWidth().aspectRatio(1.35f), contentAlignment = Alignment.Center) {
            Ring(anim, Modifier.fillMaxHeight().aspectRatio(1f))
            Text(
                when { offline -> "Kapalı"; percent == null -> "—"; else -> "%$percent" },
                color = if (offline) Pal.Sub else Pal.Text, fontSize = 17.sp
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Memory, null, tint = Pal.Sub, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(label, color = Pal.Text, fontSize = 14.sp, maxLines = 1)
        }
    }
}

/** Saat 12'den başlayan halka: dolu yay + aralıklı boş yay (ekran görüntüsündeki gibi). */
@Composable
private fun Ring(f: Float, modifier: Modifier) {
    val color = accent(f)
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
                val s = maxOf(sweep, 0.01f)
                drawArc(color, -90f, s, false, tl, sz, style = Stroke(w, cap = StrokeCap.Round))
                drawArc(Track, -90f + s + gap, 360f - s - 2 * gap, false, tl, sz, style = Stroke(w, cap = StrokeCap.Round))
            }
        }
    }
}
