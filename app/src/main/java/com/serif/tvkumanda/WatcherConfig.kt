package com.serif.tvkumanda

import java.util.Calendar
import java.util.TimeZone

/**
 * TV'deki nöbetçinin ayar dosyası (`Watcher.java` aynı anahtarları okur).
 * Gece saatleri ve süreler dakika cinsindendir; -1 / 0 "kapalı" demektir.
 */
data class WatcherConfig(
    /** Uyku anı (epoch ms); 0 = zamanlayıcı yok. */
    val sleepAt: Long = 0,
    /** Son dakikada sesi yavaşça kıs. */
    val fade: Boolean = false,
    /** Ses üst sınırı; -1 = kapalı. */
    val volMax: Int = -1,
    /** Günlük ekran süresi (dakika); 0 = kapalı. */
    val limitMin: Int = 0,
    /** Gece kapalı saatleri, gün içindeki dakika olarak; -1 = kapalı. */
    val nightFrom: Int = -1,
    val nightTo: Int = -1
) {
    fun encode(): String = buildString {
        append("sleep_at=").append(sleepAt).append('\n')
        append("sleep_fade=").append(if (fade) 1 else 0).append('\n')
        append("vol_max=").append(volMax).append('\n')
        append("limit_min=").append(limitMin).append('\n')
        append("night_from=").append(nightFrom).append('\n')
        append("night_to=").append(nightTo).append('\n')
    }

    /** Nöbetçinin yapacak işi var mı ([sleepDone]: nöbetçinin en son uyguladığı uyku anı). */
    fun hasWork(now: Long, sleepDone: Long): Boolean =
        (sleepAt > sleepDone && sleepAt > now - STALE_SLEEP_MS) || volMax >= 0 || limitMin > 0 || nightFrom >= 0

    /** Bekleyen uyku anı; yoksa null. */
    fun pendingSleep(now: Long, sleepDone: Long): Long? = sleepAt.takeIf { it > now && it > sleepDone }

    companion object {
        const val STALE_SLEEP_MS = 10 * 60_000L

        fun parse(text: String): WatcherConfig {
            val m = keyValues(text)
            return WatcherConfig(
                sleepAt = m["sleep_at"]?.toLongOrNull() ?: 0,
                fade = m["sleep_fade"] == "1",
                volMax = m["vol_max"]?.toIntOrNull() ?: -1,
                limitMin = m["limit_min"]?.toIntOrNull() ?: 0,
                nightFrom = m["night_from"]?.toIntOrNull() ?: -1,
                nightTo = m["night_to"]?.toIntOrNull() ?: -1
            )
        }

        fun keyValues(text: String): Map<String, String> = text.lineSequence()
            .mapNotNull { line -> line.indexOf('=').takeIf { it > 0 }?.let { line.substring(0, it).trim() to line.substring(it + 1).trim() } }
            .toMap()
    }
}

/** Nöbetçinin kendi yazdığı durum dosyası. */
data class WatcherState(
    val pid: Long,
    val apk: String,
    val beat: Long,
    val day: String,
    val usedSec: Long,
    val sleepDone: Long,
    val running: Boolean
) {
    companion object {
        /** Dosya boşsa ya da okunamıyorsa null. */
        fun parse(text: String): WatcherState? {
            val m = WatcherConfig.keyValues(text)
            val pid = m["pid"]?.toLongOrNull() ?: return null
            return WatcherState(
                pid = pid,
                apk = m["apk"].orEmpty(),
                beat = m["beat"]?.toLongOrNull() ?: 0,
                day = m["day"].orEmpty(),
                usedSec = m["used_sec"]?.toLongOrNull() ?: 0,
                sleepDone = m["sleep_done"]?.toLongOrNull() ?: 0,
                running = m["running"] == "1"
            )
        }
    }
}

/** Zamanlama yardımcıları (saat hesapları ve sade Türkçe süre metinleri). */
object TimerText {

    /** Verilen saat ve dakikanın bir sonraki gerçekleşeceği an (bugün geçtiyse yarın). */
    fun nextAt(hour: Int, minute: Int, now: Long, tz: TimeZone = TimeZone.getDefault()): Long {
        val c = Calendar.getInstance(tz)
        c.timeInMillis = now
        c.set(Calendar.HOUR_OF_DAY, hour)
        c.set(Calendar.MINUTE, minute)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        if (c.timeInMillis <= now) c.add(Calendar.DAY_OF_MONTH, 1)
        return c.timeInMillis
    }

    /** Kalan süre: "1 sa 05 dk" ya da "4:09". */
    fun remaining(ms: Long): String {
        val s = (ms.coerceAtLeast(0) + 999) / 1000
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) "$h sa ${m.toString().padStart(2, '0')} dk" else "$m:${sec.toString().padStart(2, '0')}"
    }

    /** Süre: "2 sa 15 dk", "45 dk". */
    fun duration(seconds: Long): String {
        val totalMin = seconds / 60
        val h = totalMin / 60
        val m = totalMin % 60
        return when {
            h > 0 && m > 0 -> "$h sa $m dk"
            h > 0 -> "$h sa"
            else -> "$m dk"
        }
    }

    /** Gün içindeki dakika → "23:00". */
    fun clock(minuteOfDay: Int): String =
        "${(minuteOfDay / 60).toString().padStart(2, '0')}:${(minuteOfDay % 60).toString().padStart(2, '0')}"

    fun clockOf(epochMs: Long, tz: TimeZone = TimeZone.getDefault()): String {
        val c = Calendar.getInstance(tz)
        c.timeInMillis = epochMs
        return clock(c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE))
    }
}
