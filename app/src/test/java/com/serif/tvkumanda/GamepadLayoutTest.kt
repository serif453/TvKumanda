package com.serif.tvkumanda

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class GamepadLayoutTest {

    @Test fun kayitGeriOkununcaAyniDuzen() {
        val edited = GamepadLayout.defaults().map {
            when (it.id) {
                "A" -> it.copy(x = 0.7f, y = 0.2f, scale = 1.4f)
                "L2" -> it.copy(visible = false)
                else -> it
            }
        }
        assertEquals(edited, GamepadLayout.decode(GamepadLayout.encode(edited)))
    }

    @Test fun bozukKayitVarsayilanaDoner() {
        assertEquals(GamepadLayout.defaults(), GamepadLayout.decode(null))
        assertEquals(GamepadLayout.defaults(), GamepadLayout.decode("çöp;A,x,1,1,1;ZZ,0.1,0.1,1,1"))
        val d = GamepadLayout.decode("A,5,-1,9,0")
        val a = d.first { it.id == "A" }
        assertEquals(1f, a.x); assertEquals(0f, a.y); assertEquals(GamepadLayout.MAX_SCALE, a.scale); assertFalse(a.visible)
        assertEquals(GamepadLayout.defs.size, d.size)
    }

    @Test fun boyutSinirlari() {
        assertEquals(0.6f, GamepadLayout.clampScale(0.2f))
        assertEquals(1.6f, GamepadLayout.clampScale(3f))
        assertEquals(1.1f, GamepadLayout.clampScale(1f + GamepadLayout.STEP))
    }

    @Test fun yonler() {
        assertEquals(0, PadDir.of(0.1f, 0.1f, 0.3f))
        assertEquals(PadDir.UP, PadDir.of(0f, -1f, 0.3f))
        assertEquals(PadDir.RIGHT, PadDir.of(1f, 0.1f, 0.3f))
        assertEquals(PadDir.DOWN or PadDir.LEFT, PadDir.of(-0.7f, 0.7f, 0.3f))
        assertEquals(0, PadDir.hat(PadDir.UP)); assertEquals(3, PadDir.hat(PadDir.RIGHT or PadDir.DOWN))
        assertEquals(7, PadDir.hat(PadDir.UP or PadDir.LEFT)); assertEquals(8, PadDir.hat(0))
        assertEquals(22, PadDir.key(0.9f, 0.2f, 0.5f)); assertEquals(19, PadDir.key(0.1f, -0.8f, 0.5f))
        assertNull(PadDir.key(0.2f, 0.2f, 0.5f))
        assertEquals(19, PadDir.keyOf(PadDir.UP or PadDir.RIGHT)); assertNull(PadDir.keyOf(0))
    }

    @Test fun sanalKolSatiri() {
        val a = GamepadLayout.def("A")!!.bit
        val r1 = GamepadLayout.def("R1")!!.bit
        val line = PadHidState(buttons = a or r1, dpad = PadDir.LEFT, lx = 1f, ly = -0.5f, rx = -2f).line()
        assertEquals("g 129 6 127 -63 -127 0", line)
        assertEquals(PadHidState.RELEASE_ALL, PadHidState().line())
    }
}
