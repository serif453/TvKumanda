package com.serif.tvkumanda

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CastUrlTest {
    @Test fun parseFindsAddress() {
        assertEquals("https://a.com/v.mp4", CastUrl.parse("  https://a.com/v.mp4 "))
        assertEquals("http://a.com/v.mp4", CastUrl.parse("Bak: http://a.com/v.mp4."))
        assertEquals("https://a.com/v.mp4", CastUrl.parse("a.com/v.mp4"))
        assertNull(CastUrl.parse("merhaba dünya"))
        assertNull(CastUrl.parse("kullanici@a.com"))
        assertNull(CastUrl.parse(""))
    }

    @Test fun mimeIgnoresQueryAndCase() {
        assertEquals("video/mp4", CastUrl.mimeOf("https://a.com/x/V.MP4?token=1#t"))
        assertEquals("application/x-mpegURL", CastUrl.mimeOf("http://a.com:8080/canli/yayin.m3u8"))
        assertNull(CastUrl.mimeOf("https://a.com/izle"))
        // Site adının sonu uzantı sayılmaz
        assertNull(CastUrl.mimeOf("https://video.mp4"))
    }

    @Test fun knownPages() {
        assertTrue(CastUrl.isKnownPage("https://www.youtube.com/watch?v=1"))
        assertTrue(CastUrl.isKnownPage("https://youtu.be/1"))
        assertFalse(CastUrl.isKnownPage("https://a.com/izle"))
        assertFalse(CastUrl.isKnownPage("https://notyoutube.com/izle"))
        assertFalse(CastUrl.isKnownPage("https://player.vimeo.com/x/v.mp4"))
    }

    @Test fun contentTypes() {
        assertTrue(CastUrl.isPageType("Text/HTML; charset=utf-8"))
        assertFalse(CastUrl.isPageType(null))
        assertTrue(CastUrl.isMediaType("video/mp4"))
        assertTrue(CastUrl.isMediaType("application/vnd.apple.mpegurl"))
        assertFalse(CastUrl.isMediaType("application/octet-stream"))
    }

    @Test fun kinds() {
        assertEquals(MediaKind.MUSIC, CastUrl.kindOf("audio/mpeg"))
        assertEquals(MediaKind.PHOTO, CastUrl.kindOf("image/png"))
        assertEquals(MediaKind.VIDEO, CastUrl.kindOf("application/x-mpegURL"))
        assertEquals(MediaKind.VIDEO, CastUrl.kindOf(null))
    }

    @Test fun titles() {
        assertEquals("Benim Film.mp4", CastUrl.title("https://a.com/x/Benim%20Film.mp4?t=1"))
        assertEquals("a.com", CastUrl.title("https://A.com/"))
        assertEquals("a.com", CastUrl.title("https://a.com"))
    }

    @Test fun historyMovesToFrontAndIsLimited() {
        assertEquals(listOf("b", "a"), CastUrl.remember(listOf("a", "b"), "b"))
        val full = (1..CastUrl.MAX_HISTORY).map { "$it" }
        val next = CastUrl.remember(full, "yeni")
        assertEquals(CastUrl.MAX_HISTORY, next.size)
        assertEquals("yeni", next.first())
    }
}
