package ani.dantotsu.media.mangaupdates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptationSeasonsTest {

    @Test
    fun `three seasons, last still announced`() {
        val seasons = AdaptationSeasons.parse(
            "Vol 1, Chap 1 (S1) / Vol 7, Chap 59 (S2) /Vol 14, Chap 126 (S3)",
            "Vol 7, Chap 58 (S1) / Vol 14, Chap 126 (S2) /TBA",
        )
        assertEquals(3, seasons.size)
        assertEquals("1", seasons[0].label)
        assertEquals(AdaptationSeasons.Point("1", "1"), seasons[0].start)
        assertEquals(AdaptationSeasons.Point("7", "58"), seasons[0].end)
        assertEquals("3", seasons[2].label)
        assertTrue(seasons[2].ongoing)
        assertNull(AdaptationSeasons.readFrom(seasons))
    }

    @Test
    fun `one finished season gives the chapter to read from`() {
        val seasons = AdaptationSeasons.parse("Vol 1, Chap 1", "Vol 5, Chapter 38")
        assertEquals(1, seasons.size)
        assertNull(seasons[0].label)
        assertEquals("39", AdaptationSeasons.readFrom(seasons))
    }

    @Test
    fun `free text is reported as unreadable`() {
        val seasons = AdaptationSeasons.parse("Unknown", "Ongoing")
        assertTrue(AdaptationSeasons.unreadable(seasons))
    }
}
