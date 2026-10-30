package com.serif.tvkumanda

import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Telefondan canlı yayın sunan küçük HTTP sunucusu (ekran yansıtma ve hoparlör için).
 * Dosya sunmaz: [publish] ile verilen parçaları, bağlı oynatıcılara bitmeyen tek bir yanıt olarak akıtır.
 * Adres rastgele bir bağlantı noktasındadır; yalnızca [path] istekleri yanıtlanır.
 *
 * Görüntülü yayında ([needKey] true) yeni katılan oynatıcıya, ilk anahtar kareden başlanır; oynatıcı
 * geride kalıp kuyruğu doldurursa kuyruk atılır ve bir sonraki anahtar kareden devam edilir (yayın canlı kalır).
 */
internal class LiveServer(private val mime: String, private val path: String, private val needKey: Boolean) {

    private class Client(val sock: Socket) {
        val queue = LinkedBlockingQueue<ByteArray>(QUEUE_MAX)
        @Volatile var started = false
        @Volatile var dead = false
    }

    private val clients = CopyOnWriteArrayList<Client>()
    @Volatile private var server: ServerSocket? = null
    @Volatile var everConnected = false
        private set

    /** Şu an izleyen oynatıcı sayısı. */
    val viewers: Int get() = clients.count { !it.dead }

    val port: Int get() = server?.localPort ?: 0

    @Synchronized
    fun start(): Int {
        server?.let { return it.localPort }
        val srv = ServerSocket(0)
        server = srv
        Thread({ acceptLoop(srv) }, "live-server").apply { isDaemon = true }.start()
        return srv.localPort
    }

    /** TV'nin [tvIp] üzerinden ulaşacağı yayın adresi. */
    fun url(tvIp: String): String {
        val host = addressFor(tvIp) ?: throw IOException("Telefonun Wi-Fi adresi bulunamadı")
        return "http://$host:$port$path"
    }

    /** Yeni bir parça yayınlar; [key]: görüntüde anahtar kare ile başlıyor (ses için her parça true sayılabilir). */
    fun publish(data: ByteArray, key: Boolean) {
        for (c in clients) {
            if (c.dead) continue
            if (!c.started) {
                if (needKey && !key) continue
                c.started = true
            }
            if (!c.queue.offer(data)) {
                // Oynatıcı yetişemiyor: eskiyi at, yeni anahtar kareden devam et
                c.queue.clear()
                c.started = !needKey
            }
        }
    }

    @Synchronized
    fun stop() {
        runCatching { server?.close() }
        server = null
        clients.forEach { it.dead = true; runCatching { it.sock.close() } }
        clients.clear()
    }

    private fun acceptLoop(srv: ServerSocket) {
        while (!srv.isClosed) {
            val s = runCatching { srv.accept() }.getOrNull() ?: break
            Thread({ runCatching { serve(s) }; runCatching { s.close() } }, "live-client").apply { isDaemon = true }.start()
        }
    }

    private fun serve(s: Socket) {
        s.tcpNoDelay = true
        s.soTimeout = 15_000
        val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.ISO_8859_1))
        val requestLine = reader.readLine() ?: return
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
        }
        val parts = requestLine.split(' ')
        val method = parts.getOrNull(0) ?: ""
        val reqPath = (parts.getOrNull(1) ?: "").substringBefore('?')
        val out = s.getOutputStream()
        if (reqPath != path || (method != "GET" && method != "HEAD")) {
            out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
            out.flush()
            return
        }
        val head = "HTTP/1.1 200 OK\r\n" +
            "Content-Type: $mime\r\n" +
            "Cache-Control: no-cache, no-store\r\nPragma: no-cache\r\n" +
            "Accept-Ranges: none\r\n" +
            "transferMode.dlna.org: Streaming\r\n" +
            "contentFeatures.dlna.org: DLNA.ORG_OP=00;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000\r\n" +
            "Access-Control-Allow-Origin: *\r\n" +
            "Connection: close\r\n\r\n"
        out.write(head.toByteArray(Charsets.ISO_8859_1))
        out.flush()
        if (method == "HEAD") return

        s.soTimeout = 0
        val c = Client(s)
        clients += c
        everConnected = true
        try {
            while (!c.dead) {
                val chunk = c.queue.poll(2, TimeUnit.SECONDS) ?: continue
                out.write(chunk)
                out.flush()
            }
        } catch (_: IOException) {
            // oynatıcı ayrıldı
        } finally {
            c.dead = true
            clients.remove(c)
        }
    }

    companion object {
        private const val QUEUE_MAX = 400

        /** TV ile aynı ağdaki telefon adresi (aynı alt ağ tercih edilir). */
        fun addressFor(tvIp: String): String? {
            val prefix = tvIp.substringBeforeLast('.')
            val all = runCatching {
                NetworkInterface.getNetworkInterfaces().toList()
                    .filter { it.isUp && !it.isLoopback }
                    .flatMap { it.inetAddresses.toList() }
                    .filterIsInstance<Inet4Address>()
                    .filter { !it.isLoopbackAddress }
            }.getOrDefault(emptyList())
            return (all.firstOrNull { it.hostAddress?.startsWith("$prefix.") == true }
                ?: all.firstOrNull { it.isSiteLocalAddress })?.hostAddress
        }
    }
}
