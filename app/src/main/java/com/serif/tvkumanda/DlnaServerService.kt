package com.serif.tvkumanda

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Paylaşılan bir galeri klasörü. */
data class SharedAlbum(val kind: MediaKind, val bucketId: Long, val name: String) {
    fun encode() = "${kind.name}|$bucketId|${name.replace("|", " ")}"

    companion object {
        fun decode(s: String): SharedAlbum? = runCatching {
            val p = s.split('|', limit = 3)
            SharedAlbum(MediaKind.valueOf(p[0]), p[1].toLong(), p.getOrElse(2) { "" })
        }.getOrNull()
    }
}

/** DLNA sunucu ayarları (telefonda saklanır). */
object DlnaServerPrefs {
    fun prefs(c: Context): SharedPreferences = c.applicationContext.getSharedPreferences("dlna_sunucu", Context.MODE_PRIVATE)

    fun defaultName(c: Context): String =
        runCatching { android.provider.Settings.Global.getString(c.contentResolver, "device_name") }.getOrNull()?.ifBlank { null }
            ?: Build.MODEL

    fun name(c: Context) = prefs(c).getString("ad", null)?.ifBlank { null } ?: defaultName(c)
    fun setName(c: Context, v: String) = prefs(c).edit().putString("ad", v.trim()).apply()

    fun allEnabled(c: Context, k: MediaKind) = prefs(c).getBoolean("tum_${k.name}", true)
    fun setAllEnabled(c: Context, k: MediaKind, v: Boolean) = prefs(c).edit().putBoolean("tum_${k.name}", v).apply()

    fun albums(c: Context): List<SharedAlbum> =
        prefs(c).getString("klasorler", "").orEmpty().split('\n').filter { it.isNotBlank() }.mapNotNull { SharedAlbum.decode(it) }

    fun setAlbums(c: Context, list: List<SharedAlbum>) =
        prefs(c).edit().putString("klasorler", list.joinToString("\n") { it.encode() }).apply()

    /** Kullanılmazsa kapanma süresi (dakika, 0 = asla). */
    fun idleMinutes(c: Context) = prefs(c).getInt("bosta_kapan", 60)
    fun setIdleMinutes(c: Context, v: Int) = prefs(c).edit().putInt("bosta_kapan", v).apply()

    fun thumbnails(c: Context) = prefs(c).getBoolean("kucuk_resim", true)
    fun setThumbnails(c: Context, v: Boolean) = prefs(c).edit().putBoolean("kucuk_resim", v).apply()

    fun musicGroups(c: Context) = prefs(c).getBoolean("muzik_grup", false)
    fun setMusicGroups(c: Context, v: Boolean) = prefs(c).edit().putBoolean("muzik_grup", v).apply()
}

/** Ekranın izlediği sunucu durumu. */
object DlnaServerUi {
    var running by mutableStateOf(false)
    var starting by mutableStateOf(false)
    var address by mutableStateOf("")
    var error by mutableStateOf("")
    /** Tam ekran sayfa açık mı. */
    var pageOpen by mutableStateOf(false)
    /** Ayar değişince ekranların yeniden okuması için. */
    var tick by mutableStateOf(0)

    fun start(c: Context) {
        if (running || starting) return
        starting = true; error = ""
        val i = Intent(c, DlnaServerService::class.java)
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i) else c.startService(i)
    }

    fun stop(c: Context) {
        c.startService(Intent(c, DlnaServerService::class.java).setAction(DlnaServerService.ACTION_STOP))
    }

    /** Ad değişti: TV'lere yeniden duyurulur. */
    fun nameChanged() { DlnaServerService.server?.reannounce() }
}

/**
 * DLNA sunucusunu arka planda ayakta tutan ön plan hizmeti. Telefon ekranı kapansa da
 * TV klasörlere girip dosya oynatabilir. Belirlenen süre boyunca hiçbir istek gelmezse kendiliğinden kapanır.
 */
class DlnaServerService : Service() {

    private var wifiLock: WifiManager.WifiLock? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var idleThread: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            shutdown()
            return START_NOT_STICKY
        }
        val n = notification()
        if (Build.VERSION.SDK_INT >= 29) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        else startForeground(ID, n)
        if (server == null) {
            Thread {
                try {
                    acquireLocks()
                    val s = DlnaMediaServer(applicationContext, MediaLibrary(applicationContext))
                    s.start()
                    server = s
                    DlnaServerUi.address = s.address
                    DlnaServerUi.running = true
                    DlnaServerUi.starting = false
                    (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(ID, notification())
                    watchIdle(s)
                } catch (e: Exception) {
                    DlnaServerUi.error = "Sunucu başlatılamadı: ${e.message ?: e.javaClass.simpleName}"
                    DlnaServerUi.starting = false
                    shutdown()
                }
            }.start()
        }
        return START_NOT_STICKY
    }

    private fun watchIdle(s: DlnaMediaServer) {
        idleThread = Thread {
            while (server === s) {
                try { Thread.sleep(60_000) } catch (_: InterruptedException) { break }
                val limit = DlnaServerPrefs.idleMinutes(applicationContext)
                if (limit > 0 && System.currentTimeMillis() - s.lastActivity > limit * 60_000L) {
                    shutdown()
                    break
                }
            }
        }.apply { isDaemon = true; start() }
    }

    private fun shutdown() {
        server?.stop()
        server = null
        idleThread?.interrupt()
        DlnaServerUi.running = false
        DlnaServerUi.starting = false
        DlnaServerUi.address = ""
        releaseLocks()
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE) else @Suppress("DEPRECATION") stopForeground(true)
        stopSelf()
    }

    override fun onDestroy() {
        if (server != null) shutdown()
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    private fun acquireLocks() {
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        if (wifiLock == null) wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "TvKumanda:dlna")
            .apply { setReferenceCounted(false); acquire() }
        // Ağdaki TV'lerin arama mesajlarını alabilmek için
        if (multicastLock == null) multicastLock = wm.createMulticastLock("TvKumanda:dlna-sunucu")
            .apply { setReferenceCounted(false); acquire() }
        if (wakeLock == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TvKumanda:dlna")
                .apply { setReferenceCounted(false); acquire(8 * 60 * 60 * 1000L) }
        }
    }

    private fun releaseLocks() {
        runCatching { wifiLock?.release() }; wifiLock = null
        runCatching { multicastLock?.release() }; multicastLock = null
        runCatching { wakeLock?.release() }; wakeLock = null
    }

    private fun notification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "DLNA Sunucu", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) }
            )
        }
        val open = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 2, Intent(this, DlnaServerService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else @Suppress("DEPRECATION") Notification.Builder(this)
        val running = server != null
        return b.setSmallIcon(R.drawable.ic_notif_cast)
            .setContentTitle(if (running) "DLNA Sunucu çalışıyor" else "DLNA Sunucu başlatılıyor…")
            .setContentText("TV'de \"${DlnaServerPrefs.name(this)}\" olarak görünüyor")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_n_stop), "Durdur", stop).build())
            .build()
    }

    companion object {
        private const val CHANNEL = "dlna_sunucu"
        private const val ID = 3002
        const val ACTION_STOP = "dlna_stop"
        @Volatile var server: DlnaMediaServer? = null
    }
}
