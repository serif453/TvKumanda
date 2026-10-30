package com.serif.tvkumanda

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ekran kaydının saf mantığı: boyut seçimi, komut, durum ayrıştırma, hata açıklaması. */
class ScreenRecTest {

    @Test fun pickSizeUses720pOnlyForWideScreens() {
        assertEquals("1280x720", ScreenRec.pickSize("Physical size: 1920x1080\n"))
        assertEquals("1280x720", ScreenRec.pickSize("Physical size: 3840x2160\n"))
        assertEquals("1280x720", ScreenRec.pickSize("Physical size: 1280x720"))
        assertNull(ScreenRec.pickSize("Physical size: 1024x768"))
        assertNull(ScreenRec.pickSize(""))
        assertNull(ScreenRec.pickSize("wm: not found"))
    }

    @Test fun overrideSizeWinsOverPhysical() {
        val out = "Physical size: 3840x2160\nOverride size: 1024x768\n"
        assertNull(ScreenRec.pickSize(out))
        assertEquals("1280x720", ScreenRec.pickSize("Physical size: 1024x768\nOverride size: 1920x1080\n"))
    }

    @Test fun startCommandRunsInBackgroundWithLimits() {
        val c = ScreenRec.startCommand(60, "1280x720")
        assertTrue(c.contains("screenrecord --time-limit 60 --size 1280x720 --bit-rate 8000000 ${ScreenRec.REMOTE}"))
        assertTrue(c.contains("nohup"))
        assertTrue(c.trimEnd().endsWith("fi"))
        assertFalse(ScreenRec.startCommand(60, null).contains("--size"))
        // En fazla 180 sn, en az 5 sn
        assertTrue(ScreenRec.startCommand(999, null).contains("--time-limit 180"))
        assertTrue(ScreenRec.startCommand(1, null).contains("--time-limit 5"))
    }

    @Test fun parseStatusReadsPidAndSize() {
        assertEquals(ScreenRec.Status(true, 123456), ScreenRec.parseStatus("pid=4321\n123456\n"))
        assertEquals(ScreenRec.Status(false, 5000), ScreenRec.parseStatus("pid=\n5000\n"))
        assertEquals(ScreenRec.Status(false, 0), ScreenRec.parseStatus("pid=\n"))
        assertEquals(ScreenRec.Status(true, 0), ScreenRec.parseStatus("pid=77 78\n"))
        assertEquals(ScreenRec.Status(false, 0), ScreenRec.parseStatus(""))
        assertEquals(ScreenRec.Status(false, 42), ScreenRec.parseStatus("  pid= \n  42  \n"))
    }

    @Test fun explainGivesPlainReasons() {
        assertTrue(ScreenRec.explain("").contains("desteklemiyor"))
        assertTrue(ScreenRec.explain("screenrecord: not found").contains("komutu yok"))
        assertTrue(ScreenRec.explain("ERR: Unable to configure encoder").contains("kodlayıcı"))
        assertTrue(ScreenRec.explain("Permission denied").contains("izin"))
        assertTrue(ScreenRec.explain("garip bir hata\nikinci satır").contains("garip bir hata"))
        assertFalse(ScreenRec.explain("garip bir hata\nikinci satır").contains("ikinci satır"))
    }

    @Test fun fileNameLooksRight() {
        val n = ScreenRec.fileName(0L)
        assertTrue(n.startsWith("TV_kayit_"))
        assertTrue(n.endsWith(".mp4"))
        assertTrue(Regex("""TV_kayit_\d{8}_\d{6}\.mp4""").matches(n))
    }

    @Test fun durationsAreWithinLimit() {
        assertTrue(ScreenRec.DURATIONS.all { it in 5..ScreenRec.MAX_SECONDS })
        assertEquals(ScreenRec.MAX_SECONDS, ScreenRec.DURATIONS.last())
    }
}
