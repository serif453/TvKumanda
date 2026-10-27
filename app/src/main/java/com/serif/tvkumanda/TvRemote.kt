package com.serif.tvkumanda

import android.content.Context
import dadb.AdbKeyPair
import dadb.AdbStream
import dadb.Dadb
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * TV ile ADB bağlantısını yönetir.
 *
 * Hızlı mod: Bağlanınca uygulamanın APK'sı TV'ye kopyalanır ve içindeki [KeyServer]
 * TV'de sürekli çalışır. Tuşlar bu açık bağlantıdan anında gönderilir.
 * Hızlı mod başlatılamazsa klasik "input keyevent" yöntemine geri döner.
 *
 * Tüm fonksiyonlar bloklayıcıdır; arka plan iş parçacığından çağrılmalıdır.
 */
class TvRemote(private val context: Context) {

    private var dadb: Dadb? = null
    private var fast: AdbStream? = null
    private var host: String = ""
    private var port: Int = 5555
    private val waiter = Executors.newSingleThreadExecutor()

    val isFast: Boolean get() = fast != null

    private val keyPair: AdbKeyPair by lazy {
        val priv = File(context.filesDir, "adbkey")
        val pub = File(context.filesDir, "adbkey.pub")
        if (!priv.exists() || !pub.exists()) AdbKeyPair.generate(priv, pub)
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
        startFastMode(d)
        return model.ifEmpty { ip }
    }

    private fun startFastMode(d: Dadb) {
        fast = runCatching {
            val apk = File(context.applicationInfo.sourceDir)
            @Suppress("DEPRECATION")
            val stamp = context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
            val remotePath = "/data/local/tmp/tvkumanda-$stamp.apk"

            // APK yalnızca uygulama güncellendiğinde yeniden kopyalanır
            val exists = d.shell("[ -f $remotePath ] && echo yes").output.trim() == "yes"
            if (!exists) {
                d.shell("rm -f /data/local/tmp/tvkumanda-*.apk")
                d.push(apk, remotePath)
            }

            val stream = d.open("shell:CLASSPATH=$remotePath app_process / com.serif.tvkumanda.KeyServer")
            val ready = try {
                waiter.submit<String?> { stream.source.readUtf8Line() }.get(8, TimeUnit.SECONDS)
            } catch (e: Exception) {
                runCatching { stream.close() }
                throw e
            }
            if (ready?.trim() != "READY") {
                runCatching { stream.close() }
                error("Tuş sunucusu başlamadı: $ready")
            }
            stream
        }.getOrNull()
    }

    private fun sendFast(line: String): Boolean {
        val s = fast ?: return false
        return try {
            s.sink.writeUtf8(line).writeUtf8("\n").flush()
            true
        } catch (e: Exception) {
            runCatching { s.close() }
            fast = null
            false
        }
    }

    @Synchronized
    fun key(code: Int) {
        if (!sendFast("k $code")) {
            shell("input keyevent $code")
        }
    }

    @Synchronized
    fun text(raw: String) {
        val t = normalizeText(raw)
        if (t.isEmpty()) return
        if (!sendFast("t $t")) {
            shell(buildTextCommand(t))
        }
    }

    /** Komut gönderir; bağlantı kopmuşsa bir kez yeniden bağlanmayı dener. */
    @Synchronized
    fun shell(command: String): String {
        val d = dadb ?: throw IllegalStateException("Önce TV'ye bağlan")
        return try {
            d.shell(command).output
        } catch (e: Exception) {
            reconnect()
            dadb!!.shell(command).output
        }
    }

    private fun reconnect() {
        runCatching { fast?.close() }
        fast = null
        runCatching { dadb?.close() }
        val d = Dadb.create(host, port, keyPair)
        dadb = d
        startFastMode(d)
    }

    @Synchronized
    fun close() {
        runCatching { fast?.close() }
        fast = null
        runCatching { dadb?.close() }
        dadb = null
    }
}

/** Türkçe harfleri sadeleştirir (TV klavye haritasında karşılığı olmayabilir). */
fun normalizeText(text: String): String {
    val tr = mapOf(
        'ı' to 'i', 'İ' to 'I', 'ğ' to 'g', 'Ğ' to 'G', 'ü' to 'u', 'Ü' to 'U',
        'ş' to 's', 'Ş' to 'S', 'ö' to 'o', 'Ö' to 'O', 'ç' to 'c', 'Ç' to 'C'
    )
    return text.map { tr[it] ?: it }.joinToString("").replace("\n", " ")
}

/** Yedek yöntem: "input text" komutu (boşluk ve tırnakları kaçırır). */
fun buildTextCommand(text: String): String {
    val sb = StringBuilder()
    for (c in text) {
        when (c) {
            ' ' -> sb.append("%s")
            '\'' -> sb.append("'\\''")
            else -> sb.append(c)
        }
    }
    return "input text '$sb'"
}
