package com.serif.tvkumanda

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Sesli komut için küçük ses işleme yardımcıları (Android'den bağımsız, test edilebilir).
 * Telefon mikrofonu hangi hızda kayıt yaparsa yapsın, ses TV'nin beklediği
 * 8 kHz / 16 bit / mono biçime çevrilir.
 */
object VoiceDsp {

    fun toShorts(b: ByteArray, n: Int): ShortArray =
        ShortArray(n / 2) { i -> ((b[2 * i + 1].toInt() shl 8) or (b[2 * i].toInt() and 0xFF)).toShort() }

    fun toBytes(s: ShortArray): ByteArray {
        val out = ByteArray(s.size * 2)
        for (i in s.indices) {
            val v = s[i].toInt()
            out[2 * i] = (v and 0xFF).toByte()
            out[2 * i + 1] = (v shr 8).toByte()
        }
        return out
    }

    /** 0..1 arası ses seviyesi (RMS, görsel ve sessizlik algılama için ölçeklenmiş). */
    fun level(s: ShortArray): Float {
        if (s.isEmpty()) return 0f
        var sum = 0.0
        for (v in s) { val f = v / 32768.0; sum += f * f }
        return (sqrt(sum / s.size) * 4).toFloat().coerceIn(0f, 1f)
    }

    /**
     * Akış halinde örnekleme hızı dönüştürücü. Hızı düşürürken her çıkış örneği,
     * karşılık gelen giriş örneklerinin ortalamasıdır (basit alçak geçiren filtre).
     * Parçalar arasında kalan örnekler bir sonraki parçaya taşınır; ses kesintisiz olur.
     */
    class Resampler(srcRate: Int, private val dstRate: Int) {
        private val ratio = srcRate.toDouble() / dstRate
        private var carry = ShortArray(0)
        private var pos = 0.0

        fun process(input: ShortArray): ShortArray {
            if (ratio == 1.0) return input
            val data = if (carry.isEmpty()) input else carry + input
            val out = ArrayList<Short>(((data.size / ratio) + 1).toInt())
            if (ratio > 1.0) {
                while (pos + ratio <= data.size) {
                    val from = pos.toInt()
                    val to = (pos + ratio).toInt().coerceAtMost(data.size)
                    var sum = 0L
                    for (i in from until to) sum += data[i]
                    out.add((sum / maxOf(1, to - from)).toShort())
                    pos += ratio
                }
            } else {
                // Hız yükseltme (gerekmez ama tamlık için): doğrusal ara değer
                while (pos + 1 < data.size) {
                    val i = pos.toInt()
                    val f = pos - i
                    out.add((data[i] * (1 - f) + data[i + 1] * f).toInt().toShort())
                    pos += ratio
                }
            }
            val consumed = pos.toInt()
            carry = data.copyOfRange(consumed, data.size)
            pos -= consumed
            return out.toShortArray()
        }
    }

    /**
     * Yumuşak otomatik kazanç: kısık sesi en fazla [maxGain] kat yükseltir, tepe değeri
     * hedefin üstüne çıkarmaz ve kırpılmayı önler. Kazanç parçalar arasında yavaş değişir.
     */
    class AutoGain(private val maxGain: Float = 4f, private val target: Float = 0.6f) {
        private var gain = 1f
        fun process(s: ShortArray): ShortArray {
            var peak = 1
            for (v in s) peak = maxOf(peak, abs(v.toInt()))
            val wanted = (target * 32767f / peak).coerceIn(1f, maxGain)
            gain = if (wanted < gain) wanted else gain * 0.8f + wanted * 0.2f   // hızlı düş, yavaş yüksel
            return ShortArray(s.size) { i -> (s[i] * gain).toInt().coerceIn(-32768, 32767).toShort() }
        }
    }
}
