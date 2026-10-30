package com.serif.tvkumanda

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Performans aracı: /proc/stat farkından yüzde, bellek ve kapalı çekirdek. */
class PerfStatsTest {

    private fun out(cpu: String, cores: List<String>, present: String = "0-3") = buildString {
        appendLine("##S")
        appendLine("cpu  $cpu")
        cores.forEachIndexed { i, c -> if (c.isNotEmpty()) appendLine("cpu$i $c") }
        appendLine("##M")
        appendLine("MemTotal:        2900000 kB")
        appendLine("MemAvailable:     812000 kB")
        appendLine("##C")
        appendLine(present)
    }

    // user nice system idle iowait irq softirq steal guest guest_nice
    private val a = out("400 0 100 1500 0 0 0 0 0 0", listOf("100 0 25 375 0 0 0 0", "100 0 25 375 0 0 0 0", "100 0 25 375 0 0 0 0", "100 0 25 375 0 0 0 0"))
    private val b = out("700 0 200 2100 0 0 0 0 0 0", listOf("100 0 25 475 0 0 0 0", "100 0 25 475 0 0 0 0", "130 0 25 445 0 0 0 0", "370 0 30 375 0 0 0 0"))

    @Test fun bellek() {
        val v = PerfStats.view(null, PerfStats.parse(a))
        assertEquals(2900000L, v.totalKb)
        assertEquals(812000L, v.availKb)
        assertEquals(72, v.usedPercent)
    }

    @Test fun ilkOlcumdeYuzdeYok() {
        val v = PerfStats.view(null, PerfStats.parse(a))
        assertNull(v.cpu)
        assertEquals(4, v.cores.size)
        assertTrue(v.cores.all { it == null })
    }

    @Test fun yuzdeler() {
        val v = PerfStats.view(PerfStats.parse(a), PerfStats.parse(b))
        assertEquals(40, v.cpu) // 400 iş / 1000
        assertEquals(listOf(0, 0, 30, 100), v.cores)
        assertTrue(v.offline.isEmpty())
    }

    @Test fun iowaitBostaSayilir() {
        val x = PerfStats.parse(out("0 0 0 0 0 0 0 0", emptyList()))
        val y = PerfStats.parse(out("50 0 0 0 50 0 0 0", emptyList()))
        assertEquals(50, PerfStats.view(x, y).cpu)
    }

    @Test fun kapaliCekirdek() {
        val s = PerfStats.parse(out("1 0 0 1 0 0 0 0", listOf("1 0 0 1", "", "1 0 0 1", "")))
        val v = PerfStats.view(null, s)
        assertEquals(4, v.cores.size)
        assertEquals(setOf(1, 3), v.offline)
    }

    @Test fun cekirdekSayisi() {
        assertEquals(4, PerfStats.presentCount("0-3"))
        assertEquals(8, PerfStats.presentCount("0-3,4-7"))
        assertEquals(1, PerfStats.presentCount("0"))
        assertEquals(0, PerfStats.presentCount(""))
    }

    @Test fun sayacSifirlaninca() {
        assertNull(PerfStats.percent(PerfStats.Ticks(1000, 500), PerfStats.Ticks(900, 400)))
    }
}
