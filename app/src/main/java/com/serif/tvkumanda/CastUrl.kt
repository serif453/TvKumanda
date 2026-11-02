package com.serif.tvkumanda

import java.net.URLDecoder

/**
 * Cast'e elle verilen ya da paylaşılan internet adresleri (saf kod, test edilir).
 * Chromecast ve DLNA videoyu bu adresten kendisi çeker; telefon yalnızca adresi iletir.
 */
object CastUrl {
    const val MAX_HISTORY = 5

    private val MIMES = mapOf(
        "mp4" to "video/mp4", "m4v" to "video/mp4", "webm" to "video/webm", "mov" to "video/quicktime",
        "mkv" to "video/x-matroska", "avi" to "video/x-msvideo", "ts" to "video/mp2t",
        "m3u8" to "application/x-mpegURL", "mpd" to "application/dash+xml",
        "mp3" to "audio/mpeg", "m4a" to "audio/mp4", "aac" to "audio/aac", "ogg" to "audio/ogg",
        "opus" to "audio/ogg", "flac" to "audio/flac", "wav" to "audio/wav",
        "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "png" to "image/png", "webp" to "image/webp", "gif" to "image/gif"
    )

    /** Video değil, sayfa adresi veren bilinen siteler: Chromecast bunları doğrudan oynatamaz. */
    private val PAGE_HOSTS = listOf(
        "youtube.com", "youtu.be", "netflix.com", "disneyplus.com", "primevideo.com", "twitch.tv", "instagram.com",
        "tiktok.com", "facebook.com", "x.com", "twitter.com", "vimeo.com", "dailymotion.com", "spotify.com"
    )

    /** Metindeki ilk adres; şemasız yazılmışsa (ornek.com/a.mp4) https eklenir. Adres yoksa null. */
    fun parse(text: String): String? {
        Regex("""(?i)https?://\S+""").find(text)?.let { return it.value.trimEnd('.', ',', ')', '"', '\'') }
        val t = text.trim()
        if (t.isEmpty() || t.any { it.isWhitespace() } || '@' in t) return null
        return if (Regex("""(?i)^(?:[a-z0-9-]+\.)+[a-z]{2,}(?::\d+)?(?:[/?#]\S*)?$""").matches(t)) "https://$t" else null
    }

    private fun path(url: String): String = url.substringAfter("://", url).substringBefore('#').substringBefore('?')

    fun host(url: String): String =
        path(url).substringBefore('/').substringAfterLast('@').substringBefore(':').lowercase()

    private fun extension(url: String): String {
        val p = path(url)
        if ('/' !in p) return ""
        return p.substringAfterLast('/').substringAfterLast('.', "").lowercase()
    }

    /** Uzantıdan biçim; uzantı tanınmıyorsa null. */
    fun mimeOf(url: String): String? = MIMES[extension(url)]

    /** Adres doğrudan bir medya dosyasını ya da yayınını mı gösteriyor? */
    fun isDirect(url: String): Boolean = mimeOf(url) != null

    /** YouTube gibi sitelerin sayfa adresi (dosya uzantısı taşıyanlar sayılmaz). */
    fun isKnownPage(url: String): Boolean {
        if (isDirect(url)) return false
        val h = host(url)
        return PAGE_HOSTS.any { h == it || h.endsWith(".$it") }
    }

    /** Sunucunun bildirdiği türden yalın biçim: "Text/HTML; charset=utf-8" → "text/html". */
    private fun plainType(contentType: String?): String = contentType.orEmpty().substringBefore(';').trim().lowercase()

    fun isPageType(contentType: String?): Boolean = plainType(contentType).let { it == "text/html" || it == "application/xhtml+xml" }

    fun isMediaType(contentType: String?): Boolean = plainType(contentType).let {
        it.startsWith("video/") || it.startsWith("audio/") || it.startsWith("image/") || "mpegurl" in it || it == "application/dash+xml"
    }

    fun kindOf(mime: String?): MediaKind {
        val m = mime.orEmpty().lowercase()
        return when {
            m.startsWith("audio/") -> MediaKind.MUSIC
            m.startsWith("image/") -> MediaKind.PHOTO
            else -> MediaKind.VIDEO
        }
    }

    /** Oynatıcıda görünecek ad: dosya adı, yoksa site adı. */
    fun title(url: String): String {
        val p = path(url)
        val last = if ('/' in p) p.substringAfterLast('/') else ""
        val name = runCatching { URLDecoder.decode(last.replace("+", "%2B"), "UTF-8") }.getOrDefault(last)
        return name.ifBlank { host(url) }
    }

    /** Son kullanılanlar: yeni adres başa gelir, aynısı tekrarlanmaz. */
    fun remember(history: List<String>, url: String): List<String> = (listOf(url) + history.filter { it != url }).take(MAX_HISTORY)
}
