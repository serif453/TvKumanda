package com.serif.tvkumanda

import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.NoRouteToHostException
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * Bağlantı testinin Android'siz (saf) kısmı: TV'nin kapılarını yoklar ve ölçümlerden
 * sade Türkçe sonuç satırları üretir. Ekran tarafı `NetSheets.kt` içinde.
 */
object ConnCheck {

    const val PORT_REMOTE = 6466
    const val PORT_PAIRING = 6467
    const val PORT_ADB = 5555

    /** Bir kapıya yapılan denemenin sonucu. */
    enum class Port {
        /** Kapı açık, bağlantı kuruldu. */
        OPEN,
        /** TV cevap verdi ama bu kapı kapalı (yani TV ağda). */
        REFUSED,
        /** Hiç cevap gelmedi (TV kapalı, uykuda ya da arada engel var). */
        TIMEOUT,
        /** Bu adreste bir cihaz bulunamadı. */
        UNREACHABLE
    }

    data class Probe(val state: Port, val ms: Long = 0)

    enum class Level { OK, WARN, FAIL, INFO }

    data class Row(val level: Level, val title: String, val detail: String = "")

    data class Report(val level: Level, val headline: String, val rows: List<Row>)

    /** Telefonun ağ durumu ve TV'ye yapılan ölçümler. */
    data class Input(
        val ip: String,
        /** Telefon Wi-Fi'a ya da kabloya bağlı. */
        val wifi: Boolean,
        val vpn: Boolean,
        val cellular: Boolean,
        /** Telefonun Wi-Fi adresi ve ağ maskesi uzunluğu (bilinmiyorsa null). */
        val phoneIp: String?,
        val prefix: Int?,
        val remote: Probe,
        val pairing: Probe,
        val adb: Probe,
        /** Açık bir kapıya yapılan ek ölçümler (ms). */
        val latencies: List<Long>,
        /** Bu telefon TV ile Standart bağlantıda eşleşmiş mi. */
        val paired: Boolean,
        /** Kullanıcının seçtiği bağlantı türü ADB mi. */
        val adbMode: Boolean
    )

    // ---------------- Ölçüm ----------------

    /** Kapıya kısa bir TCP bağlantısı dener; hiç veri gönderilmez, bağlantı hemen kapatılır. */
    fun probe(ip: String, port: Int, timeoutMs: Int = 2000): Probe {
        val start = System.nanoTime()
        val error = runCatching {
            Socket().use { it.connect(InetSocketAddress(ip, port), timeoutMs) }
        }.exceptionOrNull()
        val ms = (System.nanoTime() - start) / 1_000_000
        return Probe(classify(error), ms)
    }

    /** Bağlantı hatasını türüne ayırır (Android ve masaüstü Java'nın hata metinlerine göre). */
    fun classify(e: Throwable?): Port {
        if (e == null) return Port.OPEN
        val msg = e.message.orEmpty()
        return when {
            e is SocketTimeoutException -> Port.TIMEOUT
            e is NoRouteToHostException -> Port.UNREACHABLE
            msg.contains("ECONNREFUSED") || msg.contains("refused", ignoreCase = true) -> Port.REFUSED
            msg.contains("EHOSTUNREACH") || msg.contains("No route", ignoreCase = true) ||
                msg.contains("unreachable", ignoreCase = true) -> Port.UNREACHABLE
            e is ConnectException -> Port.TIMEOUT
            else -> Port.TIMEOUT
        }
    }

    // ---------------- Adres yardımcıları ----------------

    fun isIpv4(s: String): Boolean {
        val parts = s.trim().split('.')
        return parts.size == 4 && parts.all { p -> p.isNotEmpty() && p.length <= 3 && p.all(Char::isDigit) && p.toInt() <= 255 }
    }

    private fun toInt(ip: String): Long = ip.trim().split('.').fold(0L) { acc, p -> (acc shl 8) or p.toLong() }

    /** İki adres aynı yerel ağda mı (ör. 192.168.1.x ile 192.168.1.y, /24). */
    fun sameSubnet(a: String, b: String, prefix: Int): Boolean {
        if (!isIpv4(a) || !isIpv4(b) || prefix !in 0..32) return true   // bilinmiyorsa uyarı verme
        if (prefix == 0) return true
        val mask = (0xFFFFFFFFL shl (32 - prefix)) and 0xFFFFFFFFL
        return (toInt(a) and mask) == (toInt(b) and mask)
    }

    // ---------------- Değerlendirme ----------------

    fun evaluate(i: Input): Report {
        val rows = mutableListOf<Row>()

        // 1. Telefonun ağı
        if (!i.wifi) {
            rows += Row(
                Level.FAIL, "Telefon Wi-Fi'a bağlı değil",
                if (i.cellular) "Mobil veriyle TV'ye ulaşılamaz. Telefonu TV ile aynı Wi-Fi ağına bağla."
                else "Telefonu TV ile aynı Wi-Fi ağına bağla."
            )
        } else {
            rows += Row(Level.OK, "Telefon Wi-Fi'a bağlı", i.phoneIp?.let { "Telefonun adresi: $it" }.orEmpty())
        }

        // 2. VPN
        if (i.vpn) {
            rows += Row(
                Level.WARN, "VPN açık",
                "VPN, TV'ye giden yerel ağ trafiğini de içine alabilir. Bağlanamıyorsan VPN'i kapatıp yeniden dene."
            )
        }

        // 3. Aynı ağda mı
        if (i.wifi && i.phoneIp != null && i.prefix != null && !sameSubnet(i.phoneIp, i.ip, i.prefix)) {
            rows += Row(
                Level.WARN, "TV başka bir ağda görünüyor",
                "${i.ip} adresi telefonun ağıyla uyuşmuyor. TV'nin adresi değişmiş olabilir: TV seçiminden ağı yeniden tara."
            )
        }

        // 4. TV cevap veriyor mu
        val probes = listOf(i.remote, i.pairing, i.adb)
        val alive = probes.any { it.state == Port.OPEN || it.state == Port.REFUSED }
        if (!alive) {
            rows += if (probes.all { it.state == Port.UNREACHABLE })
                Row(
                    Level.FAIL, "Bu adreste cihaz bulunamadı",
                    "TV kapalı ya da adresi değişmiş. TV'yi açıp TV seçiminden ağı yeniden tara."
                )
            else
                Row(
                    Level.FAIL, "TV cevap vermiyor",
                    "TV kapalı ya da derin uykuda olabilir, ya da adresi değişmiş. TV'yi açıp yeniden dene."
                )
            return finish(rows)
        }
        rows += Row(Level.OK, "TV ağda, cevap veriyor")

        // 5. Standart kumanda kapısı
        if (i.remote.state == Port.OPEN) {
            rows += Row(Level.OK, "Standart kumanda kapısı açık ($PORT_REMOTE)")
        } else {
            rows += Row(
                if (i.adbMode) Level.INFO else Level.FAIL,
                "Standart kumanda kapısı kapalı ($PORT_REMOTE)",
                "TV'deki kumanda hizmeti (Android TV Remote Service) çalışmıyor olabilir. TV'yi yeniden başlatmak çoğu zaman düzeltir."
            )
        }

        // 6. Eşleştirme
        if (i.remote.state == Port.OPEN) {
            rows += when {
                i.paired -> Row(Level.OK, "Bu telefon TV ile eşleşmiş")
                i.pairing.state == Port.OPEN -> Row(
                    Level.INFO, "Bu telefon henüz eşleşmemiş",
                    "Standart ile bağlanınca TV ekranında bir kod çıkar; o kodu girmen yeterli."
                )
                else -> Row(
                    if (i.adbMode) Level.INFO else Level.WARN, "Eşleştirme kapısı kapalı ($PORT_PAIRING)",
                    "Eşleştirme başlatılamaz. TV'yi yeniden başlatıp tekrar dene."
                )
            }
        }

        // 7. ADB
        if (i.adb.state == Port.OPEN) {
            rows += Row(Level.OK, "ADB açık ($PORT_ADB)", "İlk bağlantıda TV'de çıkan izni onaylamış olman gerekir.")
        } else {
            rows += Row(
                if (i.adbMode) Level.FAIL else Level.INFO, "ADB kapalı ($PORT_ADB)",
                (if (i.adbMode) "" else "ADB yalnızca gelişmiş araçlar için gerekir. ") +
                    "Açmak için TV'de Geliştirici seçenekleri → USB hata ayıklama (ya da Ağ üzerinden hata ayıklama). " +
                    "Bazı TV'lerde yeniden başlayınca kendiliğinden kapanır."
            )
        }

        // 8. Gecikme
        if (i.latencies.isNotEmpty()) {
            val avg = i.latencies.average().toLong()
            val worst = i.latencies.max()
            val numbers = "ort. $avg ms, en kötü $worst ms"
            rows += when {
                avg < 60 -> Row(Level.OK, "Gecikme düşük ($numbers)", "Tuşlar anında gider.")
                avg < 200 -> Row(Level.WARN, "Gecikme biraz yüksek ($numbers)", "Tuşlar hafif gecikebilir. TV ya da telefon modemden uzak olabilir.")
                else -> Row(Level.WARN, "Gecikme yüksek ($numbers)", "Wi-Fi zayıf görünüyor. Modemi yaklaştırmak ya da TV'yi kabloyla bağlamak düzeltir.")
            }
        }

        return finish(rows)
    }

    private fun finish(rows: List<Row>): Report = when {
        rows.any { it.level == Level.FAIL } -> Report(Level.FAIL, "Bağlantı sorunu bulundu", rows)
        rows.any { it.level == Level.WARN } -> Report(Level.WARN, "Bağlanabilir, ama dikkat", rows)
        else -> Report(Level.OK, "Her şey yolunda", rows)
    }

    /** Teşhis kaydına yazılacak tek satırlık özet. */
    fun summary(ip: String, r: Report): String {
        val problems = r.rows.filter { it.level == Level.FAIL || it.level == Level.WARN }.joinToString("; ") { it.title }
        return "Bağlantı testi ($ip): ${r.headline}" + if (problems.isNotEmpty()) " — $problems" else ""
    }
}
