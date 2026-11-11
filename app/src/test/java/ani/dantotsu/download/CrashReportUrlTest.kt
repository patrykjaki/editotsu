package ani.dantotsu.download

import org.junit.Assert.assertEquals
import org.junit.Test

class CrashReportUrlTest {

    @Test
    fun `only the host of a signed url is kept`() {
        assertEquals(
            "cdn.example.net",
            urlHostForReport("https://cdn.example.net/v/ep1.m3u8?token=secret&expires=1")
        )
    }

    @Test
    fun `unparseable or missing url gives unknown`() {
        assertEquals("unknown", urlHostForReport(null))
        assertEquals("unknown", urlHostForReport("not a url"))
    }
}
