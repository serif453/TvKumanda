package com.serif.tvkumanda

import android.content.Context
import dadb.AdbKeyPair
import dadb.AdbStream
import dadb.Dadb
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
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
    /** Bağlı TV'nin adresi. */
    val tvIp: String get() = host
    private var port: Int = 5555
    // Bekleme süreli okumalar için iş parçacıkları. Tek iş parçacığı yerine havuz:
    // takılan bir okuma, sonraki bağlantının "hazır" beklemesini engellemez.
    private val waiter = Executors.newCachedThreadPool { r -> Thread(r, "adb-wait").apply { isDaemon = true } }

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
        // Model adı ve tuş sunucusunun TV'de hazır olup olmadığı tek komutta öğrenilir
        val out = d.shell("getprop ro.product.model; [ -f $serverPath ] && echo SERVER_OK").output
        val model = out.lineSequence().firstOrNull()?.trim().orEmpty()
        dadb = d
        startFastMode(d, out.contains("SERVER_OK"))
        return model.ifEmpty { ip }
    }

    /** TV'deki sunucu dosyası; uygulama güncellenince adı değişir ve yeniden kopyalanır. */
    private val serverPath: String by lazy {
        @Suppress("DEPRECATION")
        val stamp = context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
        "/data/local/tmp/tvkumanda-$stamp.apk"
    }

    private fun startFastMode(d: Dadb, exists: Boolean? = null) {
        fast = runCatching {
            val apk = File(context.applicationInfo.sourceDir)
            val remotePath = serverPath

            // APK yalnızca uygulama güncellendiğinde yeniden kopyalanır
            val present = exists ?: (d.shell("[ -f $remotePath ] && echo yes").output.trim() == "yes")
            if (!present) {
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
        // Philips, giriş kaynağı tuşunu yalnızca "input keyevent" yoluyla gelince güvenilir biçimde açıyor
        if (code in SLOW_KEYS) { shell("input keyevent $code"); return }
        if (!sendFast("k $code")) {
            shell("input keyevent $code")
        }
    }

    /** TV'deki bir dosyayı telefona indirir. */
    @Synchronized
    fun pull(remotePath: String, dst: java.io.File) {
        val d = dadb ?: throw IllegalStateException("Önce TV'ye bağlan")
        d.pull(dst, remotePath)
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

    private val serverLock = Any()

    /** Tuş sunucusuna bir liste komutu gönderip "END" gelene kadar satırları okur (aynı anda tek okuma). */
    private fun serverLines(cmd: String, timeoutSec: Long = 20): List<String>? = synchronized(serverLock) { serverLinesLocked(cmd, timeoutSec) }

    private fun serverLinesLocked(cmd: String, timeoutSec: Long): List<String>? {
        val s = fast ?: return null
        if (!sendFast(cmd)) return null
        val task = waiter.submit<List<String>> {
            val out = ArrayList<String>()
            while (true) {
                val l = s.source.readUtf8Line() ?: break
                if (l == "END") break
                out.add(l)
            }
            out
        }
        return try {
            task.get(timeoutSec, TimeUnit.SECONDS)
        } catch (e: java.util.concurrent.TimeoutException) {
            restartFastMode(s)
            throw e
        }
    }

    /** Kurulu tüm paketler (uygulama yöneticisi). */
    fun listPackages(): List<TvPackage> {
        val lines = runCatching { serverLines("P", 40) }.getOrNull()
        if (lines != null && lines.isNotEmpty()) {
            return lines.mapNotNull { l ->
                val p = l.split('\t')
                if (p.size < 7 || p[0] != "PKG") return@mapNotNull null
                TvPackage(p[1], p[2].ifBlank { appName(p[1]) }, p[3].contains('s'), p[3].contains('d'), p[4], p[5], p[6].toIntOrNull() ?: 0)
            }.filter { it.pkg != context.packageName }
        }
        // Yardımcı yoksa kabuk komutlarıyla (adlar paket adından)
        fun set(flag: String) = shell("pm list packages $flag").lines().map { it.removePrefix("package:").trim() }.filter { it.isNotEmpty() }.toSet()
        val sys = set("-s"); val dis = set("-d")
        return shell("pm list packages -f").lines().mapNotNull { l ->
            val t = l.removePrefix("package:").trim()
            val i = t.lastIndexOf('=')
            if (i <= 0) return@mapNotNull null
            val pkg = t.substring(i + 1)
            TvPackage(pkg, appName(pkg), pkg in sys, pkg in dis, "", t.substring(0, i), 0)
        }.filter { it.pkg != context.packageName }
    }

    /** TV'de şu an çalan medya (yardımcı yoksa ya da bir şey çalmıyorsa null). */
    fun nowPlaying(): NowPlaying? {
        val lines = runCatching { serverLines("N", 4) }.getOrNull() ?: return null
        val p = lines.firstOrNull { it.startsWith("NP\t") }?.split('\t') ?: return null
        if (p.size < 8) return null
        return NowPlaying(
            pkg = p[1], title = p[2], artist = p[3],
            durationMs = p[4].toLongOrNull() ?: -1, positionMs = p[5].toLongOrNull() ?: 0,
            playing = p[6] == "3", canSeek = ((p[7].toLongOrNull() ?: 0) and 256L) != 0L,
            at = android.os.SystemClock.elapsedRealtime()
        )
    }

    /** Çalan medyada [ms] konumuna atlar. */
    fun seekTo(ms: Long) { sendFast("s $ms") }

    /** Dosya aktarımı için ADB bağlantısı (uzun aktarımlar kilit tutmasın diye doğrudan verilir). */
    fun dadbOrThrow(): Dadb = dadb ?: throw IllegalStateException("Önce TV'ye ADB ile bağlan")

    /** Tuş sunucusundan uygulamaları TV'nin gösterdiği ad ve simgeleriyle alır. */
    private fun listAppsFromServer(): List<TvApp>? {
        val lines = serverLines("a") ?: return null
        runCatching { cacheFile(host).writeText(lines.joinToString("\n")) }
        return parseServerApps(lines)
    }

    /** Bu TV için en son alınan uygulama listesi (bağlanınca anında göstermek için). */
    fun cachedApps(ip: String): List<TvApp> =
        runCatching { parseServerApps(cacheFile(ip).readLines()) }.getOrDefault(emptyList())

    private fun cacheFile(ip: String) = File(context.cacheDir, "apps_${ip.replace(Regex("[^0-9A-Za-z.]"), "_")}.txt")

    private fun parseServerApps(lines: List<String>): List<TvApp> =
        lines.mapNotNull { l ->
            val p = l.split('\t')
            if (p.size < 4 || p[0] != "APP") return@mapNotNull null
            val pkg = p[1]
            if (pkg == context.packageName) return@mapNotNull null
            // Simge burada (arka planda) bir kez çözülür; arayüz hazır görüntüyü kullanır
            val icon = p.getOrNull(4)?.takeIf { it.isNotEmpty() }?.let {
                runCatching {
                    val b = android.util.Base64.decode(it, android.util.Base64.DEFAULT)
                    android.graphics.BitmapFactory.decodeByteArray(b, 0, b.size)?.asImageBitmap()
                }.getOrNull()
            }
            TvApp(pkg, p[2], p[3].ifBlank { appName(pkg) }, icon)
        }
            .distinctBy { it.pkg }
            .sortedWith(compareBy<TvApp> { knownRank(it.pkg) }.thenBy { it.name.lowercase() })

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

    /** TV ekranı açık mı (ADB: güç hizmetinin uyanıklık durumu); bilinmiyorsa null. */
    fun isScreenOn(): Boolean? = runCatching {
        val out = shell("dumpsys power | grep -m1 mWakefulness=")
        when {
            out.contains("Awake") -> true
            out.contains("Asleep") || out.contains("Dozing") || out.contains("Dreaming") -> false
            else -> null
        }
    }.getOrNull()

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

        val methods: List<() -> Pair<Int, Int>?> = listOf(
            // 1) cmd media_session (hızlı)
            { VOLUME_RE.find(run("cmd media_session volume --stream 3 --get"))?.let { it.groupValues[1].toInt() to it.groupValues[3].toInt() } },
            // 2) dumpsys audio içindeki STREAM_MUSIC bölümü
            {
                val dump = run("dumpsys audio")
                val start = dump.indexOf("STREAM_MUSIC:")
                if (start < 0) null else {
                    val sec = dump.substring(start, minOf(dump.length, start + 1500))
                    val max = Regex("""Max:\s*(\d+)""").find(sec)?.groupValues?.get(1)?.toIntOrNull()
                    val cur = Regex("""streamVolume:\s*(\d+)""").find(sec)?.groupValues?.get(1)?.toIntOrNull()
                        ?: Regex("""Current:[^\n]*?\):\s*(\d+)""").find(sec)?.groupValues?.get(1)?.toIntOrNull()
                    if (max != null && cur != null) cur to max else null
                }
            },
            // 3) eski "media" komutu (yavaş)
            { VOLUME_RE.find(run("media volume --stream 3 --get"))?.let { it.groupValues[1].toInt() to it.groupValues[3].toInt() } }
        )
        // Bu TV'de çalıştığı görülen yöntem önce denenir; her ses tuşunda tüm yöntemler sırayla çalışmaz
        volumeMethod?.let { i -> methods[i]()?.let { return it } }
        for (i in methods.indices) {
            if (i == volumeMethod) continue
            methods[i]()?.let { volumeMethod = i; return it }
        }
        return null
    }

    @Volatile private var volumeMethod: Int? = null

    @Synchronized
    private fun restartFastMode(stale: AdbStream) {
        if (fast !== stale) return
        runCatching { stale.close() }
        fast = null
        dadb?.let { startFastMode(it, exists = true) }
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
        volumeMethod = null
        runCatching { fast?.close() }
        fast = null
        runCatching { dadb?.close() }
        dadb = null
    }
}

/** Türkçe harfleri sadeleştirir (TV klavye haritasında karşılığı olmayabilir). */
private val TR_PLAIN = mapOf(
    'ı' to 'i', 'İ' to 'I', 'ğ' to 'g', 'Ğ' to 'G', 'ü' to 'u', 'Ü' to 'U',
    'ş' to 's', 'Ş' to 'S', 'ö' to 'o', 'Ö' to 'O', 'ç' to 'c', 'Ç' to 'C'
)

fun normalizeText(text: String): String =
    buildString(text.length) { for (c in text) append(if (c == '\n') ' ' else TR_PLAIN[c] ?: c) }

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

/** TV'de çalan medya; [at] bilginin alındığı an (konumu ilerletmek için). */
data class NowPlaying(
    val pkg: String, val title: String, val artist: String,
    val durationMs: Long, val positionMs: Long, val playing: Boolean, val canSeek: Boolean, val at: Long
) {
    /** Şu anki tahmini konum. */
    fun position(now: Long = android.os.SystemClock.elapsedRealtime()): Long {
        val p = if (playing) positionMs + (now - at) else positionMs
        return if (durationMs > 0) p.coerceIn(0, durationMs) else p.coerceAtLeast(0)
    }
}

/** Uygulama yöneticisindeki bir paket. */
data class TvPackage(
    val pkg: String, val label: String, val system: Boolean, val disabled: Boolean,
    val version: String, val apkPath: String, val splits: Int
)

data class TvApp(val pkg: String, val activity: String, val name: String, val icon: ImageBitmap? = null) {
    override fun equals(other: Any?) = other is TvApp && other.pkg == pkg && other.activity == activity && other.name == name
    override fun hashCode() = pkg.hashCode()
}

private val VOLUME_RE = Regex("""volume is (\d+) in range \[(\d+)\.\.(\d+)]""")

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
    val part = pkg.split('.').lastOrNull { it.lowercase() !in NAME_SKIP && it.length > 1 } ?: pkg.substringAfterLast('.')
    return part.replaceFirstChar { it.uppercase() }
}

private val NAME_SKIP = setOf("com", "tv", "android", "app", "apps", "mobile", "atv", "androidtv", "ninja", "tr", "net", "org", "www", "player", "leanback")

/**
 * Philips'in canlı yayın (uydu/anten) ekranı bu tuşları hızlı yoldan gelince çoğu zaman yok sayıyor;
 * kabuk komutuyla ("input keyevent") her seferinde kabul ediyor.
 * Giriş kaynağı, Rehber, Bilgi, Altyazı, Ses dili.
 */
private val SLOW_KEYS = setOf(178, 172, 165, 175, 222)
