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

    /** Fare komutu (yalnızca hızlı modda çalışır). */
    @Synchronized
    fun mouse(line: String) {
        sendFast(line)
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

    /** TV'deki başlatılabilir uygulamaları listeler. Kilit tutmaz, tuş gönderimini bekletmez. */
    fun listApps(): List<TvApp> {
        val d = dadb ?: return emptyList()
        fun run(cmd: String) = runCatching { d.shell(cmd).output }.getOrDefault("")
        val found = LinkedHashMap<String, String>()

        val brief = run("cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.LEANBACK_LAUNCHER")
        Regex("""([A-Za-z][\w.]*)/([\w.${'$'}]+)""").findAll(brief).forEach { m ->
            val pkg = m.groupValues[1]
            var act = m.groupValues[2]
            if (act.startsWith(".")) act = pkg + act
            found.putIfAbsent(pkg, act)
        }
        if (found.isEmpty()) {
            val full = run("cmd package query-activities -a android.intent.action.MAIN -c android.intent.category.LEANBACK_LAUNCHER")
            Regex("""packageName=([\w.]+)""").findAll(full).forEach { found.putIfAbsent(it.groupValues[1], "") }
        }
        if (found.isEmpty()) {
            run("pm list packages -3").lines()
                .map { it.removePrefix("package:").trim() }
                .filter { it.isNotEmpty() }
                .forEach { found.putIfAbsent(it, "") }
        }
        return found
            .filterKeys { it != context.packageName }
            .map { (pkg, act) -> TvApp(pkg, act, appName(pkg)) }
            .sortedWith(compareBy<TvApp> { knownRank(it.pkg) }.thenBy { it.name.lowercase() })
    }

    fun launchCommand(app: TvApp): String =
        if (app.activity.isNotEmpty()) "am start -n ${app.pkg}/${app.activity}"
        else "monkey -p ${app.pkg} -c android.intent.category.LEANBACK_LAUNCHER 1"

    /** Medya ses seviyesi (değer, en yüksek). Kilit tutmaz; birkaç yöntemi sırayla dener. */
    fun volume(): Pair<Int, Int>? {
        val d = dadb ?: return null
        fun run(cmd: String) = runCatching {
            val r = d.shell(cmd)
            r.output + "\n" + r.errorOutput
        }.getOrDefault("")

        // 1) cmd media_session (hızlı)
        val re = Regex("""volume is (\d+) in range \[(\d+)\.\.(\d+)]""")
        re.find(run("cmd media_session volume --stream 3 --get"))?.let {
            return it.groupValues[1].toInt() to it.groupValues[3].toInt()
        }

        // 2) dumpsys audio içindeki STREAM_MUSIC bölümü
        val dump = run("dumpsys audio")
        val start = dump.indexOf("STREAM_MUSIC:")
        if (start >= 0) {
            val sec = dump.substring(start, minOf(dump.length, start + 1500))
            val max = Regex("""Max:\s*(\d+)""").find(sec)?.groupValues?.get(1)?.toIntOrNull()
            val cur = Regex("""streamVolume:\s*(\d+)""").find(sec)?.groupValues?.get(1)?.toIntOrNull()
                ?: Regex("""Current:[^\n]*?\):\s*(\d+)""").find(sec)?.groupValues?.get(1)?.toIntOrNull()
            if (max != null && cur != null) return cur to max
        }

        // 3) eski "media" komutu (yavaş)
        re.find(run("media volume --stream 3 --get"))?.let {
            return it.groupValues[1].toInt() to it.groupValues[3].toInt()
        }
        return null
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

data class TvApp(val pkg: String, val activity: String, val name: String)

private val knownApps = linkedMapOf(
    "com.netflix.ninja" to "Netflix",
    "com.google.android.youtube.tv" to "YouTube",
    "com.amazon.amazonvideo.livingroom" to "Prime Video",
    "com.disney.disneyplus" to "Disney+",
    "com.spotify.tv.android" to "Spotify",
    "com.google.android.youtube.tvmusic" to "YT Music",
    "com.google.android.youtube.tvkids" to "YT Kids",
    "com.google.android.play.games" to "Play Games",
    "com.android.vending" to "Play Store",
    "com.android.tv.settings" to "Ayarlar",
    "com.google.android.tv" to "Canlı TV",
    "com.android.chrome" to "Chrome"
)

private fun knownRank(pkg: String): Int {
    val i = knownApps.keys.indexOf(pkg)
    return if (i >= 0) i else 1000
}

/** Bilinen paketlere güzel isim, diğerlerine paket adından türetilmiş isim. */
fun appName(pkg: String): String {
    knownApps[pkg]?.let { return it }
    val skip = setOf("com", "tv", "android", "app", "apps", "mobile", "atv", "androidtv", "ninja", "tr", "net", "org", "www", "player", "leanback")
    val part = pkg.split('.').lastOrNull { it.lowercase() !in skip && it.length > 1 } ?: pkg.substringAfterLast('.')
    return part.replaceFirstChar { it.uppercase() }
}
