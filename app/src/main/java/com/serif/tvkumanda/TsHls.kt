package com.serif.tvkumanda

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale

/** Oynatılamayacağı baştan bilinen TS dosyası; [message] kullanıcıya olduğu gibi gösterilir. */
class TsUnsupportedException(message: String) : IOException(message)

/**
 * Telefondaki TS / M2TS videosunu Chromecast'in oynatabileceği HLS'e çevirir. Görüntü ve ses yeniden işlenmez:
 * dosya anahtar karelerden ~6 saniyelik parçalara bölünür, her parçanın başına yeni PAT/PMT tablosu konur,
 * yalnızca seçilen görüntü ve ses paketleri gönderilir. Saf kod; test: `TsHlsTest`.
 */
object TsHls {
    const val MIME = "application/vnd.apple.mpegurl"
    private const val PACKET = 188
    private const val PROBE_BYTES = 64L * 1024 * 1024
    private const val TARGET_90K = 6L * 90_000
    /** Bundan büyük zaman sıçraması kaydın kesildiğini gösterir. */
    private const val JUMP_90K = 10L * 90_000
    private const val WRAP = 1L shl 33

    private const val DLNA_HINT = "DLNA Sunucu ile TV'nin kendi oynatıcısında açabilirsin."

    private val TS_EXTENSIONS = setOf("ts", "m2ts", "mts")

    fun isTs(name: String?, mime: String?): Boolean {
        val ext = name.orEmpty().substringAfterLast('.', "").lowercase(Locale.ROOT)
        if (ext in TS_EXTENSIONS) return true
        return mime.orEmpty().lowercase(Locale.ROOT).substringBefore(';').trim() in setOf("video/mp2t", "video/m2ts", "video/vnd.dlna.mpeg-tts")
    }

    /** Dosyada 188 baytlık paketlerin nasıl dizildiği: [start] ilk paketin yeri, [step] iki paket arası (188, 192 ya da 204). */
    class Layout(val start: Int, val step: Int)

    class Stream(val pid: Int, val type: Int, val language: String?)

    class Tracks(val videoPid: Int, val audio: Stream?, val pcrPid: Int)

    class Segment(val start: Long, val end: Long, val packets: Long, val seconds: Double, val discontinuity: Boolean) {
        /** Gönderilecek bayt sayısı: iki tablo paketi + seçilen paketler. */
        val bytes: Long get() = (packets + 2) * PACKET
    }

    class Index(val layout: Layout, val tracks: Tracks, val segments: List<Segment>) {
        val durationSeconds: Double get() = segments.sumOf { it.seconds }
        fun playlist(): String = buildString {
            val target = segments.maxOfOrNull { Math.ceil(it.seconds).toInt() } ?: 1
            append("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-PLAYLIST-TYPE:VOD\n")
            append("#EXT-X-TARGETDURATION:").append(target).append("\n#EXT-X-MEDIA-SEQUENCE:0\n")
            segments.forEachIndexed { i, s ->
                if (s.discontinuity) append("#EXT-X-DISCONTINUITY\n")
                append(String.format(Locale.ROOT, "#EXTINF:%.3f,\n", s.seconds))
                append(i).append(".ts\n")
            }
            append("#EXT-X-ENDLIST\n")
        }
    }

    // ---------------- Paket dizilimi ----------------

    fun detectLayout(head: ByteArray, size: Int = head.size): Layout? {
        for (step in intArrayOf(PACKET, 192, 204)) {
            for (start in 0 until minOf(step * 2, size)) {
                var ok = 0
                var p = start
                while (p < size && ok < 8 && head[p].toInt() == 0x47) { ok++; p += step }
                if (ok >= 8 || (ok >= 2 && p >= size)) return Layout(start, step)
            }
        }
        return null
    }

    /** Akıştan sırayla paket okur; paket hizası kaymışsa sıradaki 0x47'ye atlar. */
    private class PacketReader(private val input: InputStream, val layout: Layout, startOffset: Long) {
        val buf = ByteArray(layout.step)
        var position = startOffset
            private set

        fun next(): Boolean {
            if (!fill(0)) return false
            if (buf[0].toInt() != 0x47) {
                while (true) {
                    val i = (1 until buf.size).firstOrNull { buf[it].toInt() == 0x47 }
                    if (i == null) {
                        position += buf.size
                        if (!fill(0)) return false
                        if (buf[0].toInt() == 0x47) break
                        continue
                    }
                    System.arraycopy(buf, i, buf, 0, buf.size - i)
                    position += i
                    if (!fill(buf.size - i)) return false
                    break
                }
            }
            return true
        }

        fun advance() { position += buf.size }

        private fun fill(from: Int): Boolean {
            var n = from
            while (n < buf.size) {
                val r = input.read(buf, n, buf.size - n)
                if (r < 0) return false
                n += r
            }
            return true
        }
    }

    private fun skipFully(input: InputStream, count: Long) {
        var left = count
        while (left > 0) {
            val s = input.skip(left)
            if (s > 0) { left -= s; continue }
            if (input.read() < 0) throw EOFException()
            left--
        }
    }

    private fun pidOf(p: ByteArray) = ((p[1].toInt() and 0x1F) shl 8) or (p[2].toInt() and 0xFF)
    private fun unitStart(p: ByteArray) = (p[1].toInt() and 0x40) != 0
    private fun scrambled(p: ByteArray) = (p[3].toInt() and 0xC0) != 0

    /** Paketteki yükün başlangıcı; yük yoksa -1. */
    private fun payloadStart(p: ByteArray): Int {
        val control = (p[3].toInt() shr 4) and 3
        if (control == 0 || control == 2) return -1
        if (control == 1) return 4
        val at = 5 + (p[4].toInt() and 0xFF)
        return if (at < PACKET) at else -1
    }

    private fun randomAccess(p: ByteArray): Boolean {
        val control = (p[3].toInt() shr 4) and 3
        return (control == 2 || control == 3) && (p[4].toInt() and 0xFF) > 0 && (p[5].toInt() and 0x40) != 0
    }

    // ---------------- Akışları tanıma ----------------

    private fun section(p: ByteArray): IntRange? {
        if (!unitStart(p)) return null
        val at = payloadStart(p)
        if (at < 0) return null
        val s = at + 1 + (p[at].toInt() and 0xFF)
        if (s + 3 > PACKET) return null
        val len = ((p[s + 1].toInt() and 0x0F) shl 8) or (p[s + 2].toInt() and 0xFF)
        // Bölüm sonundaki 4 baytlık CRC hariç; birkaç pakete taşan tablonun ilk paketi kadarı okunur
        return s..minOf(s + 3 + len - 5, PACKET - 1)
    }

    private fun parsePat(p: ByteArray): List<Int> {
        val r = section(p) ?: return emptyList()
        val out = ArrayList<Int>()
        var i = r.first + 8
        while (i + 3 <= r.last) {
            val program = ((p[i].toInt() and 0xFF) shl 8) or (p[i + 1].toInt() and 0xFF)
            val pid = ((p[i + 2].toInt() and 0x1F) shl 8) or (p[i + 3].toInt() and 0xFF)
            if (program != 0) out += pid
            i += 4
        }
        return out
    }

    private class Pmt(val pcrPid: Int, val streams: List<Stream>)

    private fun parsePmt(p: ByteArray): Pmt? {
        val r = section(p) ?: return null
        if ((p[r.first].toInt() and 0xFF) != 2 || r.first + 12 > PACKET) return null
        val pcr = ((p[r.first + 8].toInt() and 0x1F) shl 8) or (p[r.first + 9].toInt() and 0xFF)
        val infoLen = ((p[r.first + 10].toInt() and 0x0F) shl 8) or (p[r.first + 11].toInt() and 0xFF)
        var i = r.first + 12 + infoLen
        val streams = ArrayList<Stream>()
        while (i + 4 <= r.last) {
            var type = p[i].toInt() and 0xFF
            val pid = ((p[i + 1].toInt() and 0x1F) shl 8) or (p[i + 2].toInt() and 0xFF)
            val esLen = ((p[i + 3].toInt() and 0x0F) shl 8) or (p[i + 4].toInt() and 0xFF)
            var language: String? = null
            var d = i + 5
            val dEnd = minOf(i + 5 + esLen, PACKET)
            while (d + 2 <= dEnd) {
                val tag = p[d].toInt() and 0xFF
                val len = p[d + 1].toInt() and 0xFF
                if (d + 2 + len > dEnd) break
                when (tag) {
                    0x0A -> if (len >= 3) language = String(p, d + 2, 3, Charsets.ISO_8859_1).lowercase(Locale.ROOT)
                    0x6A -> if (type == 0x06) type = 0x81
                    0x7A -> if (type == 0x06) type = 0x87
                    0x7B -> if (type == 0x06) type = 0x82
                    0x05 -> if (type == 0x06 && len >= 4) {
                        when (String(p, d + 2, 4, Charsets.ISO_8859_1)) {
                            "AC-3" -> type = 0x81
                            "EAC3" -> type = 0x87
                        }
                    }
                }
                d += 2 + len
            }
            streams += Stream(pid, type, language)
            i += 5 + esLen
        }
        return Pmt(pcr, streams)
    }

    private fun isVideoType(t: Int) = t in setOf(0x01, 0x02, 0x10, 0x1B, 0x24, 0x42, 0xEA)
    private fun isAudioType(t: Int) = t in setOf(0x03, 0x04, 0x0F, 0x11, 0x80, 0x81, 0x82, 0x87, 0x85, 0x86)

    /** Türü "özel" (0x06) yazılmış ama içinde AAC (ADTS) olan akış: bazı kayıtlarda ses böyle işaretlenir. */
    private fun startsWithAdts(p: ByteArray): Boolean {
        val at = payloadStart(p)
        if (at < 0 || at + 9 > PACKET) return false
        val i = at + 9 + (p[at + 8].toInt() and 0xFF)
        return i + 1 < PACKET && (p[i].toInt() and 0xFF) == 0xFF && (p[i + 1].toInt() and 0xF6) == 0xF0
    }

    /** PES'in ses verisindeki MPEG ses katmanı: 3 MP3, 2 MP2, bilinmiyorsa 0. */
    private fun mpegLayer(p: ByteArray): Int {
        val at = payloadStart(p)
        if (at < 0 || at + 9 > PACKET) return 0
        var i = at + 9 + (p[at + 8].toInt() and 0xFF)
        while (i + 1 < PACKET) {
            if ((p[i].toInt() and 0xFF) == 0xFF && (p[i + 1].toInt() and 0xE0) == 0xE0) {
                return when ((p[i + 1].toInt() shr 1) and 3) { 1 -> 3; 2 -> 2; 3 -> 1; else -> 0 }
            }
            i++
        }
        return 0
    }

    private fun videoName(t: Int) = when (t) {
        0x01, 0x02 -> "MPEG-2"
        0x10 -> "MPEG-4"
        0x24 -> "HEVC (H.265)"
        else -> "bu"
    }

    private fun audioName(t: Int, layer: Int) = when (t) {
        0x81 -> "AC-3"
        0x87 -> "E-AC-3"
        0x82, 0x85, 0x86 -> "DTS"
        0x11 -> "AAC-LATM"
        0x80 -> "LPCM"
        0x03, 0x04 -> if (layer == 2 || layer == 1) "MP2" else "MPEG"
        else -> "bu"
    }

    /** Dosyanın başına bakıp görüntü ve sesi seçer; oynatılamayacaksa nedenini söyler. */
    fun analyze(input: InputStream, layout: Layout): Tracks {
        val reader = PacketReader(input, layout, layout.start.toLong())
        skipFully(input, layout.start.toLong())
        var pmtPids = emptyList<Int>()
        var pmt: Pmt? = null
        var video: Stream? = null
        val layers = HashMap<Int, Int>()
        val adtsPids = HashSet<Int>()
        var sawScrambled = false
        var read = 0L
        while (read < PROBE_BYTES && reader.next()) {
            val p = reader.buf
            val pid = pidOf(p)
            if (pid == 0 && pmtPids.isEmpty()) {
                pmtPids = parsePat(p)
            } else if (pmt == null && pid in pmtPids) {
                // Birden çok program varsa görüntüsü olan ilki
                parsePmt(p)?.takeIf { m -> m.streams.any { isVideoType(it.type) } }?.let { m ->
                    pmt = m
                    video = m.streams.firstOrNull { it.type == 0x1B } ?: m.streams.first { isVideoType(it.type) }
                }
            } else pmt?.let { m ->
                if (pid == video?.pid && scrambled(p)) sawScrambled = true
                val stream = m.streams.firstOrNull { it.pid == pid }
                if (stream != null && stream.type in setOf(0x03, 0x04) && pid !in layers && unitStart(p) && !scrambled(p)) {
                    layers[pid] = mpegLayer(p)
                }
                if (stream != null && stream.type == 0x06 && unitStart(p) && !scrambled(p) && startsWithAdts(p)) adtsPids += pid
            }
            reader.advance()
            read += layout.step
            // Tablolar ve ses türleri belli olduysa dosyanın başını sonuna kadar okumaya gerek yok
            val m = pmt
            if (m != null && read > 8L * 1024 * 1024 &&
                m.streams.filter { it.type in setOf(0x03, 0x04) }.all { it.pid in layers }) break
        }
        val found = pmt ?: throw TsUnsupportedException("Bu dosyada okunabilir bir TS görüntüsü bulunamadı. $DLNA_HINT")
        val v = video!!
        if (sawScrambled) throw TsUnsupportedException("Bu TS kaydı şifreli olduğu için oynatılamaz.")
        if (v.type != 0x1B) {
            throw TsUnsupportedException("Bu TS videosu ${videoName(v.type)} görüntü kullanıyor; Chromecast oynatamaz. $DLNA_HINT")
        }
        val audios = found.streams.map { if (it.pid in adtsPids) Stream(it.pid, 0x0F, it.language) else it }.filter { isAudioType(it.type) }
        val playable = audios.filter { it.type == 0x0F || (it.type in setOf(0x03, 0x04) && layers[it.pid] == 3) }
        if (audios.isNotEmpty() && playable.isEmpty()) {
            val a = audios.first()
            throw TsUnsupportedException(
                "Bu TS videosunun sesi ${audioName(a.type, layers[a.pid] ?: 0)} biçiminde; ses çevirme henüz hazır değil. $DLNA_HINT"
            )
        }
        val chosen = playable.sortedWith(
            compareBy<Stream> { if (it.language == "tur" || it.language == "tr") 0 else 1 }.thenBy { if (it.type == 0x0F) 0 else 1 }
        ).firstOrNull()
        return Tracks(v.pid, chosen, found.pcrPid)
    }

    // ---------------- Parçalara bölme ----------------

    private fun pesPts(p: ByteArray): Long? {
        val at = payloadStart(p)
        if (at < 0 || at + 14 > PACKET) return null
        if (p[at].toInt() != 0 || p[at + 1].toInt() != 0 || p[at + 2].toInt() != 1) return null
        if ((p[at + 7].toInt() and 0x80) == 0) return null
        val b = at + 9
        return (((p[b].toLong() and 0x0E) shl 29) or
            ((p[b + 1].toLong() and 0xFF) shl 22) or
            ((p[b + 2].toLong() and 0xFE) shl 14) or
            ((p[b + 3].toLong() and 0xFF) shl 7) or
            ((p[b + 4].toLong() and 0xFE) shr 1))
    }

    /** Pakette anahtar kare işareti (H.264 IDR ya da SPS) var mı? */
    private fun hasKeyNal(p: ByteArray): Boolean {
        val at = payloadStart(p)
        if (at < 0) return false
        var i = at
        while (i + 3 < PACKET) {
            if (p[i].toInt() == 0 && p[i + 1].toInt() == 0 && p[i + 2].toInt() == 1) {
                val nal = p[i + 3].toInt() and 0x1F
                if (nal == 5 || nal == 7) return true
                i += 3
            } else i++
        }
        return false
    }

    /**
     * Dosyanın tamamını bir kez okuyup anahtar karelerden parçalara böler.
     * [onProgress] okunan bayt sayısını alır; iş parçacığı kesilirse InterruptedIOException ile durur.
     */
    fun buildIndex(input: InputStream, layout: Layout, tracks: Tracks, onProgress: (Long) -> Unit = {}): Index {
        val reader = PacketReader(input, layout, layout.start.toLong())
        skipFully(input, layout.start.toLong())
        val segments = ArrayList<Segment>()
        val keep = keptPids(tracks)

        var kept = 0L                    // bugüne kadar seçilen paket sayısı
        var segStartPos = -1L
        var segStartKept = 0L
        var segStartPts = 0L
        var segMaxPts = 0L
        var segDiscontinuity = false
        var unwrapped = -1L              // süreklileştirilmiş son PTS
        var lastRaw = -1L
        var jumped = false

        // İncelenen PES'in başı: anahtar kare işareti ilk birkaç pakette aranır
        var pesPos = 0L
        var pesKept = 0L
        var pesPtsValue = -1L
        var pesPackets = 99
        var pesCut = true

        var count = 0L
        fun cut() {
            pesCut = true
            if (pesPtsValue < 0) return
            if (segStartPos < 0) {
                segStartPos = pesPos; segStartKept = pesKept; segStartPts = pesPtsValue; segMaxPts = pesPtsValue
                jumped = false
                return
            }
            if (!jumped && pesPtsValue - segStartPts < TARGET_90K) return
            val end = if (jumped) segMaxPts + 3600 else pesPtsValue
            segments += Segment(segStartPos, pesPos, pesKept - segStartKept, ((end - segStartPts).coerceAtLeast(1)) / 90_000.0, segDiscontinuity)
            segDiscontinuity = jumped
            jumped = false
            segStartPos = pesPos; segStartKept = pesKept; segStartPts = pesPtsValue; segMaxPts = pesPtsValue
        }

        while (reader.next()) {
            val p = reader.buf
            val pid = pidOf(p)
            if (pid == tracks.videoPid) {
                if (unitStart(p)) {
                    pesPos = reader.position
                    pesKept = kept
                    pesPackets = 0
                    pesCut = false
                    pesPtsValue = -1
                    pesPts(p)?.let { raw ->
                        var delta = if (lastRaw < 0) 0L else raw - lastRaw
                        if (delta < -WRAP / 2) delta += WRAP else if (delta > WRAP / 2) delta -= WRAP
                        if (Math.abs(delta) > JUMP_90K && segStartPos >= 0) {
                            // Kayıt kesilmiş: zaman çizelgesi sıçramadan devam eder, sıradaki anahtar karede yeni parça başlar
                            jumped = true
                            delta = 0
                        }
                        lastRaw = raw
                        unwrapped = if (unwrapped < 0) 0 else unwrapped + delta
                        pesPtsValue = unwrapped
                        if (!jumped && unwrapped > segMaxPts) segMaxPts = unwrapped
                    }
                }
                if (!pesCut && pesPackets < 3) {
                    if (randomAccess(p) || hasKeyNal(p)) cut()
                    pesPackets++
                }
            }
            if (pid in keep) kept++
            reader.advance()
            if (++count and 0xFFFF == 0L) {
                if (Thread.interrupted()) throw java.io.InterruptedIOException()
                onProgress(reader.position)
            }
        }
        if (segStartPos < 0) throw TsUnsupportedException("Bu TS videosunda başlangıç noktası bulunamadı. $DLNA_HINT")
        segments += Segment(segStartPos, reader.position, kept - segStartKept, ((segMaxPts + 3600 - segStartPts).coerceAtLeast(1)) / 90_000.0, segDiscontinuity)
        return Index(layout, tracks, segments)
    }

    private fun keptPids(t: Tracks): Set<Int> = setOfNotNull(t.videoPid, t.audio?.pid, t.pcrPid)

    // ---------------- Parçayı gönderme ----------------

    /** [input] parçanın başlangıcında (bayt [Segment.start]) hazır olmalı. */
    fun writeSegment(input: InputStream, index: Index, segment: Segment, out: OutputStream) {
        out.write(tables(index.tracks))
        val reader = PacketReader(input, index.layout, segment.start)
        val keep = keptPids(index.tracks)
        var written = 0L
        while (written < segment.packets && reader.position < segment.end && reader.next()) {
            if (pidOf(reader.buf) in keep) {
                out.write(reader.buf, 0, PACKET)
                written++
            }
            reader.advance()
        }
        // Başlıkta söylenen uzunluk tutsun: okuma erken biterse boş paketle doldurulur
        if (written < segment.packets) {
            val nullPacket = ByteArray(PACKET) { 0xFF.toByte() }
            nullPacket[0] = 0x47; nullPacket[1] = 0x1F; nullPacket[2] = 0xFF.toByte(); nullPacket[3] = 0x10
            while (written < segment.packets) { out.write(nullPacket); written++ }
        }
        out.flush()
    }

    /** Yalnızca seçilen görüntü ve sesi anlatan yeni PAT + PMT (2 paket). */
    fun tables(t: Tracks): ByteArray {
        val used = keptPids(t)
        val pmtPid = (0x1000..0x1FFE).first { it !in used }
        val pat = byteArrayOf(0x00, 0x01, 0xC1.toByte(), 0x00, 0x00, 0x00, 0x01, (0xE0 or (pmtPid shr 8)).toByte(), (pmtPid and 0xFF).toByte())
        val body = ByteArrayOutputStream()
        body.write(byteArrayOf(0x00, 0x01, 0xC1.toByte(), 0x00, 0x00, (0xE0 or (t.pcrPid shr 8)).toByte(), (t.pcrPid and 0xFF).toByte(), 0xF0.toByte(), 0x00))
        fun stream(type: Int, pid: Int) = body.write(byteArrayOf(type.toByte(), (0xE0 or (pid shr 8)).toByte(), (pid and 0xFF).toByte(), 0xF0.toByte(), 0x00))
        stream(0x1B, t.videoPid)
        t.audio?.let { stream(it.type, it.pid) }
        return tablePacket(0, section(0x00, pat)) + tablePacket(pmtPid, section(0x02, body.toByteArray()))
    }

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

    private fun tablePacket(pid: Int, sectionBytes: ByteArray): ByteArray {
        val p = ByteArray(PACKET) { 0xFF.toByte() }
        p[0] = 0x47
        p[1] = (0x40 or ((pid shr 8) and 0x1F)).toByte()
        p[2] = (pid and 0xFF).toByte()
        p[3] = 0x10
        p[4] = 0
        System.arraycopy(sectionBytes, 0, p, 5, sectionBytes.size)
        return p
    }
}
