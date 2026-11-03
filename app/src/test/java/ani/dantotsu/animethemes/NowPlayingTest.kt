package ani.dantotsu.animethemes

import ani.dantotsu.others.AniSkip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NowPlayingTest {
    private fun stamp(type: String, start: Double, end: Double) = AniSkip.Stamp(AniSkip.AniSkipInterval(start, end), type, "id", 1440.0)

    @Test
    fun kindFollowsTheSegment() {
        val stamps = listOf(stamp("op", 60.0, 150.0), stamp("ed", 1300.0, 1390.0), stamp("recap", 0.0, 30.0))
        assertEquals(false, NowPlaying.kindAt(stamps, 61.0))
        assertEquals(true, NowPlaying.kindAt(stamps, 1300.0))
        assertNull(NowPlaying.kindAt(stamps, 10.0))
        assertNull(NowPlaying.kindAt(stamps, 150.0))
        assertNull(NowPlaying.kindAt(null, 61.0))
        assertEquals(false, NowPlaying.kindAt(listOf(stamp("mixed-op", 0.0, 90.0)), 5.0))
    }

    @Test
    fun labelAndEpisodeNumber() {
        val t = ThemeTrack(1, "OP 1", "Idol", "YOASOBI", null, "u", emptyList(), ThemeWarningType.NONE)
        assertEquals("OP 1 · Idol · YOASOBI", NowPlaying.label(t))
        assertEquals("OP 1", NowPlaying.label(t.copy(songTitle = "OP 1", artist = "")))
        assertEquals(12, NowPlaying.episodeNumber("12"))
        assertEquals(12, NowPlaying.episodeNumber("12.5"))
        assertNull(NowPlaying.episodeNumber("SP"))
    }
}
