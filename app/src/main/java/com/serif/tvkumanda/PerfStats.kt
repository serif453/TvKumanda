package com.serif.tvkumanda

/**
 * Performans aracının saf mantığı: TV'den tek komutla `/proc/stat`, bellek ve çekirdek sayısı okunur;
 * iki ölçüm arasındaki farktan işlemci yüzdeleri hesaplanır.
 */
object PerfStats {

    /** Bölümler "##X" satırlarıyla ayrılır: S = /proc/stat, M = bellek, C = var olan çekirdekler (ör. "0-3"). */
    /** U = açık kalma süresi, D = /data doluluğu, N = ağ sayaçları (Genel bakış ve Ağ kartı için; hepsi hafif okumalar). */
    const val COMMAND = "echo '##S'; grep '^cpu' /proc/stat; " +
        "echo '##M'; grep -E '^(MemTotal|MemAvailable):' /proc/meminfo; " +
        "echo '##C'; cat /sys/devices/system/cpu/present 2>/dev/null; " +
        "echo '##U'; cat /proc/uptime; " +
        "echo '##D'; df -k /data 2>/dev/null; " +
        "echo '##N'; grep -E '^ *(wlan|eth)[0-9]+:' /proc/net/dev 2>/dev/null"
    // Sıcaklık bilerek yok: v0.106'da saniyede bir tüm sıcaklık sensörlerini okumak Serif'in Philips TV'sini
    // yeniden başlattı. Ayrıştırıcı ([temperature], "##H" bölümü) yerinde; tekrar eklenecekse tek sensör ve seyrek.

    /** Bir işlemci satırının sayaçları: toplam ve boşta geçen süre (jiffy). */
    data class Ticks(val total: Long, val idle: Long)

    /** Tek ölçüm: [cpu] toplam, [cores] çekirdek numarasına göre (kapalı çekirdek listede olmaz). */
    data class Sample(
        val cpu: Ticks?, val cores: Map<Int, Ticks>, val coreCount: Int, val totalKb: Long, val availKb: Long, val tempC: Int? = null,
        /** Açık kalma süresi (sn), /data toplam ve dolu (KB), ağdan gelen/giden toplam bayt (wlan + eth). */
        val uptimeSec: Long? = null, val dataTotalKb: Long? = null, val dataUsedKb: Long? = null,
        val rxBytes: Long? = null, val txBytes: Long? = null
    )

    /** Ekranda gösterilen değerler; yüzde null = henüz ölçülmedi, [offline] = TV'nin o an kapattığı çekirdekler. */
    data class View(
        val cpu: Int?, val cores: List<Int?>, val offline: Set<Int>, val totalKb: Long, val availKb: Long, val tempC: Int? = null,
        val uptimeSec: Long? = null, val dataTotalKb: Long? = null, val dataUsedKb: Long? = null,
        /** Saniyede indirilen / yüklenen bayt; ilk ölçümde null. */
        val rxRate: Long? = null, val txRate: Long? = null
    ) {
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
        val uptime = sec["U"]?.firstOrNull()?.split(' ')?.firstOrNull()?.toDoubleOrNull()?.toLong()
        val df = disk(sec["D"].orEmpty())
        val net = netBytes(sec["N"].orEmpty())
        return Sample(cpu, cores, count, kb("MemTotal"), kb("MemAvailable"), temperature(sec["H"]?.firstOrNull()),
            uptime, df?.first, df?.second, net?.first, net?.second)
    }

    /** `df -k /data` satırlarından (toplam, dolu) KB; dolu = toplam − boş (sistem payı dahil, Ayarlar'daki gibi). */
    fun disk(lines: List<String>): Pair<Long, Long>? {
        // Uzun aygıt adında satır ikiye bölünebilir: sayılar /data ile biten satırda
        val l = lines.lastOrNull { it.trim().endsWith("/data") } ?: return null
        val n = l.trim().split(Regex("\\s+")).mapNotNull { it.toLongOrNull() }
        if (n.size < 3 || n[0] <= 0) return null
        return n[0] to (n[0] - n[2]).coerceAtLeast(0)
    }

    /** `/proc/net/dev` satırları ("wlan0: alınan_bayt ... gönderilen_bayt ...") → (alınan, gönderilen) toplamı. */
    fun netBytes(lines: List<String>): Pair<Long, Long>? {
        var rx = 0L; var tx = 0L; var any = false
        for (l in lines) {
            if (!l.contains(':')) continue
            val n = l.substringAfter(':').trim().split(Regex("\\s+")).mapNotNull { it.toLongOrNull() }
            if (n.size < 9) continue
            rx += n[0]; tx += n[8]; any = true
        }
        return if (any) rx to tx else null
    }

    /** İki sayaç ve aradaki süreden saniyelik hız; sayaç sıfırlandıysa (arayüz yeniden kalktı) null. */
    fun rate(a: Long?, b: Long?, ms: Long): Long? {
        if (a == null || b == null || ms <= 0 || b < a) return null
        return (b - a) * 1000 / ms
    }

    /** Bayt/sn → "1 KB/sn", "2,4 MB/sn". */
    fun rateText(bps: Long?): String = when {
        bps == null -> "—"
        bps < 1024 * 1024 -> "${Math.round(bps / 1024.0)} KB/sn"
        else -> String.format(java.util.Locale.forLanguageTag("tr"), "%.1f MB/sn", bps / 1048576.0)
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
    fun view(prev: Sample?, now: Sample, elapsedMs: Long = 1000): View = View(
        cpu = percent(prev?.cpu, now.cpu),
        cores = (0 until now.coreCount).map { percent(prev?.cores?.get(it), now.cores[it]) },
        offline = (0 until now.coreCount).filter { it !in now.cores }.toSet(),
        totalKb = now.totalKb,
        availKb = now.availKb,
        tempC = now.tempC,
        uptimeSec = now.uptimeSec,
        dataTotalKb = now.dataTotalKb,
        dataUsedKb = now.dataUsedKb,
        rxRate = rate(prev?.rxBytes, now.rxBytes, elapsedMs),
        txRate = rate(prev?.txBytes, now.txBytes, elapsedMs)
    )

    // ---------------- Genel bakış: durum, ağ, donanım ----------------

    /**
     * Pencere açılınca ve "Yenile"de (saniyede bir değil): W = güç durumu, F = ekrandaki uygulama,
     * S = Wi-Fi bilgisi, N = IP adresleri.
     */
    const val STATUS_COMMAND = "echo '##W'; dumpsys power 2>/dev/null | grep -m1 'mWakefulness='; " +
        "echo '##F'; dumpsys activity activities 2>/dev/null | grep -m1 -E 'mResumedActivity|topResumedActivity'; " +
        "echo '##S'; dumpsys wifi 2>/dev/null | grep -m1 'mWifiInfo'; " +
        "echo '##N'; ip -4 -o addr show 2>/dev/null"

    /** Bağlantı başına bir kez: donanım bilgileri değişmez. P = getprop, C = cpuinfo, W = wm, Y = görüntü modu. */
    const val HARDWARE_COMMAND = "echo '##P'; getprop; echo '##C'; cat /proc/cpuinfo; " +
        "echo '##W'; wm size; wm density; echo '##Y'; dumpsys display 2>/dev/null | grep -m1 'mBaseDisplayInfo'"

    enum class Power { ON, SLEEP }

    data class Net(
        val wired: Boolean, val ip: String?, val ssid: String?, val rssi: Int?, val freqMhz: Int?, val linkMbps: Int?
    )

    data class Status(val power: Power?, val foreground: String?, val net: Net)

    fun parseStatus(out: String): Status {
        val s = SysInfo.sections(out)
        val wake = Regex("""mWakefulness=(\w+)""").find(s["W"].orEmpty())?.groupValues?.get(1)
        val power = when {
            wake == null -> null
            wake.equals("Awake", true) -> Power.ON
            else -> Power.SLEEP // Asleep, Dozing, Dreaming
        }
        return Status(power, parseForeground(s["F"].orEmpty()), parseNet(s["S"].orEmpty(), s["N"].orEmpty()))
    }

    fun parseNet(wifi: String, ipText: String): Net {
        val lines = ipText.lines().filter { it.contains("inet ") && !it.contains(" lo ") }
        val line = lines.firstOrNull { it.contains("eth") } ?: lines.firstOrNull { it.contains("wlan") } ?: lines.firstOrNull()
        val ip = line?.let { Regex("""inet (\d+\.\d+\.\d+\.\d+)""").find(it)?.groupValues?.get(1) }
        val wired = line?.trim()?.split(Regex("\\s+"))?.getOrNull(1)?.startsWith("eth") == true
        val ssid = Regex("""SSID: "?([^",]+)"?""").find(wifi)?.groupValues?.get(1)?.trim()
            ?.takeIf { it.isNotBlank() && it != "<unknown ssid>" }
        val rssi = Regex("""RSSI: (-?\d+)""").find(wifi)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in -120..-1 }
        val freq = Regex("""Frequency: (\d+)""").find(wifi)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it > 0 }
        val link = Regex("""Link speed: (\d+)""").find(wifi)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it > 0 }
        return Net(wired, ip, ssid, rssi, freq, link)
    }

    /** Ağ kartındaki kısa öneri. */
    fun advice(n: Net): String {
        val r = n.rssi
        return when {
            n.wired -> "Kablolu bağlantı, en sağlam yol. Yapılacak bir şey yok."
            r == null -> "Wi-Fi bilgisi okunamadı."
            r < -75 -> "Sinyal zayıf: modemi TV'ye yaklaştır, araya duvar girmesin ya da kablo kullan."
            r < -67 -> "Sinyal orta: 4K yayında takılma olursa modemi yaklaştırmak işe yarar."
            n.freqMhz != null && n.freqMhz < 3000 -> "Sinyal iyi. 5 GHz ağın varsa ona geçmek daha hızlı olur."
            else -> "Sinyal iyi, yapılacak bir şey yok."
        }
    }

    /** Donanım kartı satırları; RAM ayrı verilir ki bellek kartıyla aynı sayı görünsün. */
    fun hardware(out: String, ramKb: Long?): List<Pair<String, String>> {
        val s = SysInfo.sections(out)
        val p = SysInfo.props(s["P"].orEmpty())
        fun prop(vararg keys: String) = keys.firstNotNullOfOrNull { k -> p[k]?.trim()?.takeIf { it.isNotEmpty() } }
        val cpu = SysInfo.cpu(s["C"].orEmpty())
        val rows = ArrayList<Pair<String, String>>()
        val model = prop("ro.product.model", "ro.product.vendor.model")
        val brand = prop("ro.product.brand", "ro.product.vendor.brand")?.replaceFirstChar { it.uppercase() }
        when {
            model != null && brand != null && !model.contains(brand, true) -> rows += "Cihaz" to "$brand $model"
            model != null -> rows += "Cihaz" to model
            brand != null -> rows += "Cihaz" to brand
        }
        prop("ro.build.version.release")?.let { r -> rows += "Android" to "$r${prop("ro.build.version.sdk")?.let { " (SDK $it)" }.orEmpty()}" }
        prop("ro.build.version.security_patch")?.let { rows += "Güvenlik yaması" to it }
        listOfNotNull(
            prop("ro.soc.model")?.let { m -> prop("ro.soc.manufacturer")?.let { "$it $m" } ?: m },
            cpu.hardware, prop("ro.hardware.chipname"), prop("ro.board.platform"), prop("ro.hardware")
        ).firstOrNull { it.isNotBlank() }?.let { rows += "Yonga" to SysInfo.chipName(it) }
        prop("ro.product.cpu.abi", "ro.product.cpu.abilist")?.substringBefore(',')?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { rows += "Mimari" to it }
        when {
            cpu.cores.isNotEmpty() -> rows += "İşlemci çekirdeği" to cpu.cores
            cpu.count > 0 -> rows += "İşlemci çekirdeği" to "${cpu.count}"
        }
        ramKb?.takeIf { it > 0 }?.let { rows += "RAM" to SysInfo.gb(it) }
        val wm = s["W"].orEmpty()
        fun wmPick(what: String): String? {
            val all = Regex("""(Override|Physical) $what:\s*(\S+)""").findAll(wm).toList()
            return (all.firstOrNull { it.groupValues[1] == "Override" } ?: all.firstOrNull())?.groupValues?.get(2)
        }
        val disp = s["Y"].orEmpty()
        val curId = Regex("""modeId (\d+)""").find(disp)?.groupValues?.get(1)?.toIntOrNull()
        val fps = SysInfo.displayModes(disp).firstOrNull { it.id == curId }?.fps
        wmPick("size")?.let { Regex("""(\d+)x(\d+)""").find(it) }?.let { m ->
            rows += "Ekran" to "${m.groupValues[1]}×${m.groupValues[2]}" + (fps?.let { " · ${Math.round(it)} Hz" }.orEmpty())
        }
        wmPick("density")?.let { rows += "Piksel yoğunluğu" to "$it dpi" }
        return rows
    }

    /** "En çok işlemci kullananlar": işlemciye, eşitse belleğe göre ilk [n]. */
    fun topCpu(apps: List<AppUse>, n: Int = 5): List<AppUse> =
        apps.sortedWith(compareByDescending<AppUse> { it.cpu ?: -1.0 }.thenByDescending { it.kb }).take(n)

    // ---------------- Çalışan uygulamalar ----------------

    /**
     * Toplam işlemci sayacı + her sürecin adı, RSS'i (KB) ve harcadığı işlemci süresi.
     * Tek `ps` ile (TIME+ = yüzde bir saniye = jiffy); `ps` TIME+ bilmiyorsa eski yol: `/proc/[pid]/stat`.
     */
    const val APPS_COMMAND = "echo '##S'; grep '^cpu ' /proc/stat; echo '##P'; " +
        "ps -A -o PID,RSS,TIME+,NAME 2>/dev/null || { ps -A -o PID,RSS,NAME 2>/dev/null; echo '##T'; cat /proc/[0-9]*/stat 2>/dev/null; }"

    /** Ana ekranda simgesi olan (açılabilen) uygulamalar; kısa liste boşsa uzun çıktıdan. */
    const val LAUNCHABLE_COMMAND = "o=$(cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.LEANBACK_LAUNCHER 2>/dev/null); " +
        "[ -n \"\$o\" ] && echo \"\$o\" || cmd package query-activities -a android.intent.action.MAIN -c android.intent.category.LEANBACK_LAUNCHER 2>/dev/null | grep packageName="

    /** "com.x/.Main" ya da "packageName=com.x" satırlarından paket adları. */
    fun parseLaunchable(out: String): Set<String> =
        (Regex("""([A-Za-z][\w]*(?:\.[\w]+)+)/""").findAll(out).map { it.groupValues[1] } +
            Regex("""packageName=([\w.]+)""").findAll(out).map { it.groupValues[1] }).toSet()

    /**
     * Listeyi ikiye ayırır: kullanıcının uygulamaları (ana ekranda simgesi olan ya da sonradan kurulan) ve sistem.
     * Philips'te YouTube/Netflix gibi uygulamalar fabrikadan sistem uygulaması olarak gelir; simgeleri olduğu için
     * kullanıcı tarafında sayılır. Bilgi henüz gelmediyse [protected] olanlar sisteme, kalanlar kullanıcıya.
     */
    fun isUserApp(pkg: String, launchable: Set<String>?, installedByUser: Boolean?, protected: Boolean): Boolean = when {
        launchable == null && installedByUser == null -> !protected
        pkg in launchable.orEmpty() -> true
        installedByUser == true -> true
        else -> false
    }

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
