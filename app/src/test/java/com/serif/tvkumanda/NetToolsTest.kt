package com.serif.tvkumanda

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ConnectException
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.nio.file.Files

class NetToolsTest {

    private fun input(
        remote: ConnCheck.Port = ConnCheck.Port.OPEN,
        pairing: ConnCheck.Port = ConnCheck.Port.OPEN,
        adb: ConnCheck.Port = ConnCheck.Port.OPEN,
        wifi: Boolean = true, vpn: Boolean = false,
        phoneIp: String? = "192.168.1.20", prefix: Int? = 24,
        latencies: List<Long> = listOf(10, 12, 15),
        paired: Boolean = true, adbMode: Boolean = false
    ) = ConnCheck.Input(
        "192.168.1.50", wifi, vpn, cellular = !wifi, phoneIp, prefix,
        ConnCheck.Probe(remote), ConnCheck.Probe(pairing), ConnCheck.Probe(adb),
        latencies, paired, adbMode
    )

    @Test
    fun allGoodIsOk() {
        val r = ConnCheck.evaluate(input())
        assertEquals(ConnCheck.Level.OK, r.level)
        assertEquals("Her şey yolunda", r.headline)
    }

    @Test
    fun noAnswerStopsAfterReachability() {
        val r = ConnCheck.evaluate(input(ConnCheck.Port.TIMEOUT, ConnCheck.Port.TIMEOUT, ConnCheck.Port.TIMEOUT, latencies = emptyList()))
        assertEquals(ConnCheck.Level.FAIL, r.level)
        assertEquals("TV cevap vermiyor", r.rows.last().title)
        assertTrue(r.rows.none { it.title.contains("ADB") })
    }

    @Test
    fun unreachableEverywhereMeansNoDevice() {
        val u = ConnCheck.Port.UNREACHABLE
        val r = ConnCheck.evaluate(input(u, u, u, latencies = emptyList()))
        assertEquals("Bu adreste cihaz bulunamadı", r.rows.last().title)
    }

    @Test
    fun closedAdbIsOnlyInfoInStandardMode() {
        val r = ConnCheck.evaluate(input(adb = ConnCheck.Port.REFUSED))
        assertEquals(ConnCheck.Level.OK, r.level)
        assertEquals(ConnCheck.Level.INFO, r.rows.first { it.title.startsWith("ADB kapalı") }.level)
    }

    @Test
    fun closedAdbFailsInAdbMode() {
        val r = ConnCheck.evaluate(input(adb = ConnCheck.Port.REFUSED, adbMode = true))
        assertEquals(ConnCheck.Level.FAIL, r.level)
    }

    @Test
    fun vpnAndOtherSubnetWarn() {
        val r = ConnCheck.evaluate(input(vpn = true, phoneIp = "10.0.0.5"))
        assertEquals(ConnCheck.Level.WARN, r.level)
        assertTrue(r.rows.any { it.title == "VPN açık" })
        assertTrue(r.rows.any { it.title == "TV başka bir ağda görünüyor" })
    }

    @Test
    fun noWifiFails() {
        val r = ConnCheck.evaluate(input(wifi = false, phoneIp = null, prefix = null))
        assertEquals(ConnCheck.Level.FAIL, r.level)
        assertTrue(r.rows.first().detail.contains("Mobil veri"))
    }

    @Test
    fun notPairedIsInfo() {
        val r = ConnCheck.evaluate(input(paired = false))
        assertEquals(ConnCheck.Level.OK, r.level)
        assertTrue(r.rows.any { it.title == "Bu telefon henüz eşleşmemiş" })
    }

    @Test
    fun slowNetworkWarns() {
        val r = ConnCheck.evaluate(input(latencies = listOf(250, 300)))
        assertEquals(ConnCheck.Level.WARN, r.level)
        assertTrue(r.rows.last().title.startsWith("Gecikme yüksek"))
    }

    @Test
    fun subnetMath() {
        assertTrue(ConnCheck.sameSubnet("192.168.1.20", "192.168.1.200", 24))
        assertFalse(ConnCheck.sameSubnet("192.168.1.20", "192.168.2.20", 24))
        assertTrue(ConnCheck.sameSubnet("192.168.1.20", "192.168.2.20", 16))
        assertTrue(ConnCheck.sameSubnet("x", "192.168.2.20", 24))
    }

    @Test
    fun ipv4Validation() {
        assertTrue(ConnCheck.isIpv4("192.168.1.50"))
        assertTrue(ConnCheck.isIpv4(" 10.0.0.1 "))
        assertFalse(ConnCheck.isIpv4("192.168.1"))
        assertFalse(ConnCheck.isIpv4("192.168.1.256"))
        assertFalse(ConnCheck.isIpv4("tv.local"))
    }

    @Test
    fun errorClassification() {
        assertEquals(ConnCheck.Port.OPEN, ConnCheck.classify(null))
        assertEquals(ConnCheck.Port.TIMEOUT, ConnCheck.classify(SocketTimeoutException("failed to connect after 2000ms")))
        assertEquals(ConnCheck.Port.REFUSED, ConnCheck.classify(ConnectException("isConnected failed: ECONNREFUSED (Connection refused)")))
        assertEquals(ConnCheck.Port.UNREACHABLE, ConnCheck.classify(ConnectException("isConnected failed: EHOSTUNREACH (No route to host)")))
    }

    @Test
    fun probeLocalPorts() {
        ServerSocket(0).use { server ->
            assertEquals(ConnCheck.Port.OPEN, ConnCheck.probe("127.0.0.1", server.localPort).state)
        }
        val closed = ServerSocket(0).use { it.localPort }
        assertEquals(ConnCheck.Port.REFUSED, ConnCheck.probe("127.0.0.1", closed).state)
    }

    @Test
    fun ipMasking() {
        assertEquals("TV 192.168.1.x bağlandı", DiagLog.maskIps("TV 192.168.1.37 bağlandı"))
        assertEquals("sürüm 0.97, 10.0.0.x:5555", DiagLog.maskIps("sürüm 0.97, 10.0.0.5:5555"))
    }

    @Test
    fun logKeepsLastLinesAndSurvivesRestart() {
        val dir = Files.createTempDirectory("teshis").toFile()
        DiagLog.init(dir)
        DiagLog.clear()
        repeat(DiagLog.KEEP + 30) { DiagLog.log("olay $it") }
        val lines = DiagLog.snapshot()
        assertEquals(DiagLog.KEEP, lines.size)
        assertTrue(lines.last().endsWith("olay ${DiagLog.KEEP + 29}"))
        // Dosyada da en fazla son kayıtlar durur
        val onDisk = java.io.File(dir, "teshis.log").readLines().filter { it.isNotBlank() }
        assertTrue(onDisk.size <= DiagLog.KEEP + 100)
        assertTrue(onDisk.last().endsWith("olay ${DiagLog.KEEP + 29}"))
    }

    @Test
    fun reportHasHeaderAndMasks() {
        val text = DiagLog.report(listOf("TV adresi" to "192.168.1.50"), listOf("01.01 10:00:00  Bağlandı 192.168.1.50"), maskIps = true)
        assertTrue(text.contains("TV adresi: 192.168.1.x"))
        assertFalse(text.contains("192.168.1.50"))
    }
}

class NetScanTest {

    @Test
    fun hostsCoverSubnetWithoutPhone() {
        val h = NetScan.hosts("192.168.1.20", 24)
        assertEquals(253, h.size)
        assertEquals("192.168.1.1", h.first())
        assertEquals("192.168.1.254", h.last())
        assertFalse("192.168.1.20" in h)
    }

    @Test
    fun bigNetworksAreLimitedToOwnSlash24() {
        val h = NetScan.hosts("10.0.5.9", 16)
        assertEquals(253, h.size)
        assertTrue(h.all { it.startsWith("10.0.5.") })
        assertEquals("10.0.5.0/24", NetScan.rangeLabel("10.0.5.9", 16))
    }

    @Test
    fun shortNames() {
        assertEquals("DESKTOP-AB12", NetScan.shortName("DESKTOP-AB12.lan", "192.168.1.5"))
        assertEquals(null, NetScan.shortName("192.168.1.5", "192.168.1.5"))
    }

    @Test
    fun combineGroupsAndNames() {
        val devices = NetScan.combine(
            hosts = listOf(
                NetScan.Host("192.168.1.50", setOf(6466, 8009)),
                NetScan.Host("192.168.1.9", setOf(80)),
                NetScan.Host("192.168.1.1", setOf(80, 443))
            ),
            selfIp = "192.168.1.20", gatewayIp = "192.168.1.1",
            tvNames = mapOf("192.168.1.50" to "Philips TV"),
            castNames = mapOf("192.168.1.60" to "Mutfak hoparlörü"),
            dlnaNames = mapOf("192.168.1.50" to "Philips DMR"),
            netNames = mapOf("192.168.1.9" to "DESKTOP-AB12")
        )
        assertEquals(listOf("192.168.1.1", "192.168.1.9", "192.168.1.20", "192.168.1.50", "192.168.1.60"), devices.map { it.ip })
        val tv = devices.first { it.ip == "192.168.1.50" }
        assertTrue(tv.isTv)
        assertEquals("Philips TV", tv.name)
        assertEquals(listOf("Android TV", "Chromecast", "DLNA"), tv.badges())
        assertFalse(devices.first { it.ip == "192.168.1.60" }.isTv)
        assertEquals(listOf("Modem"), devices.first().badges())
        assertTrue(devices.first { it.ip == "192.168.1.20" }.self)
    }

    @Test
    fun scannerFindsLocalhost() {
        val s = NetScan.Scanner(timeoutMs = 300, parallel = 8)
        val found = s.run(listOf("127.0.0.1"))
        assertEquals(listOf("127.0.0.1"), found.map { it.ip })
        assertEquals(1f, s.progress())
    }
}

class AllTvsTest {

    @Test
    fun reasonsAreReadable() {
        assertEquals("TV cevap vermiyor (kapalı ya da uykuda)", AllTvs.reason(SocketTimeoutException("x"), adb = false))
        assertEquals("ADB kapalı", AllTvs.reason(ConnectException("ECONNREFUSED (Connection refused)"), adb = true))
        assertEquals("TV bulunamadı (adresi değişmiş olabilir)", AllTvs.reason(ConnectException("EHOSTUNREACH (No route to host)"), adb = false))
        assertTrue(AllTvs.reason(IllegalStateException("ADB hazır değil"), adb = true).endsWith("ADB hazır değil"))
    }

    @Test
    fun summaryCountsSuccesses() {
        val s = AllTvs.summary(AllTvs.Command.OFF, listOf(AllTvs.Outcome(true, ""), AllTvs.Outcome(false, ""), AllTvs.Outcome(true, "")))
        assertEquals("Kapat: 2/3 TV'ye gitti", s)
    }

    @Test
    fun offIsSleepNotPowerToggle() {
        assertEquals(223, AllTvs.Command.OFF.key)
        assertEquals(224, AllTvs.Command.ON.key)
    }
}
