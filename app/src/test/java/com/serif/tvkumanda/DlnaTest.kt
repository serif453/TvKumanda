package com.serif.tvkumanda

import org.junit.Assert.assertEquals
import org.junit.Test

class DlnaTest {
    @Test
    fun readsTagsWithNamespaces() {
        val xml = "<s:Envelope><s:Body><u:GetPositionInfoResponse><RelTime>0:01:23</RelTime>" +
            "<TrackDuration>00:03:05.500</TrackDuration></u:GetPositionInfoResponse></s:Body></s:Envelope>"
        assertEquals(83.0, DlnaClient.seconds(DlnaClient.tag(xml, "RelTime")), 0.01)
        assertEquals(185.5, DlnaClient.seconds(DlnaClient.tag(xml, "TrackDuration")), 0.01)
        assertEquals(0.0, DlnaClient.seconds("NOT_IMPLEMENTED"), 0.0)
    }

    @Test
    fun formatsSeekTarget() {
        assertEquals("1:02:03", DlnaClient.hms(3723.4))
        assertEquals("0:00:09", DlnaClient.hms(9.9))
    }

    @Test
    fun escapesMetadata() {
        assertEquals("a &amp; b &lt;c&gt;", DlnaClient.escape("a & b <c>"))
        assertEquals("a & b <c>", DlnaClient.unescape("a &amp; b &lt;c&gt;"))
    }
}
