package com.serif.tvkumanda

import java.io.IOException
import java.net.Socket

/**
 * TV'ye giden her bağlantının soketi buradan alınır (saf kod; Android'e bağlı kısım [WifiNet]'te).
 *
 * Neden: Telefon, Wi-Fi'ı zayıf ya da internetsiz görünce (Honor "Wi-Fi+" gibi) trafiği mobil veriye
 * kaydırabiliyor. Ev ağındaki TV'ye mobil veriden ulaşılamaz; bağlantı ya hiç kurulmaz ya da takılır.
 * Bu yüzden ev ağı adreslerine giden soketler Wi-Fi ağına bağlanır (Serif, 5 Kasım 2026).
 */
object TvSocket {
    /** Ev ağı adresi için Wi-Fi'a bağlı soket üretir; Wi-Fi yoksa null döner. [WifiNet] doldurur. */
    @Volatile var wifiFactory: (() -> Socket?)? = null

    class NoWifi : IOException("Wi-Fi'a bağlı değilsin. Telefonu TV ile aynı Wi-Fi ağına bağla.")

    /**
     * Bağlanmamış bir soket döndürür. Ev ağı adresiyse Wi-Fi'a bağlanır; Wi-Fi yoksa [NoWifi] atar
     * (mobil veriden boşuna denenmez). Ev ağı dışı adresler (ör. VPN ile uzaktan) olduğu gibi kalır.
     */
    fun create(ip: String): Socket {
        val f = wifiFactory ?: return Socket()   // henüz başlatılmadı (ör. testler)
        if (!isLocal(ip)) return Socket()
        return f() ?: throw NoWifi()
    }

    /** Ev ağı (özel) adresi mi: 10.x, 172.16-31.x, 192.168.x, 169.254.x ya da .local adı. */
    fun isLocal(host: String): Boolean {
        val h = host.trim().lowercase()
        if (h.endsWith(".local")) return true
        val p = h.split('.').mapNotNull { it.toIntOrNull() }
        if (p.size != 4 || p.any { it !in 0..255 }) return false
        return p[0] == 10 ||
            (p[0] == 172 && p[1] in 16..31) ||
            (p[0] == 192 && p[1] == 168) ||
            (p[0] == 169 && p[1] == 254)
    }
}
