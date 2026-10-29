package com.serif.tvkumanda

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.FileInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap

/**
 * Telefonu ağda bir DLNA/UPnP medya sunucusuna (MediaServer:1) çevirir — ek kütüphane olmadan.
 *
 *  - Keşif: SSDP (239.255.255.250:1900) üzerinden M-SEARCH sorularına cevap verir, düzenli "alive" duyurur.
 *  - Tanım: /desc.xml (cihaz), /cds.xml ve /cms.xml (hizmet tanımları).
 *  - ContentDirectory: TV "bu klasörde ne var?" diye sorduğunda (Browse) galeriden o an okunan
 *    klasör ve dosyaları DIDL-Lite olarak verir.
 *  - Akış: /m/<tür>/<id> dosyanın kendisi (ileri-geri sarma için Range desteği), /t/... küçük resim.
 *
 * Yalnızca kullanıcının paylaştığı klasörlerdeki dosyalar sunulur.
 */
class DlnaMediaServer(private val context: Context, private val library: MediaLibrary) {

    @Volatile var lastActivity = System.currentTimeMillis(); private set
    @Volatile private var running = false
    private var http: ServerSocket? = null
    private var ssdp: MulticastSocket? = null
    private var host = ""
    private var port = 0
    val address: String get() = if (port > 0) "$host:$port" else ""

    private val uuid: String by lazy {
        val p = DlnaServerPrefs.prefs(context)
        p.getString("uuid", null) ?: java.util.UUID.randomUUID().toString().also { p.edit().putString("uuid", it).apply() }
    }

    // ---------------- Başlat / durdur ----------------

    fun start() {
        host = localIp() ?: throw java.io.IOException("Wi-Fi bağlantısı bulunamadı")
        val srv = runCatching { ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(FIXED_PORT)) } }
            .getOrElse { ServerSocket(0) }
        http = srv
        port = srv.localPort
        running = true
        Thread({ acceptLoop(srv) }, "dlna-http").apply { isDaemon = true }.start()

        val m = MulticastSocket(null).apply {
            reuseAddress = true
            bind(InetSocketAddress(1900))
            timeToLive = 4
        }
        val group = InetAddress.getByName(SSDP_ADDR)
        val nif = NetworkInterface.getByInetAddress(InetAddress.getByName(host))
        runCatching { if (nif != null) m.joinGroup(InetSocketAddress(group, 1900), nif) else @Suppress("DEPRECATION") m.joinGroup(group) }
        ssdp = m
        Thread({ ssdpLoop(m) }, "dlna-ssdp").apply { isDaemon = true }.start()
        Thread({ announceLoop() }, "dlna-notify").apply { isDaemon = true }.start()
    }

    fun stop() {
        if (!running) return
        running = false
        runCatching { notify("ssdp:byebye") }
        runCatching { ssdp?.close() }
        runCatching { http?.close() }
        ssdp = null; http = null; port = 0
    }

    /** Ad değişince TV'lerin yeniden okuması için duyuru tekrarlanır. */
    fun reannounce() { if (running) Thread { runCatching { notify("ssdp:byebye"); Thread.sleep(300); notify("ssdp:alive") } }.start() }

    // ---------------- SSDP ----------------

    private val types: List<String> get() = listOf(
        "upnp:rootdevice", "uuid:$uuid", DEVICE_TYPE, CDS_TYPE, CMS_TYPE
    )

    private fun usn(nt: String) = if (nt.startsWith("uuid:")) nt else "uuid:$uuid::$nt"

    private fun ssdpLoop(m: MulticastSocket) {
        val buf = ByteArray(2048)
        while (running) {
            val p = DatagramPacket(buf, buf.size)
            try { m.receive(p) } catch (_: Exception) { if (!running) break else continue }
            val text = String(p.data, 0, p.length, Charsets.ISO_8859_1)
            if (!text.startsWith("M-SEARCH", ignoreCase = true)) continue
            val st = Regex("""(?im)^st:\s*(.+?)\s*$""").find(text)?.groupValues?.get(1) ?: continue
            val replies = when {
                st == "ssdp:all" -> types
                st in types -> listOf(st)
                else -> emptyList()
            }
            if (replies.isEmpty()) continue
            val to = p.socketAddress
            Thread {
                runCatching {
                    Thread.sleep((50..400).random().toLong())
                    for (t in replies) {
                        val msg = "HTTP/1.1 200 OK\r\nCACHE-CONTROL: max-age=1800\r\nDATE: ${httpDate()}\r\nEXT:\r\n" +
                            "LOCATION: http://$host:$port/desc.xml\r\nSERVER: $SERVER\r\nST: $t\r\nUSN: ${usn(t)}\r\n\r\n"
                        val b = msg.toByteArray(Charsets.ISO_8859_1)
                        java.net.DatagramSocket().use { s -> s.send(DatagramPacket(b, b.size, to)) }
                    }
                }
            }.start()
        }
    }

    private fun announceLoop() {
        var n = 0
        while (running) {
            runCatching { notify("ssdp:alive") }
            // İlk saniyelerde sık, sonra dakikada bir
            Thread.sleep(if (n++ < 3) 2000 else 60_000)
        }
    }

    private fun notify(nts: String) {
        val group = InetAddress.getByName(SSDP_ADDR)
        val s = ssdp ?: return
        for (t in types) {
            val msg = buildString {
                append("NOTIFY * HTTP/1.1\r\nHOST: $SSDP_ADDR:1900\r\n")
                if (nts == "ssdp:alive") append("CACHE-CONTROL: max-age=1800\r\nLOCATION: http://$host:$port/desc.xml\r\nSERVER: $SERVER\r\n")
                append("NT: $t\r\nNTS: $nts\r\nUSN: ${usn(t)}\r\n\r\n")
            }
            val b = msg.toByteArray(Charsets.ISO_8859_1)
            s.send(DatagramPacket(b, b.size, group, 1900))
        }
    }

    // ---------------- HTTP ----------------

    private fun acceptLoop(srv: ServerSocket) {
        while (running) {
            val c = runCatching { srv.accept() }.getOrNull() ?: break
            Thread({ runCatching { serve(c) }; runCatching { c.close() } }, "dlna-client").apply { isDaemon = true }.start()
        }
    }

    private class Request(val method: String, val path: String, val headers: Map<String, String>, val body: String)

    private fun readRequest(input: InputStream): Request? {
        // Başlıklar satır satır, gövde Content-Length kadar okunur
        val head = ByteArrayOutputStream()
        var last4 = 0
        while (true) {
            val b = input.read()
            if (b < 0) return null
            head.write(b)
            last4 = (last4 shl 8) or b
            if (last4 == 0x0D0A0D0A) break
            if (head.size() > 32 * 1024) return null
        }
        val lines = head.toString("ISO-8859-1").split("\r\n")
        val parts = lines.first().split(' ')
        val headers = HashMap<String, String>()
        for (l in lines.drop(1)) {
            val i = l.indexOf(':')
            if (i > 0) headers[l.substring(0, i).trim().lowercase()] = l.substring(i + 1).trim()
        }
        val len = headers["content-length"]?.toIntOrNull() ?: 0
        val body = if (len > 0) {
            val buf = ByteArray(len.coerceAtMost(1 shl 20))
            var read = 0
            while (read < buf.size) { val n = input.read(buf, read, buf.size - read); if (n < 0) break; read += n }
            String(buf, 0, read, Charsets.UTF_8)
        } else ""
        return Request(parts.getOrElse(0) { "" }, parts.getOrElse(1) { "/" }, headers, body)
    }

    private fun serve(c: Socket) {
        c.soTimeout = 30_000
        val input = BufferedInputStream(c.getInputStream())
        val out = c.getOutputStream()
        val req = readRequest(input) ?: return
        lastActivity = System.currentTimeMillis()
        val path = req.path.substringBefore('?')
        when {
            path == "/desc.xml" -> sendXml(out, req, deviceXml())
            path == "/cds.xml" -> sendXml(out, req, CDS_SCPD)
            path == "/cms.xml" -> sendXml(out, req, CMS_SCPD)
            path == "/ctl/cds" && req.method == "POST" -> sendXml(out, req, contentDirectory(req))
            path == "/ctl/cms" && req.method == "POST" -> sendXml(out, req, connectionManager(req))
            path.startsWith("/evt/") -> {
                // Olay aboneliği: içerik değişmediği için yalnızca kabul edilir
                if (req.method == "SUBSCRIBE") writeHead(out, "200 OK", mapOf(
                    "SID" to (req.headers["sid"] ?: "uuid:${java.util.UUID.randomUUID()}"),
                    "TIMEOUT" to "Second-1800", "Content-Length" to "0"))
                else writeHead(out, "200 OK", mapOf("Content-Length" to "0"))
            }
            path.startsWith("/m/") -> serveMedia(out, req, path)
            path.startsWith("/t/") -> serveThumb(out, req, path)
            else -> writeHead(out, "404 Not Found", mapOf("Content-Length" to "0"))
        }
    }

    private fun sendXml(out: OutputStream, req: Request, xml: String) {
        val b = xml.toByteArray(Charsets.UTF_8)
        writeHead(out, "200 OK", mapOf("Content-Type" to "text/xml; charset=\"utf-8\"", "Content-Length" to b.size.toString()))
        if (req.method != "HEAD") { out.write(b); out.flush() }
    }

    private fun writeHead(out: OutputStream, status: String, headers: Map<String, String>) {
        val sb = StringBuilder("HTTP/1.1 ").append(status).append("\r\n")
        sb.append("Server: $SERVER\r\nDate: ${httpDate()}\r\nConnection: close\r\n")
        for ((k, v) in headers) sb.append(k).append(": ").append(v).append("\r\n")
        sb.append("\r\n")
        out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
        out.flush()
    }

    // ---------------- Cihaz tanımı ----------------

    private fun deviceXml(): String {
        val name = escape(DlnaServerPrefs.name(context))
        return """<?xml version="1.0" encoding="utf-8"?>
<root xmlns="urn:schemas-upnp-org:device-1-0" xmlns:dlna="urn:schemas-dlna-org:device-1-0">
<specVersion><major>1</major><minor>0</minor></specVersion>
<device>
<deviceType>$DEVICE_TYPE</deviceType>
<friendlyName>$name</friendlyName>
<manufacturer>TV Kumanda</manufacturer>
<modelName>TV Kumanda DLNA Sunucu</modelName>
<modelDescription>Telefondaki fotoğraf, video ve müzikler</modelDescription>
<modelNumber>1</modelNumber>
<serialNumber>$uuid</serialNumber>
<UDN>uuid:$uuid</UDN>
<dlna:X_DLNADOC>DMS-1.50</dlna:X_DLNADOC>
<presentationURL>/</presentationURL>
<serviceList>
<service><serviceType>$CDS_TYPE</serviceType><serviceId>urn:upnp-org:serviceId:ContentDirectory</serviceId><SCPDURL>/cds.xml</SCPDURL><controlURL>/ctl/cds</controlURL><eventSubURL>/evt/cds</eventSubURL></service>
<service><serviceType>$CMS_TYPE</serviceType><serviceId>urn:upnp-org:serviceId:ConnectionManager</serviceId><SCPDURL>/cms.xml</SCPDURL><controlURL>/ctl/cms</controlURL><eventSubURL>/evt/cms</eventSubURL></service>
</serviceList>
</device>
</root>"""
    }

    // ---------------- SOAP ----------------

    private fun soapAction(req: Request): String =
        req.headers["soapaction"]?.trim('"')?.substringAfter('#')?.ifBlank { null }
            ?: Regex("""<(?:\w+:)?(\w+)[^>]*xmlns(?::\w+)?="urn:schemas-upnp-org:service""").find(req.body)?.groupValues?.get(1)
            ?: ""

    private fun envelope(service: String, action: String, inner: String) =
        """<?xml version="1.0" encoding="utf-8"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body>""" +
            """<u:${action}Response xmlns:u="$service">$inner</u:${action}Response></s:Body></s:Envelope>"""

    private fun connectionManager(req: Request): String = when (soapAction(req)) {
        "GetProtocolInfo" -> envelope(CMS_TYPE, "GetProtocolInfo",
            "<Source>${escape(PROTOCOLS.joinToString(","))}</Source><Sink></Sink>")
        "GetCurrentConnectionIDs" -> envelope(CMS_TYPE, "GetCurrentConnectionIDs", "<ConnectionIDs>0</ConnectionIDs>")
        else -> envelope(CMS_TYPE, "GetCurrentConnectionInfo",
            "<RcsID>-1</RcsID><AVTransportID>-1</AVTransportID><ProtocolInfo></ProtocolInfo><PeerConnectionManager></PeerConnectionManager><PeerConnectionID>-1</PeerConnectionID><Direction>Output</Direction><Status>OK</Status>")
    }

    private fun contentDirectory(req: Request): String = when (val a = soapAction(req)) {
        "Browse" -> browse(req.body)
        "GetSearchCapabilities" -> envelope(CDS_TYPE, a, "<SearchCaps></SearchCaps>")
        "GetSortCapabilities" -> envelope(CDS_TYPE, a, "<SortCaps></SortCaps>")
        "GetSystemUpdateID" -> envelope(CDS_TYPE, a, "<Id>1</Id>")
        // Samsung TV'lerin sorduğu özellik listesi
        "X_GetFeatureList" -> envelope(CDS_TYPE, a, "<FeatureList>${escape("<Features xmlns=\"urn:schemas-upnp-org:av:avs\"></Features>")}</FeatureList>")
        else -> envelope(CDS_TYPE, a.ifBlank { "Browse" }, "")
    }

    // ---------------- İçerik ağacı ----------------

    /** Ağaçtaki bir düğüm: klasör ya da dosya. */
    private sealed class Node(val id: String, val title: String) {
        class Folder(id: String, title: String, val children: () -> List<Node>) : Node(id, title)
        class File(id: String, title: String, val item: MediaItem) : Node(id, title)
    }

    // Galeri sorguları kısa süre önbelleklenir (TV sayfa sayfa sorarken her seferinde yeniden okunmasın)
    private val albumCache = ConcurrentHashMap<MediaKind, Pair<Long, List<MediaAlbum>>>()

    private fun albums(kind: MediaKind): List<MediaAlbum> {
        if (!library.hasPermission(kind)) return emptyList()
        albumCache[kind]?.let { (t, v) -> if (System.currentTimeMillis() - t < 15_000) return v }
        val v = runCatching { library.albums(kind) }.getOrDefault(emptyList()).filter { it.id != Long.MIN_VALUE }
        albumCache[kind] = System.currentTimeMillis() to v
        return v
    }

    private fun allItems(kind: MediaKind) = albums(kind).flatMap { it.items }.sortedByDescending { it.id }

    private fun fileNodes(parent: String, items: List<MediaItem>) =
        items.map { Node.File("$parent/${it.kind.name[0]}${it.id}", displayTitle(it), it) }

    private fun displayTitle(i: MediaItem) =
        if (i.kind == MediaKind.MUSIC) i.title.ifBlank { "Parça" } else i.title.substringBeforeLast('.').ifBlank { i.title }

    private fun rootChildren(): List<Node> {
        val p = DlnaServerPrefs
        val out = ArrayList<Node>()
        if (p.allEnabled(context, MediaKind.PHOTO)) out += Node.Folder("ap", "Tüm fotoğraflar") { fileNodes("ap", allItems(MediaKind.PHOTO)) }
        if (p.allEnabled(context, MediaKind.VIDEO)) out += Node.Folder("av", "Tüm videolar") { fileNodes("av", allItems(MediaKind.VIDEO)) }
        if (p.allEnabled(context, MediaKind.MUSIC)) out += Node.Folder("am", "Tüm müzikler") { fileNodes("am", allItems(MediaKind.MUSIC)) }
        if (p.musicGroups(context) && library.hasPermission(MediaKind.MUSIC)) {
            out += Node.Folder("mal", "Müzik · Albümler") {
                albums(MediaKind.MUSIC).map { a -> Node.Folder("mal/${a.id}", a.name) { fileNodes("mal/${a.id}", a.items) } }
            }
            out += Node.Folder("mar", "Müzik · Sanatçılar") {
                allItems(MediaKind.MUSIC).groupBy { it.subtitle.ifBlank { "Bilinmeyen sanatçı" } }.toSortedMap(String.CASE_INSENSITIVE_ORDER)
                    .map { (artist, list) ->
                        val key = "mar/" + android.util.Base64.encodeToString(artist.toByteArray(), android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING)
                        Node.Folder(key, artist) { fileNodes(key, list.sortedBy { it.title.lowercase() }) }
                    }
            }
        }
        for (s in p.albums(context)) {
            val a = albums(s.kind).firstOrNull { it.id == s.bucketId } ?: continue
            val id = "f${s.kind.name[0]}${s.bucketId}"
            out += Node.Folder(id, a.name) { fileNodes(id, a.items) }
        }
        return out
    }

    /** Kimliği verilen düğümü ağaçta bulur (kimlik yol biçimindedir: klasör/altklasör/dosya). */
    private fun find(id: String): Node? {
        var node: Node = Node.Folder("0", "Kök") { rootChildren() }
        if (id == "0" || id.isBlank()) return node
        var cur = ""
        for (s in id.split('/')) {
            cur = if (cur.isEmpty()) s else "$cur/$s"
            val folder = node as? Node.Folder ?: return null
            node = folder.children().firstOrNull { it.id == cur } ?: return null
        }
        return node
    }

    private fun parentOf(id: String): String = when {
        id == "0" -> "-1"
        id.contains('/') -> id.substringBeforeLast('/')
        else -> "0"
    }

    private fun browse(body: String): String {
        val objectId = DlnaClient.unescape(DlnaClient.tag(body, "ObjectID") ?: "0")
        val flag = DlnaClient.tag(body, "BrowseFlag") ?: "BrowseDirectChildren"
        val start = DlnaClient.tag(body, "StartingIndex")?.toIntOrNull() ?: 0
        val count = DlnaClient.tag(body, "RequestedCount")?.toIntOrNull() ?: 0
        val node = find(objectId)
        val didl = StringBuilder(DIDL_OPEN)
        var returned = 0
        var total = 0
        if (node != null) {
            if (flag == "BrowseMetadata") {
                didl.append(nodeXml(node, parentOf(objectId)))
                returned = 1; total = 1
            } else if (node is Node.Folder) {
                val kids = node.children()
                total = kids.size
                val page = kids.drop(start).let { if (count > 0) it.take(count) else it }
                page.forEach { didl.append(nodeXml(it, objectId)) }
                returned = page.size
            }
        }
        didl.append("</DIDL-Lite>")
        return envelope(CDS_TYPE, "Browse",
            "<Result>${escape(didl.toString())}</Result><NumberReturned>$returned</NumberReturned><TotalMatches>$total</TotalMatches><UpdateID>1</UpdateID>")
    }

    private fun nodeXml(n: Node, parent: String): String = when (n) {
        is Node.Folder -> {
            val count = if (n.id == "0") rootChildren().size else -1
            """<container id="${escape(n.id)}" parentID="${escape(parent)}" restricted="1" searchable="0"${if (count >= 0) " childCount=\"$count\"" else ""}>""" +
                "<dc:title>${escape(n.title)}</dc:title><upnp:class>object.container.storageFolder</upnp:class></container>"
        }
        is Node.File -> itemXml(n, parent)
    }

    private fun itemXml(n: Node.File, parent: String): String {
        val i = n.item
        val mime = i.mime.ifBlank { i.kind.fallbackMime }
        val ext = android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: "bin"
        val url = "http://$host:$port/m/${i.kind.name}/${i.id}.$ext"
        val cls = when (i.kind) {
            MediaKind.PHOTO -> "object.item.imageItem.photo"
            MediaKind.VIDEO -> "object.item.videoItem"
            MediaKind.MUSIC -> "object.item.audioItem.musicTrack"
        }
        val flags = "DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000"
        val sb = StringBuilder()
        sb.append("""<item id="${escape(n.id)}" parentID="${escape(parent)}" restricted="1">""")
        sb.append("<dc:title>${escape(n.title)}</dc:title><upnp:class>$cls</upnp:class>")
        if (i.kind == MediaKind.MUSIC && i.subtitle.isNotBlank()) {
            sb.append("<upnp:artist>${escape(i.subtitle)}</upnp:artist><dc:creator>${escape(i.subtitle)}</dc:creator>")
        }
        val thumbs = DlnaServerPrefs.thumbnails(context)
        val thumbUrl = "http://$host:$port/t/${i.kind.name}/${i.id}_${i.albumId}.jpg"
        if (thumbs) sb.append("""<upnp:albumArtURI dlna:profileID="JPEG_TN">${escape(thumbUrl)}</upnp:albumArtURI>""")
        val dur = if (i.durationMs > 0) """ duration="${hms(i.durationMs)}"""" else ""
        sb.append("""<res protocolInfo="http-get:*:$mime:$flags"$dur>${escape(url)}</res>""")
        if (thumbs && i.kind != MediaKind.MUSIC) {
            sb.append("""<res protocolInfo="http-get:*:image/jpeg:DLNA.ORG_PN=JPEG_TN;DLNA.ORG_OP=00;DLNA.ORG_CI=1">${escape(thumbUrl)}</res>""")
        }
        sb.append("</item>")
        return sb.toString()
    }

    // ---------------- Dosya ve küçük resim sunma ----------------

    private fun baseUri(kind: MediaKind): Uri = when (kind) {
        MediaKind.PHOTO -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        MediaKind.VIDEO -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        MediaKind.MUSIC -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
    }

    /** Yalnızca paylaşılan klasörlerdeki dosyalar sunulur. */
    private fun sharedItem(kind: MediaKind, id: Long): MediaItem? {
        val p = DlnaServerPrefs
        val inAll = p.allEnabled(context, kind) || (kind == MediaKind.MUSIC && p.musicGroups(context))
        val all = albums(kind)
        return if (inAll) all.firstNotNullOfOrNull { a -> a.items.firstOrNull { it.id == id } }
        else {
            val shared = p.albums(context).filter { it.kind == kind }.map { it.bucketId }.toSet()
            all.filter { it.id in shared }.firstNotNullOfOrNull { a -> a.items.firstOrNull { it.id == id } }
        }
    }

    private fun parseMediaPath(path: String, prefix: String): Pair<MediaKind, String>? {
        val rest = path.removePrefix(prefix)
        val kind = runCatching { MediaKind.valueOf(rest.substringBefore('/')) }.getOrNull() ?: return null
        return kind to rest.substringAfter('/').substringBefore('.')
    }

    private fun serveMedia(out: OutputStream, req: Request, path: String) {
        val (kind, idText) = parseMediaPath(path, "/m/") ?: return notFound(out)
        val id = idText.toLongOrNull() ?: return notFound(out)
        val item = sharedItem(kind, id) ?: return notFound(out)
        val uri = ContentUris.withAppendedId(baseUri(kind), id)
        val mime = item.mime.ifBlank { kind.fallbackMime }
        val afd = runCatching { context.contentResolver.openAssetFileDescriptor(uri, "r") }.getOrNull() ?: return notFound(out)
        afd.use {
            val total = afd.length.takeIf { it >= 0 } ?: afd.parcelFileDescriptor.statSize
            var start = 0L
            var end = total - 1
            var partial = false
            req.headers["range"]?.let { r ->
                Regex("""bytes=(\d*)-(\d*)""").find(r)?.let { m ->
                    val a = m.groupValues[1]; val b = m.groupValues[2]
                    if (a.isNotEmpty()) { start = a.toLong(); if (b.isNotEmpty()) end = minOf(b.toLong(), total - 1) }
                    else if (b.isNotEmpty()) start = maxOf(0, total - b.toLong())
                    partial = true
                }
            }
            if (total <= 0 || start > end) {
                writeHead(out, "416 Range Not Satisfiable", mapOf("Content-Range" to "bytes */$total", "Content-Length" to "0"))
                return
            }
            val len = end - start + 1
            val h = linkedMapOf(
                "Content-Type" to mime,
                "Content-Length" to len.toString(),
                "Accept-Ranges" to "bytes",
                "transferMode.dlna.org" to if (kind == MediaKind.PHOTO) "Interactive" else "Streaming",
                "contentFeatures.dlna.org" to "DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000"
            )
            if (partial) h["Content-Range"] = "bytes $start-$end/$total"
            writeHead(out, if (partial) "206 Partial Content" else "200 OK", h)
            if (req.method == "HEAD") return
            FileInputStream(afd.fileDescriptor).use { fin ->
                fin.channel.position(afd.startOffset + start)
                val buf = ByteArray(64 * 1024)
                var left = len
                while (left > 0) {
                    val n = fin.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                    if (n <= 0) break
                    out.write(buf, 0, n)
                    left -= n
                    lastActivity = System.currentTimeMillis()
                }
                out.flush()
            }
        }
    }

    private val thumbCache = object : android.util.LruCache<String, ByteArray>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ByteArray) = value.size
    }

    private fun serveThumb(out: OutputStream, req: Request, path: String) {
        if (!DlnaServerPrefs.thumbnails(context)) return notFound(out)
        val (kind, rest) = parseMediaPath(path, "/t/") ?: return notFound(out)
        val id = rest.substringBefore('_').toLongOrNull() ?: return notFound(out)
        val albumId = rest.substringAfter('_', "0").toLongOrNull() ?: 0
        val key = "$kind:$id"
        val bytes = thumbCache.get(key) ?: run {
            val item = MediaItem(id, ContentUris.withAppendedId(baseUri(kind), id), "", "", "", kind, albumId = albumId)
            val bmp = library.thumbnail(item, 320) ?: return notFound(out)
            ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()
                .also { thumbCache.put(key, it) }
        }
        writeHead(out, "200 OK", mapOf(
            "Content-Type" to "image/jpeg", "Content-Length" to bytes.size.toString(),
            "transferMode.dlna.org" to "Interactive",
            "contentFeatures.dlna.org" to "DLNA.ORG_PN=JPEG_TN;DLNA.ORG_OP=00;DLNA.ORG_CI=1"))
        if (req.method != "HEAD") { out.write(bytes); out.flush() }
    }

    private fun notFound(out: OutputStream) = writeHead(out, "404 Not Found", mapOf("Content-Length" to "0"))

    // ---------------- Yardımcılar ----------------

    private fun localIp(): String? = runCatching {
        val all = NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
        val addrs = all.sortedBy { if (it.name.startsWith("wlan")) 0 else if (it.name.startsWith("eth")) 1 else 2 }
            .flatMap { n -> n.inetAddresses.toList().filterIsInstance<Inet4Address>().map { n to it } }
            .filter { it.second.isSiteLocalAddress }
        addrs.firstOrNull()?.second?.hostAddress
    }.getOrNull()

    private fun httpDate(): String = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("GMT") }.format(Date())

    private fun hms(ms: Long): String {
        val t = ms / 1000
        return "%d:%02d:%02d.%03d".format(t / 3600, (t % 3600) / 60, t % 60, ms % 1000)
    }

    private fun escape(s: String) = DlnaClient.escape(s).replace("'", "&apos;")

    companion object {
        private const val FIXED_PORT = 8195
        private const val SSDP_ADDR = "239.255.255.250"
        private const val SERVER = "Android/1 UPnP/1.0 TVKumanda/1.0"
        private const val DEVICE_TYPE = "urn:schemas-upnp-org:device:MediaServer:1"
        private const val CDS_TYPE = "urn:schemas-upnp-org:service:ContentDirectory:1"
        private const val CMS_TYPE = "urn:schemas-upnp-org:service:ConnectionManager:1"

        private const val DIDL_OPEN = """<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/" xmlns:dlna="urn:schemas-dlna-org:metadata-1-0/">"""

        private val PROTOCOLS = listOf(
            "image/jpeg", "image/png", "image/gif", "image/webp", "image/heic",
            "video/mp4", "video/x-matroska", "video/webm", "video/3gpp", "video/quicktime", "video/x-msvideo", "video/mpeg",
            "audio/mpeg", "audio/mp4", "audio/aac", "audio/flac", "audio/ogg", "audio/x-wav", "audio/wav"
        ).map { "http-get:*:$it:*" }

        private fun scpd(actions: String, vars: String) = """<?xml version="1.0" encoding="utf-8"?>
<scpd xmlns="urn:schemas-upnp-org:service-1-0"><specVersion><major>1</major><minor>0</minor></specVersion>
<actionList>$actions</actionList><serviceStateTable>$vars</serviceStateTable></scpd>"""

        private fun action(name: String, vararg args: Triple<String, String, String>) =
            "<action><name>$name</name><argumentList>" +
                args.joinToString("") { (n, dir, v) -> "<argument><name>$n</name><direction>$dir</direction><relatedStateVariable>$v</relatedStateVariable></argument>" } +
                "</argumentList></action>"

        private fun v(name: String, type: String, events: Boolean = false, allowed: List<String> = emptyList()) =
            "<stateVariable sendEvents=\"${if (events) "yes" else "no"}\"><name>$name</name><dataType>$type</dataType>" +
                (if (allowed.isNotEmpty()) "<allowedValueList>" + allowed.joinToString("") { "<allowedValue>$it</allowedValue>" } + "</allowedValueList>" else "") +
                "</stateVariable>"

        private val CDS_SCPD = scpd(
            action("Browse",
                Triple("ObjectID", "in", "A_ARG_TYPE_ObjectID"), Triple("BrowseFlag", "in", "A_ARG_TYPE_BrowseFlag"),
                Triple("Filter", "in", "A_ARG_TYPE_Filter"), Triple("StartingIndex", "in", "A_ARG_TYPE_Index"),
                Triple("RequestedCount", "in", "A_ARG_TYPE_Count"), Triple("SortCriteria", "in", "A_ARG_TYPE_SortCriteria"),
                Triple("Result", "out", "A_ARG_TYPE_Result"), Triple("NumberReturned", "out", "A_ARG_TYPE_Count"),
                Triple("TotalMatches", "out", "A_ARG_TYPE_Count"), Triple("UpdateID", "out", "A_ARG_TYPE_UpdateID")) +
                action("GetSearchCapabilities", Triple("SearchCaps", "out", "SearchCapabilities")) +
                action("GetSortCapabilities", Triple("SortCaps", "out", "SortCapabilities")) +
                action("GetSystemUpdateID", Triple("Id", "out", "SystemUpdateID")),
            v("A_ARG_TYPE_ObjectID", "string") + v("A_ARG_TYPE_BrowseFlag", "string", allowed = listOf("BrowseMetadata", "BrowseDirectChildren")) +
                v("A_ARG_TYPE_Filter", "string") + v("A_ARG_TYPE_Index", "ui4") + v("A_ARG_TYPE_Count", "ui4") +
                v("A_ARG_TYPE_SortCriteria", "string") + v("A_ARG_TYPE_Result", "string") + v("A_ARG_TYPE_UpdateID", "ui4") +
                v("SearchCapabilities", "string") + v("SortCapabilities", "string") + v("SystemUpdateID", "ui4", events = true)
        )

        private val CMS_SCPD = scpd(
            action("GetProtocolInfo", Triple("Source", "out", "SourceProtocolInfo"), Triple("Sink", "out", "SinkProtocolInfo")) +
                action("GetCurrentConnectionIDs", Triple("ConnectionIDs", "out", "CurrentConnectionIDs")) +
                action("GetCurrentConnectionInfo",
                    Triple("ConnectionID", "in", "A_ARG_TYPE_ConnectionID"), Triple("RcsID", "out", "A_ARG_TYPE_RcsID"),
                    Triple("AVTransportID", "out", "A_ARG_TYPE_AVTransportID"), Triple("ProtocolInfo", "out", "A_ARG_TYPE_ProtocolInfo"),
                    Triple("PeerConnectionManager", "out", "A_ARG_TYPE_ConnectionManager"), Triple("PeerConnectionID", "out", "A_ARG_TYPE_ConnectionID"),
                    Triple("Direction", "out", "A_ARG_TYPE_Direction"), Triple("Status", "out", "A_ARG_TYPE_ConnectionStatus")),
            v("SourceProtocolInfo", "string", events = true) + v("SinkProtocolInfo", "string", events = true) +
                v("CurrentConnectionIDs", "string", events = true) + v("A_ARG_TYPE_ConnectionID", "i4") +
                v("A_ARG_TYPE_RcsID", "i4") + v("A_ARG_TYPE_AVTransportID", "i4") + v("A_ARG_TYPE_ProtocolInfo", "string") +
                v("A_ARG_TYPE_ConnectionManager", "string") + v("A_ARG_TYPE_Direction", "string", allowed = listOf("Input", "Output")) +
                v("A_ARG_TYPE_ConnectionStatus", "string", allowed = listOf("OK", "ContentFormatMismatch", "InsufficientBandwidth", "UnreliableChannel", "Unknown"))
        )
    }
}
