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

    // ---------------- Çalışan uygulamalar ----------------

    /** Toplam işlemci sayacı + her sürecin adı, RSS'i (KB) ve harcadığı işlemci süresi (jiffy). */
    const val APPS_COMMAND = "echo '##S'; grep '^cpu ' /proc/stat; " +
        "echo '##P'; ps -A -o PID,RSS,NAME 2>/dev/null; " +
        "echo '##T'; cat /proc/[0-9]*/stat 2>/dev/null"

    /** Uygulama başına gerçek bellek (PSS, KB): `dumpsys meminfo` özetinden. */
    const val PSS_COMMAND = "dumpsys meminfo 2>/dev/null | sed -n '/Total PSS by process/,/Total PSS by OOM/p'"

    data class ProcSnap(val total: Long, val names: Map<Int, String>, val rssKb: Map<Int, Long>, val ticks: Map<Int, Long>)

    /** Listedeki bir uygulama; [cpu] tüm işlemcinin yüzdesi (ilk ölçümde null). */
    data class AppUse(val pkg: String, val kb: Long, val cpu: Double?)

    private val pkgName = Regex("^[A-Za-z][\\w]*(\\.[\\w]+)+$")

    /** Süreç adından uygulama paketi: "com.x:hizmet" → "com.x"; paket olmayan (sistem programları) null. */
    fun pkgOf(name: String): String? = name.substringBefore(':').takeIf { pkgName.matches(it) }

    fun parseProcs(out: String): ProcSnap {
        var cur = ""
        var total = 0L
        val names = HashMap<Int, String>(); val rss = HashMap<Int, Long>(); val ticks = HashMap<Int, Long>()
        for (raw in out.lineSequence()) {
            val line = raw.trim()
            if (line.startsWith("##") && line.length in 3..4) { cur = line.removePrefix("##"); continue }
            if (line.isEmpty()) continue
            when (cur) {
                "S" -> if (line.startsWith("cpu ")) total = line.split(Regex("\\s+")).drop(1).mapNotNull { it.toLongOrNull() }.take(8).sum()
                "P" -> {
                    val p = line.split(Regex("\\s+"), limit = 3)
                    val pid = p.getOrNull(0)?.toIntOrNull() ?: continue
                    names[pid] = p.getOrNull(2) ?: continue
                    p[1].toLongOrNull()?.let { rss[pid] = it }
                }
                "T" -> {
                    // "pid (ad) durum ..." — ad boşluk ya da parantez içerebilir, son ')' sonrası alanlar sayılır
                    val pid = line.substringBefore(' ').toIntOrNull() ?: continue
                    val f = line.substringAfterLast(')').trim().split(' ')
                    val u = f.getOrNull(11)?.toLongOrNull() ?: continue
                    val st = f.getOrNull(12)?.toLongOrNull() ?: continue
                    ticks[pid] = u + st
                }
            }
        }
        return ProcSnap(total, names, rss, ticks)
    }

    /** "  123,456K: com.x (pid 12 / activities)" satırları; aynı paketin süreçleri toplanır. */
    fun parsePss(out: String): Map<String, Long> {
        val m = HashMap<String, Long>()
        Regex("""([\d,]+)K: ([\w.:]+) \(pid""").findAll(out).forEach { r ->
            val kb = r.groupValues[1].replace(",", "").toLongOrNull() ?: return@forEach
            val pkg = pkgOf(r.groupValues[2]) ?: return@forEach
            m[pkg] = (m[pkg] ?: 0) + kb
        }
        return m
    }

    /**
     * Uygulama başına bellek ve işlemci. Bellek PSS varsa ondan (daha doğru), yoksa RSS toplamından.
     * İşlemci: iki ölçüm arasında uygulamanın süreçlerinin harcadığı süre / tüm çekirdeklerin toplam süresi.
     */
    fun apps(prev: ProcSnap?, now: ProcSnap, pss: Map<String, Long>?): List<AppUse> {
        val dt = if (prev != null) now.total - prev.total else 0
        val byPkg = now.names.entries.mapNotNull { (pid, n) -> pkgOf(n)?.let { it to pid } }.groupBy({ it.first }, { it.second })
        return byPkg.map { (pkg, pids) ->
            val cpu = if (prev == null || dt <= 0) null else {
                // Ölçümler arasında yeni doğan süreç: önceki değer yok, tüm süresi bu aralıkta sayılmaz
                val used = pids.sumOf { pid -> val a = prev.ticks[pid]; val b = now.ticks[pid]; if (a == null || b == null || b < a) 0L else b - a }
                (used * 100.0 / dt).coerceIn(0.0, 100.0)
            }
            AppUse(pkg, pss?.get(pkg) ?: pids.sumOf { now.rssKb[it] ?: 0L }, cpu)
        }
    }
}
