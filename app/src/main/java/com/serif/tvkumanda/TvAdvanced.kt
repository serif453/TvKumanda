package com.serif.tvkumanda

import android.content.Context
import java.io.IOException

/**
 * Araçlar 4. aşama: Özel DNS, Ana ekran, Gereksiz uygulamalar, ADB komut satırı, Sistem kayıtları (yalnızca ADB).
 * Tüm işlevler bloklayıcıdır; arka plan iş parçacığından çağrılmalıdır.
 */
object TvAdvanced {

    private fun adb(): TvRemote = TvTools.adb ?: throw IllegalStateException("Önce TV'ye ADB ile bağlan")
    private fun sh(cmd: String) = adb().shell(cmd)
    private fun q(s: String) = TvFiles.q(s)

    // ---------------- Özel DNS ----------------

    data class DnsOption(val label: String, val note: String, val host: String?)

    /** host == null: otomatik (modemin DNS'i, Android'in varsayılanı). */
    val dnsOptions = listOf(
        DnsOption("Otomatik", "Modemin DNS'i (fabrika ayarı)", null),
        DnsOption("AdGuard", "Reklam ve izleyicileri engeller", "dns.adguard-dns.com"),
        DnsOption("Cloudflare", "Hızlı ve gizlilik odaklı", "one.one.one.one"),
        DnsOption("Google", "Google'ın DNS'i", "dns.google")
    )

    /** Şu anki DNS: null = otomatik/kapalı, aksi halde adres. */
    fun currentDns(): String? {
        val out = sh("settings get global private_dns_mode; settings get global private_dns_specifier").lines().map { it.trim() }
        return if (out.getOrNull(0) == "hostname") out.getOrNull(1)?.takeIf { it.isNotBlank() && it != "null" } else null
    }

    private fun internetOk(): Boolean =
        sh("ping -c 1 -W 3 www.google.com >/dev/null 2>&1 && echo ok || echo yok").trim().endsWith("ok")

    /**
     * DNS'i uygular. Önceden internet varsa, sonra yoksa eski ayara kendiliğinden döner.
     * Dönüş: true = çalışıyor (ya da doğrulanamadı), false = çalışmadı ve geri alındı.
     */
    fun applyDns(host: String?, stage: (String) -> Unit): Boolean {
        val oldMode = sh("settings get global private_dns_mode").trim()
        val oldHost = sh("settings get global private_dns_specifier").trim()
        val before = runCatching { internetOk() }.getOrDefault(false)
        if (host == null) sh("settings put global private_dns_mode opportunistic")
        else sh("settings put global private_dns_specifier ${q(host)}; settings put global private_dns_mode hostname")
        if (!before) return true                                   // ölçülemiyor (ağ ICMP'yi engelliyor olabilir)
        stage("Bağlantı kontrol ediliyor…")
        for (i in 0 until 4) {                                     // ~10 sn
            Thread.sleep(2500)
            if (runCatching { internetOk() }.getOrDefault(false)) return true
        }
        // Eski ayara dön
        if (oldMode == "hostname" && oldHost.isNotBlank() && oldHost != "null")
            sh("settings put global private_dns_specifier ${q(oldHost)}; settings put global private_dns_mode hostname")
        else sh("settings put global private_dns_mode ${if (oldMode.isBlank() || oldMode == "null") "opportunistic" else oldMode}")
        return false
    }

    // ---------------- Ana ekran ----------------

    data class Launcher(val component: String, val pkg: String, val label: String)

    /** Fabrika ana ekranları (bilinenler). */
    private val stockLaunchers = listOf(
        "com.google.android.tvlauncher", "com.google.android.apps.tv.launcherx", "com.google.android.leanbacklauncher",
        "org.droidtv.home", "com.android.tv.launcher", "com.sony.dtv.launcher", "com.tcl.launcherpro"
    )

    fun launchers(labels: Map<String, String>): Pair<List<Launcher>, String?> {
        val list = sh("cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME 2>/dev/null")
            .lines().map { it.trim() }.filter { it.contains('/') && !it.contains("FallbackHome") && !it.startsWith("priority") }
            .distinct()
            .map { c -> val pkg = c.substringBefore('/'); Launcher(c.let { if (it.contains("/.")) it else it }, pkg, labels[pkg] ?: appName(pkg)) }
        val current = sh("cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME 2>/dev/null")
            .lines().map { it.trim() }.lastOrNull { it.contains('/') }
        return list to current
    }

    fun isStock(pkg: String) = pkg in stockLaunchers

    fun setHome(component: String) {
        val out = sh("cmd package set-home-activity ${q(component)} 2>&1")
        if (out.contains("Error", true) || out.contains("Exception", true)) throw IOException("Ana ekran değiştirilemedi: ${out.trim().take(100)}")
        sh("input keyevent 3")                                     // yeni ana ekran hemen görünsün
    }

    fun openLauncherStore() { sh("am start -a android.intent.action.VIEW -d 'market://search?q=android%20tv%20launcher' 2>&1") }

    // ---------------- Gereksiz uygulamalar ----------------

    data class Bloat(val pkg: String, val label: String, val note: String, val recommended: Boolean)

    /** Kapatılması güvenli bilinen uygulamalar (yalnızca TV'de kurulu olanlar gösterilir). */
    private val bloatList = listOf(
        Bloat("com.google.android.videos", "Google TV / Play Filmler", "Film kiralama ve satın alma mağazası", true),
        Bloat("com.google.android.play.games", "Google Play Oyunlar", "Oyun profili ve başarılar; oyunlar onsuz da çalışır", true),
        Bloat("com.google.android.music", "Google Play Müzik", "Kapatılmış eski müzik servisi", true),
        Bloat("com.google.android.feedback", "Google Geri Bildirim", "Google'a hata bildirme aracı", true),
        Bloat("com.google.android.tvrecommendations", "TV Önerileri", "Ana ekrandaki önerileri ve reklamları getirir", true),
        Bloat("com.google.android.leanbacklauncher.recommendations", "Ana ekran önerileri", "Ana ekrandaki öneri satırları", true),
        Bloat("com.google.android.apps.mediashell", "Chromecast yerleşik", "Telefondan TV'ye yayın (bizim Cast sekmesi de bunu kullanır!)", false),
        Bloat("com.google.android.youtube.tvmusic", "YouTube Music", "Müzik uygulaması; kullanmıyorsan kapatılabilir", false),
        Bloat("com.google.android.marvin.talkback", "TalkBack", "Görme engelliler için ekran okuyucu", false),
        Bloat("com.amazon.amazonvideo.livingroom", "Prime Video", "Kullanmıyorsan kapatılabilir", false),
        Bloat("com.netflix.ninja", "Netflix", "Kullanmıyorsan kapatılabilir", false)
    )

    /** Asla kapatılmayacaklar (TV kullanılamaz hale gelebilir). */
    private val protectedPrefixes = listOf(
        "android", "com.android.", "com.google.android.gms", "com.google.android.gsf", "com.android.vending",
        "com.google.android.tvlauncher", "com.google.android.apps.tv.launcherx", "com.google.android.leanbacklauncher",
        "com.google.android.katniss", "com.google.android.tv.remote.service", "com.google.android.inputmethod",
        "com.google.android.leanback.ime", "com.google.android.tvsetup", "org.droidtv.", "com.mediatek.", "com.tpv."
    )

    fun isProtected(pkg: String) = protectedPrefixes.any { pkg == it || pkg.startsWith(it) } && bloatList.none { it.pkg == pkg }

    data class BloatState(val items: List<Pair<Bloat, Boolean /* devre dışı mı */>>)

    fun bloatState(): BloatState {
        val installed = sh("pm list packages -u 2>/dev/null; echo '##'; pm list packages -d 2>/dev/null")
        val all = installed.substringBefore("##").lines().map { it.removePrefix("package:").trim() }.toSet()
        val disabled = installed.substringAfter("##").lines().map { it.removePrefix("package:").trim() }.toSet()
        return BloatState(bloatList.filter { it.pkg in all }.map { it to (it.pkg in disabled) })
    }

    fun setDisabled(context: Context, pkg: String, off: Boolean) {
        if (off && isProtected(pkg)) throw IOException("Bu uygulama güvenlik için kapatılamaz")
        val out = if (off) sh("pm disable-user --user 0 $pkg 2>&1") else sh("pm enable $pkg 2>&1")
        if (out.contains("Exception", true) || out.contains("Error", true)) throw IOException(out.trim().take(120))
        DisabledLog.update(context, pkg, off)
    }

    /** Bu uygulamayla kapatılanların kaydı (Hepsini geri aç için). */
    object DisabledLog {
        private fun prefs(c: Context) = c.getSharedPreferences("ayarlar", Context.MODE_PRIVATE)
        private fun key() = "kapatilanlar_" + (TvTools.adb?.tvIp ?: "")
        fun get(c: Context): Set<String> = prefs(c).getStringSet(key(), emptySet()) ?: emptySet()
        fun update(c: Context, pkg: String, off: Boolean) {
            val s = get(c).toMutableSet(); if (off) s += pkg else s -= pkg
            prefs(c).edit().putStringSet(key(), s).apply()
        }
    }

    data class RamApp(val pkg: String, val kb: Long)

    /** Arka planda en çok bellek kullanan uygulamalar (korunanlar hariç). */
    fun topRam(): List<RamApp> {
        val out = sh("dumpsys meminfo 2>/dev/null | sed -n '/Total PSS by process/,/Total PSS by OOM/p'")
        return Regex("""([\d,]+)K: ([\w.]+) \(pid""").findAll(out).mapNotNull { m ->
            val kb = m.groupValues[1].replace(",", "").toLongOrNull() ?: return@mapNotNull null
            val pkg = m.groupValues[2].substringBefore(':')
            if (isProtected(pkg) || !pkg.contains('.')) null else RamApp(pkg, kb)
        }.distinctBy { it.pkg }.take(8).toList()
    }

    // ---------------- ADB komut satırı ----------------

    /** Komutu en fazla 30 saniye çalıştırır. */
    fun run(cmd: String): String {
        val out = sh("timeout 30 sh -c ${q(cmd)} 2>&1; echo \"##KOD=$?\"")
        val code = Regex("""##KOD=(\d+)""").find(out)?.groupValues?.get(1)?.toIntOrNull()
        val body = out.substringBeforeLast("##KOD=").trimEnd()
        return when (code) {
            124 -> body + "\n(30 saniye doldu, komut durduruldu)"
            null, 0 -> body
            else -> body + "\n(çıkış kodu $code)"
        }
    }

    // ---------------- Sistem kayıtları ----------------

    data class LogLine(val level: Char, val text: String)

    fun logs(level: Char, pkg: String?): List<LogLine> {
        val pidPart = if (pkg != null) {
            val pid = sh("pidof ${q(pkg)} 2>/dev/null").trim().substringBefore(' ')
            if (pid.isBlank()) throw IOException("Bu uygulama şu an çalışmıyor")
            " --pid=$pid"
        } else ""
        val filter = when (level) { 'E' -> "*:E"; 'W' -> "*:W"; else -> "*:V" }
        val out = sh("logcat -d -v brief -t 1500$pidPart $filter 2>&1")
        return out.lines().filter { it.isNotBlank() && !it.startsWith("---------") }.map { l ->
            LogLine(l.firstOrNull()?.takeIf { it in "VDIWEF" && l.getOrNull(1) == '/' } ?: 'I', l)
        }
    }
}
