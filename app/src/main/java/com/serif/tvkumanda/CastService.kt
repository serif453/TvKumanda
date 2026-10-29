package com.serif.tvkumanda

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.media.session.MediaSession
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager

/**
 * Cast sürerken uygulamayı ayakta tutan ön plan hizmeti. Telefon ekranı kapansa bile
 * TV'ye dosya sunan küçük sunucu çalışmaya devam eder. Bildirim, müzik uygulamalarındaki
 * gibi bir oynatıcıdır (kapak, ad, önceki/oynat-duraklat/sonraki/durdur); aynı kontroller
 * kilit ekranında da görünür. Wi-Fi ve işlemci uykuya geçmez.
 */
class CastService : Service() {

    /** Bildirimde gösterilecek bilgiler. */
    data class Info(
        val title: String,
        val subtitle: String,
        val playing: Boolean,
        val canSkip: Boolean,
        val isPhoto: Boolean,
        val art: Bitmap?,
        val token: MediaSession.Token?
    )

    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Bildirim tuşları: uygulamadaki ilgili işleve iletilir
        intent?.action?.let { a -> if (a != ACTION_SHOW) controller?.invoke(a) }
        val n = notification(info)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else startForeground(ID, n)
        acquireLocks()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        runCatching { wifiLock?.release() }
        runCatching { wakeLock?.release() }
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    private fun acquireLocks() {
        if (wifiLock == null) {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "TvKumanda:cast")
                .apply { setReferenceCounted(false); acquire() }
        }
        if (wakeLock == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            // Uzun videolar için üst sınır: 6 saat
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TvKumanda:cast")
                .apply { setReferenceCounted(false); acquire(6 * 60 * 60 * 1000L) }
        }
    }

    private fun action(icon: Int, title: String, act: String): Notification.Action {
        val pi = PendingIntent.getService(
            this, act.hashCode(), Intent(this, CastService::class.java).setAction(act),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Action.Builder(Icon.createWithResource(this, icon), title, pi).build()
    }

    private fun notification(i: Info?): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "TV'ye yayın", NotificationManager.IMPORTANCE_LOW)
                    .apply { setShowBadge(false) }
            )
        }
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else @Suppress("DEPRECATION") Notification.Builder(this)
        b.setSmallIcon(R.drawable.ic_notif_cast)
            .setContentTitle(i?.title?.ifBlank { null } ?: "TV'ye yayın yapılıyor")
            .setContentText(i?.subtitle?.ifBlank { null } ?: "TV'ye yayın yapılıyor")
            .setContentIntent(open)
            .setOngoing(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)   // kilit ekranında da görünsün
        i?.art?.let { b.setLargeIcon(it) }
        if (i != null) {
            val compact = ArrayList<Int>()
            var n = 0
            if (i.canSkip) { b.addAction(action(R.drawable.ic_n_prev, "Önceki", ACTION_PREV)); compact += n++ }
            if (!i.isPhoto) {
                b.addAction(
                    if (i.playing) action(R.drawable.ic_n_pause, "Duraklat", ACTION_PAUSE)
                    else action(R.drawable.ic_n_play, "Oynat", ACTION_PLAY)
                ); compact += n++
            }
            if (i.canSkip) { b.addAction(action(R.drawable.ic_n_next, "Sonraki", ACTION_NEXT)); compact += n++ }
            b.addAction(action(R.drawable.ic_n_stop, "Durdur", ACTION_STOP)); n++
            val style = Notification.MediaStyle().setShowActionsInCompactView(*compact.take(3).toIntArray())
            i.token?.let { style.setMediaSession(it) }
            b.setStyle(style)
        }
        return b.build()
    }

    companion object {
        private const val CHANNEL = "cast"
        private const val ID = 3001
        const val ACTION_SHOW = "show"
        const val ACTION_PLAY = "play"
        const val ACTION_PAUSE = "pause"
        const val ACTION_NEXT = "next"
        const val ACTION_PREV = "prev"
        const val ACTION_STOP = "stop"

        /** Bildirim tuşlarını uygulamaya ileten köprü (MainActivity ayarlar). */
        @Volatile var controller: ((String) -> Unit)? = null
        @Volatile private var info: Info? = null

        /** Hizmeti başlatır ya da bildirimi günceller. */
        fun update(context: Context, i: Info) {
            info = i
            val intent = Intent(context, CastService::class.java).setAction(ACTION_SHOW)
            runCatching {
                if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
            }
        }

        fun stop(context: Context) {
            info = null
            runCatching { context.stopService(Intent(context, CastService::class.java)) }
        }
    }
}
