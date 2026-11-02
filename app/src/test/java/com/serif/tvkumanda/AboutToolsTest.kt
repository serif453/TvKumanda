package com.serif.tvkumanda

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.util.Date

class AboutToolsTest {
    @Test fun versionsCompareByParts() {
        assertTrue(UpdateCheck.isNewer("0.99", "v0.100"))
        assertFalse(UpdateCheck.isNewer("0.100", "v0.99"))
        assertFalse(UpdateCheck.isNewer("0.160", "v0.160"))
        assertTrue(UpdateCheck.isNewer("0.160", "v1.0"))
        assertFalse(UpdateCheck.isNewer("?", "v0.160"))
        assertFalse(UpdateCheck.isNewer("0.160", "son"))
    }

    @Test fun evaluateReadsGithubAnswer() {
        val json = """{"url":"x","tag_name" : "v0.161","name":"TV Kumanda 0.161"}"""
        assertEquals("v0.161", UpdateCheck.parseTag(json))
        assertEquals(UpdateCheck.Result.Available("0.161"), UpdateCheck.evaluate("0.160", json))
        assertEquals(UpdateCheck.Result.UpToDate, UpdateCheck.evaluate("0.161", json))
        assertTrue(UpdateCheck.evaluate("0.160", "{}") is UpdateCheck.Result.Failed)
        assertNull(UpdateCheck.parseTag("{}"))
    }

    @Test fun crashEntryHasTimeHeaderTraceAndMaskedIp() {
        val text = CrashLog.format(Date(0), "TV Kumanda 0.160", IllegalStateException("192.168.1.37 kapalı"))
        val lines = text.lines()
        assertEquals("TV Kumanda 0.160", lines[1])
        assertTrue(lines[2].contains("IllegalStateException: 192.168.1.x kapalı"))
        assertTrue(text.contains("AboutToolsTest"))
        assertFalse(text.contains("1.37"))
    }

    @Test fun longTraceIsCut() {
        val e = RuntimeException("x")
        e.stackTrace = Array(200) { StackTraceElement("A", "b$it", "A.kt", it) }
        val lines = CrashLog.format(Date(0), "", e).lines()
        assertTrue(lines.size <= 82)
        assertTrue(lines.last().contains("satır daha"))
    }

    @Test fun mergeKeepsNewestFirstAndDropsOldest() {
        var all = ""
        for (i in 1..(CrashLog.KEEP + 2)) all = CrashLog.merge(all, "kayit $i\nsatir")
        val list = CrashLog.split(all)
        assertEquals(CrashLog.KEEP, list.size)
        assertEquals("kayit ${CrashLog.KEEP + 2}", CrashLog.timeOf(list.first()))
        assertEquals("kayit 3", CrashLog.timeOf(list.last()))
    }

    @Test fun savesReadsAndClearsFile() {
        val dir = Files.createTempDirectory("cokme").toFile()
        CrashLog.init(dir)
        CrashLog.clear()
        assertTrue(CrashLog.entries().isEmpty())
        CrashLog.save(RuntimeException("bir"))
        CrashLog.save(RuntimeException("iki"))
        val list = CrashLog.entries()
        assertEquals(2, list.size)
        assertTrue(list[0].contains("iki"))
        CrashLog.clear()
        assertTrue(CrashLog.entries().isEmpty())
        dir.deleteRecursively()
    }
}
