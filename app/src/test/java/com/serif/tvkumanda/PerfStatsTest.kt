package com.serif.tvkumanda

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    // ---------------- Çalışan uygulamalar ----------------

    private fun procs(total: Long, yt: Long, ytSvc: Long, launcher: Long) = """
        ##S
        cpu  $total 0 0 0 0 0 0 0 0 0
        ##P
        PID    RSS NAME
        1      2000 /init
        500  300000 com.google.android.youtube.tv
        501   50000 com.google.android.youtube.tv:service
        600  200000 com.google.android.apps.tv.launcherx
        700    4000 surfaceflinger
        ##T
        1 (init) S 0 0 0 0 -1 0 0 0 0 0 5 5 0 0
        500 (outube.tv) S 1 0 0 0 -1 0 0 0 0 0 $yt 0 0 0
        501 (e.tv:service) S 1 0 0 0 -1 0 0 0 0 0 $ytSvc 0 0 0
        600 (Launcher (x) y) S 1 0 0 0 -1 0 0 0 0 0 ${launcher / 2} ${launcher / 2} 0 0
        700 (surfaceflinger) S 1 0 0 0 -1 0 0 0 0 0 900 0 0 0
    """.trimIndent()

    @Test fun paketAdi() {
        assertEquals("com.x.y", PerfStats.pkgOf("com.x.y:remote"))
        assertNull(PerfStats.pkgOf("surfaceflinger"))
        assertNull(PerfStats.pkgOf("/system/bin/sh"))
        assertNull(PerfStats.pkgOf("app_process"))
    }

    @Test fun uygulamaBellekVeIslemci() {
        val a = PerfStats.parseProcs(procs(1000, 100, 10, 40))
        val b = PerfStats.parseProcs(procs(2000, 250, 60, 140))
        val first = PerfStats.apps(null, a, null).associateBy { it.pkg }
        assertEquals(2, first.size) // init ve surfaceflinger uygulama değil
        assertNull(first["com.google.android.youtube.tv"]!!.cpu)
        assertEquals(350000L, first["com.google.android.youtube.tv"]!!.kb) // iki sürecin RSS toplamı
        val v = PerfStats.apps(a, b, null).associateBy { it.pkg }
        assertEquals(20.0, v["com.google.android.youtube.tv"]!!.cpu!!, 0.001) // (150 + 50) / 1000
        assertEquals(10.0, v["com.google.android.apps.tv.launcherx"]!!.cpu!!, 0.001) // parantezli ad
    }

    @Test fun pssVarsaOKullanilir() {
        val pss = PerfStats.parsePss("""
            Total PSS by process:
                212,345K: com.google.android.youtube.tv (pid 500 / activities)
                 30,000K: com.google.android.youtube.tv:service (pid 501)
                 10,000K: surfaceflinger (pid 700)
            Total PSS by OOM adjustment:
        """.trimIndent())
        assertEquals(242345L, pss["com.google.android.youtube.tv"])
        assertNull(pss["surfaceflinger"])
        val a = PerfStats.parseProcs(procs(1000, 100, 10, 40))
        val v = PerfStats.apps(null, a, pss).associateBy { it.pkg }
        assertEquals(242345L, v["com.google.android.youtube.tv"]!!.kb)
        assertEquals(200000L, v["com.google.android.apps.tv.launcherx"]!!.kb) // PSS'de yoksa RSS
    }

    @Test fun tekPsIleSure() {
        val a = PerfStats.parseProcs("##S\ncpu  1000 0 0 0 0 0 0 0\n##P\nPID    RSS     TIME+ NAME\n500  300000  0:01.00 com.google.android.youtube.tv\n700 4000 1:00.00 surfaceflinger\n")
        val b = PerfStats.parseProcs("##S\ncpu  2000 0 0 0 0 0 0 0\n##P\nPID    RSS     TIME+ NAME\n500  300000  0:03.50 com.google.android.youtube.tv\n700 4000 1:00.00 surfaceflinger\n")
        assertEquals(100L, a.ticks[500])
        assertEquals("com.google.android.youtube.tv", a.names[500])
        val v = PerfStats.apps(a, b, null).single()
        assertEquals(25.0, v.cpu!!, 0.001) // 250 / 1000
    }

    @Test fun sureBicimleri() {
        assertEquals(234L, PerfStats.timePlus("0:02.34"))
        assertEquals((3600 + 62) * 100L + 5, PerfStats.timePlus("1:01:02.05"))
        assertEquals((86400 + 3723) * 100L + 45, PerfStats.timePlus("1-01:02:03.45"))
        assertNull(PerfStats.timePlus("abc"))
    }

    @Test fun ekrandakiUygulama() {
        assertEquals("com.google.android.youtube.tv", PerfStats.parseForeground("  mResumedActivity: ActivityRecord{1a2b3c u0 com.google.android.youtube.tv/com.google.android.apps.youtube.tv.activity.ShellActivity t42}"))
        assertNull(PerfStats.parseForeground(""))
    }

    @Test fun sicaklik() {
        val out = out("1 0 0 1 0 0 0 0", listOf("1 0 0 1")) + "##H\nbattery\tcpu-thermal\tgpu\t71000\t52000\t48000\n"
        assertEquals(52, PerfStats.view(null, PerfStats.parse(out)).tempC)
        assertNull(PerfStats.temperature(null))
    }

    @Test fun anaEkranUygulamalari() {
        val brief = "com.google.android.youtube.tv/com.google.android.apps.youtube.tv.activity.ShellActivity\n" +
            "com.netflix.ninja/.MainActivity\n2 activities found"
        assertEquals(setOf("com.google.android.youtube.tv", "com.netflix.ninja"), PerfStats.parseLaunchable(brief))
        assertEquals(setOf("org.videolan.vlc"), PerfStats.parseLaunchable("      packageName=org.videolan.vlc"))
    }

    @Test fun kullaniciMiSistemMi() {
        val l = setOf("com.google.android.youtube.tv")
        assertTrue(PerfStats.isUserApp("com.google.android.youtube.tv", l, false, false)) // fabrikadan ama simgeli
        assertTrue(PerfStats.isUserApp("org.example.servis", l, true, false))            // sonradan kurulan
        assertFalse(PerfStats.isUserApp("com.google.android.gms", l, false, true))       // arka plan hizmeti
        assertFalse(PerfStats.isUserApp("com.android.systemui", null, null, true))       // bilgi gelmeden: korunan
        assertTrue(PerfStats.isUserApp("org.example.x", null, null, false))
    }
}
