package com.serif.tvkumanda

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitlesTest {

    @Test
    fun srtBecomesVtt() {
        val srt = "1\r\n00:00:01,500 --> 00:00:03,000\r\nMerhaba dünya\r\n\r\n2\r\n00:01:02,05 --> 00:01:04,100\r\nİkinci satır\r\n"
        val vtt = Subtitles.toVtt(srt)
        assertTrue(vtt.startsWith("WEBVTT\n\n"))
        assertTrue(vtt.contains("00:00:01.500 --> 00:00:03.000"))
        assertTrue(vtt.contains("00:01:02.050 --> 00:01:04.100"))
        assertTrue(vtt.contains("Merhaba dünya"))
    }

    @Test
    fun turkishWindows1254IsDecoded() {
        // "şğı" Windows-1254 ile: FE F0 FD
        val bytes = byteArrayOf(0xFE.toByte(), 0xF0.toByte(), 0xFD.toByte())
        assertEquals("şğı", Subtitles.decode(bytes))
    }

    @Test
    fun utf8IsKept() {
        assertEquals("şğı", Subtitles.decode("şğı".toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun vttPassesThrough() {
        val vtt = "WEBVTT\n\n00:00:01.000 --> 00:00:02.000\nx\n"
        assertEquals(vtt, Subtitles.toVtt(vtt))
    }
}
