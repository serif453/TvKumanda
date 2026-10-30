package com.serif.tvkumanda

/**
 * Performans aracının saf mantığı: TV'den tek komutla `/proc/stat`, bellek ve çekirdek sayısı okunur;
 * iki ölçüm arasındaki farktan işlemci yüzdeleri hesaplanır.
 */
object PerfStats {

    /** Bölümler "##X" satırlarıyla ayrılır: S = /proc/stat, M = bellek, C = var olan çekirdekler (ör. "0-3"). */
    const val COMMAND = "echo '##S'; grep '^cpu' /proc/stat; " +
        "echo '##M'; grep -E '^(MemTotal|MemAvailable):' /proc/meminfo; " +
        "echo '##C'; cat /sys/devices/system/cpu/present 2>/dev/null"

    /** Bir işlemci satırının sayaçları: toplam ve boşta geçen süre (jiffy). */
    data class Ticks(val total: Long, val idle: Long)

    /** Tek ölçüm: [cpu] toplam, [cores] çekirdek numarasına göre (kapalı çekirdek listede olmaz). */
    data class Sample(val cpu: Ticks?, val cores: Map<Int, Ticks>, val coreCount: Int, val totalKb: Long, val availKb: Long)

    /** Ekranda gösterilen değerler; yüzde null = henüz ölçülmedi, [offline] = TV'nin o an kapattığı çekirdekler. */
    data class View(val cpu: Int?, val cores: List<Int?>, val offline: Set<Int>, val totalKb: Long, val availKb: Long) {
        val usedKb get() = (totalKb - availKb).coerceAtLeast(0)
        val usedPercent get() = if (totalKb > 0) Math.round(usedKb * 100.0 / totalKb).toInt() else 0
    }

    fun parse(out: String): Sample {
        val sec = HashMap<String, MutableList<String>>()
        var cur = ""
        for (raw in out.lineSequence()) {
            val line = raw.trim()
            if (line.startsWith("##") && line.length in 3..4) { cur = line.removePrefix("##"); sec.getOrPut(cur) { mutableListOf() }; continue }
            if (line.isNotEmpty()) sec.getOrPut(cur) { mutableListOf() }.add(line)
        }
        var cpu: Ticks? = null
        val cores = HashMap<Int, Ticks>()
        for (l in sec["S"].orEmpty()) {
            val p = l.split(Regex("\\s+"))
            val nums = p.drop(1).mapNotNull { it.toLongOrNull() }
            if (nums.size < 4) continue
            // user nice system idle iowait irq softirq steal (guest'ler zaten user'ın içinde)
            val t = Ticks(nums.take(8).sum(), nums[3] + (nums.getOrNull(4) ?: 0))
            if (p[0] == "cpu") cpu = t else p[0].removePrefix("cpu").toIntOrNull()?.let { cores[it] = t }
        }
        fun kb(key: String) = sec["M"].orEmpty().firstOrNull { it.startsWith("$key:") }
            ?.split(Regex("\\s+"))?.getOrNull(1)?.toLongOrNull() ?: 0
        val present = presentCount(sec["C"]?.firstOrNull())
        val count = maxOf(present, (cores.keys.maxOrNull() ?: -1) + 1)
        return Sample(cpu, cores, count, kb("MemTotal"), kb("MemAvailable"))
    }

    /** "0-3" → 4, "0-1,4-5" → 6 (en büyük numara + 1). */
    fun presentCount(s: String?): Int {
        if (s.isNullOrBlank()) return 0
        val max = s.split(',').mapNotNull { it.trim().substringAfterLast('-').toIntOrNull() }.maxOrNull() ?: return 0
        return max + 1
    }

    /** İki ölçüm arasındaki kullanım yüzdesi (0–100); fark yoksa ya da sayaç sıfırlandıysa null. */
    fun percent(a: Ticks?, b: Ticks?): Int? {
        if (a == null || b == null) return null
        val dt = b.total - a.total
        val di = b.idle - a.idle
        if (dt <= 0 || di < 0) return null
        return Math.round((dt - di.coerceAtMost(dt)) * 100.0 / dt).toInt().coerceIn(0, 100)
    }

    /** İlk ölçümde (önceki yoksa) yüzdeler boş kalır, bellek hemen görünür. */
    fun view(prev: Sample?, now: Sample): View = View(
        cpu = percent(prev?.cpu, now.cpu),
        cores = (0 until now.coreCount).map { percent(prev?.cores?.get(it), now.cores[it]) },
        offline = (0 until now.coreCount).filter { it !in now.cores }.toSet(),
        totalKb = now.totalKb,
        availKb = now.availKb
    )
}
