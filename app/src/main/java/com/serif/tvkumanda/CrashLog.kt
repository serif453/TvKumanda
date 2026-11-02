package com.serif.tvkumanda

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Çökme kaydı: uygulama çökerse hatanın tamamı telefonda ayrı bir dosyaya yazılır (teşhis kaydındaki olaylar arasında
 * kaybolmasın). Son [KEEP] çökme saklanır, IP'ler gizlenir. Kullanıcı kopyalamadıkça hiçbir yere gitmez (Ayarlar → Hakkında).
 */
object CrashLog {
    const val KEEP = 5
    private const val MAX_LINES = 80
    private const val SEPARATOR = "\n=====\n"

    /** Kayda yazılacak sürüm ve cihaz bilgisi. */
    @Volatile var header = ""
    @Volatile private var file: File? = null

    fun init(dir: File) { file = File(dir, "cokme.txt") }

    /** Çökme anında çağrılır: uygulama birazdan kapanacağı için hemen yazar. */
    @Synchronized
    fun save(e: Throwable) {
        val f = file ?: return
        val old = if (f.exists()) f.readText() else ""
        f.writeText(merge(old, format(Date(), header, e)))
    }

    /** Kayıtlar, en yenisi başta. */
    @Synchronized
    fun entries(): List<String> {
        val f = file ?: return emptyList()
        return runCatching { if (f.exists()) split(f.readText()) else emptyList() }.getOrDefault(emptyList())
    }

    @Synchronized
    fun clear() { runCatching { file?.delete() } }

    /** Bir kaydın ilk satırı çökmenin zamanıdır. */
    fun timeOf(entry: String): String = entry.lineSequence().firstOrNull().orEmpty()

    fun format(time: Date, header: String, e: Throwable): String {
        val trace = e.stackTraceToString().trimEnd().lines()
        val shown = if (trace.size > MAX_LINES) trace.take(MAX_LINES) + "… (${trace.size - MAX_LINES} satır daha)" else trace
        return DiagLog.maskIps(buildString {
            appendLine(SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.ROOT).format(time))
            if (header.isNotBlank()) appendLine(header)
            append(shown.joinToString("\n"))
        })
    }

    fun split(text: String): List<String> = text.split(SEPARATOR).map { it.trim() }.filter { it.isNotEmpty() }

    /** Yeni kaydı başa ekler, en eskileri atar. */
    fun merge(old: String, entry: String): String = (listOf(entry) + split(old)).take(KEEP).joinToString(SEPARATOR)

    /** Kopyalanacak metin. */
    fun export(entries: List<String>): String = entries.joinToString("\n\n")
}
