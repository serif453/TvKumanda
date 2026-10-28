package com.serif.tvkumanda

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size

// Sütun adları eski Android sürümlerinde de aynıdır (MediaColumns sabitleri Android 10+)
private const val COL_BUCKET = "bucket_id"
private const val COL_BUCKET_NAME = "bucket_display_name"
private const val COL_DURATION = "duration"

/** Telefondaki bir medya dosyası. */
data class MediaItem(
    val id: Long,
    val uri: Uri,
    val title: String,
    val subtitle: String,
    val mime: String,
    val kind: MediaKind,
    val durationMs: Long = 0,
    val albumId: Long = 0
)

/** Albüm (fotoğraf/video klasörü ya da müzik albümü). */
data class MediaAlbum(
    val id: Long,
    val name: String,
    val count: Int,
    val cover: MediaItem,
    val items: List<MediaItem>
)

/**
 * Telefonun medya kütüphanesini (MediaStore) okur: fotoğraf ve videolar klasörlere,
 * müzikler albümlere göre gruplanır. Küçük resimler bellekte önbelleğe alınır.
 * Tüm işlevler bloklayıcıdır; arka plan iş parçacığından çağrılmalıdır.
 */
class MediaLibrary(private val context: Context) {

    private val prefs = context.getSharedPreferences("medya", Context.MODE_PRIVATE)

    /**
     * Bu tür için istenecek izinler. Fotoğraf ve video kullanıcıya tek izin olarak
     * görünür ("Fotoğraflar ve videolar"); ikisi her zaman birlikte istenir, yoksa önce
     * fotoğrafa izin verilince video izni hiç istenmemiş olur.
     */
    fun permissionsFor(kind: MediaKind): Array<String> = when {
        Build.VERSION.SDK_INT >= 33 && kind == MediaKind.MUSIC -> arrayOf(Manifest.permission.READ_MEDIA_AUDIO)
        Build.VERSION.SDK_INT >= 34 -> arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
        )
        Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
        else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    /** İzin penceresi açılmadan önce çağrılır ("yalnızca seçilenler" cevabını tanımak için). */
    fun markAsked(kind: MediaKind) {
        if (kind != MediaKind.MUSIC) prefs.edit().putBoolean("gorsel_soruldu", true).apply()
    }

    private fun granted(p: String) = context.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    /**
     * Bu tür okunabilir mi: önce türün kendi izni kontrol edilir. Android 14'teki
     * "yalnızca seçilenler" izni ancak kullanıcı o pencerede bunu seçtiyse sayılır.
     */
    fun hasPermission(kind: MediaKind): Boolean {
        if (Build.VERSION.SDK_INT < 33) return granted(Manifest.permission.READ_EXTERNAL_STORAGE)
        val own = when (kind) {
            MediaKind.PHOTO -> Manifest.permission.READ_MEDIA_IMAGES
            MediaKind.VIDEO -> Manifest.permission.READ_MEDIA_VIDEO
            MediaKind.MUSIC -> Manifest.permission.READ_MEDIA_AUDIO
        }
        if (granted(own)) return true
        return Build.VERSION.SDK_INT >= 34 && kind != MediaKind.MUSIC &&
            prefs.getBoolean("gorsel_soruldu", false) &&
            granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
    }

    fun albums(kind: MediaKind): List<MediaAlbum> = when (kind) {
        MediaKind.PHOTO -> visual(kind, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
        MediaKind.VIDEO -> visual(kind, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
        MediaKind.MUSIC -> music()
    }

    private fun visual(kind: MediaKind, base: Uri): List<MediaAlbum> {
        val cols = mutableListOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE,
            COL_BUCKET,
            COL_BUCKET_NAME
        )
        if (kind == MediaKind.VIDEO) cols += COL_DURATION
        val items = ArrayList<MediaItem>()
        context.contentResolver.query(base, cols.toTypedArray(), null, null,
            "${MediaStore.MediaColumns.DATE_ADDED} DESC")?.use { c ->
            val iId = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val iName = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val iMime = c.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
            val iBucket = c.getColumnIndexOrThrow(COL_BUCKET)
            val iBucketName = c.getColumnIndexOrThrow(COL_BUCKET_NAME)
            val iDur = if (kind == MediaKind.VIDEO) c.getColumnIndex(COL_DURATION) else -1
            while (c.moveToNext()) {
                val id = c.getLong(iId)
                items += MediaItem(
                    id = id,
                    uri = ContentUris.withAppendedId(base, id),
                    title = c.getString(iName) ?: "",
                    subtitle = c.getString(iBucketName) ?: "",
                    mime = c.getString(iMime) ?: kind.fallbackMime,
                    kind = kind,
                    durationMs = if (iDur >= 0) c.getLong(iDur) else 0,
                    albumId = c.getLong(iBucket)
                )
            }
        }
        return group(items) { it.subtitle.ifBlank { "Diğer" } }
    }

    private fun music(): List<MediaAlbum> {
        val base = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val cols = arrayOf(
            MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.ALBUM_ID, MediaStore.Audio.Media.MIME_TYPE,
            MediaStore.Audio.Media.DURATION
        )
        val items = ArrayList<MediaItem>()
        context.contentResolver.query(base, cols, "${MediaStore.Audio.Media.IS_MUSIC} != 0", null,
            "${MediaStore.Audio.Media.ALBUM} COLLATE NOCASE, ${MediaStore.Audio.Media.TRACK}")?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val artist = c.getString(2)?.takeIf { it != "<unknown>" } ?: ""
                items += MediaItem(
                    id = id,
                    uri = ContentUris.withAppendedId(base, id),
                    title = c.getString(1) ?: "",
                    subtitle = artist,
                    mime = c.getString(5) ?: MediaKind.MUSIC.fallbackMime,
                    kind = MediaKind.MUSIC,
                    durationMs = c.getLong(6),
                    albumId = c.getLong(4)
                )
                albumNames[c.getLong(4)] = c.getString(3) ?: ""
            }
        }
        return group(items) { albumNames[it.albumId].orEmpty().ifBlank { "Bilinmeyen albüm" } }
    }

    private val albumNames = HashMap<Long, String>()

    private fun group(items: List<MediaItem>, name: (MediaItem) -> String): List<MediaAlbum> =
        items.groupBy { it.albumId }.map { (id, list) ->
            MediaAlbum(id, name(list.first()), list.size, list.first(), list)
        }.sortedByDescending { it.count }

    // ---------------- Küçük resimler ----------------

    private val thumbs = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    /** Küçük resim (yoksa null). Müzikte albüm kapağı. */
    fun thumbnail(item: MediaItem, px: Int = 320): Bitmap? {
        val key = "${item.kind}:${item.id}:$px"
        thumbs.get(key)?.let { return it }
        val bmp = runCatching {
            if (Build.VERSION.SDK_INT >= 29) {
                context.contentResolver.loadThumbnail(item.uri, Size(px, px), null)
            } else legacyThumbnail(item)
        }.getOrNull() ?: return null
        thumbs.put(key, bmp)
        return bmp
    }

    @Suppress("DEPRECATION")
    private fun legacyThumbnail(item: MediaItem): Bitmap? = when (item.kind) {
        MediaKind.PHOTO -> MediaStore.Images.Thumbnails.getThumbnail(
            context.contentResolver, item.id, MediaStore.Images.Thumbnails.MINI_KIND, null)
        MediaKind.VIDEO -> MediaStore.Video.Thumbnails.getThumbnail(
            context.contentResolver, item.id, MediaStore.Video.Thumbnails.MINI_KIND, null)
        MediaKind.MUSIC -> runCatching {
            val art = ContentUris.withAppendedId(Uri.parse("content://media/external/audio/albumart"), item.albumId)
            context.contentResolver.openInputStream(art)?.use { android.graphics.BitmapFactory.decodeStream(it) }
        }.getOrNull()
    }
}
