package com.serif.tvkumanda

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.view.WindowManager
import androidx.annotation.RequiresApi
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Canlı yayının ekranlara yansıyan durumu (hizmet yazar, araç pencereleri okur). */
object LiveStream {
    enum class Phase { IDLE, STARTING, LIVE, ERROR }

    var phase by mutableStateOf(Phase.IDLE)
    /** true: ekran + ses, false: yalnızca ses. */
    var screen by mutableStateOf(true)
    var url by mutableStateOf("")
    var error by mutableStateOf<String?>(null)
    var viewers by mutableStateOf(0)
    /** Yayında ses var mı (görüntülü yayında ses alınamadıysa false). */
    var hasAudio by mutableStateOf(false)
    /** Görüntülü yayının boyutu, ör. "576x1280". */
    var sizeText by mutableStateOf("")

    /** Yayın açılınca TV'ye bir kez kendiliğinden gönderilir; bu bayrak tekrarı önler. */
    @Volatile var autoSent = false

    internal fun reset(screen: Boolean) {
        phase = Phase.STARTING; this.screen = screen; url = ""; error = null; viewers = 0; hasAudio = false; sizeText = ""
        autoSent = false
    }

    val active: Boolean get() = phase == Phase.STARTING || phase == Phase.LIVE
}

/**
 * Telefonun ekranını ve/veya sesini yakalayıp ağda canlı yayın olarak sunan ön plan hizmeti.
 *  - Ekran: MediaProjection → H.264, ses: çalan seslerin yakalanması (Android 10+) → AAC; ikisi MPEG-TS'te birleşir.
 *  - Yalnızca ses (hoparlör): AAC (ADTS) akışı.
 * Yayın [LiveServer] ile sunulur; TV'ye gönderme araç penceresinden yapılır. Ekran kapansa da sürer.
 */
class LiveService : Service() {

    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var videoCodec: MediaCodec? = null
    private var audioCodec: MediaCodec? = null
    private var audioRecord: AudioRecord? = null
    private var server: LiveServer? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val threads = ArrayList<Thread>()
    @Volatile private var stopping = false
    @Volatile private var cleaned = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || intent.action == ACTION_STOP) {
            cleanup()
            stopSelf()
            return START_NOT_STICKY
        }
        if (LiveStream.active && projection != null) return START_NOT_STICKY   // zaten yayında
        val screen = intent.getBooleanExtra(EXTRA_SCREEN, true)
        val tvIp = intent.getStringExtra(EXTRA_TV_IP).orEmpty()
        val code = intent.getIntExtra(EXTRA_CODE, 0)
        @Suppress("DEPRECATION")
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        else intent.getParcelableExtra(EXTRA_DATA)

        LiveStream.reset(screen)
        // Ön plana hemen çıkılmalı (Android 14'te ekran yakalama için şart)
        try {
            val n = notification(screen)
            if (Build.VERSION.SDK_INT >= 29) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            else startForeground(ID, n)
        } catch (e: Exception) {
            fail(e)
            return START_NOT_STICKY
        }
        if (data == null) {
            fail(IllegalStateException("Ekran yakalama izni alınamadı"))
            return START_NOT_STICKY
        }
        acquireLocks()
        Thread {
            try {
                setup(screen, tvIp, code, data)
            } catch (e: Throwable) {
                fail(e)
            }
        }.apply { isDaemon = true }.start()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        cleanup()
        super.onDestroy()
    }

    // ---------------- Kurulum ----------------

    private fun setup(screen: Boolean, tvIp: String, code: Int, data: Intent) {
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val proj = mpm.getMediaProjection(code, data) ?: throw IllegalStateException("Ekran yakalama başlatılamadı")
        projection = proj
        // Android 14: sesi/görüntüyü kurmadan önce geri çağrı eklenmeli; kullanıcı sistemden durdurursa yayın biter
        proj.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { DiagLog.log("Canlı yayın: ekran yakalama sistem tarafından durduruldu"); cleanup(); stopSelf() }
        }, Handler(Looper.getMainLooper()))

        val srv = LiveServer(
            mime = if (screen) "video/mp2t" else "audio/aac",
            path = if (screen) "/live.ts" else "/live.aac",
            needKey = screen
        )
        server = srv
        srv.start()
        if (tvIp.isNotEmpty()) LiveStream.url = srv.url(tvIp)

        val baseUs = System.nanoTime() / 1000
        val canAudio = Build.VERSION.SDK_INT >= 29 &&
            checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (!screen && !canAudio) {
            throw IllegalStateException(
                if (Build.VERSION.SDK_INT < 29) "Telefon sesini yakalamak Android 10 ve üstünü ister"
                else "Ses yakalamak için mikrofon izni gerekli (Ayarlar → Uygulamalar → TV Kumanda → İzinler)"
            )
        }

        var audioErr: String? = null
        var rec: AudioRecord? = null
        var aac: MediaCodec? = null
        if (canAudio && Build.VERSION.SDK_INT >= 29) {
            try {
                val pair = buildAudio(proj)
                rec = pair.first; aac = pair.second
            } catch (e: Exception) {
                if (!screen) throw e
                audioErr = e.message   // görüntülü yayın sessiz devam eder
                DiagLog.log("Canlı yayın: ses yakalanamadı (${e.javaClass.simpleName}: ${e.message.orEmpty()})")
            }
        }
        LiveStream.hasAudio = rec != null

        val muxer: TsMuxer? = if (screen) TsMuxer(hasVideo = true, hasAudio = rec != null, baseUs = baseUs) { b, key -> srv.publish(b, key) } else null

        if (screen) {
            val (w, h) = screenSize().let { LiveSize.fit(it.first, it.second) }
            val enc = buildVideo(w, h)
            videoCodec = enc.first
            val surface = enc.second
            display = proj.createVirtualDisplay(
                "TvKumanda", w, h, resources.displayMetrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, surface, null, null
            )
            LiveStream.sizeText = "${w}x$h"
            val vc = enc.first
            start("canli-goruntu") { videoLoop(vc, muxer!!) }
        }
        if (rec != null && aac != null) {
            audioRecord = rec; audioCodec = aac
            val emit: (ByteArray, Long) -> Unit = { adts, pts ->
                if (muxer != null) muxer.writeAudio(adts, pts) else srv.publish(adts, true)
            }
            val r = rec; val c = aac
            start("canli-ses") { audioLoop(r, c, emit) }
        }
        start("canli-izleyen") {
            while (!stopping) {
                LiveStream.viewers = srv.viewers
                try { Thread.sleep(1000) } catch (_: InterruptedException) { break }
            }
        }
        if (stopping) return
        LiveStream.phase = LiveStream.Phase.LIVE
        DiagLog.log("Canlı yayın başladı (${if (screen) "ekran" else "ses"}${if (screen) ", ${LiveStream.sizeText}" else ""}${if (screen && rec == null) ", sessiz" else ""}${audioErr?.let { ", ses hatası: $it" }.orEmpty()})")
    }

    private fun start(name: String, body: () -> Unit) {
        val t = Thread({ runCatching(body).onFailure { if (!stopping) fail(it) } }, name).apply { isDaemon = true }
        synchronized(threads) { threads += t }
        t.start()
    }

    private fun buildVideo(w: Int, h: Int): Pair<MediaCodec, android.view.Surface> {
        fun format(cbr: Boolean) = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, 4_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            // Ekran durağanken de görüntü akmaya devam etsin (oynatıcı beklemede kalmasın)
            setLong("repeat-previous-frame-after", 500_000L)
            if (cbr) setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        try {
            codec.configure(format(true), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        } catch (e: Exception) {
            codec.reset()
            codec.configure(format(false), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }
        val surface = codec.createInputSurface()
        codec.start()
        return codec to surface
    }

    @SuppressLint("MissingPermission")
    @RequiresApi(29)
    private fun buildAudio(proj: MediaProjection): Pair<AudioRecord, MediaCodec> {
        val cfg = AudioPlaybackCaptureConfiguration.Builder(proj)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
        val fmt = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .build()
        val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord.Builder()
            .setAudioFormat(fmt)
            .setBufferSizeInBytes(maxOf(min * 2, 16384))
            .setAudioPlaybackCaptureConfig(cfg)
            .build()
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            throw IllegalStateException("Telefon sesi yakalanamadı")
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        val af = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, RATE, 2).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        }
        codec.configure(af, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        return rec to codec
    }

    // ---------------- Döngüler ----------------

    private fun videoLoop(codec: MediaCodec, mux: TsMuxer) {
        val info = MediaCodec.BufferInfo()
        var last = -1L
        while (!stopping) {
            val i = try { codec.dequeueOutputBuffer(info, 100_000) } catch (_: IllegalStateException) { break }
            if (i < 0) continue
            val buf = codec.getOutputBuffer(i)
            if (buf != null && info.size > 0) {
                val bytes = ByteArray(info.size)
                buf.position(info.offset)
                buf.limit(info.offset + info.size)
                buf.get(bytes)
                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                    mux.setVideoConfig(bytes)
                } else {
                    var pts = info.presentationTimeUs
                    if (pts <= last) pts = last + 1
                    last = pts
                    mux.writeVideo(bytes, pts, info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0)
                }
            }
            codec.releaseOutputBuffer(i, false)
        }
    }

    private fun audioLoop(rec: AudioRecord, codec: MediaCodec, emit: (ByteArray, Long) -> Unit) {
        val pcm = ByteArray(4096)
        val info = MediaCodec.BufferInfo()
        var frames = 0L
        var startUs = -1L
        rec.startRecording()
        while (!stopping) {
            val n = rec.read(pcm, 0, pcm.size)
            if (n < 0) throw IllegalStateException("Ses okunamadı (kod $n)")
            if (n == 0) continue
            val chunkFrames = n / 4
            if (startUs < 0) startUs = System.nanoTime() / 1000 - chunkFrames * 1_000_000L / RATE
            val idx = codec.dequeueInputBuffer(10_000)
            if (idx >= 0) {
                val ib = codec.getInputBuffer(idx)!!
                ib.clear()
                ib.put(pcm, 0, n)
                codec.queueInputBuffer(idx, 0, n, startUs + frames * 1_000_000L / RATE, 0)
            }
            frames += chunkFrames
            while (true) {
                val o = codec.dequeueOutputBuffer(info, 0)
                if (o == MediaCodec.INFO_TRY_AGAIN_LATER) break
                if (o < 0) continue
                val ob = codec.getOutputBuffer(o)
                if (ob != null && info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                    val frame = ByteArray(7 + info.size)
                    System.arraycopy(Adts.header(info.size, RATE, 2), 0, frame, 0, 7)
                    ob.position(info.offset)
                    ob.limit(info.offset + info.size)
                    ob.get(frame, 7, info.size)
                    emit(frame, info.presentationTimeUs)
                }
                codec.releaseOutputBuffer(o, false)
            }
        }
    }

    // ---------------- Bitirme ----------------

    private fun fail(e: Throwable) {
        DiagLog.log("Canlı yayın hatası: ${e.javaClass.simpleName}: ${e.message.orEmpty()}")
        val msg = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
        cleanup()
        LiveStream.error = msg
        LiveStream.phase = LiveStream.Phase.ERROR
        stopSelf()
    }

    @Synchronized
    private fun cleanup() {
        if (cleaned) return
        cleaned = true
        stopping = true
        val list = synchronized(threads) { ArrayList(threads) }
        list.forEach { if (it !== Thread.currentThread()) runCatching { it.join(300) } }
        runCatching { audioRecord?.stop() }
        runCatching { audioRecord?.release() }
        runCatching { audioCodec?.stop() }
        runCatching { audioCodec?.release() }
        runCatching { display?.release() }
        runCatching { videoCodec?.stop() }
        runCatching { videoCodec?.release() }
        runCatching { projection?.stop() }
        runCatching { server?.stop() }
        runCatching { wifiLock?.release() }
        runCatching { wakeLock?.release() }
        if (LiveStream.phase != LiveStream.Phase.ERROR) LiveStream.phase = LiveStream.Phase.IDLE
        LiveStream.viewers = 0
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
    }

    @Suppress("DEPRECATION")
    private fun acquireLocks() {
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "TvKumanda:canli").apply { setReferenceCounted(false); acquire() }
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        // Üst sınır: 4 saat
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TvKumanda:canli").apply { setReferenceCounted(false); acquire(4 * 60 * 60 * 1000L) }
    }

    @Suppress("DEPRECATION")
    private fun screenSize(): Pair<Int, Int> {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.maximumWindowMetrics.bounds
            b.width() to b.height()
        } else {
            val m = android.util.DisplayMetrics()
            wm.defaultDisplay.getRealMetrics(m)
            m.widthPixels to m.heightPixels
        }
    }

    private fun notification(screen: Boolean): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Canlı yayın", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) })
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, LiveService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else @Suppress("DEPRECATION") Notification.Builder(this)
        return b.setSmallIcon(R.drawable.ic_notif_cast)
            .setContentTitle(if (screen) "Ekran TV'ye yayınlanıyor" else "Telefonun sesi TV'ye yayınlanıyor")
            .setContentText("Durdurmak için dokun ve Durdur'a bas")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_n_stop), "Durdur", stop).build())
            .build()
    }

    companion object {
        private const val CHANNEL = "canli"
        private const val ID = 3002
        private const val RATE = 44100
        const val ACTION_STOP = "com.serif.tvkumanda.CANLI_DURDUR"
        private const val EXTRA_CODE = "code"
        private const val EXTRA_DATA = "data"
        private const val EXTRA_SCREEN = "screen"
        private const val EXTRA_TV_IP = "tv_ip"

        /** Ekran yakalama izninin sonucuyla yayını başlatır. [screen] false ise yalnızca ses. */
        fun start(context: Context, resultCode: Int, data: Intent, screen: Boolean, tvIp: String) {
            LiveStream.reset(screen)
            val i = Intent(context, LiveService::class.java)
                .putExtra(EXTRA_CODE, resultCode).putExtra(EXTRA_DATA, data)
                .putExtra(EXTRA_SCREEN, screen).putExtra(EXTRA_TV_IP, tvIp)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i) else context.startService(i)
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, LiveService::class.java)) }
            if (LiveStream.phase != LiveStream.Phase.ERROR) LiveStream.phase = LiveStream.Phase.IDLE
        }
    }
}
