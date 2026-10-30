package com.serif.tvkumanda

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

private val CardBg = Color(0xFF161618)
private val CardLine = Color(0xFF38383C)
private val Track = Color(0xFF3A3A3D)

private fun accent(f: Float) = if (f > 0.85f) Pal.Amber else Pal.Blue

/** Performans: bellek kartı + işlemci halkaları; pencere açıkken saniyede bir yenilenir. */
@Composable
internal fun PerformanceTool() {
    var view by remember { mutableStateOf<PerfStats.View?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        // Pencere kapanınca bu döngü de kendiliğinden durur
        var prev: PerfStats.Sample? = null
        while (true) {
            val r = io { TvTools.perfSample() }
            r.onSuccess { s ->
                // İlk ölçümde yüzde yok; ikinci ölçüm hemen gelsin diye kısa bekle
                view = PerfStats.view(prev, s)
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
                MemoryCard(v)
                Spacer(Modifier.height(12.dp))
                val cards = listOf(Triple("İşlemci kullanımı", v.cpu, false)) +
                    v.cores.mapIndexed { i, p -> Triple("Çekirdek ${i + 1}", p, i in v.offline) }
                cards.chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        row.forEach { (label, p, off) -> CpuCard(label, p, off, Modifier.weight(1f)) }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(12.dp))
                }
                error?.let { ResultLine(ToolResult(false, it)) }
            }
        }
    }
}

@Composable
private fun PerfCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.clip(RoundedCornerShape(22.dp)).background(CardBg)
            .border(1.dp, CardLine, RoundedCornerShape(22.dp)).padding(18.dp),
        content = content
    )
}

@Composable
private fun MemoryCard(v: PerfStats.View) {
    val f = if (v.totalKb > 0) (v.usedKb.toFloat() / v.totalKb).coerceIn(0f, 1f) else 0f
    val anim by animateFloatAsState(f, tween(600), label = "ram")
    PerfCard(Modifier.fillMaxWidth()) {
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
private fun CpuCard(label: String, percent: Int?, offline: Boolean, modifier: Modifier) {
    val f = (percent ?: 0) / 100f
    val anim by animateFloatAsState(f, tween(600), label = "cpu")
    PerfCard(modifier) {
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
