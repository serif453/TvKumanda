package com.serif.tvkumanda

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Telefonun Wi-Fi (ya da kablolu) ağını izler.
 *  - TV soketleri bu ağa bağlanır ([TvSocket]); telefon mobil veriye kaysa da TV'ye Wi-Fi'dan gidilir.
 *  - Wi-Fi gidince / gelince [onChange] çağrılır (kumanda durumu anında güncellenir, geri gelince yeniden bağlanılır).
 * İnternetsiz Wi-Fi de sayılır: modemin interneti kesik olsa da TV aynı ağda ulaşılabilir.
 */
object WifiNet {
    private val nets = CopyOnWriteArrayList<Network>()

    /** Wi-Fi var mı değişince çağrılır (arka plan iş parçacığından). */
    @Volatile var onChange: ((Boolean) -> Unit)? = null

    val available: Boolean get() = nets.isNotEmpty()

    private var started = false

    @Synchronized
    fun init(context: Context) {
        if (started) return
        val cm = context.applicationContext.getSystemService(ConnectivityManager::class.java) ?: return
        val req = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        // Açılışta var olan Wi-Fi hemen kaydedilir: izleme cevabı gelmeden otomatik bağlanma "Wi-Fi yok" sanmasın
        runCatching {
            @Suppress("DEPRECATION")
            cm.allNetworks.forEach { n ->
                val c = cm.getNetworkCapabilities(n) ?: return@forEach
                if ((c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) &&
                    !c.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) nets.add(n)
            }
        }
        runCatching {
            cm.registerNetworkCallback(req, object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    val was = available
                    nets.remove(network); nets.add(network)   // en son gelen en sona
                    if (!was) onChange?.invoke(true)
                }
                override fun onLost(network: Network) {
                    nets.remove(network)
                    if (!available) onChange?.invoke(false)
                }
            })
            started = true
            TvSocket.wifiFactory = {
                // Wi-Fi var ama bağlama başarısızsa sıradan sokete düşülür (eski davranış)
                nets.lastOrNull()?.let { n -> runCatching { n.socketFactory.createSocket() }.getOrElse { java.net.Socket() } }
            }
        }.onFailure { DiagLog.log("Wi-Fi izlenemiyor: ${it.message.orEmpty()}") }
    }
}
