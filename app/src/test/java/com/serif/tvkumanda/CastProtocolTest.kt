package com.serif.tvkumanda

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class CastProtocolTest {

    @Test
    fun castFrameHasBigEndianLength() {
        val out = ByteArrayOutputStream()
        writeCastFrame(out, ByteArray(300) { 7 })
        val b = out.toByteArray()
        assertArrayEquals(byteArrayOf(0, 0, 1, 44), b.copyOfRange(0, 4))
        assertEquals(304, b.size)
        assertEquals(300, readCastFrame(ByteArrayInputStream(b)).size)
    }

    @Test
    fun castMessageFieldsRoundTrip() {
        // CastMessage: 1 sürüm, 2 kaynak, 3 hedef, 4 ad alanı, 5 tür, 6 JSON
        val msg = Pb().int(1, 0).str(2, "sender-0").str(3, "receiver-0")
            .str(4, CastClient.NS_CONNECTION).int(5, 0).str(6, """{"type":"CONNECT"}""").build()
        val p = PbMsg.parse(msg)
        assertEquals("sender-0", p.str(2))
        assertEquals("receiver-0", p.str(3))
        assertEquals(CastClient.NS_CONNECTION, p.str(4))
        assertEquals("""{"type":"CONNECT"}""", p.str(6))
        // protocol_version=0 alanı da kodlanmış olmalı (08 00 ile başlar)
        assertArrayEquals(byteArrayOf(0x08, 0x00), msg.copyOfRange(0, 2))
    }
}
