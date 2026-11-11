package ani.dantotsu.media.anime.cast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CastSubtitlesTest {
    @Test
    fun `receiver formats are sent with their mime type`() {
        assertEquals(CastSubtitles.VTT, CastSubtitles.mimeFor("VTT", "https://x.example/a.vtt"))
        assertEquals(CastSubtitles.VTT, CastSubtitles.mimeFor("UNKNOWN", "https://x.example/a.vtt?sig=1"))
        assertEquals(CastSubtitles.TTML, CastSubtitles.mimeFor("UNKNOWN", "https://x.example/a.dfxp"))
    }

    @Test
    fun `formats the default receiver cannot render are not sent`() {
        assertNull(CastSubtitles.mimeFor("ASS", "https://x.example/a.ass"))
        assertNull(CastSubtitles.mimeFor("SRT", "https://x.example/a.srt"))
        assertNull(CastSubtitles.mimeFor("UNKNOWN", "https://x.example/sub?id=4"))
    }
}
