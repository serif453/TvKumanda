package com.serif.tvkumanda

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** IR sinyalleri: herkesçe bilinen kodlarla (LG, Samsung, Sony, Philips güç tuşu) karşılaştırılır. */
class IrCodecTest {

    private fun sig(s: String) = IrCodec.encode(IrCodec.parseCode(s)!!)

    /** NEC/Samsung süre dizisini, kumandaların kutusunda yazan biçimde (yüksek bitten) 32 bitlik sayıya çevirir. */
    private fun pdmToHex(p: IntArray, oneSpace: Int): String {
        var v = 0L
        // [0],[1] öncü; sonra (işaret, boşluk) çiftleri
        for (i in 0 until 32) v = (v shl 1) or (if (p[3 + 2 * i] > oneSpace - 300) 1 else 0)
        return "%08X".format(v)
    }

    @Test fun lgGuc() {
        val s = sig("P,NEC,4,8") // LG güç = 0x20DF10EF
        assertEquals(38000, s.carrierHz)
        assertEquals(67, s.pattern.size) // öncü 2 + 32 bit × 2 + bitiş işareti
        assertEquals(9000, s.pattern[0]); assertEquals(4500, s.pattern[1])
        assertEquals("20DF10EF", pdmToHex(s.pattern, 1690))
    }

    @Test fun samsungGuc() {
        val s = sig("P,Samsung32,7,2") // Samsung güç = 0xE0E040BF
        assertEquals(4500, s.pattern[0])
        assertEquals("E0E040BF", pdmToHex(s.pattern, 1650))
    }

    @Test fun necExt() {
        // Adres 16 bit olduğu gibi gider (tersi eklenmez)
        val s = sig("P,NECext,7D02,EF10")
        assertEquals("40BE08F7", pdmToHex(s.pattern, 1690))
    }

    @Test fun sonyGuc() {
        // Sony güç: 12 bit 0xA90, 3 kez; her çerçeve 45 ms
        val s = sig("P,SIRC,1,15")
        assertEquals(40000, s.carrierHz)
        val frame = 2 + 12 * 2 - 1 // öncü işaret+boşluk, 12 işaret, aralarında 11 boşluk
        assertEquals(frame * 3 + 2, s.pattern.size) // çerçeveler arası boşluk birleşir
        var bits = 0
        for (i in 0 until 12) if (s.pattern[2 + 2 * i] == 1200) bits = bits or (1 shl (11 - i))
        assertEquals(0xA90, bits)
        assertEquals(45000, s.pattern.take(frame + 1).sum())
    }

    @Test fun philipsRc6() {
        val a = sig("P,RC6,0,C")
        assertEquals(36000, a.carrierHz)
        assertEquals(2666, a.pattern[0])
        // Değişim biti iki basışta farklı: diziler farklı olmalı
        val b = IrCodec.encode(IrCodec.parseCode("P,RC6,0,C")!!, toggle = true)
        assertTrue(!a.pattern.contentEquals(b.pattern))
        // Toplam süre: öncü (2666+889) + 4 bit + değişim (2 kat) + 16 bit, son boşluk hariç
        val full = 2666 + 889 + (4 + 2 + 16) * 2 * 444
        assertTrue(a.pattern.sum() in (full - 444 * 2)..full)
    }

    @Test fun philipsRc5() {
        // Adres 0, komut 12 (güç): 1 1 T 00000 001100
        val s = sig("P,RC5,0,C")
        assertEquals(36000, s.carrierHz)
        // İlk bit 1 = boşluk + işaret → dizi işaretle başlar
        assertEquals(889, s.pattern[0])
        // Yarım bitlerden geri çöz
        val halves = ArrayList<Int>()
        halves.add(0) // atılan ilk boşluk
        s.pattern.forEachIndexed { i, d -> repeat(d / 889) { halves.add(if (i % 2 == 0) 1 else 0) } }
        while (halves.size < 28) halves.add(0)
        val bits = (0 until 14).map { if (halves[2 * it] == 0 && halves[2 * it + 1] == 1) 1 else 0 }
        assertEquals(listOf(1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 0, 0), bits)
    }

    @Test fun rc5x() {
        val s = IrCodec.encode(IrCodec.parseCode("P,RC5X,0,10")!!)
        // İkinci başlangıç biti 0: ilk bitin işaretinden sonra hemen işaret gelir (889 + 889)
        assertEquals(1778, s.pattern[0])
    }

    @Test fun panasonicKaseikyo() {
        // Panasonic güç: üretici 0x2002, bilinen 48 bit dizi 02 20 80 00 3D BD
        val s = sig("P,Kaseikyo,200280,3D0") // dosyada "80 02 20 00", "D0 03"
        assertEquals(3456, s.pattern[0])
        val bytes = IntArray(6)
        for (i in 0 until 48) if (s.pattern[3 + 2 * i] > 1000) bytes[i / 8] = bytes[i / 8] or (1 shl (i % 8))
        assertArrayEquals(intArrayOf(0x02, 0x20, 0x80, 0x00, 0x3D, 0xBD), bytes)
    }

    @Test fun hamSinyal() {
        val s = sig("W,38000,9000 4500 560 560 560")
        assertArrayEquals(intArrayOf(9000, 4500, 560, 560, 560), s.pattern)
        assertNull(IrCodec.parseCode("P,Bilinmez,0,0"))
        assertNull(IrCodec.parseCode("X"))
    }

    @Test fun tumProtokollerKodlanir() {
        for (p in IrCodec.PROTOCOLS) {
            val s = sig("P,$p,1,2")
            assertTrue(p, s.pattern.isNotEmpty() && s.pattern.size % 2 == 1 && s.pattern.all { it > 0 })
            assertTrue(p, s.pattern.sum() < 2_000_000) // Android sınırı 2 sn
        }
    }

    @Test fun veritabaniOkunur() {
        val text = """
            # yorum
            B	Philips
            S	13	power=P,RC6,0,C;vol_up=P,RC6,0,10;mute=P,RC6,0,D
            S	2	power=P,RC5,0,C;up=P,RC5X,0,10
            B	Bos
            B	LG
            S	5	power=P,NEC,4,8
        """.trimIndent()
        val db = IrDb.parse(text)
        assertEquals(listOf("Philips", "LG"), db.map { it.name })
        assertEquals(2, db[0].sets.size)
        assertEquals(13, db[0].sets[0].remotes)
        assertEquals("vol_up", IrDb.testKey(db[0].sets[0]))
        assertEquals("up", IrDb.testKey(db[0].sets[1]))
        assertEquals("power", IrDb.testKey(db[1].sets[0]))
        assertEquals(listOf("Philips", "LG", "Zenith"), IrDb.sorted(db + IrDb.Brand("Zenith", db[1].sets)).map { it.name })
    }

    @Test fun gercekVeritabani() {
        val f = listOf("src/main/assets/ir/tv.txt", "app/src/main/assets/ir/tv.txt").map { java.io.File(it) }.firstOrNull { it.exists() }
        assertNotNull("tv.txt bulunamadı", f)
        val db = IrDb.parse(f!!.readText())
        assertTrue(db.size > 50)
        assertTrue(db.any { it.name == "Philips" } && db.any { it.name == "Samsung" } && db.any { it.name == "LG" })
        // Her kod çözülebilmeli ve kodlanabilmeli
        for (b in db) for (s in b.sets) for ((k, v) in s.keys) {
            val c = IrCodec.parseCode(v)
            assertNotNull("${b.name} $k", c)
            val sig = IrCodec.encode(c!!)
            assertTrue("${b.name} $k", sig.pattern.isNotEmpty() && sig.pattern.sum() < 2_000_000)
        }
    }
}
