package com.serif.tvkumanda

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

/**
 * Telefonda çalışan zamanlayıcılar (Android'in alarm servisiyle):
 *  - Zamanlı açma: kapalı TV kendini açamadığı için belirlenen saatte telefon Wi-Fi ile uyandırma gönderir.
 *  - Standart bağlantıda uyku zamanlayıcısı (ADB'de bu işi TV'deki nöbetçi yapar).
 * Telefon o saatte TV ile aynı Wi-Fi ağında olmalıdır. Telefon yeniden başlarsa alarmlar geri kurulur.
 */
object PhoneTimers {
    const val ACTION_SLEEP = "com.serif.tvkumanda.ZAMANLI_UYKU"
    const val ACTION_WAKE = "com.serif.tvkumanda.ZAMANLI_ACMA"
    private const val RC_SLEEP = 41
    private const val RC_WAKE = 42

    data class Wake(val ip: String, val hour: Int, val minute: Int, val daily: Boolean, val at: Long)

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences("zamanlayici", Context.MODE_PRIVATE)

    /** Tam zamanlı alarm izni var mı (Android 12+ için Ayarlar'dan verilir). */
    fun canExact(c: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return runCatching { c.getSystemService(AlarmManager::class.java).canScheduleExactAlarms() }.getOrDefault(false)
    }

    /** Alarm izni ekranı (yalnızca Android 12+). */
    fun exactSettingsIntent(c: Context): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${c.packageName}"))
        else null

    // ---------------- Uyku (Standart) ----------------

    /** Bekleyen uyku: (TV adresi, an); yoksa null. */
    fun sleep(c: Context): Pair<String, Long>? {
        val p = prefs(c)
        val ip = p.getString("uyku_ip", null) ?: return null
        val at = p.getLong("uyku_an", 0)
        return if (at > System.currentTimeMillis()) ip to at else null
    }

    fun scheduleSleep(c: Context, ip: String, at: Long) {
        prefs(c).edit().putString("uyku_ip", ip).putLong("uyku_an", at).apply()
        setAlarm(c, RC_SLEEP, ACTION_SLEEP, ip, at)
    }

    fun cancelSleep(c: Context) {
        clearSleep(c)
        cancelAlarm(c, RC_SLEEP, ACTION_SLEEP)
    }

    internal fun clearSleep(c: Context) {
        prefs(c).edit().remove("uyku_ip").remove("uyku_an").apply()
    }

    // ---------------- Zamanlı açma ----------------

    fun wake(c: Context): Wake? {
        val p = prefs(c)
        val ip = p.getString("ac_ip", null) ?: return null
        return Wake(ip, p.getInt("ac_saat", 7), p.getInt("ac_dakika", 0), p.getBoolean("ac_hergun", false), p.getLong("ac_an", 0))
    }

    /** Alarmı kurar; kurulan anı döndürür. */
    fun scheduleWake(c: Context, ip: String, hour: Int, minute: Int, daily: Boolean): Long {
        val at = TimerText.nextAt(hour, minute, System.currentTimeMillis())
        prefs(c).edit().putString("ac_ip", ip).putInt("ac_saat", hour).putInt("ac_dakika", minute)
            .putBoolean("ac_hergun", daily).putLong("ac_an", at).apply()
        setAlarm(c, RC_WAKE, ACTION_WAKE, ip, at)
        return at
    }

    fun cancelWake(c: Context) {
        prefs(c).edit().remove("ac_ip").remove("ac_an").apply()
        cancelAlarm(c, RC_WAKE, ACTION_WAKE)
    }

    /** Açma alarmı çaldıktan sonra: her gün ise yarına kurulur, değilse silinir. */
    internal fun afterWakeFired(c: Context) {
        val w = wake(c) ?: return
        if (w.daily) scheduleWake(c, w.ip, w.hour, w.minute, true) else cancelWake(c)
    }

    /** Telefon yeniden başlayınca ya da uygulama güncellenince alarmlar yeniden kurulur. */
    fun restore(c: Context) {
        val s = sleep(c)
        if (s != null) setAlarm(c, RC_SLEEP, ACTION_SLEEP, s.first, s.second) else clearSleep(c)
        val w = wake(c) ?: return
        when {
            w.daily -> scheduleWake(c, w.ip, w.hour, w.minute, true)
            w.at > System.currentTimeMillis() -> setAlarm(c, RC_WAKE, ACTION_WAKE, w.ip, w.at)
            else -> cancelWake(c)
        }
    }

    /** Alarm için kayıtlı TV bilgisi (yöntem ve Wi-Fi ile uyandırma adresi). */
    internal fun savedTv(c: Context, ip: String): SavedTv =
        SavedTv.load(c.applicationContext.getSharedPreferences("ayarlar", Context.MODE_PRIVATE).getString("tvs", null))
            .firstOrNull { it.ip == ip } ?: SavedTv(ip, ip, ConnMethod.STANDARD)

    // ---------------- Alarm ----------------

    private fun pending(c: Context, rc: Int, action: String, ip: String?): PendingIntent {
        val i = Intent(c, TimerReceiver::class.java).setAction(action)
        if (ip != null) i.putExtra("ip", ip)
        return PendingIntent.getBroadcast(c, rc, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun setAlarm(c: Context, rc: Int, action: String, ip: String, at: Long) {
        val am = c.getSystemService(AlarmManager::class.java) ?: return
        val pi = pending(c, rc, action, ip)
        val exact = canExact(c) && runCatching {
            // Alarm saati durum çubuğunda görünür; telefon derin uykudayken de tam vaktinde çalışır
            val show = PendingIntent.getActivity(c, rc, Intent(c, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            am.setAlarmClock(AlarmManager.AlarmClockInfo(at, show), pi)
        }.isSuccess
        if (!exact) am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)   // izin yoksa birkaç dakika gecikebilir
    }

    private fun cancelAlarm(c: Context, rc: Int, action: String) {
        c.getSystemService(AlarmManager::class.java)?.cancel(pending(c, rc, action, null))
    }
}

/** Zamanlayıcı alarmı çalınca TV'ye komutu gönderir. */
class TimerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val ip = intent.getStringExtra("ip") ?: return
        val action = intent.action
        val done = goAsync()
        Thread {
            try {
                DiagLog.init(app.filesDir)
                val tv = PhoneTimers.savedTv(app, ip)
                when (action) {
                    PhoneTimers.ACTION_SLEEP -> {
                        PhoneTimers.clearSleep(app)
                        val o = AllTvSender.send(app, tv, AllTvs.Command.OFF, timeoutSec = 8)
                        DiagLog.log("Uyku zamanlayıcısı (telefon): ${tv.display} — ${o.text}")
                    }
                    PhoneTimers.ACTION_WAKE -> {
                        PhoneTimers.afterWakeFired(app)
                        val o = AllTvSender.send(app, tv, AllTvs.Command.ON, timeoutSec = 8)
                        DiagLog.log("Zamanlı açma: ${tv.display} — ${o.text}")
                    }
                }
            } catch (e: Exception) {
                runCatching { DiagLog.log("Zamanlayıcı hatası: ${e.javaClass.simpleName}: ${e.message.orEmpty()}") }
            } finally {
                done.finish()
            }
        }.start()
    }
}

/** Telefon yeniden başlayınca ya da uygulama güncellenince zamanlayıcıları geri kurar. */
class TimerBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            runCatching { PhoneTimers.restore(context.applicationContext) }
        }
    }
}
