package com.serif.tvkumanda

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.Socket

/** Canlı yayın paketleyicisi (MPEG-TS), ADTS başlığı, boyut hesabı ve yayın sunucusu. */
class LiveTsTest {

    // ---------- Yardımcılar: üretilen TS akışını bağımsız biçimde çözer ----------

    private class Pkt(val pid: Int, val pusi: Boolean, val cc: Int, val af: ByteArray?, val payload: ByteArray)

    private fun parse(bytes: ByteArray): List<Pkt> {
        assertEquals("Paket boyutu 188'in katı olmalı", 0, bytes.size % 188)
        return (0 until bytes.size / 188).map { i ->
            val p = bytes.copyOfRange(i * 188, i * 188 + 188)
            assertEquals(0x47, p[0].toInt() and 0xFF)
            val pusi = (p[1].toInt() and 0x40) != 0
            val pid = ((p[1].toInt() and 0x1F) shl 8) or (p[2].toInt() and 0xFF)
            val afc = (p[3].toInt() shr 4) and 3
            val cc = p[3].toInt() and 0x0F
            var off = 4
            var af: ByteArray? = null
            if (afc and 2 != 0) {
                val len = p[4].toInt() and 0xFF
                af = p.copyOfRange(5, 5 + len)
                off = 5 + len
                assertTrue("Uyarlama alanı pakete sığmalı", off <= 188)
            }
            val payload = if (afc and 1 != 0) p.copyOfRange(off, 188) else ByteArray(0)
            Pkt(pid, pusi, cc, af, payload)
        }
    }

    private fun payloadOf(pkts: List<Pkt>, pid: Int): ByteArray {
        val o = ByteArrayOutputStream()
        pkts.filter { it.pid == pid }.forEach { o.write(it.payload) }
        return o.toByteArray()
    }

    private fun decodePts(b: ByteArray, o: Int): Long =
        ((b[o].toLong() shr 1 and 7) shl 30) or ((b[o + 1].toLong() and 0xFF) shl 22) or
            ((b[o + 2].toLong() and 0xFF shr 1) shl 15) or ((b[o + 3].toLong() and 0xFF) shl 7) or
            (b[o + 4].toLong() and 0xFF shr 1)

    private fun decodePcrBase(af: ByteArray): Long =
        ((af[1].toLong() and 0xFF) shl 25) or ((af[2].toLong() and 0xFF) shl 17) or ((af[3].toLong() and 0xFF) shl 9) or
            ((af[4].toLong() and 0xFF) shl 1) or ((af[5].toLong() and 0xFF) shr 7)

    private fun hex(s: String) = s.split(' ').map { it.toInt(16).toByte() }.toByteArray()

    // ---------- CRC, ADTS, boyut ----------

    @Test fun crcMatchesKnownPatSection() {
        // PID 0x1000'deki tek programlı PAT'ın bilinen CRC'si (ffmpeg çıktılarında görülen)
        val section = hex("00 b0 0d 00 01 c1 00 00 00 01 f0 00")
        assertEquals(0x2AB104B2, Crc32Mpeg.of(section))
        // Tüm bölüm (CRC dahil) üzerinden CRC her zaman 0 çıkar
        assertEquals(0, Crc32Mpeg.of(section + hex("2a b1 04 b2")))
    }

    @Test fun adtsHeaderFor44k1Stereo() {
        assertArrayEquals(hex("ff f1 50 80 0d 7f fc"), Adts.header(100, 44100, 2))
        // 48 kHz mono, 1 baytlık kare
        val h = Adts.header(1, 48000, 1)
        assertEquals(0xFF, h[0].toInt() and 0xFF)
        assertEquals((1 shl 6) or (3 shl 2), h[2].toInt() and 0xFF)
    }

    @Test fun liveSizeFits() {
        assertEquals(576 to 1280, LiveSize.fit(1080, 2400))
        assertEquals(1280 to 720, LiveSize.fit(1920, 1080))
        assertEquals(640 to 480, LiveSize.fit(640, 480))      // büyütülmez
        assertEquals(1072 to 720, LiveSize.fit(1081, 721))     // 16'nın katına iner
        val (w, h) = LiveSize.fit(3, 5)
        assertTrue(w >= 16 && h >= 16)
    }

    @Test fun detectsSpsStart() {
        assertTrue(TsMuxer.startsWithSps(hex("00 00 00 01 67 42 00 1f")))
        assertTrue(TsMuxer.startsWithSps(hex("00 00 01 67 42")))
        assertFalse(TsMuxer.startsWithSps(hex("00 00 00 01 65 88 84")))
        assertFalse(TsMuxer.startsWithSps(hex("00 00 00 01 41 9a")))
    }

    // ---------- Paketleyici ----------

    private fun muxerCollecting(video: Boolean, audio: Boolean, base: Long = 1_000_000L): Pair<TsMuxer, MutableList<Pair<ByteArray, Boolean>>> {
        val out = mutableListOf<Pair<ByteArray, Boolean>>()
        return TsMuxer(video, audio, base) { b, k -> out += b to k } to out
    }

    @Test fun keyFrameStartsWithPatPmtAndCarriesPtsAndPcr() {
        val (mux, out) = muxerCollecting(video = true, audio = false)
        mux.setVideoConfig(hex("00 00 00 01 67 42 00 1f 00 00 00 01 68 ce 3c 80"))
        val frame = hex("00 00 00 01 65 88 84 00 11 22 33")
        mux.writeVideo(frame, 1_500_000L, key = true)

        assertEquals(1, out.size)
        assertTrue("Anahtar kare işaretli olmalı", out[0].second)
        val pkts = parse(out[0].first)
        // PAT, PMT, sonra görüntü
        assertEquals(0, pkts[0].pid)
        assertEquals(TsMuxer.PID_PMT, pkts[1].pid)
        assertEquals(TsMuxer.PID_VIDEO, pkts[2].pid)
        // PAT ve PMT'nin CRC'si doğru
        val pat = pkts[0].payload
        assertEquals(0, pat[0].toInt())                       // pointer_field
        val patLen = 3 + (((pat[2].toInt() and 0x0F) shl 8) or (pat[3].toInt() and 0xFF))
        assertEquals(0, Crc32Mpeg.of(pat, 1, 1 + patLen))
        val pmt = pkts[1].payload
        val pmtLen = 3 + (((pmt[2].toInt() and 0x0F) shl 8) or (pmt[3].toInt() and 0xFF))
        assertEquals(0, Crc32Mpeg.of(pmt, 1, 1 + pmtLen))
        assertEquals(0x1B, pmt[1 + 12].toInt())               // ilk akış: H.264
        // Görüntü: PES başlığı, PTS (0.5 sn = 45000 + 1 sn pay) ve PCR (45000)
        val vpkts = pkts.filter { it.pid == TsMuxer.PID_VIDEO }
        assertTrue(vpkts[0].pusi)
        assertNotNull(vpkts[0].af)
        assertEquals(0x50, vpkts[0].af!![0].toInt() and 0x50)  // PCR + rastgele erişim bayrakları
        assertEquals(45_000L, decodePcrBase(vpkts[0].af!!))
        val pes = payloadOf(pkts, TsMuxer.PID_VIDEO)
        assertArrayEquals(hex("00 00 01 e0 00 00 80 80 05"), pes.copyOfRange(0, 9))
        assertEquals(135_000L, decodePts(pes, 9))
        // Yük: AUD + (SPS/PPS) + kare
        val expected = hex("00 00 00 01 09 f0") + hex("00 00 00 01 67 42 00 1f 00 00 00 01 68 ce 3c 80") + frame
        assertArrayEquals(expected, pes.copyOfRange(14, pes.size))
    }

    @Test fun deltaFrameHasNoTablesAndNoConfig() {
        val (mux, out) = muxerCollecting(video = true, audio = false)
        mux.setVideoConfig(hex("00 00 00 01 67 42 00 1f"))
        mux.writeVideo(hex("00 00 00 01 65 01"), 1_000_000L, key = true)
        mux.writeVideo(hex("00 00 00 01 41 02 03"), 1_033_000L, key = false)
        assertFalse(out[1].second)
        val pkts = parse(out[1].first)
        assertTrue(pkts.all { it.pid == TsMuxer.PID_VIDEO })
        val pes = payloadOf(pkts, TsMuxer.PID_VIDEO)
        assertArrayEquals(hex("00 00 00 01 09 f0 00 00 00 01 41 02 03"), pes.copyOfRange(14, pes.size))
    }

    @Test fun keyFrameWithOwnSpsIsNotDoubled() {
        val (mux, out) = muxerCollecting(video = true, audio = false)
        mux.setVideoConfig(hex("00 00 00 01 67 AA"))
        val frame = hex("00 00 00 01 67 42 00 00 00 00 01 65 01")
        mux.writeVideo(frame, 1_000_000L, key = true)
        val pes = payloadOf(parse(out[0].first), TsMuxer.PID_VIDEO)
        assertArrayEquals(hex("00 00 00 01 09 f0") + frame, pes.copyOfRange(14, pes.size))
    }

    @Test fun everyFrameSizeSurvivesPacketization() {
        // Dolgu (stuffing) sınır durumları dahil: 0..700 baytlık kareler hatasız bölünüp birleşmeli
        val (mux, out) = muxerCollecting(video = true, audio = false)
        var t = 1_000_000L
        for (n in 0..700) {
            out.clear()
            val frame = ByteArray(n) { (it * 7 + n).toByte() }
            mux.writeVideo(frame, t, key = false)
            t += 33_000
            val pkts = parse(out[0].first)
            val pes = payloadOf(pkts, TsMuxer.PID_VIDEO)
            // Her kare AUD (6 bayt) ile başlar
            assertArrayEquals("kare boyutu $n", hex("00 00 00 01 09 f0") + frame, pes.copyOfRange(14, pes.size))
            assertTrue(pkts[0].pusi)
            assertTrue(pkts.drop(1).none { it.pusi })
        }
    }

    @Test fun continuityCountersIncreasePerPid() {
        val (mux, out) = muxerCollecting(video = true, audio = true)
        mux.setVideoConfig(hex("00 00 00 01 67 42"))
        repeat(12) { i ->
            mux.writeVideo(ByteArray(900) { 1 }, 1_000_000L + i * 33_000L, key = i % 6 == 0)
            mux.writeAudio(Adts.header(300, 44100, 2) + ByteArray(300), 1_000_000L + i * 23_000L)
        }
        val all = parse(out.fold(ByteArray(0)) { a, b -> a + b.first })
        for (pid in listOf(0, TsMuxer.PID_PMT, TsMuxer.PID_VIDEO, TsMuxer.PID_AUDIO)) {
            val ccs = all.filter { it.pid == pid }.map { it.cc }
            assertTrue("PID $pid paketi var", ccs.isNotEmpty())
            for (i in 1 until ccs.size) assertEquals("PID $pid sayacı", (ccs[i - 1] + 1) and 0x0F, ccs[i])
        }
    }

    @Test fun audioPesHasCorrectLengthAndPts() {
        val (mux, out) = muxerCollecting(video = true, audio = true)
        val adts = Adts.header(200, 44100, 2) + ByteArray(200) { 5 }
        mux.writeAudio(adts, 1_020_000L)    // 20 ms → 1800 + 90000
        assertFalse("Görüntülü yayında ses parçası anahtar sayılmaz", out[0].second)
        val pkts = parse(out[0].first)
        assertTrue(pkts.all { it.pid == TsMuxer.PID_AUDIO })
        assertTrue("Sesin PCR'ı olmaz (PCR görüntüden gelir)", pkts.all { it.af == null || (it.af[0].toInt() and 0x10) == 0 })
        val pes = payloadOf(pkts, TsMuxer.PID_AUDIO)
        assertEquals(0xC0, pes[3].toInt() and 0xFF)
        val declared = ((pes[4].toInt() and 0xFF) shl 8) or (pes[5].toInt() and 0xFF)
        assertEquals(pes.size - 6, declared)
        assertEquals(91_800L, decodePts(pes, 9))
        assertArrayEquals(adts, pes.copyOfRange(14, pes.size))
    }

    @Test fun pmtListsOnlyPresentStreams() {
        val v = TsMuxer(true, false, 0) { _, _ -> }.tables()
        val va = TsMuxer(true, true, 0) { _, _ -> }.tables()
        val pmtV = parse(v)[1].payload
        val pmtVA = parse(va)[1].payload
        val lenV = ((pmtV[2].toInt() and 0x0F) shl 8) or (pmtV[3].toInt() and 0xFF)
        val lenVA = ((pmtVA[2].toInt() and 0x0F) shl 8) or (pmtVA[3].toInt() and 0xFF)
        assertEquals(5, lenVA - lenV)    // ikinci akış 5 bayt ekler
    }

    // ---------- Yayın sunucusu ----------

    private fun readHeaders(s: Socket): String {
        val sb = StringBuilder()
        val ins = s.getInputStream()
        while (!sb.endsWith("\r\n\r\n")) {
            val c = ins.read()
            if (c < 0) break
            sb.append(c.toChar())
        }
        return sb.toString()
    }

    private fun waitViewers(srv: LiveServer, n: Int) {
        val end = System.currentTimeMillis() + 3000
        while (srv.viewers < n && System.currentTimeMillis() < end) Thread.sleep(20)
        assertEquals(n, srv.viewers)
    }

    @Test fun serverWaitsForKeyFrameThenStreams() {
        val srv = LiveServer("video/mp2t", "/live.ts", needKey = true)
        val port = srv.start()
        try {
            Socket("127.0.0.1", port).use { s ->
                s.soTimeout = 3000
                s.getOutputStream().write("GET /live.ts HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray())
                val head = readHeaders(s)
                assertTrue(head.startsWith("HTTP/1.1 200 OK"))
                assertTrue(head.contains("Content-Type: video/mp2t"))
                assertFalse("Akışın uzunluğu bilinmez", head.contains("Content-Length"))
                waitViewers(srv, 1)
                srv.publish(byteArrayOf(1, 1), key = false)    // anahtar kareden önce: atlanır
                srv.publish(byteArrayOf(2, 2), key = true)
                srv.publish(byteArrayOf(3), key = false)
                val got = ByteArray(3)
                var n = 0
                while (n < 3) { val r = s.getInputStream().read(got, n, 3 - n); if (r < 0) break; n += r }
                assertArrayEquals(byteArrayOf(2, 2, 3), got)
            }
        } finally {
            srv.stop()
        }
    }

    @Test fun audioServerForwardsEverything() {
        val srv = LiveServer("audio/aac", "/live.aac", needKey = false)
        val port = srv.start()
        try {
            Socket("127.0.0.1", port).use { s ->
                s.soTimeout = 3000
                s.getOutputStream().write("GET /live.aac HTTP/1.1\r\n\r\n".toByteArray())
                readHeaders(s)
                waitViewers(srv, 1)
                srv.publish(byteArrayOf(9, 8), key = true)
                srv.publish(byteArrayOf(7), key = true)
                val got = ByteArray(3)
                var n = 0
                while (n < 3) { val r = s.getInputStream().read(got, n, 3 - n); if (r < 0) break; n += r }
                assertArrayEquals(byteArrayOf(9, 8, 7), got)
            }
        } finally {
            srv.stop()
        }
    }

    @Test fun serverRejectsOtherPathsAndStopsCleanly() {
        val srv = LiveServer("video/mp2t", "/live.ts", needKey = true)
        val port = srv.start()
        Socket("127.0.0.1", port).use { s ->
            s.soTimeout = 3000
            s.getOutputStream().write("GET /baska HTTP/1.1\r\n\r\n".toByteArray())
            assertTrue(readHeaders(s).startsWith("HTTP/1.1 404"))
        }
        srv.stop()
        assertEquals(0, srv.viewers)
    }
}
