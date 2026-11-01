package com.serif.tvkumanda

import org.junit.Assert.assertEquals
import org.junit.Test

/** dumpsys audio çıktısından sessiz durumunun okunması (ADB). */
class MuteParseTest {
    private val music = { muted: Boolean ->
        "- STREAM_VOICE_CALL:\n   Muted: false\n- STREAM_MUSIC:\n   Muted: $muted\n   Min: 0\n   Max: 100\n   streamVolume:14\n- STREAM_ALARM:\n   Muted: false\n"
    }

    @Test fun streamMusicMuted() = assertEquals(true, parseMuted(music(true)))
    @Test fun streamMusicNotMuted() = assertEquals(false, parseMuted(music(false)))
    @Test fun masterMuteWins() = assertEquals(true, parseMuted("mMasterMute: true\n" + music(false)))
    @Test fun unknownIsNull() = assertEquals(null, parseMuted("nothing useful here"))
}
