package ani.dantotsu.connections.subtitles

import org.junit.Assert.assertEquals
import org.junit.Test

class OpenSubtitlesUserAgentTest {
    @Test
    fun namesEditotsuAndTheVersionWithoutBuildId() {
        assertEquals("Editotsu/0.8.0-alpha02", OpenSubtitlesRestApi.userAgent("0.8.0-alpha02+dev.1a2b3c4d.dirty"))
        assertEquals("Editotsu/1.0.0", OpenSubtitlesRestApi.userAgent("1.0.0"))
    }
}
