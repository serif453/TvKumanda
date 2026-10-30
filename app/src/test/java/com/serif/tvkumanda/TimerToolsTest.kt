package com.serif.tvkumanda

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.TimeZone

class TimerToolsTest {

    /** Sahte TV: ekran açık mı, ses kaç; gönderilen komutları kaydeder. */
    private class FakeTv(var awake: Boolean = true, var volume: Int = 20) : Watcher.Shell {
        val commands = mutableListOf<String>()
        val keys get() = commands.filter { it.startsWith("input keyevent") }.map { it.removePrefix("input keyevent ").toInt() }
        override fun run(command: String): String {
            commands += command
            return when {
                command.startsWith("dumpsys power") -> if (awake) "  mWakefulness=Awake" else "  mWakefulness=Asleep"
                command.startsWith("cmd media_session volume --stream 3 --get") -> "volume is $volume in range [0..60]"
                command.startsWith("cmd media_session volume --stream 3 --set ") -> { volume = command.substringAfterLast(' ').toInt(); "" }
                command.startsWith("input keyevent") -> { if (command.endsWith(" 223")) awake = false; "" }
                else -> ""
            }
        }
    }

    private fun watcher(cfg: WatcherConfig, tv: FakeTv): Pair<Watcher, File> {
        val dir = Files.createTempDirectory("nobetci").toFile()
        File(dir, Watcher.CONF).writeText(cfg.encode())
        val w = Watcher(dir, tv, "/data/local/tmp/tvkumanda-1.apk", 42)
        w.restore()
        return w to dir
    }

    @Test
    fun configRoundTrip() {
        val c = WatcherConfig(sleepAt = 123, fade = true, volMax = 30, limitMin = 120, nightFrom = 23 * 60, nightTo = 7 * 60)
        assertEquals(c, WatcherConfig.parse(c.encode()))
        assertEquals(WatcherConfig(), WatcherConfig.parse(""))
    }

    @Test
    fun nothingToDoExitsAndWritesState() {
        val tv = FakeTv()
        val (w, dir) = watcher(WatcherConfig(), tv)
        assertFalse(w.tick(System.currentTimeMillis()))
        val st = WatcherState.parse(File(dir, Watcher.STATE).readText())!!
        assertEquals(42L, st.pid)
        assertFalse(st.running)
    }

    @Test
    fun sleepTimerFiresOnceWithFade() {
        val tv = FakeTv()
        val start = System.currentTimeMillis()
        val (w, _) = watcher(WatcherConfig(sleepAt = start + 120_000, fade = true), tv)
        assertTrue(w.tick(start))
        assertTrue(tv.keys.isEmpty())
        // son dakika: ses kısılır
        assertTrue(w.tick(start + 65_000))
        assertTrue(w.tick(start + 72_000))
        assertEquals(listOf(25, 25), tv.keys)
        // süre doldu: TV uyur, iş kalmaz
        assertTrue(w.tick(start + 120_500))
        assertEquals(223, tv.keys.last())
        assertFalse(w.tick(start + 125_000))
        assertEquals(1, tv.keys.count { it == 223 })
    }

    @Test
    fun staleSleepIsSkipped() {
        val tv = FakeTv()
        val now = System.currentTimeMillis()
        val (w, _) = watcher(WatcherConfig(sleepAt = now - 30 * 60_000), tv)
        w.tick(now)
        assertTrue(223 !in tv.keys)
    }

    @Test
    fun volumeCapLowersVolume() {
        val tv = FakeTv(volume = 45)
        val (w, _) = watcher(WatcherConfig(volMax = 25), tv)
        assertTrue(w.tick(System.currentTimeMillis()))
        assertEquals(25, tv.volume)
    }

    @Test
    fun screenLimitCountsOnlyAwakeTime() {
        val tv = FakeTv()
        val t0 = System.currentTimeMillis()
        val (w, _) = watcher(WatcherConfig(limitMin = 1), tv)
        w.tick(t0)
        w.tick(t0 + 30_000)
        assertEquals(30L, w.usedSec)
        assertTrue(223 !in tv.keys)
        w.tick(t0 + 60_000)      // 60 sn doldu → uyut
        assertEquals(223, tv.keys.last())
        tv.awake = false
        w.tick(t0 + 90_000)
        w.tick(t0 + 120_000)
        assertEquals(60L, w.usedSec)   // uyurken sayılmaz
        tv.awake = true                // biri yeniden açtı
        w.tick(t0 + 150_000)
        assertEquals(2, tv.keys.count { it == 223 })
    }

    @Test
    fun nightRangeWrapsMidnight() {
        assertTrue(Watcher.inNight(23 * 60 + 30, 23 * 60, 7 * 60))
        assertTrue(Watcher.inNight(3 * 60, 23 * 60, 7 * 60))
        assertFalse(Watcher.inNight(7 * 60, 23 * 60, 7 * 60))
        assertFalse(Watcher.inNight(12 * 60, 23 * 60, 7 * 60))
        assertTrue(Watcher.inNight(14 * 60, 13 * 60, 15 * 60))
        assertFalse(Watcher.inNight(14 * 60, -1, -1))
    }

    @Test
    fun volumeParsing() {
        assertEquals(17, Watcher.parseVolume("[v] volume is 17 in range [0..100]"))
        assertEquals(-1, Watcher.parseVolume("Error"))
    }

    @Test
    fun hasWorkAndPendingSleep() {
        val now = 1_000_000_000L
        assertFalse(WatcherConfig().hasWork(now, 0))
        assertTrue(WatcherConfig(sleepAt = now + 60_000).hasWork(now, 0))
        assertFalse(WatcherConfig(sleepAt = now + 60_000).hasWork(now, now + 60_000))
        assertEquals(now + 60_000, WatcherConfig(sleepAt = now + 60_000).pendingSleep(now, 0))
        assertNull(WatcherConfig(sleepAt = now - 1).pendingSleep(now, 0))
        assertTrue(WatcherConfig(volMax = 0).hasWork(now, 0))
    }

    @Test
    fun nextWakeTime() {
        val tz = TimeZone.getTimeZone("Europe/Istanbul")
        // 30.10.2026 18:00 İstanbul = 15:00 UTC
        val now = 1_793_372_400_000L
        assertEquals("18:00", TimerText.clockOf(now, tz))
        val today = TimerText.nextAt(19, 30, now, tz)
        assertEquals(90 * 60_000L, today - now)
        val tomorrow = TimerText.nextAt(7, 0, now, tz)
        assertEquals(13 * 3600_000L, tomorrow - now)
    }

    @Test
    fun texts() {
        assertEquals("1 sa 05 dk", TimerText.remaining(3_900_000))
        assertEquals("4:09", TimerText.remaining(249_000))
        assertEquals("2 sa 15 dk", TimerText.duration(8100))
        assertEquals("45 dk", TimerText.duration(2700))
        assertEquals("23:00", TimerText.clock(23 * 60))
    }
}
