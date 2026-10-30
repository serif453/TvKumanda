package com.serif.tvkumanda

/**
 * Telefon tarafından TV'deki nöbetçiyi (`Watcher.java`) yönetir: ayarları yazar, durumunu okur,
 * gerekirse başlatır. Tüm işlevler bloklayıcıdır ve ADB bağlantısı ister.
 */
object TvWatcher {
    private const val DIR = "/data/local/tmp/"
    private val CONF = DIR + Watcher.CONF
    private val STATE = DIR + Watcher.STATE
    private const val MAIN_CLASS = "com.serif.tvkumanda.Watcher"

    data class Status(val config: WatcherConfig, val state: WatcherState?, val running: Boolean)

    private fun adb(): TvRemote = TvTools.adb ?: throw IllegalStateException("Önce TV'ye ADB ile bağlan")

    fun read(): Status {
        val out = adb().shell(
            "cat $CONF 2>/dev/null; echo '#--#'; cat $STATE 2>/dev/null; echo '#--#'; " +
                "p=\$(sed -n 's/^pid=//p' $STATE 2>/dev/null); [ -n \"\$p\" ] && [ -d /proc/\$p ] && echo alive"
        )
        val parts = out.split("#--#")
        val config = WatcherConfig.parse(parts.getOrElse(0) { "" })
        val state = WatcherState.parse(parts.getOrElse(1) { "" })
        val running = parts.getOrElse(2) { "" }.contains("alive") && state?.running == true
        return Status(config, state, running)
    }

    /** Ayarları değiştirir; yapılacak iş varsa nöbetçinin çalıştığından emin olur. */
    fun update(change: (WatcherConfig) -> WatcherConfig): Status {
        val current = read()
        val next = change(current.config)
        // Ayar dosyasında yalnızca rakam, harf, '=', '_' ve satır sonu var; printf biçiminde güvenle yazılır
        adb().shell("printf '${next.encode().replace("\n", "\\n")}' > $CONF")
        val now = System.currentTimeMillis()
        if (next.hasWork(now, current.state?.sleepDone ?: 0) && needsStart(current)) start(current)
        DiagLog.log("Nöbetçi ayarları: ${describe(next)}")
        return read()
    }

    /** ADB bağlantısı kurulunca çağrılır: iş varsa ve nöbetçi durmuşsa (ör. TV yeniden başladıysa) başlatır. */
    fun ensureRunning() {
        val s = read()
        if (s.config.hasWork(System.currentTimeMillis(), s.state?.sleepDone ?: 0) && needsStart(s)) {
            start(s)
            DiagLog.log("Nöbetçi yeniden başlatıldı")
        }
    }

    /** Çalışmıyorsa ya da uygulamanın eski sürümüyle çalışıyorsa yeniden başlatılır. */
    private fun needsStart(s: Status) = !s.running || s.state?.apk != adb().serverApkPath

    private fun start(s: Status) {
        if (s.running) s.state?.pid?.let { runCatching { adb().shell("kill $it") } }
        adb().startTvProgram(MAIN_CLASS)
        // Nöbetçinin ilk durumunu yazması beklenir
        repeat(6) {
            Thread.sleep(600)
            if (read().running) return
        }
        throw IllegalStateException("Nöbetçi TV'de başlatılamadı")
    }

    private fun describe(c: WatcherConfig): String = buildList {
        if (c.sleepAt > System.currentTimeMillis()) add("uyku ${TimerText.clockOf(c.sleepAt)}${if (c.fade) " (ses kısılarak)" else ""}")
        if (c.volMax >= 0) add("ses en fazla ${c.volMax}")
        if (c.limitMin > 0) add("günlük ${TimerText.duration(c.limitMin * 60L)}")
        if (c.nightFrom >= 0) add("gece ${TimerText.clock(c.nightFrom)}–${TimerText.clock(c.nightTo)}")
    }.joinToString(", ").ifEmpty { "hepsi kapalı" }
}
