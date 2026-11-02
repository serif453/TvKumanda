package com.serif.tvkumanda

import java.net.HttpURLConnection
import java.net.URL

/**
 * Güncelleme denetimi: yalnızca kullanıcı dokununca GitHub'daki son sürüme bakar; arka planda hiçbir şey yapmaz,
 * indirme ve kurulum tarayıcıdan, kullanıcı eliyle olur.
 */
object UpdateCheck {
    const val RELEASES_PAGE = "https://github.com/serif453/TvKumanda/releases/latest"
    private const val API = "https://api.github.com/repos/serif453/TvKumanda/releases/latest"

    sealed class Result {
        data class Available(val version: String) : Result()
        object UpToDate : Result()
        data class Failed(val message: String) : Result()
    }

    /** Ağ işlemi: ekranı çizen iş parçacığında çağrılmaz. */
    fun check(current: String): Result = try {
        val c = URL(API).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 8000
            c.readTimeout = 8000
            c.setRequestProperty("Accept", "application/vnd.github+json")
            c.setRequestProperty("User-Agent", "TvKumanda")
            if (c.responseCode == 200) evaluate(current, c.inputStream.bufferedReader().use { it.readText() })
            else Result.Failed("GitHub şu an yanıt vermiyor, biraz sonra dene")
        } finally {
            c.disconnect()
        }
    } catch (e: java.io.IOException) {
        Result.Failed("İnternete ulaşılamadı")
    }

    fun evaluate(current: String, json: String): Result {
        val tag = parseTag(json) ?: return Result.Failed("Sürüm bilgisi okunamadı")
        return if (isNewer(current, tag)) Result.Available(tag.removePrefix("v")) else Result.UpToDate
    }

    fun parseTag(json: String): String? = Regex("\"tag_name\"\\s*:\\s*\"([^\"]+)\"").find(json)?.groupValues?.get(1)

    /** Sürümler ondalık sayı değil, parça parça karşılaştırılır: 0.100, 0.99'dan yenidir. */
    fun isNewer(current: String, tag: String): Boolean {
        val a = parts(current) ?: return false
        val b = parts(tag) ?: return false
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return y > x
        }
        return false
    }

    private fun parts(v: String): List<Int>? {
        val list = v.trim().removePrefix("v").split('.').map { it.toIntOrNull() ?: return null }
        return list.ifEmpty { null }
    }
}
