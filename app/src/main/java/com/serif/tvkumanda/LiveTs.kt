package com.serif.tvkumanda

import java.io.ByteArrayOutputStream

/**
 * Canlı yayın için saf (Android'siz) parçalar: MPEG-TS paketleyici, AAC (ADTS) başlığı ve boyut hesabı.
 * Ekran yansıtma, ekranı H.264 + sesi AAC olarak sıkıştırıp bu paketleyiciyle TV'nin oynatabileceği
 * tek bir akışa çevirir. Birim testleri `LiveTsTest`'te.
 */

/** MPEG-2 CRC32 (PAT/PMT tabloları için): çokterimli 0x04C11DB7, başlangıç 0xFFFFFFFF, yansıtma ve son XOR yok. */
internal object Crc32Mpeg {
    fun of(data: ByteArray, from: Int = 0, to: Int = data.size): Int {
        var crc = -1
        for (i in from until to) {
            crc = crc xor ((data[i].toInt() and 0xFF) shl 24)
            repeat(8) { crc = if (crc < 0) (crc shl 1) xor 0x04C11DB7 else crc shl 1 }
        }
        return crc
    }
}

/** AAC-LC kareleri için 7 baytlık ADTS başlığı. */
internal object Adts {
    private val RATES = intArrayOf(96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11000, 8000, 7350)

    fun rateIndex(sampleRate: Int): Int = RATES.indexOf(sampleRate).also { require(it >= 0) { "Desteklenmeyen örnekleme hızı: $sampleRate" } }

    /** [payloadSize]: başlıksız AAC karesinin bayt sayısı. */
    fun header(payloadSize: Int, sampleRate: Int, channels: Int): ByteArray {
        val len = payloadSize + 7
        require(len < (1 shl 13)) { "AAC karesi çok büyük" }
        val profile = 1   // AAC-LC (nesne türü 2) → ADTS alanında 1
        val idx = rateIndex(sampleRate)
        return byteArrayOf(
            0xFF.toByte(),
            0xF1.toByte(),
            ((profile shl 6) or (idx shl 2) or ((channels shr 2) and 1)).toByte(),
            (((channels and 3) shl 6) or ((len shr 11) and 3)).toByte(),
            ((len shr 3) and 0xFF).toByte(),
            (((len and 7) shl 5) or 0x1F).toByte(),
            0xFC.toByte()
        )
    }
}

/** Yayın boyutu hesabı. */
internal object LiveSize {
    /** Uzun kenarı [maxLong]'u geçmeyecek ve iki kenarı da 16'nın katı olacak biçimde küçültür. */
    fun fit(width: Int, height: Int, maxLong: Int = 1280): Pair<Int, Int> {
        require(width > 0 && height > 0)
        val scale = minOf(1.0, maxLong.toDouble() / maxOf(width, height))
        fun r(x: Int) = (maxOf(16, ((x * scale).toInt() / 16) * 16))
        return r(width) to r(height)
    }
}

/**
 * H.264 (Annex-B) ve AAC (ADTS) karelerini tek bir MPEG-TS akışına paketler.
 *
 * [sink] her çağrıda bir kareye ait bir ya da birkaç 188 baytlık paketi alır; ikinci değer, bu parçanın
 * görüntüde "anahtar kare" ile (PAT/PMT dahil) başladığını söyler: yayına sonradan katılan oynatıcı oradan başlayabilir.
 * [baseUs]: zaman çizelgesinin sıfır anı (mikrosaniye); tüm kare zamanları aynı saate göre verilmelidir.
 */
internal class TsMuxer(
    private val hasVideo: Boolean,
    private val hasAudio: Boolean,
    private val baseUs: Long,
    private val sink: (ByteArray, Boolean) -> Unit
) {
    companion object {
        const val PID_PMT = 0x1000
        const val PID_VIDEO = 0x100
        const val PID_AUDIO = 0x101
        private const val SYNC = 0x47
        /** Çözücüye tampon payı: PTS, PCR'dan 1 sn ilerde olur. */
        private const val DELAY_90K = 90_000L
        private val AUD = byteArrayOf(0, 0, 0, 1, 0x09, 0xF0.toByte())

        /** Annex-B verisi SPS (NAL türü 7) ile mi başlıyor? */
        fun startsWithSps(d: ByteArray): Boolean {
            var i = 0
            while (i + 3 < d.size && i < 8) {
                if (d[i].toInt() == 0 && d[i + 1].toInt() == 0) {
                    if (d[i + 2].toInt() == 1) return (d[i + 3].toInt() and 0x1F) == 7
                    if (d[i + 2].toInt() == 0 && d[i + 3].toInt() == 1 && i + 4 < d.size) return (d[i + 4].toInt() and 0x1F) == 7
                }
                i++
            }
            return false
        }
    }

    private val cc = HashMap<Int, Int>()
    private var videoConfig: ByteArray? = null

    /** Kodlayıcının verdiği SPS/PPS (yapılandırma) baytları; anahtar karelere eklenir. */
    @Synchronized
    fun setVideoConfig(config: ByteArray) { videoConfig = config }

    @Synchronized
    fun writeVideo(annexB: ByteArray, ptsUs: Long, key: Boolean) {
        check(hasVideo)
        val rel90 = rel90k(ptsUs)
        val body = ByteArrayOutputStream(annexB.size + 64)
        body.write(AUD)
        val cfg = videoConfig
        if (key && cfg != null && !startsWithSps(annexB)) body.write(cfg)
        body.write(annexB)
        val pes = pes(0xE0, 0, body.toByteArray(), (rel90 + DELAY_90K) and MASK33)
        val out = ByteArrayOutputStream(pes.size + 600)
        if (key) out.write(tables())
        out.write(packetize(PID_VIDEO, pes, pcr = rel90, randomAccess = key))
        sink(out.toByteArray(), key)
    }

    @Synchronized
    fun writeAudio(adts: ByteArray, ptsUs: Long) {
        check(hasAudio)
        val pts = (rel90k(ptsUs) + DELAY_90K) and MASK33
        val pes = pes(0xC0, 3 + 5 + adts.size, adts, pts)
        // Yalnızca sesli akışta PCR sesin üstünde taşınır
        val pcr = if (hasVideo) null else rel90k(ptsUs)
        val out = ByteArrayOutputStream(pes.size + 400)
        if (!hasVideo) out.write(tables())
        out.write(packetize(PID_AUDIO, pes, pcr = pcr, randomAccess = false))
        sink(out.toByteArray(), !hasVideo)
    }

    private val MASK33 = (1L shl 33) - 1

    private fun rel90k(us: Long): Long = (((us - baseUs).coerceAtLeast(0)) * 9 / 100) and MASK33

    /** PAT + PMT paketleri. */
    fun tables(): ByteArray {
        val pat = section(
            0x00, byteArrayOf(0x00, 0x01, 0xC1.toByte(), 0x00, 0x00, 0x00, 0x01, (0xE0 or (PID_PMT shr 8)).toByte(), (PID_PMT and 0xFF).toByte())
        )
        val pcrPid = if (hasVideo) PID_VIDEO else PID_AUDIO
        val body = ByteArrayOutputStream()
        body.write(byteArrayOf(0x00, 0x01, 0xC1.toByte(), 0x00, 0x00, (0xE0 or (pcrPid shr 8)).toByte(), (pcrPid and 0xFF).toByte(), 0xF0.toByte(), 0x00))
        fun stream(type: Int, pid: Int) = body.write(byteArrayOf(type.toByte(), (0xE0 or (pid shr 8)).toByte(), (pid and 0xFF).toByte(), 0xF0.toByte(), 0x00))
        if (hasVideo) stream(0x1B, PID_VIDEO)
        if (hasAudio) stream(0x0F, PID_AUDIO)
        val pmt = section(0x02, body.toByteArray())
        return tableToPackets(0, pat) + tableToPackets(PID_PMT, pmt)
    }

    /** table_id + bölüm uzunluğu + [rest] + CRC32. */
    private fun section(tableId: Int, rest: ByteArray): ByteArray {
        val len = rest.size + 4
        val s = ByteArray(3 + rest.size + 4)
        s[0] = tableId.toByte()
        s[1] = (0xB0 or ((len shr 8) and 0x0F)).toByte()
        s[2] = (len and 0xFF).toByte()
        System.arraycopy(rest, 0, s, 3, rest.size)
        val crc = Crc32Mpeg.of(s, 0, s.size - 4)
        for (i in 0 until 4) s[s.size - 4 + i] = (crc shr (24 - 8 * i)).toByte()
        return s
    }

    private fun tableToPackets(pid: Int, sectionBytes: ByteArray): ByteArray {
        val p = ByteArray(188) { 0xFF.toByte() }
        p[0] = SYNC.toByte()
        p[1] = (0x40 or ((pid shr 8) and 0x1F)).toByte()
        p[2] = (pid and 0xFF).toByte()
        p[3] = (0x10 or nextCc(pid)).toByte()
        p[4] = 0   // pointer_field
        System.arraycopy(sectionBytes, 0, p, 5, sectionBytes.size)
        return p
    }

    private fun nextCc(pid: Int): Int {
        val v = ((cc[pid] ?: -1) + 1) and 0x0F
        cc[pid] = v
        return v
    }

    private fun ptsBytes(pts: Long): ByteArray = byteArrayOf(
        (0x21 or (((pts shr 30) and 7).toInt() shl 1)).toByte(),
        ((pts shr 22) and 0xFF).toByte(),
        ((((pts shr 15) and 0x7F).toInt() shl 1) or 1).toByte(),
        ((pts shr 7) and 0xFF).toByte(),
        (((pts and 0x7F).toInt() shl 1) or 1).toByte()
    )

    /** PES paketi; [pesLength] 0 ise sınırsız (görüntü için). */
    private fun pes(streamId: Int, pesLength: Int, payload: ByteArray, pts: Long): ByteArray {
        val h = byteArrayOf(0, 0, 1, streamId.toByte(), (pesLength shr 8).toByte(), (pesLength and 0xFF).toByte(), 0x80.toByte(), 0x80.toByte(), 5)
        return h + ptsBytes(pts) + payload
    }

    private fun pcrBytes(base: Long): ByteArray = byteArrayOf(
        ((base shr 25) and 0xFF).toByte(),
        ((base shr 17) and 0xFF).toByte(),
        ((base shr 9) and 0xFF).toByte(),
        ((base shr 1) and 0xFF).toByte(),
        ((((base and 1).toInt()) shl 7) or 0x7E).toByte(),
        0
    )

    /** PES'i 188 baytlık paketlere böler; ilk pakete (varsa) PCR ve "rastgele erişim" bayrağı konur. */
    private fun packetize(pid: Int, pes: ByteArray, pcr: Long?, randomAccess: Boolean): ByteArray {
        val out = ByteArrayOutputStream(pes.size + pes.size / 100 * 4 + 400)
        var pos = 0
        var first = true
        while (pos < pes.size) {
            val remaining = pes.size - pos
            val withPcr = first && pcr != null
            val baseAf = if (withPcr) 8 else 0                  // uyarlama alanı (uzunluk baytı dahil)
            val capacity = 184 - baseAf
            val n = minOf(remaining, capacity)
            val afTotal = baseAf + (capacity - n)               // kalan boşluk uyarlama alanında doldurulur
            val p = ByteArray(188) { 0xFF.toByte() }
            p[0] = SYNC.toByte()
            p[1] = (((if (first) 0x40 else 0) or ((pid shr 8) and 0x1F))).toByte()
            p[2] = (pid and 0xFF).toByte()
            p[3] = (((if (afTotal > 0) 0x30 else 0x10) or nextCc(pid))).toByte()
            var w = 4
            if (afTotal > 0) {
                p[w++] = (afTotal - 1).toByte()
                if (afTotal > 1) {
                    var flags = 0
                    if (withPcr) flags = flags or 0x10
                    if (first && randomAccess) flags = flags or 0x40
                    p[w++] = flags.toByte()
                    if (withPcr) {
                        val b = pcrBytes(pcr!!)
                        System.arraycopy(b, 0, p, w, 6); w += 6
                    }
                    // kalan baytlar 0xFF (dolgu) olarak zaten yazılı
                    w = 4 + afTotal
                }
            }
            System.arraycopy(pes, pos, p, w, n)
            out.write(p)
            pos += n
            first = false
        }
        return out.toByteArray()
    }
}
