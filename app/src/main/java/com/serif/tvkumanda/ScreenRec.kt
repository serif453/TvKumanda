package com.serif.tvkumanda

import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * TV ekranını videoya çekme: TV'nin kendi `screenrecord` komutu ADB ile arka planda başlatılır.
 * Video önce TV'de oluşur, bitince telefona çekilir. Komut uygulamadan bağımsız çalıştığı için
 * uygulama kapansa da kayıt sürer; uygulama yeniden açılınca kayıt bulunur.
 * Ses kaydedilmez (TV'nin komutu ses almıyor) ve Android tek kaydı en fazla 3 dakikayla sınırlar.
 */
object ScreenRec {
    const val REMOTE = "/data/local/tmp/tvk_rec.mp4"
    private const val LOG = "/data/local/tmp/tvk_rec.log"
    const val MAX_SECONDS = 180

    /** Süre seçenekleri (saniye). */
    val DURATIONS = listOf(30, 60, 120, MAX_SECONDS)

    /** TV'deki kaydın durumu: komut çalışıyor mu, dosya kaç bayt. */
    data class Status(val running: Boolean, val bytes: Long)

    fun clampSeconds(s: Int) = s.coerceIn(5, MAX_SECONDS)

    /**
     * `wm size` çıktısından kayıt boyutunu seçer. 16:9 ekranlarda 1280x720 (4K TV'de dev dosya ve
     * kodlayıcı hatası olmasın diye); başka oranda null döner, komut kendi boyutunu seçer.
     */
    fun pickSize(wmOutput: String): String? {
        val matches = Regex("""(Override|Physical) size:\s*(\d+)x(\d+)""").findAll(wmOutput).toList()
        val m = matches.firstOrNull { it.groupValues[1] == "Override" } ?: matches.firstOrNull() ?: return null
        val w = m.groupValues[2].toIntOrNull() ?: return null
        val h = m.groupValues[3].toIntOrNull() ?: return null
        if (w <= 0 || h <= 0) return null
        return if (kotlin.math.abs(w * 9 - h * 16) <= w / 50 + 1) "1280x720" else null
    }

    /** Telefona çekilen dosyanın adı, ör. TV_kayit_20261030_184500.mp4 */
    fun fileName(timeMillis: Long): String =
        "TV_kayit_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(timeMillis)) + ".mp4"

    /** TV'de arka planda başlatan kabuk komutu. */
    fun startCommand(seconds: Int, size: String?): String {
        val sizeArg = if (size != null) " --size $size" else ""
        val run = "screenrecord --time-limit ${clampSeconds(seconds)}$sizeArg --bit-rate 8000000 $REMOTE >$LOG 2>&1 </dev/null &"
        return "rm -f $REMOTE $LOG; cd /data/local/tmp; " +
            "if command -v setsid >/dev/null 2>&1; then setsid nohup $run else nohup $run fi"
    }

    /** Durum komutunun çıktısını ("pid=123" satırı, ardından dosya boyutu) okur. */
    fun parseStatus(out: String): Status {
        val lines = out.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val pid = lines.firstOrNull { it.startsWith("pid=") }?.removePrefix("pid=")?.trim().orEmpty()
        val bytes = lines.lastOrNull { it.all(Char::isDigit) }?.toLongOrNull() ?: 0L
        return Status(pid.isNotEmpty() && pid.all { it == ' ' || it.isDigit() }, bytes)
    }

    /** Komutun hata çıktısından sade bir açıklama üretir. */
    fun explain(log: String): String {
        val t = log.trim()
        return when {
            t.isEmpty() -> "TV ekran kaydını başlatamadı (bu TV komutu desteklemiyor olabilir)"
            t.contains("not found", true) || t.contains("inaccessible", true) -> "Bu TV'de ekran kaydı komutu yok"
            t.contains("encoder", true) || t.contains("codec", true) -> "TV'nin video kodlayıcısı kaydı başlatamadı"
            t.contains("permission", true) || t.contains("denied", true) -> "TV ekran kaydına izin vermedi"
            else -> "TV ekran kaydını başlatamadı: " + t.lines().first().take(120)
        }
    }

    // ---------------- TV işlemleri (bloklayıcı; arka planda çağrılır) ----------------

    private fun sh(cmd: String): String =
        (TvTools.adb ?: throw IllegalStateException("Önce TV'ye ADB ile bağlan")).shell(cmd)

    fun status(): Status =
        parseStatus(sh("echo pid=\$(pidof screenrecord); wc -c < $REMOTE 2>/dev/null"))

    /** Kaydı başlatır; TV komutu hemen bırakırsa nedenini söyleyen bir hata fırlatır. */
    fun start(seconds: Int) {
        if (status().running) throw IOException("TV'de zaten bir kayıt sürüyor")
        val size = pickSize(sh("wm size 2>/dev/null"))
        sh(startCommand(seconds, size))
        Thread.sleep(1500)
        if (!status().running) {
            throw IOException(explain(sh("cat $LOG 2>/dev/null")))
        }
    }

    /** Kaydı düzgünce bitirir (video dosyası kapatılsın diye önce nazik durdurma, sonra bekleme). */
    fun stop() {
        sh("kill -2 \$(pidof screenrecord) 2>/dev/null")
        repeat(16) {
            Thread.sleep(500)
            if (!status().running) return
        }
        sh("kill -9 \$(pidof screenrecord) 2>/dev/null")
    }

    /** Biten kaydı telefona çeker ve TV'deki kopyayı siler. */
    fun pull(dst: File) {
        val d = TvTools.adb ?: throw IllegalStateException("Önce TV'ye ADB ile bağlan")
        d.pull(REMOTE, dst)
        runCatching { sh("rm -f $REMOTE $LOG") }
        if (!dst.exists() || dst.length() == 0L) {
            dst.delete()
            throw IOException("Kayıt boş çıktı (korumalı içerik ya da TV kodlayıcısı sorunu olabilir)")
        }
    }

    /** TV'deki bitmemiş ya da alınmamış kaydı siler. */
    fun discard() {
        runCatching { sh("rm -f $REMOTE $LOG") }
    }
}
