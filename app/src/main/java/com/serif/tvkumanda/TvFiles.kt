package com.serif.tvkumanda

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.OpenableColumns
import okio.Buffer
import okio.ForwardingSink
import okio.ForwardingSource
import okio.Sink
import okio.Source
import okio.sink
import okio.source
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Araçlar 3. aşama: uygulamalar ve dosyalar (yalnızca ADB).
 * Tüm işlevler bloklayıcıdır; arka plan iş parçacığından çağrılmalıdır.
 * [progress] geri çağrıları 0..1 arası değer alır.
 */
object TvFiles {

    private fun adb(): TvRemote = TvTools.adb ?: throw IllegalStateException("Önce TV'ye ADB ile bağlan")
    private fun sh(cmd: String) = adb().shell(cmd)

    /** Kabuk için tek tırnaklı güvenli metin. */
    fun q(s: String) = "'" + s.replace("'", "'\\''") + "'"

    // ---------------- Uygulamalar ----------------

    fun packages(): List<TvPackage> = adb().listPackages()

    data class PackageDetail(val version: String, val sizeKb: Long?, val installed: String)

    fun detail(p: TvPackage): PackageDetail {
        val dir = p.apkPath.substringBeforeLast('/', "")
        val out = sh(
            "dumpsys package ${p.pkg} | grep -m3 -E 'versionName|firstInstallTime'; echo '##'; " +
                (if (dir.isNotBlank() && !p.system) "du -sk ${q(dir)} 2>/dev/null" else "du -sk ${q(p.apkPath)} 2>/dev/null")
        )
        val head = out.substringBefore("##")
        val version = Regex("""versionName=(\S+)""").find(head)?.groupValues?.get(1) ?: p.version
        val installed = Regex("""firstInstallTime=([\d-]+)""").find(head)?.groupValues?.get(1).orEmpty()
        val size = out.substringAfter("##").trim().split(Regex("\\s+")).firstOrNull()?.toLongOrNull()
        return PackageDetail(version, size, installed)
    }

    private fun check(out: String, what: String) {
        val o = out.trim()
        if (o.contains("Exception", true) || o.contains("Failure", true) || o.contains("Error", true) || o.contains("not allowed", true))
            throw IOException("$what olmadı: ${o.lines().firstOrNull { it.isNotBlank() }?.take(120) ?: o.take(120)}")
    }

    fun open(p: TvPackage) {
        fun failed(o: String) = o.contains("No activities", true) || o.contains("aborted", true)
        if (!failed(sh("monkey -p ${p.pkg} -c android.intent.category.LEANBACK_LAUNCHER 1 2>&1"))) return
        if (failed(sh("monkey -p ${p.pkg} -c android.intent.category.LAUNCHER 1 2>&1"))) throw IOException("Bu uygulamanın açılacak bir ekranı yok")
    }

    fun forceStop(p: TvPackage) = check(sh("am force-stop ${p.pkg}"), "Durdurma")
    fun clearData(p: TvPackage) = check(sh("pm clear ${p.pkg}").let { if (it.contains("Success")) "" else it }, "Veri silme")
    fun disable(p: TvPackage) = check(sh("pm disable-user --user 0 ${p.pkg}").let { if (it.contains("disabled")) "" else it }, "Devre dışı bırakma")
    fun enable(p: TvPackage) = check(sh("pm enable ${p.pkg}").let { if (it.contains("enabled")) "" else it }, "Etkinleştirme")
    fun uninstall(p: TvPackage) = check(sh("pm uninstall ${p.pkg}").let { if (it.contains("Success")) "" else it }, "Kaldırma")

    // ---------------- İlerleme sayan akışlar ----------------

    /** Süren bir aktarımı durdurmak için. */
    class Cancel { @Volatile var cancelled = false }

    private class CountingSource(src: Source, private val total: Long, private val progress: (Float) -> Unit, private val cancel: Cancel? = null) : ForwardingSource(src) {
        private var done = 0L
        private var last = 0L
        override fun read(sink: Buffer, byteCount: Long): Long {
            if (cancel?.cancelled == true) throw IOException("Durduruldu")
            val n = super.read(sink, byteCount)
            if (n > 0) {
                done += n
                val now = System.currentTimeMillis()
                if (now - last > 150 && total > 0) { last = now; progress((done.toFloat() / total).coerceIn(0f, 1f)) }
            }
            return n
        }
    }

    private class CountingSink(dst: Sink, private val total: Long, private val progress: (Float) -> Unit, private val cancel: Cancel? = null) : ForwardingSink(dst) {
        private var done = 0L
        private var last = 0L
        override fun write(source: Buffer, byteCount: Long) {
            if (cancel?.cancelled == true) throw IOException("Durduruldu")
            super.write(source, byteCount)
            done += byteCount
            val now = System.currentTimeMillis()
            if (now - last > 150 && total > 0) { last = now; progress((done.toFloat() / total).coerceIn(0f, 1f)) }
        }
    }

    private fun sizeOf(context: Context, uri: Uri): Long =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else -1
        } ?: -1

    fun displayName(context: Context, uri: Uri): String =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: uri.lastPathSegment ?: "dosya"

    // ---------------- Telefondan TV'ye aktarım (hibrit) ----------------

    /** Hızlı yolun bu oturumda çalışmadığı TV'ler (bir daha denenip vakit kaybedilmez). */
    private val fastBroken = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** Bu boyutun altındaki dosyalar doğrudan güvenli yoldan gider (hızlı yolu kurmak daha uzun sürer). */
    private const val FAST_MIN = 4L * 1024 * 1024

    /** md5 ile içerik kontrolü yapılan en büyük dosya. */
    private const val MD5_MAX = 32L * 1024 * 1024

    /**
     * Telefondaki bir akışı TV'deki [remote] dosyasına yazar. İki yol vardır:
     *
     *  - Hızlı yol: TV'de "nc" ile geçici bir dinleyici açılır, dosya Wi-Fi üzerinden doğrudan yazılır.
     *  - Güvenli yol: ADB'nin dosya gönderme yolu; her parçada TV'nin onayı beklenir (dadb'ye eklenen
     *    akış denetimi), her TV'de çalışır.
     *
     * Büyük dosyalar önce hızlı yolu dener; kurulamaz ya da yarıda koparsa güvenli yoldan baştan gönderilir.
     * Dosya önce "<ad>.part" olarak yazılır, boyutu (küçüklerde md5'i) doğrulanınca asıl adına taşınır ve
     * herkesin okuyabileceği izin verilir. Hata ya da Durdur'da yarım dosya kalmaz.
     *
     * [open] her denemede akışı baştan açar (yedek yola geçerken dosya yeniden okunur).
     */
    private fun pushStream(open: () -> java.io.InputStream, size: Long, remote: String, progress: (Float) -> Unit, cancel: Cancel?, roomFactor: Int = 1) {
        val tv = adb()
        val part = "$remote.part"
        if (size > 0) ensureRoom(remote.substringBeforeLast('/', "/").ifEmpty { "/" }, size * roomFactor)
        val wantMd5 = size in 1..MD5_MAX
        var md5: String? = null
        fun hashing(inp: java.io.InputStream): java.io.InputStream =
            if (!wantMd5) inp else java.security.DigestInputStream(inp, java.security.MessageDigest.getInstance("MD5")).also { dis ->
                lastDigest = dis
            }
        try {
            var sent = false
            if ((size < 0 || size >= FAST_MIN) && tv.tvIp !in fastBroken) {
                sent = try {
                    open().use { inp -> fastPush(hashing(inp), size, part, progress, cancel) }
                } catch (e: Exception) {
                    if (cancel?.cancelled == true) throw IOException("Durduruldu")
                    runCatching { sh("rm -f ${q(part)}") }
                    false
                }
                if (!sent) fastBroken += tv.tvIp
            }
            if (!sent) {
                progress(0f)
                open().use { inp ->
                    val d = tv.dadbOrThrow()
                    d.push(CountingSource(hashing(inp).source(), size, progress, cancel), part, 33188 /* düzenli dosya, 0644 */, System.currentTimeMillis())
                }
            }
            md5 = lastDigest?.messageDigest?.digest()?.joinToString("") { "%02x".format(it) }
            verifyAndMove(part, remote, size, if (wantMd5) md5 else null)
            progress(1f)
        } catch (e: Exception) {
            runCatching { sh("rm -f ${q(part)}") }
            if (cancel?.cancelled == true) throw IOException("Durduruldu")
            throw e
        } finally {
            lastDigest = null
        }
    }

    @Volatile private var lastDigest: java.security.DigestInputStream? = null

    /** Boyutu (ve verildiyse md5'i) kontrol edip ".part" dosyasını asıl adına taşır, okuma izni verir. */
    private fun verifyAndMove(part: String, remote: String, size: Long, md5: String?) {
        if (size > 0) {
            // TV'deki yazma, akış kapandıktan kısa süre sonra biter
            var got: Long? = null
            for (i in 0 until 40) {
                got = sh("stat -c %s ${q(part)} 2>/dev/null").trim().toLongOrNull()
                if (got == size) break
                Thread.sleep(150)
            }
            if (got != size) throw IOException("Dosya TV'ye eksik ulaştı (${TvTools.bytes(got ?: 0)} / ${TvTools.bytes(size)}). TV'nin depolaması dolmuş olabilir.")
        }
        if (md5 != null) {
            val sum = sh("(md5sum ${q(part)} || toybox md5sum ${q(part)}) 2>/dev/null").trim().substringBefore(' ').lowercase()
            if (sum.length == 32 && sum != md5) throw IOException("Dosya TV'ye bozuk ulaştı, tekrar dene")
        }
        val r = sh("mv -f ${q(part)} ${q(remote)} && chmod 644 ${q(remote)} && echo tamam").trim()
        if (!r.endsWith("tamam")) throw IOException("Dosya TV'de kaydedilemedi: ${r.take(100)}")
    }

    /** Göndermeden önce yer kontrolü: yer yoksa hiç başlamadan söyler. */
    private fun ensureRoom(dir: String, need: Long) {
        val free = runCatching {
            sh("df -k ${q("$dir/")} 2>/dev/null | tail -1").trim().split(Regex("\\s+")).getOrNull(3)?.toLongOrNull()?.times(1024)
        }.getOrNull() ?: return
        val reserve = 50L * 1024 * 1024
        if (free < need + reserve) throw IOException("TV'de yeterli yer yok: ${TvTools.bytes(need)} gerekiyor, ${TvTools.bytes(free)} boş")
    }

    /** Hızlı yol: TV'de nc dinleyicisi, dosya Wi-Fi üzerinden. Kurulamazsa false döner (hiç veri gitmeden). */
    private fun fastPush(input: java.io.InputStream, size: Long, remote: String, progress: (Float) -> Unit, cancel: Cancel?): Boolean {
        val tv = adb()
        val d = tv.dadbOrThrow()
        val nc = runCatching { d.shell("command -v nc >/dev/null 2>&1 && echo nc || (toybox nc --help >/dev/null 2>&1 && echo toybox)").output.trim() }.getOrDefault("")
        if (nc != "nc" && nc != "toybox") return false
        val bin = if (nc == "nc") "nc" else "toybox nc"
        val port = (40000..49999).random()
        val listener = Thread {
            runCatching { d.shell("$bin -l -p $port < /dev/null > ${q(remote)} 2>/dev/null; echo NC_BITTI") }
        }.apply { isDaemon = true; start() }
        // Dinleyicinin açılmasını bekle
        var sock: java.net.Socket? = null
        val until = System.currentTimeMillis() + 5000
        while (sock == null && System.currentTimeMillis() < until) {
            sock = runCatching { java.net.Socket().apply { connect(java.net.InetSocketAddress(tv.tvIp, port), 1500) } }.getOrNull()
            if (sock == null) Thread.sleep(200)
        }
        if (sock == null) {
            runCatching { sh("pkill -f 'nc -l -p $port'") }
            return false
        }
        sock.use { s ->
            s.soTimeout = 30_000
            val out = s.getOutputStream()
            val buf = ByteArray(128 * 1024)
            var done = 0L
            var last = 0L
            while (true) {
                if (cancel?.cancelled == true) throw IOException("Durduruldu")
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                done += n
                val now = System.currentTimeMillis()
                if (size > 0 && now - last > 150) { last = now; progress((done.toFloat() / size).coerceIn(0f, 1f)) }
            }
            out.flush()
            s.shutdownOutput()
        }
        listener.join(30_000)
        return true
    }

    /** TV ile aynı ağdaki telefon adresi. */
    private fun localIpFor(tvIp: String): String? = runCatching {
        val prefix = tvIp.substringBeforeLast('.')
        val all = java.net.NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }.filterIsInstance<java.net.Inet4Address>()
        (all.firstOrNull { it.hostAddress?.startsWith("$prefix.") == true } ?: all.firstOrNull { it.isSiteLocalAddress })?.hostAddress
    }.getOrNull()

    // ---------------- APK yükle ----------------

    /** Seçilen .apk ya da .apks/.xapk/.apkm dosyasını kurar; kurulan paket adını döndürür (bulunabilirse). */
    fun installApk(context: Context, uri: Uri, stage: (String) -> Unit, progress: (Float) -> Unit, cancel: Cancel? = null): String? {
        val name = displayName(context, uri).lowercase()
        val split = name.endsWith(".apks") || name.endsWith(".xapk") || name.endsWith(".apkm") || name.endsWith(".zip")
        val work = File(context.cacheDir, "kurulum").apply { deleteRecursively(); mkdirs() }
        try {
            stage("Hazırlanıyor…")
            val apks = ArrayList<File>()
            if (!split) {
                val local = File(work, "base.apk")
                context.contentResolver.openInputStream(uri)!!.use { inp -> local.outputStream().use { inp.copyTo(it) } }
                apks += local
            } else {
                val zip = File(work, "paket.zip")
                context.contentResolver.openInputStream(uri)!!.use { inp -> zip.outputStream().use { inp.copyTo(it) } }
                ZipFile(zip).use { z ->
                    z.entries().toList().filter { !it.isDirectory && it.name.endsWith(".apk", true) }.forEachIndexed { i, e ->
                        val f = File(work, "part$i.apk")
                        z.getInputStream(e).use { inp -> f.outputStream().use { inp.copyTo(it) } }
                        apks += f
                    }
                }
                if (apks.isEmpty()) throw IOException("Pakette APK bulunamadı")
            }
            installLocalApks(apks, stage, progress, cancel)
            return apks.firstNotNullOfOrNull { packageName(context, it) }
        } finally {
            work.deleteRecursively()
        }
    }

    /** Telefondaki APK parçalarını TV'ye gönderip kurar. */
    private fun installLocalApks(apks: List<File>, stage: (String) -> Unit, progress: (Float) -> Unit, cancel: Cancel?) {
        val total = apks.sumOf { it.length() }.coerceAtLeast(1)
        var done = 0L
        val remotes = apks.mapIndexed { i, f ->
            val r = "/data/local/tmp/tvk_kur_$i.apk"
            stage(if (apks.size > 1) "TV'ye gönderiliyor (${i + 1}/${apks.size})…" else "TV'ye gönderiliyor…")
            val start = done
            pushStream({ f.inputStream() }, f.length(), r, { v -> progress(((start + v * f.length()) / total).toFloat()) }, cancel, roomFactor = 2)
            done += f.length()
            r
        }
        stage("Kuruluyor…")
        progress(-1f)
        try {
            installRemoteApks(remotes)
        } finally {
            runCatching { sh("rm -f /data/local/tmp/tvk_kur_*.apk") }
        }
    }

    /** TV'de duran APK parçalarını kurar (tek parça: pm install, çok parça: kurulum oturumu). */
    private fun installRemoteApks(remotes: List<String>) {
        if (remotes.size == 1) {
            val out = sh("pm install -r -t ${q(remotes[0])} 2>&1")
            if (!out.contains("Success")) throw IOException(friendlyInstallError(out.trim()))
            return
        }
        val sizes = remotes.map { sh("stat -c %s ${q(it)}").trim().toLongOrNull() ?: 0L }
        val create = sh("pm install-create -r -t -S ${sizes.sum()} 2>&1")
        val session = Regex("""\[(\d+)]""").find(create)?.groupValues?.get(1) ?: throw IOException(friendlyInstallError(create.trim()))
        remotes.forEachIndexed { i, r ->
            val w = sh("pm install-write -S ${sizes[i]} $session part$i ${q(r)} 2>&1")
            if (!w.contains("Success")) { sh("pm install-abandon $session"); throw IOException(friendlyInstallError(w.trim())) }
        }
        val commit = sh("pm install-commit $session 2>&1")
        if (!commit.contains("Success")) throw IOException(friendlyInstallError(commit.trim()))
    }

    private fun friendlyInstallError(m: String): String = when {
        m.contains("INSUFFICIENT_STORAGE") -> "TV'de yeterli boş alan yok"
        m.contains("UPDATE_INCOMPATIBLE") || m.contains("SIGNATURES") -> "Aynı uygulamanın farklı imzalı bir sürümü kurulu; önce onu kaldır"
        m.contains("VERSION_DOWNGRADE") -> "TV'de daha yeni bir sürüm kurulu"
        m.contains("NO_MATCHING_ABIS") -> "Bu APK TV'nin işlemcisine uygun değil"
        m.contains("OLDER_SDK") -> "Bu APK TV'nin Android sürümü için fazla yeni"
        m.contains("MISSING_SPLIT") -> "Paketin bazı parçaları eksik"
        else -> "Kurulum olmadı: ${m.removePrefix("Install failed: ").take(140)}"
    }

    private fun packageName(context: Context, apk: File): String? = runCatching {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageArchiveInfo(apk.absolutePath, 0)?.packageName
    }.getOrNull()

    // ---------------- Uygulama yedekle ----------------

    /** Uygulamanın APK'sını (parçalıysa .apks) telefonun İndirilenler/TV Kumanda klasörüne kaydeder; dosya adını döndürür. */
    fun backup(context: Context, p: TvPackage, progress: (Float) -> Unit): Pair<String, Uri> {
        val d = adb().dadbOrThrow()
        val paths = sh("pm path ${p.pkg}").lines().map { it.removePrefix("package:").trim() }.filter { it.endsWith(".apk") }
        if (paths.isEmpty()) throw IOException("Uygulamanın dosyası bulunamadı")
        val sizes = paths.map { sh("stat -c %s ${q(it)}").trim().toLongOrNull() ?: 0L }
        val total = sizes.sum()
        val base = safeName("${p.label}_${p.version.ifBlank { "yedek" }}")
        val work = File(context.cacheDir, "yedek").apply { deleteRecursively(); mkdirs() }
        try {
            var done = 0L
            val files = paths.mapIndexed { i, path ->
                val f = File(work, if (i == 0) "base.apk" else path.substringAfterLast('/'))
                val start = done
                d.pull(CountingSink(f.sink(), sizes[i], { fr -> progress(((start + fr * sizes[i]) / total.coerceAtLeast(1)).toFloat()) }), path)
                done += sizes[i]
                f
            }
            val (fileName, mime, src) = if (files.size == 1) Triple("$base.apk", "application/vnd.android.package-archive", files[0])
            else {
                val z = File(work, "$base.apks")
                ZipOutputStream(z.outputStream()).use { zo ->
                    for (f in files) { zo.putNextEntry(ZipEntry(f.name)); f.inputStream().use { it.copyTo(zo) }; zo.closeEntry() }
                }
                Triple("$base.apks", "application/zip", z)
            }
            val uri = saveToDownloads(context, fileName, mime) { out -> src.inputStream().use { it.copyTo(out) } }
            return fileName to uri
        } finally {
            work.deleteRecursively()
        }
    }

    private fun safeName(s: String) = s.replace(Regex("""[\\/:*?"<>|\s]+"""), "_").trim('_').take(80).ifBlank { "dosya" }

    /** Telefonun İndirilenler/TV Kumanda klasörüne yazar; paylaşmak için adresini döndürür. */
    fun saveToDownloads(context: Context, name: String, mime: String, write: (java.io.OutputStream) -> Unit): Uri {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) throw IOException("Kaydetme Android 10 ve üstünde çalışır")
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, "Download/TV Kumanda")
        }
        val r = context.contentResolver
        val uri = r.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: throw IOException("İndirilenler klasörüne yazılamadı")
        try {
            r.openOutputStream(uri)!!.use(write)
        } catch (e: Exception) {
            runCatching { r.delete(uri, null, null) }
            throw e
        }
        return uri
    }

    // ---------------- Dosya gönder ----------------

    const val TV_DOWNLOAD = "/sdcard/Download"

    /** Seçilen dosyaları TV'deki [dest] klasörüne (varsayılan Download) gönderir. Durdurulursa yarım dosya silinir. */
    fun sendFiles(context: Context, uris: List<Uri>, stage: (String) -> Unit, progress: (Float) -> Unit, dest: String = TV_DOWNLOAD, cancel: Cancel? = null) {
        val sizes = uris.map { sizeOf(context, it).coerceAtLeast(0) }
        val total = sizes.sum().coerceAtLeast(1)
        var done = 0L
        uris.forEachIndexed { i, u ->
            val name = displayName(context, u).replace("/", "_")
            stage(if (uris.size > 1) "Gönderiliyor (${i + 1}/${uris.size}): $name" else "Gönderiliyor: $name")
            val start = done
            pushStream({ context.contentResolver.openInputStream(u)!! }, sizes[i], "$dest/$name", { fr -> progress(((start + fr * sizes[i]) / total).toFloat()) }, cancel)
            done += sizes[i]
        }
        scan(dest)
        progress(1f)
    }

    /** TV'nin medya kütüphanesi yeni dosyaları hemen görsün. */
    private fun scan(dir: String) { runCatching { sh("am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d ${q("file://$dir")}") } }

    // ---------------- Linkten indirme ----------------

    private class Download(val conn: java.net.HttpURLConnection, val name: String, val size: Long)

    /** Link akışı: ilk açılış hazır bağlantıyı kullanır, yedek yola geçilirse link yeniden açılır. */
    private fun linkOpener(url: String, first: Download): () -> java.io.InputStream {
        var used = false
        return {
            if (!used) { used = true; first.conn.inputStream } else openLink(url).conn.inputStream
        }
    }

    private fun openLink(url: String): Download {
        var u = java.net.URL(url)
        var conn: java.net.HttpURLConnection
        var hops = 0
        while (true) {
            conn = (u.openConnection() as java.net.HttpURLConnection).apply {
                connectTimeout = 10_000; readTimeout = 30_000; instanceFollowRedirects = false
                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) TVKumanda")
            }
            val code = conn.responseCode
            if (code in 300..399 && hops++ < 8) {
                val loc = conn.getHeaderField("Location") ?: break
                u = java.net.URL(u, loc); conn.disconnect(); continue
            }
            if (code !in 200..299) throw IOException("Link açılmadı (HTTP $code)")
            break
        }
        val cd = conn.getHeaderField("Content-Disposition").orEmpty()
        val fromHeader = Regex("""filename\*?=(?:UTF-8'')?"?([^";]+)"?""", RegexOption.IGNORE_CASE).find(cd)?.groupValues?.get(1)
            ?.let { runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) }
        val fromPath = u.path.substringAfterLast('/').let { runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) }
        val name = (fromHeader ?: fromPath).replace(Regex("""[\\/:*?"<>|]"""), "_").trim().ifBlank { "indirilen_dosya" }
        return Download(conn, name, conn.contentLengthLong)
    }

    /** Linkteki dosyayı telefon indirir ve anında TV'deki [dest] klasörüne yazar; dosya adını döndürür. */
    fun downloadLinkToTv(url: String, dest: String, stage: (String) -> Unit, progress: (Float) -> Unit, cancel: Cancel?): String {
        stage("Bağlanılıyor…")
        val dl = openLink(url)
        try {
            stage("İndiriliyor: ${dl.name}")
            if (dl.size <= 0) progress(-1f)
            pushStream(linkOpener(url, dl), if (dl.size > 0) dl.size else -1, "$dest/${dl.name}", progress, cancel)
        } finally { dl.conn.disconnect() }
        scan(dest)
        return dl.name
    }

    /** Linkteki APK'yı indirip TV'ye kurar; dosya adını döndürür. */
    fun installFromLink(url: String, stage: (String) -> Unit, progress: (Float) -> Unit, cancel: Cancel?): String {
        stage("Bağlanılıyor…")
        val dl = openLink(url)
        val remote = "/data/local/tmp/tvk_kur_0.apk"
        try {
            stage("İndiriliyor: ${dl.name}")
            if (dl.size <= 0) progress(-1f)
            pushStream(linkOpener(url, dl), if (dl.size > 0) dl.size else -1, remote, progress, cancel, roomFactor = 2)
        } finally { dl.conn.disconnect() }
        stage("Kuruluyor…")
        progress(-1f)
        try { installRemoteApks(listOf(remote)) } finally { runCatching { sh("rm -f $remote") } }
        return dl.name
    }

    // ---------------- TV dosya gezgini ----------------

    data class TvFile(val path: String, val name: String, val dir: Boolean, val size: Long, val modified: Long)

    /** TV'deki depolama alanları: dahili depolama ve takılı USB bellekler. */
    fun roots(): List<Pair<String, String>> {
        val out = arrayListOf("/sdcard" to "Dahili depolama")
        sh("ls /storage 2>/dev/null").lines().map { it.trim() }
            .filter { it.isNotEmpty() && it != "emulated" && it != "self" }
            .forEach { out += "/storage/$it" to "USB bellek ($it)" }
        return out
    }

    fun list(dir: String, showHidden: Boolean): List<TvFile> {
        val out = sh("find ${q(dir)}/ -mindepth 1 -maxdepth 1 -exec stat -L -c '%F|%s|%Y|%n' {} + 2>/dev/null")
        return out.lines().mapNotNull { l ->
            val p = l.split('|', limit = 4)
            if (p.size < 4) return@mapNotNull null
            val path = p[3].trimEnd('/').replace("//", "/")
            val name = path.substringAfterLast('/')
            if (name.isEmpty() || (!showHidden && name.startsWith("."))) return@mapNotNull null
            TvFile(path, name, p[0].contains("directory"), p[1].toLongOrNull() ?: 0, (p[2].toLongOrNull() ?: 0) * 1000)
        }.sortedWith(compareBy<TvFile> { !it.dir }.thenBy { it.name.lowercase() })
    }

    /** Klasörün bulunduğu depolamada boş ve toplam alan (bayt). */
    fun space(dir: String): Pair<Long, Long>? {
        val line = sh("df -k ${q("$dir/")} 2>/dev/null | tail -1").trim().split(Regex("\\s+"))
        val total = line.getOrNull(1)?.toLongOrNull() ?: return null
        val free = line.getOrNull(3)?.toLongOrNull() ?: return null
        return free * 1024 to total * 1024
    }

    fun isApk(f: TvFile) = !f.dir && f.name.endsWith(".apk", true)

    /** Dosyanın türü: video, müzik, fotoğraf (TV'de açılabilenler) ya da null. */
    fun mediaMime(f: TvFile): String? {
        if (f.dir) return null
        val ext = f.name.substringAfterLast('.', "").lowercase()
        val m = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            ?: when (ext) { "mkv" -> "video/x-matroska"; "ts" -> "video/mp2t"; "flac" -> "audio/flac"; else -> null }
        return m?.takeIf { it.startsWith("video/") || it.startsWith("audio/") || it.startsWith("image/") }
    }

    /** TV'de duran bir APK'yı kurar. */
    fun installFromTv(f: TvFile) {
        val tmp = "/data/local/tmp/tvk_kur.apk"
        val out = sh("cp ${q(f.path)} $tmp && pm install -r -t $tmp 2>&1; rm -f $tmp")
        if (!out.contains("Success")) throw IOException(friendlyInstallError(out.trim()))
    }

    /** TV'deki dosyayı TV'nin kendi oynatıcısında / galerisinde açar. */
    fun openOnTv(f: TvFile) {
        val mime = mediaMime(f) ?: throw IOException("Bu dosya türü TV'de açılamıyor")
        val real = if (f.path.startsWith("/sdcard")) "/storage/emulated/0" + f.path.removePrefix("/sdcard") else f.path
        val uri = "file://" + real.split('/').joinToString("/") { java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
        val args = "-a android.intent.action.VIEW -d ${q(uri)} -t ${q(mime)}"
        // Google TV, açamadığı dosyaları hiçbir şey yapmayan bir yer tutucuya yönlendiriyor: o da "uygulama yok" demek
        val resolved = sh("cmd package resolve-activity --brief $args 2>&1")
        if (resolved.contains("frameworkpackagestubs") || resolved.contains("No activity found", true))
            throw IOException("TV'de bu dosyayı açacak bir oynatıcı yok")
        val out = sh("am start $args 2>&1")
        if (out.contains("Error", true)) throw IOException("TV'de bu dosyayı açacak bir oynatıcı yok")
    }

    /** Fotoğrafı önizleme için telefona alır (büyük dosyalar alınmaz). */
    fun preview(context: Context, f: TvFile): File {
        if (f.size > 25L * 1024 * 1024) throw IOException("Fotoğraf önizleme için çok büyük; telefona indirebilirsin")
        val dst = File(context.cacheDir, "onizleme").apply { mkdirs(); listFiles()?.forEach { it.delete() } }.let { File(it, "p_" + f.name) }
        adb().dadbOrThrow().pull(dst, f.path)
        return dst
    }

    fun mkdir(dir: String, name: String) = check(sh("mkdir -p ${q("$dir/$name")} 2>&1"), "Klasör oluşturma")
    fun rename(f: TvFile, newName: String) = check(sh("mv ${q(f.path)} ${q(f.path.substringBeforeLast('/') + "/" + newName)} 2>&1"), "Yeniden adlandırma")
    fun delete(f: TvFile) = check(sh("rm -rf ${q(f.path)} 2>&1"), "Silme")

    /** TV'deki dosyayı telefonun İndirilenler/TV Kumanda klasörüne indirir. */
    fun download(context: Context, f: TvFile, progress: (Float) -> Unit, cancel: Cancel? = null): Uri {
        val d = adb().dadbOrThrow()
        val mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.name.substringAfterLast('.', "").lowercase())
            ?: "application/octet-stream"
        return saveToDownloads(context, f.name, mime) { out -> d.pull(CountingSink(out.sink(), f.size, progress, cancel), f.path) }
    }
}
