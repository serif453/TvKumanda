package com.serif.tvkumanda

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class TsHlsTest {
    private val sps = byteArrayOf(0, 0, 0, 1, 0x67, 0x42, 0, 0x1E)
    private val idr = byteArrayOf(0, 0, 0, 1, 0x65, 1, 2, 3)
    private val slice = byteArrayOf(0, 0, 0, 1, 0x41, 1, 2, 3)

    /** 25 kare/sn görüntü, her [gopSec] saniyede anahtar kare, araya AAC sesi; 188 baytlık paketler. */
    private fun sample(seconds: Int, gopSec: Int = 2, withAudio: Boolean = true, startUs: Long = 0): ByteArray {
        val out = ByteArrayOutputStream()
        val mux = TsMuxer(true, withAudio, 0) { b, _ -> out.write(b) }
        for (i in 0 until seconds * 25) {
            val us = startUs + i * 40_000L
            val key = i % (gopSec * 25) == 0
            mux.writeVideo(if (key) sps + idr else slice, us, key)
            if (withAudio) mux.writeAudio(Adts.header(4, 48000, 2) + byteArrayOf(1, 2, 3, 4), us)
        }
        return out.toByteArray()
    }

    private fun index(data: ByteArray): TsHls.Index {
        val layout = TsHls.detectLayout(data.copyOf(minOf(data.size, 8192)))!!
        val tracks = TsHls.analyze(ByteArrayInputStream(data), layout)
        return TsHls.buildIndex(ByteArrayInputStream(data), layout, tracks)
    }

    /** Sonuçtaki paketlerin PID'leri. */
    private fun pids(b: ByteArray) = (0 until b.size / 188).map { ((b[it * 188 + 1].toInt() and 0x1F) shl 8) or (b[it * 188 + 2].toInt() and 0xFF) }

    @Test fun recognizesTsNamesAndTypes() {
        assertTrue(TsHls.isTs("kayit.TS", null))
        assertTrue(TsHls.isTs("kamera.m2ts", null))
        assertTrue(TsHls.isTs("kamera.mts", null))
        assertTrue(TsHls.isTs(null, "video/mp2t"))
        assertFalse(TsHls.isTs("film.mp4", "video/mp4"))
    }

    @Test fun detectsPacketLayouts() {
        val ts = sample(1)
        assertEquals(188, TsHls.detectLayout(ts)!!.step)
        val m2ts = ByteArrayOutputStream()
        for (i in 0 until ts.size / 188) { m2ts.write(ByteArray(4)); m2ts.write(ts, i * 188, 188) }
        val layout = TsHls.detectLayout(m2ts.toByteArray())!!
        assertEquals(192, layout.step)
        assertEquals(4, layout.start)
        assertNull(TsHls.detectLayout(ByteArray(4096)))
    }

    @Test fun cutsAtKeyFramesAboutEverySixSeconds() {
        val idx = index(sample(20))
        assertEquals(4, idx.segments.size)
        assertEquals(6.0, idx.segments[0].seconds, 0.001)
        assertEquals(20.0, idx.durationSeconds, 0.1)
        idx.segments.zipWithNext().forEach { (a, b) -> assertEquals(a.end, b.start) }
    }

    @Test fun playlistIsCompleteVod() {
        val text = index(sample(14)).playlist()
        assertTrue(text.startsWith("#EXTM3U\n"))
        assertTrue(text.contains("#EXT-X-PLAYLIST-TYPE:VOD"))
        assertTrue(text.contains("#EXTINF:6.000,\n0.ts\n"))
        assertTrue(text.trimEnd().endsWith("#EXT-X-ENDLIST"))
    }

    @Test fun segmentHasFreshTablesAndExactLength() {
        val data = sample(14)
        val idx = index(data)
        val s = idx.segments[1]
        val out = ByteArrayOutputStream()
        TsHls.writeSegment(ByteArrayInputStream(data, s.start.toInt(), data.size - s.start.toInt()), idx, s, out)
        val b = out.toByteArray()
        assertEquals(s.bytes, b.size.toLong())
        val list = pids(b)
        assertEquals(0, list[0])
        assertEquals(TsMuxer.PID_VIDEO, list[2])
        assertTrue(list.drop(2).all { it == TsMuxer.PID_VIDEO || it == TsMuxer.PID_AUDIO })
    }

    @Test fun recoversFromLostSync() {
        val data = sample(14)
        val broken = data.copyOfRange(0, 188 * 500) + ByteArray(50) + data.copyOfRange(188 * 500, data.size)
        val idx = index(broken)
        assertEquals(14.0, idx.durationSeconds, 0.1)
    }

    @Test fun recordingGapBecomesDiscontinuity() {
        val idx = index(sample(8) + sample(8, startUs = 600_000_000L))
        assertTrue(idx.segments.any { it.discontinuity })
        assertEquals(16.0, idx.durationSeconds, 1.0)
        assertTrue(idx.playlist().contains("#EXT-X-DISCONTINUITY"))
    }

    @Test fun rewrittenAudioTablePicksStream() {
        val idx = index(sample(4))
        assertNotNull(idx.tracks.audio)
        assertEquals(0x0F, idx.tracks.audio!!.type)
        assertNull(index(sample(4, withAudio = false)).tracks.audio)
    }

    @Test fun garbageIsRejected() {
        val junk = ByteArray(188 * 20).also { d -> for (i in 0 until 20) d[i * 188] = 0x47 }
        val layout = TsHls.detectLayout(junk)!!
        assertThrows(TsUnsupportedException::class.java) { TsHls.analyze(ByteArrayInputStream(junk), layout) }
    }
}
