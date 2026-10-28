package com.serif.tvkumanda

import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

/**
 * Google Cast (Chromecast) gönderici istemcisi — ek kütüphane olmadan.
 *
 * TV'deki yerleşik Chromecast'e 8009 portundan TLS ile bağlanır, "Default Media Receiver"
 * uygulamasını (CC1AD845) açar ve verilen adresteki medyayı oynatır.
 *
 * Mesajlar: 4 bayt (büyük uçlu) uzunluk + CastMessage protobuf'ı
 *   1 protocol_version (0 = CASTV2_1_0), 2 source_id, 3 destination_id, 4 namespace,
 *   5 payload_type (0 = metin), 6 payload_utf8 (JSON)
 *
 * Tüm işlevler bloklayıcıdır; arka plan iş parçacığından çağrılmalıdır.
 */
class CastClient {

    /** Oynatıcı durumu (TV'nin MEDIA_STATUS bildirimlerinden). */
    data class Status(
        val playerState: String,          // IDLE, BUFFERING, PLAYING, PAUSED
        val currentTime: Double,
        val duration: Double,
        val idleReason: String = ""        // FINISHED, ERROR, CANCELLED…
    )

    @Volatile var onStatus: ((Status) -> Unit)? = null
    @Volatile var onClosed: ((String) -> Unit)? = null
    /** Chromecast ses düzeyi (0..1) değiştiğinde çağrılır. */
    @Volatile var onVolume: ((Float) -> Unit)? = null

    private var socket: SSLSocket? = null
    private var out: OutputStream? = null
    private var transportId: String? = null
    /** TV'de açılan oynatıcı oturumu (kapatmak için). */
    @Volatile private var appSessionId: String? = null
    @Volatile private var mediaSessionId = 0
    private var requestId = 1
    private val waiters = HashMap<Int, java.util.concurrent.CompletableFuture<JSONObject>>()
    @Volatile private var lastPong = 0L

    val isConnected: Boolean get() = socket?.isClosed == false
    /** TV'de oynatıcı açık ve bağlı mı (açıksa yükleme anında başlar). */
    val hasReceiver: Boolean get() = isConnected && transportId != null
    @Volatile private var connectedIp = ""

    /** TV'ye bağlanır (zaten bağlıysa bir şey yapmaz). */
    @Synchronized
    fun connect(ip: String) {
        if (isConnected && connectedIp == ip) return
        if (isConnected) close()                      // başka bir TV'ye geçildi
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, arrayOf(TrustAll), null)   // Chromecast kendinden imzalı sertifika kullanır
        val raw = Socket()
        raw.connect(InetSocketAddress(ip, PORT), 5_000)
        raw.tcpNoDelay = true
        val s = ctx.socketFactory.createSocket(raw, ip, PORT, true) as SSLSocket
        s.soTimeout = 30_000
        s.startHandshake()
        socket = s
        connectedIp = ip
        out = BufferedOutputStream(s.outputStream)
        transportId = null
        appSessionId = null
        mediaSessionId = 0
        lastPong = System.currentTimeMillis()
        send(RECEIVER, NS_CONNECTION, JSONObject().put("type", "CONNECT"))
        val inp = DataInputStream(BufferedInputStream(s.inputStream))
        Thread({ readLoop(s, inp) }, "cast-reader").apply { isDaemon = true }.start()
        Thread({ heartbeat(s) }, "cast-ping").apply { isDaemon = true }.start()
    }

    /**
     * Medyayı TV'de açar. [url] TV'nin erişebileceği bir http adresidir (telefondaki sunucu).
     * Varsayılan medya alıcısı gerekirse başlatılır.
     */
    fun load(url: String, mime: String, title: String) {
        val app = prepareReceiver()
        val media = JSONObject()
            .put("contentId", url)
            .put("contentType", mime)
            .put("streamType", "BUFFERED")
            .put("metadata", JSONObject().put("metadataType", 0).put("title", title))
        val reply = request(app, NS_MEDIA, JSONObject()
            .put("type", "LOAD")
            .put("media", media)
            .put("autoplay", true)
            .put("currentTime", 0), timeoutMs = 20_000)
        when (reply.optString("type")) {
            "MEDIA_STATUS" -> handleMediaStatus(reply)
            "LOAD_FAILED" -> throw IOException("TV bu dosyayı açamadı (biçim desteklenmiyor olabilir)")
            "LOAD_CANCELLED" -> throw IOException("Oynatma iptal edildi")
            else -> throw IOException("TV yanıtı: ${reply.optString("type")}")
        }
    }

    fun play() = mediaCommand("PLAY")
    fun pause() = mediaCommand("PAUSE")
    fun stopMedia() = mediaCommand("STOP")

    /**
     * Yayını tamamen bitirir: medyayı durdurur ve TV'deki oynatıcı uygulamasını kapatır,
     * böylece TV'de Chromecast bekleme ekranı kalmaz (telefonun bildirimindeki "durdur" gibi).
     */
    fun stopApp() {
        runCatching { if (mediaSessionId != 0) mediaCommand("STOP") }
        val sid = appSessionId
        if (sid != null) {
            runCatching { send(RECEIVER, NS_RECEIVER, JSONObject().put("type", "STOP").put("sessionId", sid).put("requestId", nextId())) }
        }
        transportId = null
        appSessionId = null
        mediaSessionId = 0
    }
    fun seek(seconds: Double) = mediaCommand("SEEK", JSONObject().put("currentTime", seconds))

    /** TV'nin (Chromecast'in) ses düzeyi, 0..1. */
    fun setVolume(level: Float) {
        send(RECEIVER, NS_RECEIVER, JSONObject()
            .put("type", "SET_VOLUME")
            .put("volume", JSONObject().put("level", level.coerceIn(0f, 1f).toDouble()))
            .put("requestId", nextId()))
    }

    /** Oynatıcı durumunu ister (cevap [onStatus] ile gelir). */
    fun refresh() {
        val app = transportId ?: return
        if (mediaSessionId == 0) return
        runCatching { send(app, NS_MEDIA, JSONObject().put("type", "GET_STATUS").put("requestId", nextId())) }
    }

    @Synchronized
    fun close() {
        runCatching { socket?.close() }
        socket = null
        out = null
        transportId = null
        mediaSessionId = 0
        synchronized(waiters) {
            waiters.values.forEach { it.completeExceptionally(IOException("Bağlantı kapandı")) }
            waiters.clear()
        }
    }

    // ---------------- İç işler ----------------

    private fun mediaCommand(type: String, extra: JSONObject = JSONObject()) {
        val app = transportId ?: throw IOException("Oynatılan bir şey yok")
        extra.put("type", type).put("mediaSessionId", mediaSessionId)
        send(app, NS_MEDIA, extra.put("requestId", nextId()))
    }

    /**
     * Varsayılan medya alıcısını başlatır (açıksa TV hemen cevap verir) ve onun bağlantı
     * kimliğini döndürür. Önce "açık mı?" diye ayrıca sorulmaz; bir gidiş-dönüş kazanılır.
     */
    fun prepareReceiver(): String {
        transportId?.let { return it }
        val app = findApp(request(RECEIVER, NS_RECEIVER,
            JSONObject().put("type", "LAUNCH").put("appId", APP_ID), timeoutMs = 20_000))
            ?: throw IOException("TV'de oynatıcı başlatılamadı")
        send(app, NS_CONNECTION, JSONObject().put("type", "CONNECT"))
        transportId = app
        return app
    }

    private fun findApp(reply: JSONObject): String? {
        if (reply.optString("type") == "LAUNCH_ERROR") throw IOException("TV oynatıcıyı açmadı: ${reply.optString("reason")}")
        val apps = reply.optJSONObject("status")?.optJSONArray("applications") ?: return null
        for (i in 0 until apps.length()) {
            val a = apps.getJSONObject(i)
            if (a.optString("appId") == APP_ID) {
                a.optString("sessionId").ifEmpty { null }?.let { appSessionId = it }
                return a.optString("transportId").ifEmpty { null }
            }
        }
        return null
    }

    private fun nextId(): Int = synchronized(waiters) { requestId++ }

    /** İstek gönderir ve aynı requestId'li cevabı bekler. */
    private fun request(dest: String, ns: String, msg: JSONObject, timeoutMs: Long = 8_000): JSONObject {
        val id = nextId()
        val f = java.util.concurrent.CompletableFuture<JSONObject>()
        synchronized(waiters) { waiters[id] = f }
        try {
            send(dest, ns, msg.put("requestId", id))
            return try {
                f.get(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
            } catch (e: java.util.concurrent.TimeoutException) {
                throw IOException("TV yanıt vermedi")
            } catch (e: java.util.concurrent.ExecutionException) {
                throw (e.cause as? IOException) ?: IOException(e.cause?.message ?: "Cast hatası")
            }
        } finally {
            synchronized(waiters) { waiters.remove(id) }
        }
    }

    private fun send(dest: String, ns: String, payload: JSONObject) {
        val msg = Pb()
            .int(1, 0)
            .str(2, SENDER)
            .str(3, dest)
            .str(4, ns)
            .int(5, 0)
            .str(6, payload.toString())
            .build()
        val o = out ?: throw IOException("Cast bağlantısı yok")
        synchronized(o) { writeCastFrame(o, msg) }
    }

    private fun readLoop(s: SSLSocket, inp: DataInputStream) {
        var reason = "Bağlantı kapandı"
        try {
            while (true) handle(PbMsg.parse(readCastFrame(inp)))
        } catch (e: Exception) {
            if (e.message != null) reason = e.message!!
        }
        if (socket === s) {
            close()
            onClosed?.invoke(reason)
        }
    }

    private fun heartbeat(s: SSLSocket) {
        while (socket === s) {
            runCatching { send(RECEIVER, NS_HEARTBEAT, JSONObject().put("type", "PING")) }
            Thread.sleep(5_000)
            if (System.currentTimeMillis() - lastPong > 20_000) { runCatching { s.close() }; return }
        }
    }

    private fun handle(m: PbMsg) {
        val ns = m.str(4)
        val json = runCatching { JSONObject(m.str(6)) }.getOrNull() ?: return
        when (json.optString("type")) {
            "PING" -> runCatching { send(m.str(2), NS_HEARTBEAT, JSONObject().put("type", "PONG")) }
            "PONG" -> lastPong = System.currentTimeMillis()
            "CLOSE" -> if (m.str(2) == transportId) { transportId = null; mediaSessionId = 0 }
        }
        lastPong = System.currentTimeMillis()
        if (ns == NS_MEDIA && json.optString("type") == "MEDIA_STATUS") handleMediaStatus(json)
        if (json.optString("type") == "RECEIVER_STATUS") {
            json.optJSONObject("status")?.optJSONObject("volume")?.let { v ->
                if (v.has("level")) onVolume?.invoke(v.optDouble("level", 0.0).toFloat())
            }
        }
        val id = json.optInt("requestId", 0)
        if (id != 0) synchronized(waiters) { waiters[id] }?.complete(json)
    }

    private fun handleMediaStatus(json: JSONObject) {
        val arr = json.optJSONArray("status") ?: return
        if (arr.length() == 0) return
        val st = arr.getJSONObject(0)
        st.optInt("mediaSessionId", 0).takeIf { it != 0 }?.let { mediaSessionId = it }
        val duration = st.optJSONObject("media")?.optDouble("duration", 0.0) ?: lastDuration
        if (duration > 0) lastDuration = duration
        onStatus?.invoke(Status(
            st.optString("playerState", "IDLE"),
            st.optDouble("currentTime", 0.0),
            lastDuration,
            st.optString("idleReason", "")
        ))
    }

    @Volatile private var lastDuration = 0.0

    private object TrustAll : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    }

    companion object {
        const val PORT = 8009
        const val APP_ID = "CC1AD845"   // Default Media Receiver
        const val SENDER = "sender-0"
        const val RECEIVER = "receiver-0"
        const val NS_CONNECTION = "urn:x-cast:com.google.cast.tp.connection"
        const val NS_HEARTBEAT = "urn:x-cast:com.google.cast.tp.heartbeat"
        const val NS_RECEIVER = "urn:x-cast:com.google.cast.receiver"
        const val NS_MEDIA = "urn:x-cast:com.google.cast.media"
    }
}

/** Cast çerçevesi: 4 bayt büyük uçlu uzunluk + içerik. */
fun writeCastFrame(out: OutputStream, payload: ByteArray) {
    val n = payload.size
    out.write(byteArrayOf((n ushr 24).toByte(), (n ushr 16).toByte(), (n ushr 8).toByte(), n.toByte()))
    out.write(payload)
    out.flush()
}

fun readCastFrame(inp: InputStream): ByteArray {
    val d = if (inp is DataInputStream) inp else DataInputStream(inp)
    val n = d.readInt()
    if (n < 0 || n > 2_000_000) throw IOException("Geçersiz Cast çerçevesi")
    val buf = ByteArray(n)
    d.readFully(buf)
    return buf
}
