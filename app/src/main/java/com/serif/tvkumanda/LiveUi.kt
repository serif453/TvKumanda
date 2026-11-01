package com.serif.tvkumanda

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Sık değişen değerler (TV'de çalan, mikrofon seviyesi) [UiState]'e girmez: değiştiklerinde bütün ekran değil,
 * yalnızca onları gösteren küçük parça yeniden çizilir.
 */
object LiveUi {
    var nowPlaying by mutableStateOf<NowPlaying?>(null)
    var micLevel by mutableFloatStateOf(0f)
    /** Kumanda sekmesi açık mı: "şu an çalan" yalnızca orada göründüğü için yalnızca o zaman TV'ye sorulur. */
    @Volatile var remoteTabOpen = true
}

/** Aynı medya, aynı durum ve konum tahminle uyuşuyorsa eski nesne korunur (gereksiz yeniden çizim olmasın). */
internal fun NowPlaying.sameAs(o: NowPlaying?): Boolean {
    if (o == null || pkg != o.pkg || title != o.title || artist != o.artist || playing != o.playing ||
        durationMs != o.durationMs || canSeek != o.canSeek) return false
    return kotlin.math.abs(o.position(at) - positionMs) < 1500
}
