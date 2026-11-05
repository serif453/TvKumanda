package com.serif.tvkumanda

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Air Mouse hesabı (saf kod, Android'siz; `AirMotionTest`).
 * Jiroskobun dönüş hızını (rad/sn) TV'deki imlecin piksel hareketine çevirir.
 *
 * - Sağa-sola: telefonun "dünyaya göre dik eksen" etrafındaki dönüşü. Yerçekimi yönü kullanıldığı için telefon
 *   yatık da tutulsa dik de tutulsa sağa çevirmek imleci sağa götürür.
 * - Yukarı-aşağı: telefonun uzun kenarının kalkıp inmesi (cihazın X ekseni).
 * - Çok küçük dönüşler (el titremesi, sensör kayması) yok sayılır, hareket yumuşatılır.
 * - Yavaş çevirmek hassas, hızlı çevirmek daha uzağa gider. Çarpan kullanıcının fare hızı ayarından gelir.
 */
class AirMotion(private val pxPerRad: Float = 1300f) {
    private var sx = 0f
    private var sy = 0f
    private var rx = 0f
    private var ry = 0f

    fun reset() { sx = 0f; sy = 0f; rx = 0f; ry = 0f }

    /**
     * [wx], [wy], [wz]: cihaz eksenlerinde dönüş hızı (rad/sn). [gx], [gy], [gz]: yerçekimi (bilinmiyorsa 0, 0, 1).
     * [dt]: önceki ölçümden bu yana saniye. [gain]: hız ayarı çarpanı. Gönderilecek tam sayı hareketi döner
     * (artanlar bir sonraki ölçüme eklenir, yavaş hareket kaybolmaz).
     */
    fun step(wx: Float, wy: Float, wz: Float, gx: Float, gy: Float, gz: Float, dt: Float, gain: Float): Pair<Int, Int> {
        val t = dt.coerceIn(0f, 0.05f)          // uzun ara (ilk ölçüm, takılma) sıçrama yapmasın
        val gl = sqrt(gx * gx + gy * gy + gz * gz)
        val yaw = if (gl > 0.5f) (wx * gx + wy * gy + wz * gz) / gl else wz
        var h = -yaw
        var v = -wx
        h = deadZone(h)
        v = deadZone(v)
        // Yumuşatma: ani titremeyi süzer, gecikmeyi az tutar
        sx += (h - sx) * SMOOTH
        sy += (v - sy) * SMOOTH
        val speed = sqrt(sx * sx + sy * sy)
        val accel = 0.6f + (speed / 2.5f).coerceAtMost(1.4f)
        val k = pxPerRad * accel * gain * t
        rx += sx * k
        ry += sy * k
        val ix = rx.toInt()
        val iy = ry.toInt()
        rx -= ix
        ry -= iy
        return ix to iy
    }

    private fun deadZone(w: Float): Float = when {
        abs(w) < DEAD -> 0f
        w > 0 -> w - DEAD
        else -> w + DEAD
    }

    companion object {
        const val DEAD = 0.025f    // rad/sn; elin doğal titremesi bunun altında kalır
        const val SMOOTH = 0.55f
    }
}
