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

    private class CountingSource(src: Source, private val total: Long, private val progress: (Float) -> Unit) : ForwardingSource(src) {
        private var done = 0L
        private var last = 0L
        override fun read(sink: Buffer, byteCount: Long): Long {
            val n = super.read(sink, byteCount)
            if (n > 0) {
                done += n
                val now = System.currentTimeMillis()
                if (now - last > 150 && total > 0) { last = now; progress((done.toFloat() / total).coerceIn(0f, 1f)) }
            }
            return n
        }
    }

    private class CountingSink(dst: Sink, private val total: Long, private val progress: (Float) -> Unit) : ForwardingSink(dst) {
        private var done = 0L
        private var last = 0L
        override fun write(source: Buffer, byteCount: Long) {
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

    // ---------------- APK yükle ----------------

    /** Seçilen .apk ya da .apks/.xapk/.apkm dosyasını kurar; kurulan paket adını döndürür (bulunabilirse). */
    fun installApk(context: Context, uri: Uri, stage: (String) -> Unit, progress: (Float) -> Unit): String? {
        val d = adb().dadbOrThrow()
        val name = displayName(context, uri).lowercase()
        val split = name.endsWith(".apks") || name.endsWith(".xapk") || name.endsWith(".apkm") || name.endsWith(".zip")
        val work = File(context.cacheDir, "kurulum").apply { deleteRecursively(); mkdirs() }
        try {
            if (!split) {
                // Paket adı için dosya telefonda geçici olarak tutulur
                stage("Hazırlanıyor…")
                val local = File(work, "base.apk")
                context.contentResolver.openInputStream(uri)!!.use { inp -> local.outputStream().use { inp.copyTo(it) } }
                stage("TV'ye gönderiliyor…")
                d.install(CountingSource(local.source(), local.length(), progress), local.length(), "-r", "-t")
                return packageName(context, local)
            }
            stage("Paket açılıyor…")
            val zip = File(work, "paket.zip")
            context.contentResolver.openInputStream(uri)!!.use { inp -> zip.outputStream().use { inp.copyTo(it) } }
            val apks = ArrayList<File>()
            ZipFile(zip).use { z ->
                z.entries().toList().filter { !it.isDirectory && it.name.endsWith(".apk", true) }.forEachIndexed { i, e ->
                    val f = File(work, "part$i.apk")
                    z.getInputStream(e).use { inp -> f.outputStream().use { inp.copyTo(it) } }
                    apks += f
                }
            }
            if (apks.isEmpty()) throw IOException("Pakette APK bulunamadı")
            stage("Kuruluyor…")
            progress(-1f)
            if (apks.size == 1) d.install(apks[0], "-r", "-t") else d.installMultiple(apks, "-r", "-t")
            return apks.firstNotNullOfOrNull { packageName(context, it) }
        } catch (e: IOException) {
            val m = e.message.orEmpty()
            throw IOException(friendlyInstallError(m))
        } finally {
            work.deleteRecursively()
        }
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
                d.pull(CountingSink(f.sink(), sizes[i]) { fr -> progress(((start + fr * sizes[i]) / total.coerceAtLeast(1)).toFloat()) }, path)
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

    /** Seçilen dosyaları TV'nin Download klasörüne gönderir. */
    fun sendFiles(context: Context, uris: List<Uri>, stage: (String) -> Unit, progress: (Float) -> Unit) {
        val d = adb().dadbOrThrow()
        val sizes = uris.map { sizeOf(context, it).coerceAtLeast(0) }
        val total = sizes.sum().coerceAtLeast(1)
        var done = 0L
        uris.forEachIndexed { i, u ->
            val name = displayName(context, u).replace("/", "_")
            stage(if (uris.size > 1) "Gönderiliyor (${i + 1}/${uris.size}): $name" else "Gönderiliyor: $name")
            val start = done
            context.contentResolver.openInputStream(u)!!.use { inp ->
                val src = CountingSource(inp.source(), sizes[i]) { fr -> progress(((start + fr * sizes[i]) / total).toFloat()) }
                d.push(src, "$TV_DOWNLOAD/$name", 33188 /* düzenli dosya, 0644 */, System.currentTimeMillis())
            }
            done += sizes[i]
        }
        // TV'nin medya kütüphanesi yeni dosyaları hemen görsün
        runCatching { sh("am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file://$TV_DOWNLOAD") }
        progress(1f)
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

    fun mkdir(dir: String, name: String) = check(sh("mkdir -p ${q("$dir/$name")} 2>&1"), "Klasör oluşturma")
    fun rename(f: TvFile, newName: String) = check(sh("mv ${q(f.path)} ${q(f.path.substringBeforeLast('/') + "/" + newName)} 2>&1"), "Yeniden adlandırma")
    fun delete(f: TvFile) = check(sh("rm -rf ${q(f.path)} 2>&1"), "Silme")

    /** TV'deki dosyayı telefonun İndirilenler/TV Kumanda klasörüne indirir. */
    fun download(context: Context, f: TvFile, progress: (Float) -> Unit): Uri {
        val d = adb().dadbOrThrow()
        val mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.name.substringAfterLast('.', "").lowercase())
            ?: "application/octet-stream"
        return saveToDownloads(context, f.name, mime) { out -> d.pull(CountingSink(out.sink(), f.size, progress), f.path) }
    }
}
