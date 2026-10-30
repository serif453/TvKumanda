package com.serif.tvkumanda

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Sistem bilgisi: Serif'in Philips TV'sinden alınan değerlere benzer örnek çıktıyla. */
class SysInfoTest {

    private val cpuBlock = { n: Int ->
        """
        processor	: $n
        model name	: ARMv8 Processor rev 0 (v8l)
        BogoMIPS	: 48.00
        Features	: half thumb fastmult vfp edsp neon vfpv3 tls vfpv4 idiva idivt lpae evtstrm aes pmull sha1 sha2 crc32
        CPU implementer	: 0x41
        CPU architecture: 8
        CPU variant	: 0x1
        CPU part	: 0xd09
        CPU revision	: 0
        """.trimIndent()
    }

    private val sample = buildString {
        appendLine("##P")
        appendLine("[ro.product.brand]: [Philips]")
        appendLine("[ro.product.manufacturer]: [TPV]")
        appendLine("[ro.product.model]: [2021/22 Philips UHD Android TV]")
        appendLine("[ro.product.board]: [TP1M_EA_9970A]")
        appendLine("[ro.product.device]: [PH1M_EA_9970A]")
        appendLine("[ro.build.version.release]: [11]")
        appendLine("[ro.build.version.sdk]: [30]")
        appendLine("[ro.build.version.security_patch]: [2024-05-01]")
        appendLine("[ro.build.version.incremental]: [21029]")
        appendLine("[ro.build.display.id]: [TPM211E_R.103.001.211.029]")
        appendLine("[ro.product.cpu.abilist]: [armeabi-v7a,armeabi]")
        appendLine("[ro.serialno]: [ABC123SECRET]")
        appendLine("[persist.vendor.wifimac]: [aa:bb:cc:dd:ee:ff]")
        appendLine("##C")
        for (i in 0..3) { appendLine(cpuBlock(i)); appendLine() }
        appendLine("Hardware	: MT5895")
        appendLine("##F")
        for (i in 0..3) appendLine("cpu$i 1100000 1500000")
        appendLine("##T")
        appendLine("mtktscpu 45000")
        appendLine("mtktspmic 39000")
        appendLine("##M")
        appendLine("MemTotal:        2798440 kB")
        appendLine("MemFree:           60184 kB")
        appendLine("MemAvailable:     700028 kB")
        appendLine("SwapTotal:        409596 kB")
        appendLine("SwapFree:         289020 kB")
        appendLine("##D")
        appendLine("Filesystem     1K-blocks    Used Available Use% Mounted on")
        appendLine("/dev/block/dm-5  10000000 6000000   4000000  60% /data")
        appendLine("##E")
        appendLine("/dev/fuse 30000000 10000000 20000000 34% /storage/1A2B-3C4D")
        appendLine("##W")
        appendLine("Physical size: 1920x1080")
        appendLine("Physical density: 320")
        appendLine("##Y")
        appendLine("  mBaseDisplayInfo=DisplayInfo{\"Built-in Screen\", displayId 0, real 3840 x 2160, modeId 1, defaultModeId 1, " +
            "supportedModes [{id=1, width=3840, height=2160, fps=60.0}, {id=2, width=3840, height=2160, fps=50.0}], " +
            "hdrCapabilities HdrCapabilities{mSupportedHdrTypes=[2, 3, 1], mMaxLuminance=500.0}}")
        appendLine("##N")
        appendLine("1: lo    inet 127.0.0.1/8 scope host lo")
        appendLine("5: wlan0    inet 192.168.1.37/24 brd 192.168.1.255 scope global wlan0")
        appendLine("##L")
        appendLine("wlan0 aa:bb:cc:dd:ee:ff up")
        appendLine("eth0  down")
        appendLine("##S")
        appendLine("mWifiInfo SSID: \"EvAgi\", BSSID: 11:22:33:44:55:66, MAC: aa:bb:cc:dd:ee:ff, RSSI: -58, Link speed: 433Mbps, Frequency: 5180MHz")
        appendLine("##K")
        appendLine("4.19.157")
        appendLine("##U")
        appendLine("93784.21 300000.00")
        appendLine("##A")
        appendLine("212")
        appendLine("14")
        appendLine("package:com.google.android.tvlauncher")
    }

    private fun row(r: SysInfo.Report, section: String, key: String): String? =
        r.sections.firstOrNull { it.title == section }?.rows?.firstOrNull { it.first == key }?.second

    @Test fun deviceSection() {
        val r = SysInfo.build(sample)
        assertEquals("Philips", row(r, "Cihaz", "Marka"))
        assertEquals("TPV", row(r, "Cihaz", "Üretici"))
        assertEquals("2021/22 Philips UHD Android TV", row(r, "Cihaz", "Model"))
        assertEquals("TP1M_EA_9970A · PH1M_EA_9970A", row(r, "Cihaz", "Kart / cihaz kodu"))
        assertEquals("Android TV", row(r, "Cihaz", "Sistem"))
        assertEquals("Android 11 (API 30)", row(r, "Cihaz", "Android sürümü"))
        assertEquals("2024-05-01", row(r, "Cihaz", "Güvenlik yaması"))
        assertEquals("TPM211E_R.103.001.211.029 (21029)", row(r, "Cihaz", "Yazılım sürümü"))
    }

    @Test fun cpuSectionNamesCoresAnd32BitApps() {
        val r = SysInfo.build(sample)
        assertEquals("MediaTek MT5895", row(r, "İşlemci", "Çip"))
        assertEquals("4 × Cortex-A73", row(r, "İşlemci", "Çekirdekler"))
        assertEquals("ARMv8 (64 bit işlemci)", row(r, "İşlemci", "Mimari"))
        assertTrue(row(r, "İşlemci", "Uygulama türü")!!.startsWith("Yalnızca 32 bit"))
        assertTrue(row(r, "İşlemci", "Hız")!!.contains("GHz"))
        assertEquals("45 °C", row(r, "İşlemci", "Sıcaklık"))
    }

    @Test fun memoryAndStorage() {
        val r = SysInfo.build(sample)
        assertEquals("2.7 GB".replace('.', decimalSep()), row(r, "Bellek", "Toplam RAM"))
        assertEquals("683 MB", row(r, "Bellek", "Kullanılabilir"))
        assertTrue(row(r, "Bellek", "Takas alanı")!!.contains("kullanılıyor"))
        val ram = r.bars.first { it.label == "RAM" }
        assertEquals(2798440L - 700028L, ram.usedKb)
        assertTrue(row(r, "Depolama", "Dahili")!!.contains("boş"))
        assertTrue(row(r, "Depolama", "USB bellek") != null)
        assertEquals(6000000L, r.bars.first { it.label == "Depolama" }.usedKb)
    }

    private fun decimalSep() = "%.1f".format(1.5)[1]

    @Test fun displaySection() {
        val r = SysInfo.build(sample)
        assertEquals("1920 × 1080 (Full HD)", row(r, "Ekran", "Arayüz çözünürlüğü"))
        assertEquals("3840 × 2160 (4K) · 60 Hz", row(r, "Ekran", "Görüntü modu"))
        assertEquals("50, 60 Hz", row(r, "Ekran", "Yenileme hızları"))
        assertEquals("320 dpi", row(r, "Ekran", "Yoğunluk"))
        assertEquals("HDR10, HLG, Dolby Vision", row(r, "Ekran", "HDR"))
    }

    @Test fun networkSection() {
        val r = SysInfo.build(sample)
        assertEquals("Wi-Fi: EvAgi", row(r, "Ağ", "Bağlantı"))
        assertEquals("192.168.1.37", row(r, "Ağ", "IP adresi"))
        assertEquals("aa:bb:cc:dd:ee:ff", row(r, "Ağ", "MAC adresi"))
        assertEquals("-58 dBm (iyi)", row(r, "Ağ", "Sinyal"))
        assertEquals("5 GHz (5180 MHz)", row(r, "Ağ", "Bant"))
        assertEquals("433 Mbps", row(r, "Ağ", "Bağlantı hızı"))
    }

    @Test fun systemSection() {
        val r = SysInfo.build(sample)
        assertEquals("1 gün 2 saat", row(r, "Sistem", "Açık kalma süresi"))
        assertEquals("212 (14 tanesini sen kurdun)", row(r, "Sistem", "Uygulamalar"))
        assertEquals("4.19.157", row(r, "Sistem", "Çekirdek (kernel)"))
    }

    @Test fun rawHidesSecretsButKeepsTheRest() {
        val raw = SysInfo.build(sample).raw
        assertFalse(raw.contains("ABC123SECRET"))
        assertFalse(raw.contains("aa:bb:cc:dd:ee:ff"))
        assertTrue(raw.contains("[ro.serialno]: [gizlendi]"))
        assertTrue(raw.contains("CPU part\t: 0xd09"))
        assertTrue(raw.contains("MemTotal:"))
        assertTrue(raw.contains("[ro.product.model]: [2021/22 Philips UHD Android TV]"))
    }

    @Test fun shareTextMasksIpAndMacButNotVersions() {
        val t = SysInfo.shareText(SysInfo.build(sample))
        assertFalse(t.contains("192.168.1.37"))
        assertTrue(t.contains("192.168.1.x"))
        assertFalse(t.contains("aa:bb:cc:dd:ee:ff"))
        assertTrue(t.contains("aa:bb:cc:xx:xx:xx"))
        assertTrue("Yazılım sürümü bozulmamalı", t.contains("TPM211E_R.103.001.211.029"))
        assertTrue(t.contains("Marka: Philips"))
    }

    @Test fun emptyOutputGivesNoCrashAndNoEmptySections() {
        val r = SysInfo.build("")
        assertTrue(r.sections.all { it.rows.isNotEmpty() })
        assertTrue(r.bars.isEmpty())
    }

    @Test fun helpers() {
        assertEquals("Qualcomm SM8250", SysInfo.chipName("sm8250"))
        assertEquals("Amlogic S905X4", SysInfo.chipName("s905x4"))
        assertEquals("Mediatek MT8695", SysInfo.chipName("Mediatek MT8695"))
        assertNull(SysInfo.hdrTypes("bir şey yok"))
        assertEquals(emptyList<String>(), SysInfo.hdrTypes("mSupportedHdrTypes=[]"))
        assertEquals("2.4 GHz", SysInfo.band(2437))
        assertEquals("6 GHz", SysInfo.band(5955))
        assertEquals("zayıf", SysInfo.rssiQuality(-80))
        assertNull(SysInfo.temperature("x abc"))
        assertEquals(52, SysInfo.temperature("battery 30\nsoc_thermal 52000"))
        // Karışık çekirdekli işlemci
        val mixed = (0..3).joinToString("\n\n") { "processor\t: $it\nCPU implementer\t: 0x41\nCPU architecture: 8\nCPU part\t: 0xd05" } +
            "\n\n" + (4..5).joinToString("\n\n") { "processor\t: $it\nCPU implementer\t: 0x41\nCPU part\t: 0xd09" }
        val c = SysInfo.cpu(mixed)
        assertEquals(6, c.count)
        assertEquals("4 × Cortex-A55 + 2 × Cortex-A73", c.cores)
    }
}
