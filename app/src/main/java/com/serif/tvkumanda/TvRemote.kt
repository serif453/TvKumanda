package com.serif.tvkumanda

import dadb.AdbKeyPair
import dadb.Dadb
import java.io.File

/**
 * TV ile ADB bağlantısını yönetir.
 * Tüm fonksiyonlar bloklayıcıdır; arka plan iş parçacığından çağrılmalıdır.
 */
class TvRemote(private val keyDir: File) {

    private var dadb: Dadb? = null
    private var host: String = ""
    private var port: Int = 5555

    // Uygulamaya özel ADB anahtarı. İlk çalışmada üretilir, sonra hep aynısı kullanılır,
    // böylece TV'de "Her zaman izin ver" dediğinde bir daha sormaz.
    private val keyPair: AdbKeyPair by lazy {
        val priv = File(keyDir, "adbkey")
        val pub = File(keyDir, "adbkey.pub")
        if (!priv.exists() || !pub.exists()) {
            AdbKeyPair.generate(priv, pub)
        }
        AdbKeyPair.read(priv, pub)
    }

    /** Bağlanır ve TV'nin model adını döndürür. */
    @Synchronized
    fun connect(ip: String, port: Int = 5555): String {
        close()
        host = ip
        this.port = port
        val d = Dadb.create(ip, port, keyPair)
        val model = d.shell("getprop ro.product.model").output.trim()
        dadb = d
        return model.ifEmpty { ip }
    }

    /** Komut gönderir; bağlantı kopmuşsa bir kez yeniden bağlanmayı dener. */
    @Synchronized
    fun shell(command: String): String {
        val d = dadb ?: throw IllegalStateException("Önce TV'ye bağlan")
        return try {
            d.shell(command).output
        } catch (e: Exception) {
            runCatching { d.close() }
            val fresh = Dadb.create(host, port, keyPair)
            dadb = fresh
            fresh.shell(command).output
        }
    }

    @Synchronized
    fun close() {
        runCatching { dadb?.close() }
        dadb = null
    }
}
