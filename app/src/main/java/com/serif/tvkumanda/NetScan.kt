package com.serif.tvkumanda

import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Ağ tarayıcının Android'siz (saf) kısmı: telefonun ağındaki adresleri kısa TCP denemeleriyle yoklar
 * ve bulunanları adları ve rozetleriyle birleştirir. Ekran tarafı `NetSheets.kt` içinde.
 *
 * Android 10'dan beri başka cihazların donanım (MAC) adresi okunamadığı için cihazlar,
 * birkaç bilinen kapıya verdikleri cevapla bulunur. "Kapı kapalı" cevabı da cihazın orada olduğunu gösterir.
 */
object NetScan {

    /** Önce TV kapıları yoklanır ki TV'ler listede hemen görünsün. */
    val TV_PORTS = listOf(ConnCheck.PORT_REMOTE, ConnCheck.PORT_ADB, 8009)
    /** Diğer cihazları bulmak için: echo, web, Windows/NAS paylaşımı, iPhone/iPad. */
    val OTHER_PORTS = listOf(7, 80, 443, 445, 62078)

    /** Ağda cevap veren bir adres ve açık bulunan kapıları. */
    data class Host(val ip: String, val open: Set<Int>)

    /** Ekranda gösterilecek cihaz. */
    data class Device(
        val ip: String,
        val name: String?,
        val open: Set<Int> = emptySet(),
        /** Kendini Android TV kumanda servisiyle duyuruyor. */
        val mdnsTv: Boolean = false,
        val dlna: Boolean = false,
        val gateway: Boolean = false,
        val self: Boolean = false
    ) {
        val isTv: Boolean get() = !self && (mdnsTv || ConnCheck.PORT_REMOTE in open || ConnCheck.PORT_ADB in open)
        val adbOpen: Boolean get() = ConnCheck.PORT_ADB in open

        fun badges(): List<String> = buildList {
            if (self) add("Bu telefon")
            if (gateway) add("Modem")
            if (mdnsTv || ConnCheck.PORT_REMOTE in open) add("Android TV")
            if (ConnCheck.PORT_ADB in open) add("ADB açık")
            if (8009 in open) add("Chromecast")
            if (dlna) add("DLNA")
        }
    }

    // ---------------- Adresler ----------------

    private fun toLong(ip: String): Long = ip.trim().split('.').fold(0L) { acc, p -> (acc shl 8) or p.toLong() }
    private fun fromLong(v: Long): String = listOf(24, 16, 8, 0).joinToString(".") { ((v shr it) and 0xFF).toString() }

    /**
     * Taranacak adresler: telefonun ağındaki tüm adresler (telefonun kendisi hariç).
     * Ağ /24'ten büyükse (ör. /16) taramanın kısa sürmesi için yalnızca telefonun /24'ü taranır.
     */
    fun hosts(phoneIp: String, prefix: Int): List<String> {
        if (!ConnCheck.isIpv4(phoneIp)) return emptyList()
        val p = prefix.coerceIn(24, 30)
        val mask = (0xFFFFFFFFL shl (32 - p)) and 0xFFFFFFFFL
        val base = toLong(phoneIp) and mask
        val count = 1L shl (32 - p)
        return (1 until count - 1).map { fromLong(base + it) }.filter { it != phoneIp.trim() }
    }

    /** Taranan alanın kısa gösterimi, ör. 192.168.1.0/24 */
    fun rangeLabel(phoneIp: String, prefix: Int): String {
        val p = prefix.coerceIn(24, 30)
        val mask = (0xFFFFFFFFL shl (32 - p)) and 0xFFFFFFFFL
        return "${fromLong(toLong(phoneIp) and mask)}/$p"
    }

    fun ipOrder(ip: String): Long = if (ConnCheck.isIpv4(ip)) toLong(ip) else Long.MAX_VALUE

    // ---------------- Tarama ----------------

    /** Bir tarama; ilerlemesi ve o ana kadar bulunanlar başka iş parçacığından okunabilir. */
    class Scanner(private val timeoutMs: Int = 500, private val parallel: Int = 128) {
        private val open = ConcurrentHashMap<String, MutableSet<Int>>()
        private val done = AtomicInteger(0)
        @Volatile private var total = 1
        private val stopped = AtomicBoolean(false)

        fun progress(): Float = (done.get().toFloat() / total).coerceIn(0f, 1f)

        /** O ana kadar cevap veren adresler. */
        fun found(): List<Host> = open.entries.map { Host(it.key, it.value.toSet()) }

        fun cancel() = stopped.set(true)

        /** Bloklayıcıdır; bitince bulunan adresleri döndürür. */
        fun run(ips: List<String>): List<Host> {
            val tasks = (TV_PORTS + OTHER_PORTS).flatMap { port -> ips.map { it to port } }
            total = tasks.size.coerceAtLeast(1)
            val pool = Executors.newFixedThreadPool(parallel)
            try {
                for ((ip, port) in tasks) {
                    pool.execute {
                        if (!stopped.get()) {
                            val r = ConnCheck.probe(ip, port, timeoutMs).state
                            if (r == ConnCheck.Port.OPEN || r == ConnCheck.Port.REFUSED) {
                                val set = open.getOrPut(ip) { ConcurrentHashMap.newKeySet<Int>() }
                                if (r == ConnCheck.Port.OPEN) set += port
                            }
                        }
                        done.incrementAndGet()
                    }
                }
                pool.shutdown()
                while (!pool.awaitTermination(200, TimeUnit.MILLISECONDS)) {
                    if (stopped.get()) { pool.shutdownNow(); break }
                }
            } finally {
                pool.shutdownNow()
            }
            return found()
        }
    }

    /** Adı bilinmeyen adresler için ağ adı (modemin verdiği ad, ör. "DESKTOP-AB12"); en fazla [timeoutMs] beklenir. */
    fun reverseNames(ips: List<String>, timeoutMs: Long = 3000): Map<String, String> {
        if (ips.isEmpty()) return emptyMap()
        val names = ConcurrentHashMap<String, String>()
        val pool = Executors.newFixedThreadPool(ips.size.coerceAtMost(32))
        ips.forEach { ip ->
            pool.execute {
                runCatching { InetAddress.getByName(ip).canonicalHostName }.getOrNull()
                    ?.let { shortName(it, ip) }?.let { names[ip] = it }
            }
        }
        pool.shutdown()
        runCatching { pool.awaitTermination(timeoutMs, TimeUnit.MILLISECONDS) }
        pool.shutdownNow()
        return HashMap(names)
    }

    /** "DESKTOP-AB12.lan" → "DESKTOP-AB12"; ad yoksa (adresin kendisi dönerse) null. */
    fun shortName(host: String, ip: String): String? {
        val h = host.trim().trimEnd('.')
        if (h.isEmpty() || h == ip || ConnCheck.isIpv4(h)) return null
        return h.substringBefore('.').ifBlank { null }
    }

    // ---------------- Birleştirme ----------------

    /**
     * Kapı taraması, mDNS (Android TV ve Chromecast adları), DLNA ve ağ adlarını tek listede birleştirir.
     * Ad önceliği: Android TV adı > Chromecast adı > DLNA adı > ağ adı.
     */
    fun combine(
        hosts: List<Host>,
        selfIp: String?,
        gatewayIp: String?,
        tvNames: Map<String, String>,
        castNames: Map<String, String>,
        dlnaNames: Map<String, String>,
        netNames: Map<String, String>
    ): List<Device> {
        val openBy = hosts.associate { it.ip to it.open }
        val ips = LinkedHashSet<String>().apply {
            addAll(openBy.keys); addAll(tvNames.keys); addAll(castNames.keys); addAll(dlnaNames.keys)
            selfIp?.let { add(it) }
            gatewayIp?.let { add(it) }
        }
        return ips.filter { ConnCheck.isIpv4(it) }.map { ip ->
            Device(
                ip = ip,
                name = tvNames[ip] ?: castNames[ip] ?: dlnaNames[ip] ?: netNames[ip],
                open = openBy[ip].orEmpty(),
                mdnsTv = ip in tvNames,
                dlna = ip in dlnaNames,
                gateway = ip == gatewayIp,
                self = ip == selfIp
            )
        }.sortedBy { ipOrder(it.ip) }
    }
}
