package com.serif.tvkumanda

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MouseQueueTest {
    private fun drain(q: MouseQueue): List<String> = generateSequence { q.poll() }.toList()

    @Test fun bekleyenHareketlerBirlesir() {
        val q = MouseQueue()
        q.offer("m 3 1"); q.offer("m 2 0"); q.offer("m -1 4")
        assertEquals(listOf("m 4 5"), drain(q))
    }

    @Test fun tikSirasiBozulmaz() {
        val q = MouseQueue()
        q.offer("m 5 0"); q.offer("d"); q.offer("m 1 1"); q.offer("m 1 1"); q.offer("u"); q.offer("m 2 2")
        assertEquals(listOf("m 5 0", "d", "m 2 2", "u", "m 2 2"), drain(q))
    }

    @Test fun gonderilmisHareketeEklenmez() {
        val q = MouseQueue()
        q.offer("m 1 0")
        assertEquals("m 1 0", q.poll())   // gönderildi
        q.offer("m 2 0")
        assertEquals(listOf("m 2 0"), drain(q))
    }

    @Test fun tekerlekBirlesirSifirsaDuser() {
        val q = MouseQueue()
        q.offer("w 1"); q.offer("w 2"); q.offer("c"); q.offer("w 1"); q.offer("w -1")
        assertEquals(listOf("w 3", "c"), drain(q))
    }

    @Test fun birbiriniSilenHareketlerDuser() {
        val q = MouseQueue()
        q.offer("m 3 -2"); q.offer("m -3 2")
        assertNull(q.poll())
    }

    @Test fun bozukVeBosKomutlarAtilir() {
        val q = MouseQueue()
        q.offer(""); q.offer("m x 1"); q.offer("m 0 0"); q.offer("w 0"); q.offer("M")
        assertEquals(listOf("M"), drain(q))
    }

    @Test fun takeBekleyipAlir() {
        val q = MouseQueue()
        val t = Thread { Thread.sleep(50); q.offer("c") }
        t.start()
        assertEquals("c", q.take())
        t.join()
    }

    @Test fun clearTemizler() {
        val q = MouseQueue()
        q.offer("m 1 1"); q.offer("c")
        q.clear()
        assertEquals(0, q.size)
    }
}
