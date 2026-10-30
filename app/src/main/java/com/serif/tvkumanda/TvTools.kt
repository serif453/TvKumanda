package com.serif.tvkumanda

import java.io.File

/**
 * Araçlar sekmesinin TV tarafı: ADB kabuk komutlarıyla çalışan işlevler.
 * Tüm işlevler bloklayıcıdır; arka plan iş parçacığından çağrılmalıdır.
 */
object TvTools {
    /** MainActivity bağlanınca atanır. */
    var adb: TvRemote? = null
    /** Yeniden başlatma komutu gönderildikten sonra çağrılır (yeniden bağlanmayı başlatır). */
    var onRebooting: () -> Unit = {}

    /** Her iki bağlantıda çalışan araçlar için köprüler (MainActivity atar). */
    var isAdb: () -> Boolean = { false }
    var sendKey: (Int) -> Unit = {}
    var sendText: (String) -> Unit = {}
    var atvLink: (String) -> Unit = {}
    /** Kumanda sekmesindeki uygulama simgeleri (paket → simge). */
    var appIcons: () -> Map<String, androidx.compose.ui.graphics.ImageBitmap?> = { emptyMap() }

    /** Ağ ve test araçları için köprüler (MainActivity atar). */
    var currentIp: () -> String = { "" }
    var savedTvs: () -> List<SavedTv> = { emptyList() }
    var isPaired: (String) -> Boolean = { false }
    /** Bağlı TV'nin adresi; bağlı değilse boş. */
    var connectedIp: () -> String = { "" }
    /** Ağ tarayıcıdan seçilen TV'ye, seçilen yöntemle bağlanır. */
    var connectTo: (String, ConnMethod) -> Unit = { _, _ -> }
    /** Teşhis kaydının başındaki bağlantı bilgisi. */
    var diagInfo: () -> List<Pair<String, String>> = { emptyList() }

    private fun sh(cmd: String): String = (adb ?: throw IllegalStateException("Önce TV'ye ADB ile bağlan")).shell(cmd)

    // ---------------- Sistem bilgisi ----------------

    data class MemInfo(val totalKb: Long, val availKb: Long) {
        val usedKb get() = (totalKb - availKb).coerceAtLeast(0)
    }

    data class DiskInfo(val totalKb: Long, val freeKb: Long) {
        val usedKb get() = (totalKb - freeKb).coerceAtLeast(0)
    }

    fun memInfo(): MemInfo? = parseMem(sh("cat /proc/meminfo"))

    private fun parseMem(text: String): MemInfo? {
        fun kb(key: String) = Regex("""(?m)^$key:\s+(\d+)""").find(text)?.groupValues?.get(1)?.toLongOrNull()
        val total = kb("MemTotal") ?: return null
        val avail = kb("MemAvailable") ?: kb("MemFree") ?: return null
        return MemInfo(total, avail)
    }

    fun diskInfo(): DiskInfo? = parseDisk(sh("df -k /data"))

    private fun parseDisk(text: String): DiskInfo? {
        // Filesystem 1K-blocks Used Available Use% Mounted on
        val line = text.lines().lastOrNull { it.trim().endsWith("/data") } ?: return null
        val nums = line.trim().split(Regex("\\s+")).mapNotNull { it.toLongOrNull() }
        if (nums.size < 3) return null
        return DiskInfo(nums[0], nums[2])
    }

    /** Sistem bilgisi aracı: tek komut, çıktının yorumu `SysInfo`'da. */
    fun systemInfo(): SysInfo.Report = SysInfo.build(sh(SysInfo.COMMAND))

    // ---------------- RAM ----------------

    /** Arka plandaki uygulamaları kapatır; boşalan miktarı (KB) döndürür. */
    fun freeRam(): Pair<MemInfo?, Long> {
        val before = memInfo()
        sh("am kill-all")
        Thread.sleep(1500)
        val after = memInfo()
        val freed = if (before != null && after != null) (after.availKb - before.availKb).coerceAtLeast(0) else 0
        return after to freed
    }

    // ---------------- Önbellek ----------------

    /** Uygulamaların toplam önbelleği (bayt, sistemin son ölçümü; bilinmiyorsa null). */
    fun cacheSize(): Long? =
        Regex("""App Cache Size:\s*(\d+)""").find(sh("dumpsys diskstats"))?.groupValues?.get(1)?.toLongOrNull()

    /** Tüm uygulamaların önbelleğini siler; boşalan alanı (KB) döndürür. */
    fun clearCache(): Long {
        val before = diskInfo()
        sh("pm trim-caches 999G")
        Thread.sleep(1000)
        val after = diskInfo()
        return if (before != null && after != null) (after.freeKb - before.freeKb).coerceAtLeast(0) else 0
    }

    // ---------------- Yeniden başlat ----------------

    fun reboot() {
        // Komut gönderildikten sonra bağlantı kopar; hata beklenir
        runCatching { sh("reboot") }
        onRebooting()
    }

    // ---------------- Ekran görüntüsü ----------------

    /** TV ekranının görüntüsünü [dst] dosyasına kaydeder. */
    fun screenshot(dst: File) {
        val d = adb ?: throw IllegalStateException("Önce TV'ye ADB ile bağlan")
        val remote = "/data/local/tmp/tvk_shot.png"
        sh("screencap -p $remote")
        try {
            d.pull(remote, dst)
        } finally {
            runCatching { sh("rm -f $remote") }
        }
        if (!dst.exists() || dst.length() == 0L) throw java.io.IOException("Ekran görüntüsü alınamadı")
    }

    // ---------------- Animasyon hızı ----------------

    private val animKeys = listOf("window_animation_scale", "transition_animation_scale", "animator_duration_scale")

    /** Şu anki animasyon hızı (ayarlanmamışsa 1). */
    fun animationScale(): Float =
        sh("settings get global animator_duration_scale").trim().toFloatOrNull() ?: 1f

    fun setAnimationScale(scale: Float) {
        val v = if (scale == 0f) "0" else scale.toString()
        sh(animKeys.joinToString("; ") { "settings put global $it $v" })
    }

    // ---------------- Tuşlar, metin, bağlantı (her iki bağlantıda) ----------------

    /**
     * Sistem tuşu. ADB'de "input keyevent" yoluyla gider (Philips bazı sistem tuşlarını
     * yalnızca bu yoldan güvenilir biçimde kabul ediyor), Standart'ta kumanda protokolüyle.
     */
    fun systemKey(code: Int) {
        if (isAdb()) sh("input keyevent $code") else sendKey(code)
    }

    private fun started(out: String) = !out.contains("Error", ignoreCase = true) && !out.contains("unable to resolve", ignoreCase = true)

    /** TV'nin Ayarlar ekranı: ADB'de doğrudan açılır, Standart'ta Ayarlar tuşu gönderilir. */
    fun openSettings() {
        if (!isAdb()) { sendKey(176); return }
        if (!started(sh("am start -a android.settings.SETTINGS"))) sh("input keyevent 176")
    }

    /** Bildirim paneli: ADB'de Android TV'nin panel komutu, olmazsa bildirim tuşu. */
    fun openNotifications() {
        if (!isAdb()) { sendKey(83); return }
        if (!started(sh("am start -a com.android.tv.action.OPEN_NOTIFICATIONS_PANEL"))) sh("input keyevent 83")
    }

    /** Ekran koruyucuyu başlatır (yalnızca ADB). */
    fun screensaver() {
        val out = sh("am start -n com.android.systemui/.Somnambulator")
        if (out.contains("Error", ignoreCase = true)) throw java.io.IOException("Bu TV ekran koruyucuyu bu yolla başlatmıyor")
    }

    /** Linki TV'de açar: ADB'de doğrudan, Standart'ta kumanda protokolünün "uygulama bağlantısı" isteğiyle. */
    fun openLink(url: String) {
        if (isAdb()) {
            val q = "'" + url.replace("'", "'\\''") + "'"
            val out = sh("am start -a android.intent.action.VIEW -d $q")
            if (out.contains("Error", ignoreCase = true) || out.contains("unable to resolve", ignoreCase = true))
                throw java.io.IOException("TV'de bu linki açacak uygulama bulunamadı")
        } else atvLink(url)
    }

    /**
     * Telefonun canlı yayın adresini TV'deki oynatıcıda açar. ADB'de türü ([mime]) belirterek, Standart'ta
     * kumandanın "bağlantı aç" isteğiyle; hangi uygulamanın açacağını TV seçer.
     */
    fun openStream(url: String, mime: String) {
        if (!isAdb()) { atvLink(url); return }
        fun q(s: String) = "'" + s.replace("'", "'\\''") + "'"
        val args = "-a android.intent.action.VIEW -d ${q(url)} -t ${q(mime)}"
        val resolved = sh("cmd package resolve-activity --brief $args 2>&1")
        if (resolved.contains("frameworkpackagestubs") || resolved.contains("No activity found", true))
            throw java.io.IOException("TV'de bu yayını açacak bir oynatıcı yok")
        val out = sh("am start $args 2>&1")
        if (out.contains("Error", true)) throw java.io.IOException("TV'de bu yayını açacak bir oynatıcı yok")
    }

    /** Metindeki ilk web adresini bulur; şema yoksa https eklenir. */
    fun extractUrl(text: String): String? {
        Regex("""https?://\S+""").find(text)?.let { return it.value.trimEnd('.', ',', ')', '"', '\'') }
        val bare = Regex("""(?i)\b(?:[a-z0-9-]+\.)+[a-z]{2,}(?:/\S*)?""").find(text.trim())?.value ?: return null
        return if (bare.contains('.') && !bare.contains('@')) "https://$bare" else null
    }

    // ---------------- Biçimlendirme ----------------

    fun mb(kb: Long): String = if (kb >= 1024 * 1024) "%.1f GB".format(kb / 1048576.0) else "${kb / 1024} MB"
    fun gb(kb: Long): String = "%.1f GB".format(kb / 1048576.0)
    fun bytes(b: Long): String = when {
        b < 1024 -> "$b B"
        b < 1024 * 1024 -> "${b / 1024} KB"
        b < 1024L * 1024 * 1024 -> "%.1f MB".format(b / 1048576.0)
        else -> "%.1f GB".format(b / 1073741824.0)
    }
}
