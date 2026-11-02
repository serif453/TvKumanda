package com.serif.tvkumanda

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Teşhis kaydı: bağlanma, kopma, eşleştirme, Cast ve araç hatalarının kısa günlüğü.
 * Yalnızca telefonda (uygulamanın kendi klasöründe) durur; kullanıcı paylaşmadıkça hiçbir yere gitmez.
 * Son [KEEP] satır saklanır. Uygulama çökerse hatanın kendisi de yazılır.
 */
object DiagLog {
    const val KEEP = 500
    private const val REWRITE_AFTER = 100

    private val lines = ArrayDeque<String>()
    private var file: File? = null
    private var appendedSinceRewrite = 0
    private val time = SimpleDateFormat("dd.MM HH:mm:ss", Locale.ROOT)
    // Dosyaya yazma arka planda, sırayla (ekranı çizen iş parçacığı diskte beklemesin); çökmede ise hemen yazılır
    @Volatile private var crashed = false
    private val writer = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "teshis-kaydi").apply { isDaemon = true } }

    /** Kayıt dosyasını açar ve çökme yakalayıcısını kurar; birden fazla çağrılırsa yalnızca ilki geçerlidir. */
    @Synchronized
    fun init(dir: File) {
        if (file != null) return
        val f = File(dir, "teshis.log")
        val old = runCatching { if (f.exists()) f.readLines() else emptyList() }.getOrDefault(emptyList())
        // Dosya açılmadan önce yazılanlar eski kayıtların sonuna eklenir
        val early = lines.toList()
        lines.clear()
        (old.filter { it.isNotBlank() } + early).forEach { lines.addLast(it) }
        trim()
        file = f
        rewrite()
        CrashLog.init(dir)
        installCrashHandler()
    }

    @Synchronized
    fun log(message: String) {
        add("${time.format(Date())}  ${message.replace('\n', ' ').trim()}")
    }

    /** Uygulama çökerken çağrılır: hata ve ilk satırları dosyaya hemen yazılır. */
    @Synchronized
    fun crash(e: Throwable) {
        log("ÇÖKME: ${e.javaClass.name}: ${e.message.orEmpty()}")
        e.stackTrace.take(15).forEach { add("    at $it") }
        e.cause?.let { c ->
            add("    sebep: ${c.javaClass.name}: ${c.message.orEmpty()}")
            c.stackTrace.take(5).forEach { add("    at $it") }
        }
        // Uygulama birazdan kapanacak: sıradaki yazmaları beklemeden tamamı hemen diske (sıradakiler artık yazılmaz)
        crashed = true
        file?.let { f -> runCatching { f.writeText(lines.joinToString("\n", postfix = "\n")) } }
    }

    /** Sıradaki dosya yazmaları bitene kadar bekler (testler ve dosyayı hemen okuyacak yerler için). */
    fun flush() { runCatching { writer.submit {}.get(5, java.util.concurrent.TimeUnit.SECONDS) } }

    @Synchronized
    fun snapshot(): List<String> = lines.toList()

    @Synchronized
    fun clear() {
        lines.clear()
        rewrite()
    }

    /** Paylaşılacak metin: üstte cihaz ve bağlantı bilgisi, altta olaylar. */
    fun report(header: List<Pair<String, String>>, events: List<String>, maskIps: Boolean): String {
        val text = buildString {
            appendLine("TV Kumanda — teşhis kaydı")
            appendLine("Oluşturma: ${SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.ROOT).format(Date())}")
            header.forEach { (k, v) -> appendLine("$k: $v") }
            appendLine()
            appendLine("---- Olaylar (son ${events.size}) ----")
            if (events.isEmpty()) appendLine("(kayıt yok)")
            events.forEach { appendLine(it) }
        }
        return if (maskIps) maskIps(text) else text
    }

    private val ipv4 = Regex("""(?<![\d.])(\d{1,3}\.\d{1,3}\.\d{1,3})\.\d{1,3}(?![\d.])""")

    /** IP adreslerinin son hanesini gizler: 192.168.1.37 → 192.168.1.x */
    fun maskIps(text: String): String = ipv4.replace(text) { "${it.groupValues[1]}.x" }

    // ---------------- İç işler ----------------

    private fun add(line: String) {
        lines.addLast(line)
        trim()
        val f = file ?: return
        if (++appendedSinceRewrite >= REWRITE_AFTER) rewrite()
        else writer.execute { if (!crashed) runCatching { f.appendText(line + "\n") } }
    }

    private fun trim() {
        while (lines.size > KEEP) lines.removeFirst()
    }

    private fun rewrite() {
        val f = file ?: return
        appendedSinceRewrite = 0
        val text = if (lines.isEmpty()) "" else lines.joinToString("\n", postfix = "\n")
        writer.execute { if (!crashed) runCatching { f.writeText(text) } }
    }

    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching { crash(e) }
            runCatching { CrashLog.save(e) }
            previous?.uncaughtException(t, e)
        }
    }
}
