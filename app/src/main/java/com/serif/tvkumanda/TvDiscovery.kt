package com.serif.tvkumanda

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import java.net.Inet4Address

data class FoundTv(val name: String, val ip: String)

/**
 * Aynı Wi-Fi ağındaki Android TV / Google TV cihazlarını mDNS ile bulur.
 * TV'ler kendilerini "_androidtvremote2._tcp" servis türüyle duyurur
 * (Standart bağlantının servisi; ADB modundaki TV'ler de aynı cihazlar olduğu için
 * bulunan IP her iki yöntemle de kullanılabilir).
 */
class TvDiscovery(context: Context) {

    private val nsd = context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val main = Handler(Looper.getMainLooper())
    private val found = LinkedHashMap<String, FoundTv>()      // servis adı -> TV
    private val resolveQueue = ArrayDeque<NsdServiceInfo>()
    private var resolving = false
    private var listener: NsdManager.DiscoveryListener? = null
    private var onUpdate: ((List<FoundTv>) -> Unit)? = null

    fun start(onUpdate: (List<FoundTv>) -> Unit) {
        stop()
        this.onUpdate = onUpdate
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onServiceFound(info: NsdServiceInfo) {
                main.post { enqueue(info) }
            }
            override fun onServiceLost(info: NsdServiceInfo) {
                main.post {
                    if (found.remove(info.serviceName) != null) publish()
                }
            }
        }
        listener = l
        runCatching { nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, l) }
    }

    fun stop() {
        listener?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        listener = null
        onUpdate = null
        resolveQueue.clear()
        resolving = false
        found.clear()
    }

    // Eski Android sürümleri aynı anda tek çözümlemeye izin verir; sırayla çözümlenir.
    private fun enqueue(info: NsdServiceInfo) {
        if (listener == null || found.containsKey(info.serviceName)) return
        if (resolveQueue.none { it.serviceName == info.serviceName }) resolveQueue.addLast(info)
        resolveNext()
    }

    @Suppress("DEPRECATION")
    private fun resolveNext() {
        if (resolving || listener == null) return
        val next = resolveQueue.removeFirstOrNull() ?: return
        resolving = true
        val cb = object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                main.post { resolving = false; resolveNext() }
            }
            override fun onServiceResolved(info: NsdServiceInfo) {
                main.post {
                    resolving = false
                    val ip = (info.host as? Inet4Address)?.hostAddress ?: info.host?.hostAddress
                    if (ip != null && listener != null) {
                        found[info.serviceName] = FoundTv(info.serviceName, ip)
                        publish()
                    }
                    resolveNext()
                }
            }
        }
        runCatching { nsd.resolveService(next, cb) }.onFailure { resolving = false; resolveNext() }
    }

    private fun publish() {
        // Aynı IP'yi birden fazla adla duyuran cihazlar tek satırda gösterilir
        onUpdate?.invoke(found.values.distinctBy { it.ip })
    }

    companion object {
        const val SERVICE_TYPE = "_androidtvremote2._tcp."
    }
}

/**
 * Kayıtlı TV'ler için hafif "açık mı" kontrolü: tam tarama yapmadan, yalnızca
 * Standart (6466) ve ADB (5555) portlarına kısa bir TCP bağlantısı denenir.
 * Hiç veri gönderilmez; bağlantı hemen kapatılır.
 */
object TvPing {
    fun isOnline(ip: String, timeoutMs: Int = 1200): Boolean =
        listOf(AtvRemote.PORT_REMOTE, 5555).any { port ->
            runCatching {
                java.net.Socket().use { it.connect(java.net.InetSocketAddress(ip, port), timeoutMs) }
                true
            }.getOrDefault(false)
        }
}
