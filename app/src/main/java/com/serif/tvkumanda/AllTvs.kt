package com.serif.tvkumanda

import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException

/**
 * Tüm TV'lere komut — saf (Android'siz) kısım: komutlar ve hata metinleri.
 * Gönderme `NetSheets.kt` içindeki `AllTvSender` ile yapılır: kayıtlı her TV'ye en son çalışan
 * yöntemiyle (Standart/ADB) kısa, geçici bir bağlantı açılır; bağlı TV'de mevcut bağlantı kullanılır.
 */
object AllTvs {

    enum class Command(val label: String, val key: Int) {
        /** Uyku tuşu: açık TV'yi kapatır, kapalıysa açmaz (güç tuşu gibi aç/kapa yapmaz). */
        OFF("Kapat", 223),
        /** Uyandırma tuşu; kapalı TV için ayrıca Wi-Fi ile uyandırma sinyali gönderilir. */
        ON("Aç", 224),
        MUTE("Sessiz", 164),
        VOL_UP("Ses +", 24),
        VOL_DOWN("Ses −", 25),
        HOME("Ana ekran", 3)
    }

    data class Outcome(val ok: Boolean, val text: String)

    /** Bağlantı hatasını kısa, sade bir sebebe çevirir. */
    fun reason(e: Throwable, adb: Boolean): String {
        if (e.javaClass.simpleName == "PairingRequired") return "Bu telefonla eşleşmemiş; önce Standart ile bir kez bağlan"
        return when (e) {
            is SocketTimeoutException -> "TV cevap vermiyor (kapalı ya da uykuda)"
            is ConnectException, is NoRouteToHostException -> when (ConnCheck.classify(e)) {
                ConnCheck.Port.REFUSED -> if (adb) "ADB kapalı" else "TV'nin kumanda hizmeti cevap vermiyor"
                ConnCheck.Port.UNREACHABLE -> "TV bulunamadı (adresi değişmiş olabilir)"
                else -> "TV cevap vermiyor (kapalı ya da uykuda)"
            }
            else -> "Olmadı: ${e.message?.ifBlank { null } ?: e.javaClass.simpleName}"
        }
    }

    /** Sonuç özeti, ör. "Kapat: 2/3 TV'ye gitti". */
    fun summary(cmd: Command, outcomes: Collection<Outcome>): String =
        "${cmd.label}: ${outcomes.count { it.ok }}/${outcomes.size} TV'ye gitti"
}
