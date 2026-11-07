package com.serif.tvkumanda

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShortcutsTest {

    /** Sahte TV: yapılan her işi sırayla yazar; [failOn] verilen işte hata fırlatır. */
    private class FakeHost(val failOn: String? = null) : ShortcutHost {
        val log = mutableListOf<String>()
        private fun rec(s: String) { log += s; if (s == failOn) error("kapalı") }
        override fun key(code: Int) = rec("key:$code")
        override fun launch(pkg: String, activity: String, name: String) = rec("launch:$pkg")
        override fun text(value: String) = rec("text:$value")
        override fun wake() = rec("wake")
        override fun setVolume(level: Int) = rec("vol:$level")
        override fun sleepAfter(minutes: Int) = rec("sleep:$minutes")
    }

    private fun run(steps: List<Step>, host: FakeHost, pauses: MutableList<Long> = mutableListOf()) =
        runBlocking { ShortcutRunner.run(steps, host, pause = { pauses += it }) }

    @Test fun adimlarSirayla_calisir() {
        val h = FakeHost()
        val r = run(listOf(Step.Wake, Step.Wait(3000), Step.Launch("com.netflix", "", "Netflix"), Step.Volume(10)), h)
        assertEquals(RunResult.Done, r)
        assertEquals(listOf("wake", "launch:com.netflix", "vol:10"), h.log)
    }

    @Test fun bekleme_beklenenSureyi_ister() {
        val p = mutableListOf<Long>()
        run(listOf(Step.Wait(2500)), FakeHost(), p)
        assertEquals(listOf(2500L), p)   // tek adım: arada ek bekleme yok
    }

    @Test fun tusTekrari_veAraliklari() {
        val h = FakeHost(); val p = mutableListOf<Long>()
        run(listOf(Step.Key(Keys.VOL_UP, 3)), h, p)
        assertEquals(List(3) { "key:${Keys.VOL_UP}" }, h.log)
        assertEquals(listOf(ShortcutRunner.KEY_GAP_MS, ShortcutRunner.KEY_GAP_MS), p)
    }

    @Test fun kanal_rakamlariVeOk() {
        val h = FakeHost()
        run(listOf(Step.Channel("105")), h)
        assertEquals(listOf("key:${Keys.digit(1)}", "key:${Keys.digit(0)}", "key:${Keys.digit(5)}", "key:${Keys.OK}"), h.log)
    }

    @Test fun hataOlunca_durur_vehangiAdimOldugunuSoyler() {
        val h = FakeHost(failOn = "vol:5")
        val r = run(listOf(Step.Key(Keys.HOME), Step.Volume(5), Step.Sleep(30)), h)
        assertTrue(r is RunResult.Failed)
        r as RunResult.Failed
        assertEquals(1, r.index)
        assertEquals("kapalı", r.reason)
        assertEquals(listOf("key:${Keys.HOME}", "vol:5"), h.log)   // uyku adımına geçilmedi
    }

    @Test fun kayit_gidipGelir() {
        val list = listOf(
            Shortcut("a1", "Film", "movie", listOf(Step.Wake, Step.Wait(3000), Step.Launch("com.x", "com.x.Main", "X"),
                Step.Key(Keys.VOL_UP, 4), Step.Text("merhaba"), Step.Volume(12), Step.Sleep(45), Step.Channel("7")))
        )
        assertEquals(list, ShortcutCodec.decode(ShortcutCodec.encode(list)))
    }

    @Test fun bozukKayit_cokmez() {
        assertEquals(emptyList<Shortcut>(), ShortcutCodec.decode("bozuk{"))
        assertEquals(emptyList<Shortcut>(), ShortcutCodec.decode(null))
        // Bilinmeyen adım atlanır, kısayol kalır
        val s = ShortcutCodec.decode("""[{"id":"x","name":"A","icon":"tv","steps":[{"t":"gelecek"},{"t":"wake"}]}]""")
        assertEquals(listOf(Step.Wake), s.single().steps)
    }

    @Test fun kanallar_gidipGelir_veRakamDisiTemizlenir() {
        val l = listOf(FavChannel("5", "Kanal 5"), FavChannel("120", ""))
        assertEquals(l, ShortcutCodec.decodeChannels(ShortcutCodec.encodeChannels(l)))
        assertEquals(listOf(FavChannel("12", "x")), ShortcutCodec.decodeChannels("""[{"no":"1a2","name":"x"},{"no":"abc"}]"""))
    }

    @Test fun sablonlar_gecerliVeBenzersiz() {
        val t = ShortcutTemplates.all()
        assertTrue(t.all { it.steps.isNotEmpty() && it.name.isNotBlank() })
        assertEquals(t.size, t.map { it.id }.toSet().size)
    }
}
