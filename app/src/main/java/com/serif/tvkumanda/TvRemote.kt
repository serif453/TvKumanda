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
class TvRemote(private val context: Context) : RemoteLink {

    private var dadb: Dadb? = null
    private var fast: AdbStream? = null
    private var host: String = ""
    private var port: Int = 5555
    private val waiter = Executors.newSingleThreadExecutor()

    override val isFast: Boolean get() = fast != null

    private val keyPair: AdbKeyPair by lazy {
        val priv = File(context.filesDir, "adbkey")
        val pub = File(context.filesDir, "adbkey.pub")
        if (!priv.exists() || !pub.exists()) AdbKeyPair.generate(priv, pub)
        AdbKeyPair.read(priv, pub)
    }

    override fun connectTo(ip: String): String = connect(ip, 5555)

    /** Bağlanır ve TV'nin model adını döndürür. */
    @Synchronized
    fun connect(ip: String, port: Int): String {
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
    override fun key(code: Int) {
        if (!sendFast("k $code")) {
            shell("input keyevent $code")
        }
    }

    /** Fare komutu (yalnızca hızlı modda çalışır). */
    @Synchronized
    override fun mouse(line: String) {
        sendFast(line)
    }

    @Synchronized
    override fun text(raw: String) {
        val t = normalizeText(raw)
        if (t.isEmpty()) return
        if (!sendFast("t $t")) {
            shell(buildTextCommand(t))
        }
    }

    /**
     * Telefonda tanınan sesli komutu TV'de arama olarak açar.
     * Önce Android TV'nin genel arama ekranı denenir; açılamazsa metin odaktaki alana yazılır.
     */
    @Synchronized
    fun voiceSearch(query: String) {
        val q = query.replace("'", "'\\''")
        val out = runCatching { shell("am start -a android.search.action.GLOBAL_SEARCH --es query '$q'") }.getOrDefault("Error")
        if (out.contains("Error", ignoreCase = true)) text(query)
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
    override fun listApps(): List<TvApp> {
        // Önce TV'deki sunucudan gerçek adlar ve simgeler; olmazsa eski yöntem
        runCatching { listAppsFromServer() }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { return it }
        return listAppsFromShell()
    }

    /** Tuş sunucusundan uygulamaları TV'nin gösterdiği ad ve simgeleriyle alır. */
    private fun listAppsFromServer(): List<TvApp>? {
        val s = fast ?: return null
        if (!sendFast("a")) return null
        val lines = waiter.submit<List<String>> {
            val out = ArrayList<String>()
            while (true) {
                val l = s.source.readUtf8Line() ?: break
                if (l == "END") break
                out.add(l)
            }
            out
        }.get(20, TimeUnit.SECONDS)
        return lines.mapNotNull { l ->
            val p = l.split('\t')
            if (p.size < 4 || p[0] != "APP") return@mapNotNull null
            val pkg = p[1]
            if (pkg == context.packageName) return@mapNotNull null
            val icon = p.getOrNull(4)?.takeIf { it.isNotEmpty() }
                ?.let { runCatching { android.util.Base64.decode(it, android.util.Base64.DEFAULT) }.getOrNull() }
            TvApp(pkg, p[2], p[3].ifBlank { appName(pkg) }, icon)
        }
            .distinctBy { it.pkg }
            .sortedWith(compareBy<TvApp> { knownRank(it.pkg) }.thenBy { it.name.lowercase() })
    }

    private fun listAppsFromShell(): List<TvApp> {
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

    override fun launch(app: TvApp) {
        shell(launchCommand(app))
    }

    fun launchCommand(app: TvApp): String =
        if (app.activity.isNotEmpty()) "am start -n ${app.pkg}/${app.activity}"
        else "monkey -p ${app.pkg} -c android.intent.category.LEANBACK_LAUNCHER 1"

    /** Medya ses seviyesi (değer, en yüksek). Kilit tutmaz; birkaç yöntemi sırayla dener. */
    override fun volume(): Pair<Int, Int>? {
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
    override fun close() {
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

data class TvApp(val pkg: String, val activity: String, val name: String, val icon: ByteArray? = null) {
    override fun equals(other: Any?) = other is TvApp && other.pkg == pkg && other.activity == activity && other.name == name
    override fun hashCode() = pkg.hashCode()
}

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
