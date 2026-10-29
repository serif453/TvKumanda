package com.serif.tvkumanda

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * Altyazı dönüştürücü: SRT dosyalarını Chromecast'in anladığı WebVTT biçimine çevirir.
 * Türkçe SRT'ler çoğu zaman UTF-8 değil Windows-1254 kodlamasıyla gelir; geçersiz UTF-8
 * görülürse Windows-1254 olarak okunur (yoksa ş, ğ, ı bozuk görünür).
 */
object Subtitles {

    fun decode(bytes: ByteArray): String {
        var b = bytes
        // UTF-8 BOM
        if (b.size >= 3 && b[0] == 0xEF.toByte() && b[1] == 0xBB.toByte() && b[2] == 0xBF.toByte()) b = b.copyOfRange(3, b.size)
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(b)).toString()
        } catch (e: CharacterCodingException) {
            String(b, runCatching { Charset.forName("windows-1254") }.getOrDefault(Charsets.ISO_8859_1))
        }
    }

    /** SRT (ya da zaten VTT) metnini WebVTT'ye çevirir. */
    fun toVtt(text: String): String {
        val t = text.replace("\r\n", "\n").replace('\r', '\n').trimStart('﻿')
        if (t.trimStart().startsWith("WEBVTT")) return t
        // 00:00:01,500 --> 00:00:03,000  →  00:00:01.500 --> 00:00:03.000
        val body = Regex("""(\d{1,2}:\d{2}:\d{2}),(\d{1,3})""").replace(t) { m ->
            val (hms, ms) = m.destructured
            val h = hms.padStart(8, '0')
            "$h.${ms.padEnd(3, '0')}"
        }
        return "WEBVTT\n\n" + body.trim() + "\n"
    }

    fun convert(bytes: ByteArray): ByteArray = toVtt(decode(bytes)).toByteArray(Charsets.UTF_8)
}
