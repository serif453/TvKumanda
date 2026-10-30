package com.serif.tvkumanda

/**
 * Sistem bilgisi aracının saf (Android'siz) mantığı: TV'de tek kabuk komutu ([COMMAND]) çalışır,
 * çıktısı burada bölümlere ayrılmış, sade Türkçe satırlara çevrilir. Birim testleri `SysInfoTest`'te.
 */
object SysInfo {
    data class Section(val title: String, val rows: List<Pair<String, String>>)
    data class Bar(val label: String, val usedKb: Long, val totalKb: Long)
    data class Report(val sections: List<Section>, val bars: List<Bar>, val raw: String) {
        /** Panoya kopyalanan düzenli özet. */
        fun summary(): String = sections.joinToString("\n\n") { s ->
            s.title + "\n" + s.rows.joinToString("\n") { "${it.first}: ${it.second}" }
        }
    }

    /** Bölümler "##X" satırlarıyla ayrılır. */
    val COMMAND: String = listOf(
        "echo '##P'; getprop",
        "echo '##C'; cat /proc/cpuinfo",
        "echo '##F'; for c in /sys/devices/system/cpu/cpu[0-9]*; do echo \"\$(basename \$c) \$(cat \$c/cpufreq/scaling_cur_freq 2>/dev/null) \$(cat \$c/cpufreq/cpuinfo_max_freq 2>/dev/null)\"; done",
        "echo '##T'; for z in /sys/class/thermal/thermal_zone*; do echo \"\$(cat \$z/type 2>/dev/null) \$(cat \$z/temp 2>/dev/null)\"; done",
        "echo '##M'; cat /proc/meminfo",
        "echo '##D'; df -k /data 2>/dev/null",
        "echo '##E'; df -k 2>/dev/null | grep -E 'media_rw|/storage/[0-9A-Fa-f]{4}-'",
        "echo '##W'; wm size; wm density",
        "echo '##Y'; dumpsys display 2>/dev/null | grep -m1 'mBaseDisplayInfo'",
        "echo '##N'; ip -4 -o addr show 2>/dev/null",
        "echo '##L'; for i in wlan0 eth0; do echo \"\$i \$(cat /sys/class/net/\$i/address 2>/dev/null) \$(cat /sys/class/net/\$i/operstate 2>/dev/null)\"; done",
        "echo '##S'; dumpsys wifi 2>/dev/null | grep -m1 'mWifiInfo'",
        "echo '##K'; uname -r",
        "echo '##U'; cat /proc/uptime",
        "echo '##A'; p=\$(pm list packages 2>/dev/null); echo \"\$p\" | grep -c package:; pm list packages -3 2>/dev/null | grep -c package:; echo \"\$p\" | grep -E 'launcherx|tvlauncher'"
    ).joinToString("; ")

    // ---------------- Ayrıştırma ----------------

    fun sections(out: String): Map<String, String> {
        val map = LinkedHashMap<String, StringBuilder>()
        var cur = ""
        for (line in out.lines()) {
            if (line.startsWith("##") && line.length in 3..4) { cur = line.removePrefix("##").trim(); map.getOrPut(cur) { StringBuilder() } }
            else map[cur]?.append(line)?.append('\n')
        }
        return map.mapValues { it.value.toString().trimEnd() }
    }

    fun props(text: String): Map<String, String> {
        val r = LinkedHashMap<String, String>()
        val re = Regex("""^\[(.+?)]: \[(.*)]$""")
        for (l in text.lines()) re.find(l.trim())?.let { r[it.groupValues[1]] = it.groupValues[2] }
        return r
    }

    fun memKb(text: String): Map<String, Long> {
        val r = HashMap<String, Long>()
        val re = Regex("""^([A-Za-z_()]+):\s+(\d+)""")
        for (l in text.lines()) re.find(l.trim())?.let { r[it.groupValues[1]] = it.groupValues[2].toLong() }
        return r
    }

    /** ARM çekirdek kodları (CPU implementer 0x41). */
    private val armCores = mapOf(
        0xc05 to "Cortex-A5", 0xc07 to "Cortex-A7", 0xc08 to "Cortex-A8", 0xc09 to "Cortex-A9", 0xc0d to "Cortex-A17",
        0xc0e to "Cortex-A17", 0xc0f to "Cortex-A15", 0xd01 to "Cortex-A32", 0xd03 to "Cortex-A53", 0xd04 to "Cortex-A35",
        0xd05 to "Cortex-A55", 0xd06 to "Cortex-A65", 0xd07 to "Cortex-A57", 0xd08 to "Cortex-A72", 0xd09 to "Cortex-A73",
        0xd0a to "Cortex-A75", 0xd0b to "Cortex-A76", 0xd0d to "Cortex-A77", 0xd41 to "Cortex-A78", 0xd44 to "Cortex-X1",
        0xd46 to "Cortex-A510", 0xd47 to "Cortex-A710", 0xd48 to "Cortex-X2", 0xd4d to "Cortex-A715"
    )

    data class Cpu(val count: Int, val cores: String, val arch: String?, val hardware: String?)

    fun cpu(text: String): Cpu {
        val blocks = text.split(Regex("""\n\s*\n"""))
        val parts = ArrayList<String>()
        var count = 0
        var arch: String? = null
        var hardware: String? = null
        for (b in blocks) {
            fun field(name: String) = Regex("""(?im)^$name\s*:\s*(.+)$""").find(b)?.groupValues?.get(1)?.trim()
            if (field("processor") != null) {
                count++
                val impl = field("CPU implementer")?.removePrefix("0x")?.toIntOrNull(16)
                val part = field("CPU part")?.removePrefix("0x")?.toIntOrNull(16)
                if (impl == 0x41 && part != null) armCores[part]?.let { parts += it }
                if (arch == null) arch = field("CPU architecture")
            }
            field("Hardware")?.let { hardware = it }
        }
        val cores = parts.groupingBy { it }.eachCount().entries.joinToString(" + ") { "${it.value} × ${it.key}" }
        return Cpu(count, cores, arch, hardware)
    }

    fun chipName(raw: String): String {
        val s = raw.trim()
        return when {
            s.isEmpty() -> s
            s.contains("mediatek", true) || s.contains("amlogic", true) || s.contains("qualcomm", true) || s.contains("rockchip", true) -> s
            Regex("""(?i)^mt\d""").containsMatchIn(s) -> "MediaTek ${s.uppercase()}"
            Regex("""(?i)^(s9\d\d|t9\d\d|a311|s805|s812)""").containsMatchIn(s) -> "Amlogic ${s.uppercase()}"
            Regex("""(?i)^(sm|sdm|msm|apq|qcs)\d""").containsMatchIn(s) -> "Qualcomm ${s.uppercase()}"
            Regex("""(?i)^rk\d""").containsMatchIn(s) -> "Rockchip ${s.uppercase()}"
            else -> s
        }
    }

    fun ghz(khz: Long): String = if (khz >= 1_000_000) "%.1f GHz".format(khz / 1_000_000.0) else "${khz / 1000} MHz"

    /** Sıcaklık bölgeleri ("tür değer") → °C; işlemciye ait bölge öne alınır. */
    fun temperature(text: String): Int? {
        val zones = text.lines().mapNotNull { l ->
            val p = l.trim().split(Regex("\\s+"))
            if (p.size < 2) return@mapNotNull null
            val v = p.last().toLongOrNull() ?: return@mapNotNull null
            val c = if (v > 1000) (v / 1000).toInt() else v.toInt()
            if (c in 1..150) p.dropLast(1).joinToString(" ") to c else null
        }
        if (zones.isEmpty()) return null
        return (zones.firstOrNull { Regex("(?i)cpu|soc|tscpu|ap_ntc|tsens").containsMatchIn(it.first) } ?: zones.maxByOrNull { it.second })?.second
    }

    fun resolutionLabel(w: Int, h: Int): String {
        val long = maxOf(w, h); val short = minOf(w, h)
        val tag = when {
            long >= 7680 -> "8K"
            long >= 3840 && short >= 2160 -> "4K"
            long >= 2560 && short >= 1440 -> "QHD"
            long >= 1920 && short >= 1080 -> "Full HD"
            long >= 1280 && short >= 720 -> "HD"
            else -> null
        }
        return "$w × $h" + (tag?.let { " ($it)" } ?: "")
    }

    data class DisplayMode(val id: Int, val w: Int, val h: Int, val fps: Double)

    fun displayModes(text: String): List<DisplayMode> =
        Regex("""\{id=(\d+), width=(\d+), height=(\d+), fps=([\d.]+)""").findAll(text).map {
            DisplayMode(it.groupValues[1].toInt(), it.groupValues[2].toInt(), it.groupValues[3].toInt(), it.groupValues[4].toDouble())
        }.toList()

    private val hdrNames = mapOf(1 to "Dolby Vision", 2 to "HDR10", 3 to "HLG", 4 to "HDR10+")

    /** null: bilgi yok; boş liste: HDR yok. */
    fun hdrTypes(text: String): List<String>? {
        val m = Regex("""mSupportedHdrTypes=\[([\d,\s]*)]""").find(text) ?: return null
        return m.groupValues[1].split(',').mapNotNull { it.trim().toIntOrNull() }.distinct().map { hdrNames[it] ?: "tür $it" }
    }

    fun rssiQuality(rssi: Int): String = when {
        rssi >= -55 -> "çok iyi"
        rssi >= -67 -> "iyi"
        rssi >= -75 -> "orta"
        else -> "zayıf"
    }

    fun band(mhz: Int): String = when {
        mhz < 3000 -> "2.4 GHz"
        mhz < 5925 -> "5 GHz"
        else -> "6 GHz"
    }

    fun duration(sec: Long): String {
        val d = sec / 86400; val h = (sec % 86400) / 3600; val m = (sec % 3600) / 60
        return when {
            d > 0 -> "$d gün $h saat"
            h > 0 -> "$h saat $m dakika"
            else -> "$m dakika"
        }
    }

    fun mb(kb: Long): String = if (kb >= 1024 * 1024) "%.1f GB".format(kb / 1048576.0) else "${kb / 1024} MB"
    fun gb(kb: Long): String = "%.1f GB".format(kb / 1048576.0)

    private val secretKey = Regex("(?i)serial|mac|imei|meid|bt_addr|ethaddr|wifiaddr|android_id|ssid|bssid")

    /** Ham bilgiler: işlemci, bellek ve sistem özellikleri; seri numarası ve MAC gibi değerler gizlenir. */
    fun raw(s: Map<String, String>): String {
        val props = props(s["P"].orEmpty()).entries.joinToString("\n") { (k, v) ->
            "[$k]: [${if (secretKey.containsMatchIn(k) && v.isNotEmpty()) "gizlendi" else v}]"
        }
        return buildString {
            append("=== İşlemci (/proc/cpuinfo) ===\n").append(s["C"].orEmpty().trim()).append("\n\n")
            append("=== Bellek (/proc/meminfo) ===\n").append(s["M"].orEmpty().trim()).append("\n\n")
            append("=== Ekran (dumpsys display) ===\n").append(s["Y"].orEmpty().trim()).append("\n\n")
            append("=== Sistem özellikleri (getprop) ===\n").append(props)
        }
    }

    private val ipRe = Regex("""(?<![\w.])(\d{1,3}\.\d{1,3}\.\d{1,3})\.\d{1,3}(?![\w.])""")
    private val macRe = Regex("""\b([0-9A-Fa-f]{2}:[0-9A-Fa-f]{2}:[0-9A-Fa-f]{2})(:[0-9A-Fa-f]{2}){3}\b""")

    /** Paylaşılacak dosya: özet + ham bilgiler; IP'nin son hanesi ve MAC'in son yarısı gizlenir. */
    fun shareText(r: Report): String {
        val body = "TV bilgileri (TV Kumanda)\n\n" + r.summary() + "\n\n" + r.raw + "\n"
        return macRe.replace(ipRe.replace(body) { "${it.groupValues[1]}.x" }) { "${it.groupValues[1]}:xx:xx:xx" }
    }

    // ---------------- Rapor ----------------

    fun build(out: String): Report {
        val s = sections(out)
        val p = props(s["P"].orEmpty())
        fun prop(vararg keys: String) = keys.firstNotNullOfOrNull { k -> p[k]?.trim()?.takeIf { it.isNotEmpty() } }
        val secs = ArrayList<Section>()
        val bars = ArrayList<Bar>()

        // Cihaz
        val launchers = s["A"].orEmpty().lines().map { it.trim() }
        val tvType = when {
            launchers.any { it.contains("launcherx") } -> "Google TV"
            launchers.any { it.contains("tvlauncher") } -> "Android TV"
            prop("ro.build.characteristics")?.contains("tv") == true -> "Android TV"
            else -> null
        }
        val brand = prop("ro.product.brand", "ro.product.vendor.brand")?.replaceFirstChar { it.uppercase() }
        val maker = prop("ro.product.manufacturer", "ro.product.vendor.manufacturer")
        val build = prop("ro.build.display.id")
        val incremental = prop("ro.build.version.incremental")
        secs += Section("Cihaz", buildList {
            brand?.let { add("Marka" to it) }
            maker?.takeIf { !it.equals(brand, true) }?.let { add("Üretici" to it) }
            prop("ro.product.model", "ro.product.vendor.model")?.let { add("Model" to it) }
            val board = prop("ro.product.board", "ro.board.platform")
            val device = prop("ro.product.device", "ro.product.vendor.device")
            listOfNotNull(board, device).distinct().joinToString(" · ").takeIf { it.isNotEmpty() }?.let { add("Kart / cihaz kodu" to it) }
            tvType?.let { add("Sistem" to it) }
            prop("ro.build.version.release")?.let { r -> add("Android sürümü" to "Android $r${prop("ro.build.version.sdk")?.let { " (API $it)" }.orEmpty()}") }
            prop("ro.build.version.security_patch")?.let { add("Güvenlik yaması" to it) }
            val sw = when {
                build != null && incremental != null && !build.contains(incremental) -> "$build ($incremental)"
                build != null -> build
                else -> incremental
            }
            sw?.let { add("Yazılım sürümü" to it) }
        })

        // İşlemci
        val cpuInfo = cpu(s["C"].orEmpty())
        val chipRaw = listOfNotNull(
            prop("ro.soc.model")?.let { m -> prop("ro.soc.manufacturer")?.let { "$it $m" } ?: m },
            cpuInfo.hardware, prop("ro.hardware.chipname"), prop("ro.board.platform"), prop("ro.hardware")
        ).firstOrNull { it.isNotBlank() }
        val abis = prop("ro.product.cpu.abilist")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
        val freqs = s["F"].orEmpty().lines().mapNotNull { l ->
            val parts = l.trim().split(Regex("\\s+"))
            if (parts.isEmpty() || !parts[0].startsWith("cpu")) null else parts.getOrNull(1)?.toLongOrNull() to parts.getOrNull(2)?.toLongOrNull()
        }
        val cur = freqs.mapNotNull { it.first }.takeIf { it.isNotEmpty() }?.average()?.toLong()
        val max = freqs.mapNotNull { it.second }.maxOrNull()
        val temp = temperature(s["T"].orEmpty())
        secs += Section("İşlemci", buildList {
            chipRaw?.let { add("Çip" to chipName(it)) }
            val count = if (cpuInfo.count > 0) cpuInfo.count else freqs.size
            when {
                cpuInfo.cores.isNotEmpty() -> add("Çekirdekler" to cpuInfo.cores)
                count > 0 -> add("Çekirdekler" to "$count çekirdek")
            }
            cpuInfo.arch?.let { a -> add("Mimari" to if (a == "8") "ARMv8 (64 bit işlemci)" else if (a == "7") "ARMv7 (32 bit)" else "ARM $a") }
            if (abis.isNotEmpty()) {
                add("Uygulama türü" to when {
                    abis.contains("arm64-v8a") -> "64 ve 32 bit (${abis.joinToString(", ")})"
                    abis.contains("armeabi-v7a") -> "Yalnızca 32 bit (armeabi-v7a). APK indirirken \"armeabi-v7a\" ya da \"arm\" sürümünü seç"
                    else -> abis.joinToString(", ")
                })
            }
            when {
                cur != null && max != null -> add("Hız" to "şu an ${ghz(cur)} · en çok ${ghz(max)}")
                max != null -> add("Hız" to "en çok ${ghz(max)}")
            }
            temp?.let { add("Sıcaklık" to "$it °C") }
        })

        // Bellek
        val mem = memKb(s["M"].orEmpty())
        val total = mem["MemTotal"]
        val avail = mem["MemAvailable"] ?: mem["MemFree"]
        if (total != null && avail != null) bars += Bar("RAM", (total - avail).coerceAtLeast(0), total)
        secs += Section("Bellek", buildList {
            total?.let { add("Toplam RAM" to mb(it)) }
            avail?.let { add("Kullanılabilir" to mb(it)) }
            val st = mem["SwapTotal"]; val sf = mem["SwapFree"]
            if (st != null && st > 0) add("Takas alanı" to "${mb((st - (sf ?: 0)).coerceAtLeast(0))} / ${mb(st)} kullanılıyor")
        })

        // Depolama
        val dfLine = s["D"].orEmpty().lines().lastOrNull { it.trim().endsWith("/data") }
        val dfNums = dfLine?.trim()?.split(Regex("\\s+"))?.mapNotNull { it.toLongOrNull() }.orEmpty()
        val storage = ArrayList<Pair<String, String>>()
        if (dfNums.size >= 3) {
            val t = dfNums[0]; val free = dfNums[2]
            bars += Bar("Depolama", (t - free).coerceAtLeast(0), t)
            storage += "Dahili" to "${gb(t - free)} / ${gb(t)} dolu · ${gb(free)} boş"
        }
        s["E"].orEmpty().lines().filter { it.isNotBlank() }.forEach { l ->
            val parts = l.trim().split(Regex("\\s+"))
            val nums = parts.mapNotNull { it.toLongOrNull() }
            if (nums.size >= 3 && nums[0] > 0) storage += "USB bellek" to "${gb(nums[0] - nums[2])} / ${gb(nums[0])} dolu"
        }
        if (storage.isNotEmpty()) secs += Section("Depolama", storage.distinct())

        // Ekran
        val wm = s["W"].orEmpty()
        fun wmPick(what: String): String? {
            val all = Regex("""(Override|Physical) $what:\s*(\S+)""").findAll(wm).toList()
            return (all.firstOrNull { it.groupValues[1] == "Override" } ?: all.firstOrNull())?.groupValues?.get(2)
        }
        val disp = s["Y"].orEmpty()
        val modes = displayModes(disp)
        val curId = Regex("""modeId (\d+)""").find(disp)?.groupValues?.get(1)?.toIntOrNull()
        val curMode = modes.firstOrNull { it.id == curId }
        secs += Section("Ekran", buildList {
            wmPick("size")?.let { v ->
                Regex("""(\d+)x(\d+)""").find(v)?.let { add("Arayüz çözünürlüğü" to resolutionLabel(it.groupValues[1].toInt(), it.groupValues[2].toInt())) }
            }
            curMode?.let { add("Görüntü modu" to "${resolutionLabel(it.w, it.h)} · ${"%.0f".format(it.fps)} Hz") }
            val rates = modes.map { Math.round(it.fps).toInt() }.distinct().sorted()
            if (rates.size > 1) add("Yenileme hızları" to rates.joinToString(", ") + " Hz")
            wmPick("density")?.let { add("Yoğunluk" to "$it dpi") }
            hdrTypes(disp)?.let { add("HDR" to if (it.isEmpty()) "Desteklenmiyor" else it.joinToString(", ")) }
        })

        // Ağ
        val net = s["N"].orEmpty().lines().filter { !it.contains(" lo ") && it.contains("inet ") }
        val ipLine = net.firstOrNull { it.contains("eth") } ?: net.firstOrNull { it.contains("wlan") } ?: net.firstOrNull()
        val ip = ipLine?.let { Regex("""inet (\d+\.\d+\.\d+\.\d+)""").find(it)?.groupValues?.get(1) }
        val iface = ipLine?.trim()?.split(Regex("\\s+"))?.getOrNull(1)
        val wired = iface?.startsWith("eth") == true
        val wifi = s["S"].orEmpty()
        val ssid = Regex("""SSID: "?([^",]+)"?""").find(wifi)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotBlank() && it != "<unknown ssid>" }
        val rssi = Regex("""RSSI: (-?\d+)""").find(wifi)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in -120..-1 }
        val freq = Regex("""Frequency: (\d+)""").find(wifi)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it > 0 }
        val link = Regex("""Link speed: (\d+)""").find(wifi)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it > 0 }
        val macs = s["L"].orEmpty().lines().mapNotNull { l ->
            val parts = l.trim().split(Regex("\\s+"))
            val m = parts.getOrNull(1)?.takeIf { Regex("""^[0-9a-fA-F]{2}(:[0-9a-fA-F]{2}){5}$""").matches(it) && it != "02:00:00:00:00:00" }
            if (m != null) parts[0] to m else null
        }.toMap()
        val wifiMac = Regex("""MAC: ([0-9a-fA-F:]{17})""").find(wifi)?.groupValues?.get(1)?.takeIf { it != "02:00:00:00:00:00" }
        secs += Section("Ağ", buildList {
            when {
                wired -> add("Bağlantı" to "Kablolu (Ethernet)")
                ip != null || ssid != null -> add("Bağlantı" to (ssid?.let { "Wi-Fi: $it" } ?: "Wi-Fi"))
            }
            ip?.let { add("IP adresi" to it) }
            (if (wired) macs["eth0"] else macs["wlan0"] ?: wifiMac)?.let { add("MAC adresi" to it.lowercase()) }
            if (!wired) {
                rssi?.let { add("Sinyal" to "$it dBm (${rssiQuality(it)})") }
                freq?.let { add("Bant" to "${band(it)} ($it MHz)") }
                link?.let { add("Bağlantı hızı" to "$it Mbps") }
            }
        })

        // Sistem
        val apps = s["A"].orEmpty().lines().map { it.trim() }
        val allApps = apps.getOrNull(0)?.toIntOrNull()
        val userApps = apps.getOrNull(1)?.toIntOrNull()
        secs += Section("Sistem", buildList {
            s["U"].orEmpty().trim().split(' ').firstOrNull()?.toDoubleOrNull()?.let { add("Açık kalma süresi" to duration(it.toLong())) }
            if (allApps != null && allApps > 0) add("Uygulamalar" to "$allApps${userApps?.let { " ($it tanesini sen kurdun)" }.orEmpty()}")
            s["K"].orEmpty().trim().lines().firstOrNull()?.takeIf { it.isNotBlank() }?.let { add("Çekirdek (kernel)" to it) }
        })

        return Report(secs.filter { it.rows.isNotEmpty() }, bars, raw(s))
    }
}
