package com.serif.tvkumanda

import android.content.Context
import android.net.wifi.WifiManager
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.URL

/** Ağda bulunan bir DLNA (UPnP MediaRenderer) cihazı. */
data class DlnaDevice(
    val name: String,
    val ip: String,
    val location: String,
    val avControl: String,
    val rcControl: String?
) {
    val key: String get() = location
}

/**
 * DLNA istemcisi — ek kütüphane olmadan.
 *
 *  - Keşif: SSDP (239.255.255.250:1900) ile "MediaRenderer" aranır, cihaz tanım XML'i okunur.
 *  - Oynatma: AVTransport (SetAVTransportURI, Play, Pause, Stop, Seek, GetPositionInfo, GetTransportInfo)
 *  - Ses: RenderingControl (GetVolume, SetVolume)
 *
 * Tüm işlevler bloklayıcıdır; arka plan iş parçacığından çağrılmalıdır.
 */
class DlnaClient(private val context: Context) {

    /** Ağdaki DLNA oynatıcılarını arar (birkaç saniye sürer). */
    fun discover(timeoutMs: Int = 3500): List<DlnaDevice> {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val lock = wifi.createMulticastLock("TvKumanda:dlna").apply { setReferenceCounted(false) }
        val locations = LinkedHashSet<String>()
        try {
            lock.acquire()
            DatagramSocket().use { sock ->
                sock.soTimeout = 400
                val group = InetAddress.getByName("239.255.255.250")
                for (st in listOf("urn:schemas-upnp-org:device:MediaRenderer:1", "urn:schemas-upnp-org:service:AVTransport:1")) {
                    val msg = "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\nST: $st\r\n\r\n"
                    val b = msg.toByteArray()
                    repeat(2) { sock.send(DatagramPacket(b, b.size, group, 1900)) }
                }
                val buf = ByteArray(4096)
                val end = System.currentTimeMillis() + timeoutMs
                while (System.currentTimeMillis() < end) {
                    val p = DatagramPacket(buf, buf.size)
                    try { sock.receive(p) } catch (_: SocketTimeoutException) { continue }
                    val text = String(p.data, 0, p.length, Charsets.ISO_8859_1)
                    Regex("""(?im)^location:\s*(\S+)""").find(text)?.groupValues?.get(1)?.let { locations += it.trim() }
                }
            }
        } finally {
            runCatching { lock.release() }
        }
        return locations.mapNotNull { runCatching { describe(it) }.getOrNull() }.distinctBy { it.key }
    }

    /** Cihaz tanım XML'inden ad ve denetim adreslerini çıkarır. */
    private fun describe(location: String): DlnaDevice? {
        val xml = httpGet(location)
        val base = tag(xml, "URLBase")?.trimEnd('/') ?: URL(location).let { "${it.protocol}://${it.host}:${it.port}" }
        fun abs(u: String) = when {
            u.startsWith("http") -> u
            u.startsWith("/") -> URL(URL(base), u).toString()
            else -> "$base/$u"
        }
        var av: String? = null
        var rc: String? = null
        Regex("""<service>(.*?)</service>""", RegexOption.DOT_MATCHES_ALL).findAll(xml).forEach { m ->
            val sv = m.groupValues[1]
            val type = tag(sv, "serviceType").orEmpty()
            val ctl = tag(sv, "controlURL") ?: return@forEach
            if (type.contains("AVTransport")) av = abs(ctl)
            if (type.contains("RenderingControl")) rc = abs(ctl)
        }
        val avUrl = av ?: return null
        val name = tag(xml, "friendlyName")?.let(::unescape)?.ifBlank { null } ?: URL(location).host
        return DlnaDevice(name, URL(location).host, location, avUrl, rc)
    }

    // ---------------- Oynatma ----------------

    fun load(dev: DlnaDevice, url: String, mime: String, title: String, kind: MediaKind) {
        val cls = when (kind) {
            MediaKind.PHOTO -> "object.item.imageItem.photo"
            MediaKind.VIDEO -> "object.item.videoItem"
            MediaKind.MUSIC -> "object.item.audioItem.musicTrack"
        }
        val didl = """<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/">""" +
            """<item id="1" parentID="0" restricted="1"><dc:title>${escape(title)}</dc:title><upnp:class>$cls</upnp:class>""" +
            """<res protocolInfo="http-get:*:$mime:*">${escape(url)}</res></item></DIDL-Lite>"""
        // Bazı cihazlar yeni adres vermeden önce durdurulmayı ister
        runCatching { av(dev, "Stop") }
        av(dev, "SetAVTransportURI", "CurrentURI" to url, "CurrentURIMetaData" to didl)
        av(dev, "Play", "Speed" to "1")
    }

    fun play(dev: DlnaDevice) { av(dev, "Play", "Speed" to "1") }
    fun pause(dev: DlnaDevice) { av(dev, "Pause") }
    fun stop(dev: DlnaDevice) { av(dev, "Stop") }
    fun seek(dev: DlnaDevice, seconds: Double) { av(dev, "Seek", "Unit" to "REL_TIME", "Target" to hms(seconds)) }

    /** Oynatıcı durumu, CastClient.Status biçiminde (aynı ekran mantığı kullanılsın diye). */
    fun status(dev: DlnaDevice): CastClient.Status {
        val t = av(dev, "GetTransportInfo")
        val state = tag(t, "CurrentTransportState").orEmpty()
        val p = runCatching { av(dev, "GetPositionInfo") }.getOrDefault("")
        val pos = seconds(tag(p, "RelTime"))
        val dur = seconds(tag(p, "TrackDuration"))
        val ps = when (state) {
            "PLAYING" -> "PLAYING"
            "PAUSED_PLAYBACK", "PAUSED_RECORDING" -> "PAUSED"
            "TRANSITIONING" -> "BUFFERING"
            else -> "IDLE"
        }
        return CastClient.Status(ps, pos, dur, if (ps == "IDLE") state else "")
    }

    /** Ses düzeyi 0..1 (desteklenmiyorsa null). */
    fun volume(dev: DlnaDevice): Float? = runCatching {
        val r = rc(dev, "GetVolume", "Channel" to "Master") ?: return null
        tag(r, "CurrentVolume")?.toInt()?.div(100f)
    }.getOrNull()

    fun setVolume(dev: DlnaDevice, level: Float) {
        rc(dev, "SetVolume", "Channel" to "Master", "DesiredVolume" to (level.coerceIn(0f, 1f) * 100).toInt().toString())
    }

    // ---------------- SOAP ----------------

    private fun av(dev: DlnaDevice, action: String, vararg args: Pair<String, String>) =
        soap(dev.avControl, "urn:schemas-upnp-org:service:AVTransport:1", action, *args)

    private fun rc(dev: DlnaDevice, action: String, vararg args: Pair<String, String>): String? =
        dev.rcControl?.let { soap(it, "urn:schemas-upnp-org:service:RenderingControl:1", action, *args) }

    private fun soap(url: String, service: String, action: String, vararg args: Pair<String, String>): String {
        val body = StringBuilder()
            .append("""<?xml version="1.0" encoding="utf-8"?>""")
            .append("""<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body>""")
            .append("<u:$action xmlns:u=\"$service\"><InstanceID>0</InstanceID>")
        for ((k, v) in args) body.append("<$k>").append(escape(v)).append("</$k>")
        body.append("</u:$action></s:Body></s:Envelope>")
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 4000
            c.readTimeout = 6000
            c.requestMethod = "POST"
            c.doOutput = true
            c.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
            c.setRequestProperty("SOAPACTION", "\"$service#$action\"")
            c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
            if (code !in 200..299) {
                val err = tag(text, "errorDescription") ?: "HTTP $code"
                throw IOException("Cihaz isteği reddetti: $err")
            }
            return text
        } finally {
            c.disconnect()
        }
    }

    private fun httpGet(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 3000
            c.readTimeout = 4000
            return c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } finally {
            c.disconnect()
        }
    }

    companion object {
        /** XML içinden ad alanı ön ekinden bağımsız ilk etiketi okur. */
        fun tag(xml: String, name: String): String? =
            Regex("""<(?:\w+:)?$name(?:\s[^>]*)?>(.*?)</(?:\w+:)?$name>""", RegexOption.DOT_MATCHES_ALL)
                .find(xml)?.groupValues?.get(1)?.trim()

        fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
        fun unescape(s: String) = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&amp;", "&")

        fun hms(sec: Double): String {
            val t = sec.toLong().coerceAtLeast(0)
            return "%d:%02d:%02d".format(t / 3600, (t % 3600) / 60, t % 60)
        }

        /** "0:01:23" ya da "00:01:23.500" → saniye (geçersizse 0). */
        fun seconds(s: String?): Double {
            if (s.isNullOrBlank() || s == "NOT_IMPLEMENTED") return 0.0
            val parts = s.split(':')
            if (parts.size != 3) return 0.0
            return runCatching { parts[0].toDouble() * 3600 + parts[1].toDouble() * 60 + parts[2].toDouble() }.getOrDefault(0.0)
        }
    }
}
