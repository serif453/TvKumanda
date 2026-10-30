package com.serif.tvkumanda

/**
 * Performans aracının saf mantığı: TV'den tek komutla `/proc/stat`, bellek ve çekirdek sayısı okunur;
 * iki ölçüm arasındaki farktan işlemci yüzdeleri hesaplanır.
 */
object PerfStats {

    /** Bölümler "##X" satırlarıyla ayrılır: S = /proc/stat, M = bellek, C = var olan çekirdekler (ör. "0-3"). */
    const val COMMAND = "echo '##S'; grep '^cpu' /proc/stat; " +
        "echo '##M'; grep -E '^(MemTotal|MemAvailable):' /proc/meminfo; " +
        "echo '##C'; cat /sys/devices/system/cpu/present 2>/dev/null; " +
        // Tek süreçle tüm sıcaklık bölgeleri: önce adlar, sonra değerler, tek satırda sekmeyle
        "echo '##H'; paste /sys/class/thermal/thermal_zone*/type /sys/class/thermal/thermal_zone*/temp 2>/dev/null"

    /** Bir işlemci satırının sayaçları: toplam ve boşta geçen süre (jiffy). */
    data class Ticks(val total: Long, val idle: Long)

    /** Tek ölçüm: [cpu] toplam, [cores] çekirdek numarasına göre (kapalı çekirdek listede olmaz). */
    data class Sample(val cpu: Ticks?, val cores: Map<Int, Ticks>, val coreCount: Int, val totalKb: Long, val availKb: Long, val tempC: Int? = null)

    /** Ekranda gösterilen değerler; yüzde null = henüz ölçülmedi, [offline] = TV'nin o an kapattığı çekirdekler. */
    data class View(val cpu: Int?, val cores: List<Int?>, val offline: Set<Int>, val totalKb: Long, val availKb: Long, val tempC: Int? = null) {
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
        return Sample(cpu, cores, count, kb("MemTotal"), kb("MemAvailable"), temperature(sec["H"]?.firstOrNull()))
    }

    /** `paste` satırı: ilk yarısı bölge adları, ikinci yarısı değerler (milli°C). İşlemci bölgesi seçilir. */
    fun temperature(line: String?): Int? {
        // Okunamayan değer boş sütun olur; sıra bozulmasın diye boşlar atılmaz
        val p = line?.split('\t')?.map { it.trim() } ?: return null
        if (p.size < 2 || p.size % 2 != 0) return null
        val n = p.size / 2
        return SysInfo.temperature((0 until n).filter { p[it].isNotEmpty() && p[n + it].isNotEmpty() }.joinToString("\n") { "${p[it]} ${p[n + it]}" })
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
        availKb = now.availKb,
        tempC = now.tempC
    )

    // ---------------- Çalışan uygulamalar ----------------

    /**
     * Toplam işlemci sayacı + her sürecin adı, RSS'i (KB) ve harcadığı işlemci süresi.
     * Tek `ps` ile (TIME+ = yüzde bir saniye = jiffy); `ps` TIME+ bilmiyorsa eski yol: `/proc/[pid]/stat`.
     */
    const val APPS_COMMAND = "echo '##S'; grep '^cpu ' /proc/stat; echo '##P'; " +
        "ps -A -o PID,RSS,TIME+,NAME 2>/dev/null || { ps -A -o PID,RSS,NAME 2>/dev/null; echo '##T'; cat /proc/[0-9]*/stat 2>/dev/null; }"

    /** Ekrandaki uygulama. */
    const val FOREGROUND_COMMAND = "dumpsys activity activities 2>/dev/null | grep -m1 -E 'mResumedActivity|topResumedActivity'"

    /** "ActivityRecord{1a2b u0 com.x/.Main t12}" → "com.x". */
    fun parseForeground(out: String): String? = Regex("""u\d+ ([\w.]+)/""").find(out)?.groupValues?.get(1)

    /** TIME+ ("1:02.34", "12:01:02.34", "2-01:02:03.45") → yüzde bir saniye. */
    fun timePlus(s: String): Long? {
        var t = s
        var days = 0L
        if (t.contains('-')) { days = t.substringBefore('-').toLongOrNull() ?: return null; t = t.substringAfter('-') }
        val frac = t.substringAfter('.', "")
        val hund = when (frac.length) { 0 -> 0L; 1 -> (frac.toLongOrNull() ?: return null) * 10; else -> frac.take(2).toLongOrNull() ?: return null }
        var sec = 0L
        for (part in t.substringBefore('.').split(':')) sec = sec * 60 + (part.toLongOrNull() ?: return null)
        return (days * 86400 + sec) * 100 + hund
    }

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
        var withTime = false
        val names = HashMap<Int, String>(); val rss = HashMap<Int, Long>(); val ticks = HashMap<Int, Long>()
        for (raw in out.lineSequence()) {
            val line = raw.trim()
            if (line.startsWith("##") && line.length in 3..4) { cur = line.removePrefix("##"); continue }
            if (line.isEmpty()) continue
            when (cur) {
                "S" -> if (line.startsWith("cpu ")) total = line.split(Regex("\\s+")).drop(1).mapNotNull { it.toLongOrNull() }.take(8).sum()
                "P" -> {
                    if (line.startsWith("PID")) { withTime = line.contains("TIME"); continue }
                    val p = line.split(Regex("\\s+"), limit = if (withTime) 4 else 3)
                    val pid = p.getOrNull(0)?.toIntOrNull() ?: continue
                    names[pid] = p.getOrNull(if (withTime) 3 else 2) ?: continue
                    p[1].toLongOrNull()?.let { rss[pid] = it }
                    if (withTime) timePlus(p[2])?.let { ticks[pid] = it }
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
