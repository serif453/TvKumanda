package com.serif.tvkumanda

import android.content.Context
import android.net.Uri
import java.io.BufferedReader
import java.io.FileInputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Telefondaki dosyaları TV'nin Chromecast'ine sunan küçük HTTP sunucusu.
 *
 * Yalnızca kayıtlı dosyalar, tahmin edilemeyen rastgele bir adresle sunulur
 * (/m/<rastgele>); başka hiçbir dosyaya erişilemez. Videoda ileri-geri sarma için
 * "Range" (kısmi içerik) istekleri desteklenir.
 */
class MediaServer(private val context: Context) {

    private class Entry(val uri: Uri, val mime: String)

    private val entries = ConcurrentHashMap<String, Entry>()
    @Volatile private var server: ServerSocket? = null

    /** [uri]'yi sunar ve TV'nin [tvIp] üzerinden erişeceği adresi döndürür. */
    @Synchronized
    fun publish(uri: Uri, mime: String, tvIp: String): String {
        val host = localAddressFor(tvIp) ?: throw java.io.IOException("Telefonun Wi-Fi adresi bulunamadı")
        val s = server ?: ServerSocket(0).also { srv ->
            server = srv
            Thread({ acceptLoop(srv) }, "media-server").apply { isDaemon = true }.start()
        }
        // Her yeni yayında eski dosyaların erişimi kapatılır
        entries.clear()
        val token = UUID.randomUUID().toString().replace("-", "")
        entries[token] = Entry(uri, mime)
        return "http://$host:${s.localPort}/m/$token"
    }

    @Synchronized
    fun stop() {
        entries.clear()
        runCatching { server?.close() }
        server = null
    }

    private fun acceptLoop(srv: ServerSocket) {
        while (!srv.isClosed) {
            val c = runCatching { srv.accept() }.getOrNull() ?: break
            Thread({ runCatching { serve(c) }; runCatching { c.close() } }, "media-client").apply { isDaemon = true }.start()
        }
    }

    private fun serve(c: Socket) {
        c.soTimeout = 30_000
        val reader = BufferedReader(InputStreamReader(c.getInputStream(), Charsets.ISO_8859_1))
        val requestLine = reader.readLine() ?: return
        val headers = HashMap<String, String>()
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
            val i = line.indexOf(':')
            if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
        }
        val parts = requestLine.split(' ')
        val method = parts.getOrNull(0) ?: ""
        val path = parts.getOrNull(1) ?: ""
        val out = c.getOutputStream()

        if (method == "OPTIONS") { writeHead(out, "204 No Content", emptyMap()); return }
        val entry = path.removePrefix("/m/").takeIf { path.startsWith("/m/") }?.let { entries[it] }
        if (entry == null || (method != "GET" && method != "HEAD")) {
            writeHead(out, "404 Not Found", mapOf("Content-Length" to "0"))
            return
        }

        val afd = context.contentResolver.openAssetFileDescriptor(entry.uri, "r")
            ?: run { writeHead(out, "404 Not Found", mapOf("Content-Length" to "0")); return }
        afd.use {
            val total = afd.length.takeIf { it >= 0 } ?: afd.parcelFileDescriptor.statSize
            var start = 0L
            var end = total - 1
            var partial = false
            headers["range"]?.let { r ->
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
                "Content-Type" to entry.mime,
                "Content-Length" to len.toString(),
                "Accept-Ranges" to "bytes"
            )
            if (partial) h["Content-Range"] = "bytes $start-$end/$total"
            writeHead(out, if (partial) "206 Partial Content" else "200 OK", h)
            if (method == "HEAD") return
            FileInputStream(afd.fileDescriptor).use { fin ->
                val ch = fin.channel
                ch.position(afd.startOffset + start)
                val buf = ByteArray(64 * 1024)
                var left = len
                while (left > 0) {
                    val n = fin.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                    if (n <= 0) break
                    out.write(buf, 0, n)
                    left -= n
                }
                out.flush()
            }
        }
    }

    private fun writeHead(out: OutputStream, status: String, headers: Map<String, String>) {
        val sb = StringBuilder("HTTP/1.1 ").append(status).append("\r\n")
        // Chromecast alıcısı medyayı tarayıcı gibi çeker; başka kaynaktan erişime izin verilir
        sb.append("Access-Control-Allow-Origin: *\r\n")
        sb.append("Access-Control-Allow-Headers: Range, Content-Type\r\n")
        sb.append("Access-Control-Expose-Headers: Content-Range, Content-Length, Accept-Ranges\r\n")
        sb.append("Connection: close\r\n")
        for ((k, v) in headers) sb.append(k).append(": ").append(v).append("\r\n")
        sb.append("\r\n")
        out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
        out.flush()
    }

    /** TV ile aynı ağdaki telefon adresi (aynı alt ağ tercih edilir). */
    private fun localAddressFor(tvIp: String): String? {
        val tvPrefix = tvIp.substringBeforeLast('.')
        val candidates = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .filter { !it.isLoopbackAddress }
        }.getOrDefault(emptyList<InetAddress>())
        return (candidates.firstOrNull { it.hostAddress?.startsWith("$tvPrefix.") == true }
            ?: candidates.firstOrNull { it.isSiteLocalAddress })?.hostAddress
    }
}
