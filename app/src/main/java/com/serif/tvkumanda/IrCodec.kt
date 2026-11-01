package com.serif.tvkumanda

/**
 * Kızılötesi (IR) kumanda sinyalleri: veritabanındaki kod → telefonun IR vericisine giden süre dizisi.
 * Saf kod (Android'siz), testlerle doğrulanır.
 *
 * Kodlar Flipper-IRDB'den (CC0) gelir; adres/komut alanlarının anlamı Flipper Zero yazılımının kodlayıcılarıyla
 * birebir aynıdır (lib/infrared/encoder_decoder), çünkü kodlar o cihazla kaydedildi.
 * Süre dizisi işaret (ışık yanık) ile başlar, işaret/boşluk diye sırayla gider, mikrosaniyedir.
 */
object IrCodec {

    class Signal(val carrierHz: Int, val pattern: IntArray)

    /** Veritabanındaki tek bir tuşun kodu. */
    sealed class Code {
        data class Parsed(val protocol: String, val address: Long, val command: Long) : Code()
        class Raw(val carrierHz: Int, val data: IntArray) : Code()
    }

    val PROTOCOLS = setOf("NEC", "NECext", "Samsung32", "RC5", "RC5X", "RC6", "SIRC", "SIRC15", "SIRC20", "Kaseikyo", "RCA", "Pioneer")

    /** "P,RC6,0,C" ya da "W,38000,9000 4500 ..." */
    fun parseCode(s: String): Code? {
        val p = s.split(',', limit = 4)
        return when (p.getOrNull(0)) {
            "P" -> {
                if (p.size < 4 || p[1] !in PROTOCOLS) return null
                Code.Parsed(p[1], p[2].toLongOrNull(16) ?: return null, p[3].toLongOrNull(16) ?: return null)
            }
            "W" -> {
                val f = p.getOrNull(1)?.toIntOrNull() ?: return null
                val d = p.getOrNull(2)?.trim()?.split(' ')?.mapNotNull { it.toIntOrNull() }?.filter { it > 0 } ?: return null
                if (d.size < 2) null else Code.Raw(f, d.toIntArray())
            }
            else -> null
        }
    }

    /** [toggle] RC5/RC6'da tuşa her yeni basışta değişir; TV aynı tuşa iki ayrı basışı böyle ayırt eder. */
    fun encode(code: Code, toggle: Boolean = false): Signal = when (code) {
        is Code.Raw -> Signal(code.carrierHz, code.data.copyOf())
        is Code.Parsed -> encodeParsed(code, toggle)
    }

    // ---------------- Süre dizisi kurucu ----------------

    private class Pulses {
        private val out = ArrayList<Int>()
        /** Sıradaki öğe işaret mi (dizinin çift sıraları işarettir). */
        private val nextIsMark get() = out.size % 2 == 0
        fun mark(us: Int) = add(true, us)
        fun space(us: Int) = add(false, us)
        private fun add(isMark: Boolean, us: Int) {
            if (us <= 0) return
            if (out.isEmpty() && !isMark) return // dizi işaretle başlar
            if (isMark == nextIsMark) out.add(us) else out[out.size - 1] += us
        }
        val total get() = out.sum()
        fun build(): IntArray {
            // Sondaki boşluk gönderilmez
            if (out.isNotEmpty() && out.size % 2 == 0) out.removeAt(out.size - 1)
            return out.toIntArray()
        }
    }

    /** Uzunluk/boşluk kodlaması (NEC ailesi): her bit işaret + boşluk, bitler düşük bitten başlar. */
    private fun Pulses.pdm(bytes: ByteArray, bits: Int, mark: Int, one: Int, zero: Int) {
        for (i in 0 until bits) {
            val b = (bytes[i / 8].toInt() shr (i % 8)) and 1
            mark(mark); space(if (b == 1) one else zero)
        }
    }

    private fun le(v: Long, n: Int) = ByteArray(n) { ((v shr (8 * it)) and 0xFF).toByte() }

    private fun encodeParsed(c: Code.Parsed, toggle: Boolean): Signal {
        val a = c.address
        val cmd = c.command
        val p = Pulses()
        return when (c.protocol) {
            "NEC", "NECext" -> {
                val data = if (c.protocol == "NEC") {
                    (a and 0xFF) or ((a.inv() and 0xFF) shl 8) or ((cmd and 0xFF) shl 16) or ((cmd.inv() and 0xFF) shl 24)
                } else {
                    (a and 0xFFFF) or ((cmd and 0xFFFF) shl 16)
                }
                p.mark(9000); p.space(4500)
                p.pdm(le(data, 4), 32, 560, 1690, 560)
                p.mark(560)
                Signal(38000, p.build())
            }
            "Samsung32" -> {
                val data = (a and 0xFF) or ((a and 0xFF) shl 8) or ((cmd and 0xFF) shl 16) or ((cmd.inv() and 0xFF) shl 24)
                p.mark(4500); p.space(4500)
                p.pdm(le(data, 4), 32, 550, 1650, 550)
                p.mark(550)
                Signal(38000, p.build())
            }
            "Kaseikyo" -> {
                val id = ((a shr 24) and 3).toInt()
                val vendor = ((a shr 8) and 0xFFFF).toInt()
                val genre1 = ((a shr 4) and 0xF).toInt()
                val genre2 = (a and 0xF).toInt()
                val command = (cmd and 0xFFFF).toInt()
                val d = IntArray(6)
                d[0] = vendor and 0xFF
                d[1] = vendor shr 8
                var parity = d[0] xor d[1]
                parity = (parity and 0xF) xor (parity shr 4)
                d[2] = (parity and 0xF) or (genre1 shl 4)
                d[3] = (genre2 and 0xF) or ((command and 0xF) shl 4)
                d[4] = ((id shl 6) or (command shr 4)) and 0xFF
                d[5] = d[2] xor d[3] xor d[4]
                val u = 432
                p.mark(8 * u); p.space(4 * u)
                p.pdm(ByteArray(6) { d[it].toByte() }, 48, u, 3 * u, u)
                p.mark(u)
                Signal(38000, p.build())
            }
            "RCA" -> {
                val data = (a and 0xF) or ((cmd and 0xFF) shl 4) or ((a.inv() and 0xF) shl 12) or ((cmd.inv() and 0xFF) shl 16)
                p.mark(4000); p.space(4000)
                p.pdm(le(data, 3), 24, 500, 2000, 1000)
                p.mark(500)
                Signal(38000, p.build())
            }
            "Pioneer" -> {
                val bytes = byteArrayOf((a and 0xFF).toByte(), (a.inv() and 0xFF).toByte(), (cmd and 0xFF).toByte(), (cmd.inv() and 0xFF).toByte(), 0)
                repeat(2) {
                    if (it > 0) p.space(26000)
                    p.mark(8500); p.space(4225)
                    p.pdm(bytes, 33, 500, 1500, 500)
                }
                Signal(40000, p.build())
            }
            "SIRC", "SIRC15", "SIRC20" -> {
                val (bits, mask) = when (c.protocol) { "SIRC" -> 12 to 0x1FL; "SIRC15" -> 15 to 0xFFL; else -> 20 to 0x1FFFL }
                val data = (cmd and 0x7F) or ((a and mask) shl 7)
                // Sony: tuş en az 3 kez, her çerçeve 45 ms
                repeat(3) {
                    val start = p.total
                    p.mark(2400); p.space(600)
                    for (i in 0 until bits) {
                        if (i > 0) p.space(600)
                        p.mark(if ((data shr i) and 1L == 1L) 1200 else 600)
                    }
                    if (it < 2) p.space(45000 - (p.total - start))
                }
                Signal(40000, p.build())
            }
            "RC5", "RC5X" -> {
                // 14 bit, yüksek bitten: başlangıç 1, ikinci başlangıç (RC5X'te 0 = komutun 7. biti), değişim, 5 adres, 6 komut
                val t = 889
                val bitsList = ArrayList<Int>()
                bitsList.add(1)
                bitsList.add(if (c.protocol == "RC5") 1 else 0)
                bitsList.add(if (toggle) 1 else 0)
                for (i in 4 downTo 0) bitsList.add(((a shr i) and 1).toInt())
                for (i in 5 downTo 0) bitsList.add(((cmd shr i) and 1).toInt())
                // Manchester: 1 = boşluk sonra işaret, 0 = işaret sonra boşluk
                for (b in bitsList) if (b == 1) { p.space(t); p.mark(t) } else { p.mark(t); p.space(t) }
                Signal(36000, p.build())
            }
            "RC6" -> {
                // Mod 0: öncü, başlangıç 1, mod 000, çift uzunlukta değişim biti, 8 adres, 8 komut (yüksek bitten)
                val t = 444
                p.mark(2666); p.space(889)
                fun bit(b: Int, w: Int = t) { if (b == 1) { p.mark(w); p.space(w) } else { p.space(w); p.mark(w) } }
                bit(1)
                repeat(3) { bit(0) }
                bit(if (toggle) 1 else 0, 2 * t)
                for (i in 7 downTo 0) bit(((a shr i) and 1).toInt())
                for (i in 7 downTo 0) bit(((cmd shr i) and 1).toInt())
                Signal(36000, p.build())
            }
            else -> throw IllegalArgumentException("Bilinmeyen IR biçimi: ${c.protocol}")
        }
    }

    /** Değişim biti kullanan biçimler (her basışta değişir). */
    fun usesToggle(code: Code) = code is Code.Parsed && code.protocol in setOf("RC5", "RC5X", "RC6")
}

/** TV IR kod veritabanı (`assets/ir/tv.txt`, tools/ir_db_build.py üretir). */
object IrDb {
    /**
     * Bir markanın kod seti: [remotes] kaç kumandadan birleştiği (çok olan önce denenir).
     * [source] null değilse set aynı fabrikadan çıkan başka bir markadan gelir (bkz. [FAMILIES]).
     */
    class CodeSet(val remotes: Int, val keys: Map<String, String>, val source: String? = null)
    class Brand(val name: String, val sets: List<CodeSet>) {
        /** Markanın kendi kodu yok, yalnızca aile kodları var. */
        val familyOnly get() = sets.isNotEmpty() && sets.all { it.source != null }
    }

    /** Veritabanındaki adı bozuk yazılmış markalar. Eski kayıtlar ada büyük/küçük harf duyarsız bakar. */
    private val renames = mapOf("FINLUX" to "Finlux")

    /**
     * Aynı fabrikadan çıkan markalar: kumandaları çoğu zaman aynı kodları kullanır. Bir marka seçilince önce kendi
     * kodları, sonra ailenin diğer markalarının kodları denenir. Veritabanında hiç kodu olmayan aile markaları
     * (Vestel, Arçelik, Beko…) yalnızca aile kodlarıyla listeye eklenir. Sıra: en olası olan önce.
     */
    val FAMILIES = listOf(
        listOf("Vestel", "Regal", "Telefunken", "Finlux", "Toshiba", "Hitachi", "JVC", "Digihome", "Bush", "Blaupunkt", "Luxor", "Techwood"),
        listOf("Arçelik", "Beko", "Grundig", "Altus")
    )

    private fun signature(s: CodeSet) = s.keys["power"] to (s.keys["vol_up"] ?: s.keys["mute"] ?: s.keys["up"])

    /** Her markaya ailesinin kodlarını ekler; kodu olmayan aile markalarını da listeye katar. */
    fun withFamilies(brands: List<Brand>): List<Brand> {
        val byName = brands.associateBy { it.name.lowercase() }
        fun merge(own: Brand, family: List<String>): List<CodeSet> {
            val out = ArrayList(own.sets)
            val seen = own.sets.map(::signature).toHashSet()
            for (m in family) {
                if (m.equals(own.name, true)) continue
                val other = byName[m.lowercase()] ?: continue
                for (s in other.sets) if (seen.add(signature(s))) out.add(CodeSet(s.remotes, s.keys, other.name))
            }
            return out
        }
        val result = brands.map { b ->
            val family = FAMILIES.firstOrNull { f -> f.any { it.equals(b.name, true) } } ?: return@map b
            Brand(b.name, merge(b, family))
        }.toMutableList()
        for (family in FAMILIES) for (m in family) if (m.lowercase() !in byName) {
            val sets = merge(Brand(m, emptyList()), family)
            if (sets.isNotEmpty()) result.add(Brand(m, sets))
        }
        return result
    }

    /** "Markamı bilmiyorum" taraması için bir güç kodu ve geldiği kod seti. */
    class PowerCandidate(val brand: String, val set: CodeSet)

    /**
     * Veritabanındaki tüm farklı güç kodları, [brands] sırasıyla (yaygın markalar önce). Aile kodları atlanır
     * (zaten kendi markasında var); aynı güç kodu bir kez gönderilir.
     */
    fun powerScan(brands: List<Brand>): List<PowerCandidate> {
        val seen = HashSet<String>()
        val out = ArrayList<PowerCandidate>()
        for (b in brands) for (s in b.sets) {
            if (s.source != null) continue
            val p = s.keys["power"] ?: continue
            if (seen.add(p)) out.add(PowerCandidate(b.name, s))
        }
        return out
    }

    // ---------------- Flipper .ir dosyası ----------------

    /** Flipper tuş adı → uygulamanın ortak adı (tools/ir_db_build.py ile aynı liste). */
    private val aliases: Map<String, String> = buildMap {
        fun a(to: String, vararg from: String) { for (f in from) put(f, to) }
        a("power", "power", "on_off", "standby", "power_toggle")
        a("vol_up", "vol_up", "vol+", "volume_up", "plus")
        a("vol_dn", "vol_dn", "vol_down", "vol-", "vol_dwn", "volume_down", "minus")
        a("mute", "mute")
        a("ch_up", "ch_next", "ch_up", "ch+", "pr_up", "page_up")
        a("ch_dn", "ch_prev", "ch_dn", "ch_down", "ch-", "pr_down")
        a("up", "up", "up_arrow", "arrow_up"); a("down", "down", "dwn", "arrow_down")
        a("left", "left", "left_arrow", "arrow_left"); a("right", "right", "right_arrow", "arrow_right")
        a("ok", "ok", "enter", "select", "ent"); a("back", "back", "return"); a("exit", "exit")
        a("home", "home", "smart_hub"); a("menu", "menu"); a("settings", "settings", "setup")
        a("info", "info", "display"); a("source", "source", "input", "sources"); a("guide", "guide", "epg", "tv_guide")
        a("play", "play"); a("pause", "pause"); a("stop", "stop"); a("play_pause", "play_pause", "play/pause", "play_pa")
        a("rewind", "rewind", "rew", "rwd", "fast_ba", "rw")
        a("ffwd", "fast_forward", "fast_fo", "ff", "fwd", "fastforward", "fast_fwd", "forward", "fast forward", "ffw", "fw")
        a("red", "red"); a("green", "green"); a("yellow", "yellow"); a("blue", "blue")
        a("netflix", "netflix"); a("youtube", "youtube")
        a("subtitle", "subtitle", "subtitles", "cc", "sttl")
        for (d in '0'..'9') put(d.toString(), d.toString())
    }

    /** Uygulamanın tanımadığı tuş adları bu önekle saklanır ve "Diğer tuşlar"da adıyla gösterilir. */
    const val EXTRA = "x:"
    private const val MAX_EXTRA = 48

    /** [keys] okunan tuşlar; [skipped] uygulamanın gönderemediği (bilinmeyen biçim, çok uzun) sinyal sayısı. */
    class Imported(val keys: Map<String, String>, val skipped: Int)

    /**
     * Flipper Zero'nun .ir dosyası ("Filetype: IR signals file"). Adres/komut baytları küçük bayttan başlar
     * ("04 00 00 00" = 4). Aynı adda ikinci tuş atlanır.
     */
    fun parseFlipper(text: String): Imported {
        val keys = LinkedHashMap<String, String>()
        var skipped = 0
        var extras = 0
        var cur = HashMap<String, String>()
        fun le(hex: String): Long? = runCatching {
            hex.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.withIndex()
                .fold(0L) { acc, (i, b) -> acc or (b.toLong(16) shl (8 * i)) }
        }.getOrNull()
        fun flush() {
            val raw = cur["name"]?.trim().orEmpty()
            if (raw.isEmpty()) return
            val known = aliases[raw.lowercase()]
            val key = known ?: (EXTRA + raw.take(24))
            if (key in keys) return
            val code = when (cur["type"]?.trim()) {
                "parsed" -> {
                    val p = cur["protocol"]?.trim().orEmpty()
                    val a = cur["address"]?.let(::le); val c = cur["command"]?.let(::le)
                    if (p in IrCodec.PROTOCOLS && a != null && c != null) "P,$p,%X,%X".format(a, c) else null
                }
                "raw" -> {
                    val f = cur["frequency"]?.trim()?.toDoubleOrNull()?.toInt() ?: 38000
                    val d = cur["data"]?.trim()?.split(Regex("\\s+"))?.map { it.toIntOrNull() ?: -1 }.orEmpty()
                    if (d.size >= 2 && d.all { it > 0 } && d.sum() < 1_900_000) "W,$f,${d.joinToString(" ")}" else null
                }
                else -> null
            }
            if (code == null || IrCodec.parseCode(code) == null) { skipped++; return }
            if (known == null) { if (extras >= MAX_EXTRA) return; extras++ }
            keys[key] = code
        }
        for (line in text.lineSequence()) {
            val l = line.trim()
            if (l.startsWith("#")) { flush(); cur = HashMap(); continue }
            val i = l.indexOf(':')
            if (i <= 0) continue
            val k = l.substring(0, i).trim()
            if (k == "name" && "name" in cur) { flush(); cur = HashMap() }
            cur[k] = l.substring(i + 1).trim()
        }
        flush()
        return Imported(keys, skipped)
    }

    fun parse(text: String): List<Brand> {
        val brands = ArrayList<Brand>()
        var name: String? = null
        var sets = ArrayList<CodeSet>()
        fun flush() { name?.let { if (sets.isNotEmpty()) brands.add(Brand(it, sets)) } }
        for (line in text.lineSequence()) {
            if (line.isBlank() || line.startsWith("#")) continue
            val p = line.split('\t')
            when (p[0]) {
                "B" -> { flush(); name = p.getOrNull(1)?.trim()?.let { renames[it] ?: it }; sets = ArrayList() }
                "S" -> if (p.size >= 3) {
                    val keys = LinkedHashMap<String, String>()
                    for (kv in p[2].split(';')) {
                        val i = kv.indexOf('=')
                        if (i > 0) keys[kv.substring(0, i)] = kv.substring(i + 1)
                    }
                    if (keys.isNotEmpty()) sets.add(CodeSet(p[1].toIntOrNull() ?: 1, keys))
                }
            }
        }
        flush()
        return brands
    }

    /** Türkiye'de yaygın markalar listenin başında; kalanlar alfabetik. */
    private val popular = listOf("Philips", "Samsung", "LG", "Sony", "Toshiba", "Grundig", "Arçelik", "Beko", "Vestel", "Telefunken",
        "TCL", "Hisense", "Xiaomi", "Panasonic", "Sharp", "Hitachi", "JVC", "Finlux", "Sunny", "Axen", "Regal", "Seg")

    fun sorted(brands: List<Brand>): List<Brand> = brands.sortedWith(
        compareBy<Brand> { b -> popular.indexOfFirst { it.equals(b.name, true) }.let { if (it < 0) Int.MAX_VALUE else it } }
            .thenBy { it.name.lowercase() }
    )

    /** Deneme tuşu: sesi aç (TV'yi kapatmaz, tepkisi hemen görülür); yoksa sessiz, kanal, yön. */
    fun testKey(set: CodeSet): String? = listOf("vol_up", "mute", "ch_up", "up", "power").firstOrNull { it in set.keys }
}
