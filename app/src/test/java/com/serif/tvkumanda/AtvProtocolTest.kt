package com.serif.tvkumanda

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.math.BigInteger

/**
 * Beklenen baytlar, referans uygulamanın (tronikos/androidtvremote2) kendi protobuf
 * tanımlarıyla (polo_pb2 / remotemessage_pb2) Python'da üretildi. Böylece kodlamamızın
 * referansla bayt bayt aynı olduğu doğrulanır. Eşleştirme hash'i pairing.py'deki formülle üretildi.
 */
class AtvProtocolTest {
    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    @Test fun pairingMessagesMatchReference() {
        assertArrayEquals(hex("080210c80152170a0961747672656d6f7465120a5456204b756d616e6461"), Polo.pairingRequest("TV Kumanda"))
        assertArrayEquals(hex("080210c801a201080a04080310061801"), Polo.options())
        assertArrayEquals(hex("080210c801f201080a04080310061001"), Polo.configuration())
        assertArrayEquals(hex("080210c801c202220a20000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"), Polo.secret(ByteArray(32) { it.toByte() }))
    }

    @Test fun remoteMessagesMatchReference() {
        assertArrayEquals(hex("0a1c08e704121718012201312a0961747672656d6f74653205312e302e30"), RemoteMsg.configureReply(615))
        assertEquals(615, RemoteMsg.FEATURES)
        assertArrayEquals(hex("120308e704"), RemoteMsg.setActive(615))
        assertArrayEquals(hex("4a020807"), RemoteMsg.pong(7))
        assertArrayEquals(hex("520408131003"), RemoteMsg.key(19))
        assertArrayEquals(hex("d205190a1768747470733a2f2f7777772e796f75747562652e636f6d"), RemoteMsg.appLink("https://www.youtube.com"))
        assertArrayEquals(hex("aa0115080310051a0f0801120b080410041a0568656c6c6f"), RemoteMsg.text(3, 5, "hello"))
    }

    @Test fun pairingHashMatchesReference() {
        val e = BigInteger.valueOf(65537)
        val code = "BFBEEF"
        val h = Polo.pairingHash(BigInteger("9546AC0960012E08E6A29E3EE40726BAD368826F21B8E217FA84DEB7827E3129617A645077A51F09A8C2728836DEF36C67A23B70D35F8FB7919296DDB71B8A713B1CDB019828953D260ABEEF47B9E8E22A9E76CA2F2B66476DCC45138C95BBE77C5699EEBD2FDB884949B20925BCD18A4FFCBC369B3A2F396D843E3C75FB30B40BDE7685D7803F738213FB4BC5FE8CCD19430FF6A2F087E1D9B0C943B33409A166E7F26162D6A4BC78C6897FA72E4EBA90560DE882534C9F99C480E7744650CC1C39AA79D35E973BCA605CBD3CDD2EBDADA0D4B47F22F10AC91E03BF887519654131149720B8FAF946FD3A4C7AB4F9187546388BF9B35F8DA57BF32465FD1FF5", 16), e, BigInteger("A3894FE0C957A4AC68B5E79092E41D1A6DE34C41D5CE45FFE58659DEE54FD7323E381B666555EF41AE7CDB090AAA9128AE5CE1605DA795D740B832D6FA54F429F5D94580166D3E548CE467A44A18A969CD442B2127A6F2E18AA6F85A342F0C7585A02B515580AA58AEE205A36413C0543A90EFC96C1FE4E27AB4AF0840BF0FC43FAEAD66FCC3E72786AF90AF4D11FBA3E988F602E1CB7269938939CE54F0CC0DD6CBA3C3B1BA607A637BA1628C8FB2C7B0A4A5A9E965462DB7303D5C1B3480800AC31055E98F24FE724D7A04886DC20BC5ABB417EE55546E57D3B657DC0D7AA0F291D9C6742E935944536999C6DA5898F5B8DD8A21425D2517808E05DA0F2EC1", 16), e, code)
        assertArrayEquals(hex("bf0af8212c2fe9b12dfaf1d85aa3f16f0377872a51dfead7f9914f7ee0da4d08"), h)
        assertTrue(Polo.codeMatches(h, code))
        assertTrue(Polo.isValidCode(code))
    }

    @Test fun framingAndParsingRoundTrip() {
        val payload = ByteArray(300) { 1 }
        val out = ByteArrayOutputStream()
        writeFrame(out, payload)
        assertArrayEquals(hex("ac0200"), out.toByteArray().copyOfRange(0, 3))
        assertArrayEquals(payload, readFrame(ByteArrayInputStream(out.toByteArray())))

        val m = PbMsg.parse(hex("0a1c08e704121718012201312a0961747672656d6f74653205312e302e30"))
        assertTrue(m.has(1))
        val cfg = m.msg(1)!!
        assertEquals(615, cfg.int(1))
        assertEquals("atvremote", cfg.msg(2)!!.str(5))
        // Boş alt mesaj (ör. configuration_ack {}) da "var" sayılmalı
        assertTrue(PbMsg.parse(hex("fa0100")).has(31))
    }
}
