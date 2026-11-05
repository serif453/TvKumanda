package com.serif.tvkumanda

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AirMotionTest {
    private val dt = 0.02f

    /** Aynı dönüşü [n] ölçüm boyunca verir, toplam hareketi döner. */
    private fun run(m: AirMotion, wx: Float, wy: Float, wz: Float, g: FloatArray, n: Int = 25): Pair<Int, Int> {
        var x = 0
        var y = 0
        repeat(n) {
            val (dx, dy) = m.step(wx, wy, wz, g[0], g[1], g[2], dt, 1f)
            x += dx; y += dy
        }
        return x to y
    }

    private val flat = floatArrayOf(0f, 0f, 9.8f)      // ekran yukarı, düz
    private val upright = floatArrayOf(0f, 9.8f, 0f)   // dik tutulmuş

    @Test fun titremeYokSayilir() {
        assertEquals(0 to 0, run(AirMotion(), 0.01f, -0.015f, 0.02f, flat))
    }

    @Test fun duzTutarkenSolaCevirmekImleciSolaGoturur() {
        val (x, y) = run(AirMotion(), 0f, 0f, 1f, flat)
        assertTrue("x=$x", x < -100)
        assertEquals(0, y)
    }

    @Test fun dikTutarkenDeSolaCevirmekSolaGoturur() {
        // Dik tutulan telefonda dik eksen cihazın Y'si; sola dönüş +Y etrafında
        val (x, y) = run(AirMotion(), 0f, 1f, 0f, upright)
        assertTrue("x=$x", x < -100)
        assertEquals(0, y)
    }

    @Test fun ucuKaldirmakImleciYukariGoturur() {
        val (x, y) = run(AirMotion(), 1f, 0f, 0f, flat)
        assertEquals(0, x)
        assertTrue("y=$y", y < -100)
    }

    @Test fun hizliCevirmekOrantilidanDahaUzagaGider() {
        val slow = run(AirMotion(), 0f, 0f, 0.5f, flat).first
        val fast = run(AirMotion(), 0f, 0f, 2f, flat).first
        assertTrue("slow=$slow fast=$fast", fast < slow * 4)
    }

    @Test fun uzunAraSicramaYapmaz() {
        val (x, _) = AirMotion().step(0f, 0f, 3f, 0f, 0f, 9.8f, 5f, 1f)
        assertTrue("x=$x", x > -200)
    }
}
