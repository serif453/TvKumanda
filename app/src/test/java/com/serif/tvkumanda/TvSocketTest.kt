package com.serif.tvkumanda

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Socket

class TvSocketTest {

    @After fun reset() { TvSocket.wifiFactory = null }

    @Test fun evAgiAdresleri() {
        listOf("192.168.1.30", "10.0.0.5", "172.16.4.1", "172.31.255.1", "169.254.3.3", "philips-tv.local")
            .forEach { assertTrue(it, TvSocket.isLocal(it)) }
    }

    @Test fun evAgiDisiAdresler() {
        listOf("100.64.1.2", "172.32.0.1", "8.8.8.8", "192.169.1.1", "", "abc", "300.1.1.1")
            .forEach { assertFalse(it, TvSocket.isLocal(it)) }
    }

    @Test fun wifiYoksaEvAginaDenenmez() {
        TvSocket.wifiFactory = { null }
        val e = runCatching { TvSocket.create("192.168.1.30") }.exceptionOrNull()
        assertTrue(e is TvSocket.NoWifi)
        // Uzak (VPN) adrese yine sıradan soket verilir
        TvSocket.create("100.64.1.2").close()
    }

    @Test fun baslatilmamissaEskiDavranis() {
        TvSocket.create("192.168.1.30").close()
    }

    @Test fun wifiVarsaOndanAlinir() {
        var used = false
        TvSocket.wifiFactory = { used = true; Socket() }
        TvSocket.create("192.168.1.30").close()
        assertTrue(used)
    }
}
