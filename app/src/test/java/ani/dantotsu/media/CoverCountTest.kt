package ani.dantotsu.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CoverCountTest {
    @Test
    fun countsWhatIsOutAndUnseen() {
        assertEquals(9, coverUnseenCount(progress = 2, listStatus = "CURRENT", released = 11))
        assertEquals(4, coverUnseenCount(progress = 8, listStatus = "PAUSED", released = 12))
        assertEquals(1, coverUnseenCount(progress = 11, listStatus = "REPEATING", released = 12))
    }

    @Test
    fun hiddenWhenNothingToShow() {
        assertNull(coverUnseenCount(progress = 11, listStatus = "CURRENT", released = 11))
        assertNull(coverUnseenCount(progress = null, listStatus = null, released = 11))
        assertNull(coverUnseenCount(progress = 3, listStatus = "COMPLETED", released = 12))
        assertNull(coverUnseenCount(progress = 3, listStatus = "DROPPED", released = 12))
        assertNull(coverUnseenCount(progress = 3, listStatus = "CURRENT", released = null))
        // a planned show would only repeat its total
        assertNull(coverUnseenCount(progress = 0, listStatus = "PLANNING", released = 12))
    }

    @Test
    fun labelCapsAt99() {
        assertEquals("7", coverCountLabel(7))
        assertEquals("99", coverCountLabel(99))
        assertEquals("99+", coverCountLabel(1200))
    }

    @Test
    fun progressTextMatchesTheOldLine() {
        assertEquals("2" to " | 9 | 11", coverProgressParts(2, 9, 11))
        assertEquals("2" to " | 9 | ~", coverProgressParts(2, 9, null))
        assertEquals("~" to " | 12", coverProgressParts(null, null, 12))
        assertEquals("40" to " | ~", coverProgressParts(40, null, null))
    }

    @Test
    fun modeFromPref() {
        assertEquals(CoverCountMode.COVER, CoverCountMode.of(0))
        assertEquals(CoverCountMode.BELOW, CoverCountMode.of(1))
        assertEquals(CoverCountMode.NEW, CoverCountMode.of(2))
        assertEquals(CoverCountMode.COVER, CoverCountMode.of(7))
    }

    @Test
    fun pillOnListShowsProgress() {
        assertEquals(CoverPill.Progress("2", " | 9 | 11"), coverPill(2, "CURRENT", 9, 11, isAnime = true))
        // planned: on the list, nothing watched
        assertEquals(CoverPill.Progress("0", " | 12"), coverPill(0, "PLANNING", null, 12, isAnime = true))
    }

    @Test
    fun pillOffListShowsHowManyAreOut() {
        assertEquals(CoverPill.Out(7), coverPill(null, null, 7, 12, isAnime = true))
        assertEquals(CoverPill.Out(12), coverPill(null, "", null, 12, isAnime = true))
        assertEquals(CoverPill.Out(272), coverPill(null, null, null, 272, isAnime = false))
        assertNull(coverPill(null, null, null, null, isAnime = true))
    }

    @Test
    fun pillHiddenOnMovies() {
        assertNull(coverPill(null, null, null, 1, isAnime = true))
        assertNull(coverPill(1, "COMPLETED", null, 1, isAnime = true))
        // a one-chapter manga still shows
        assertEquals(CoverPill.Out(1), coverPill(null, null, null, 1, isAnime = false))
    }

    @Test fun nextEpisodeNeedsAiringAndAFutureTime() {
        assertNull(nextEpisode(null, 3_600_000))
        assertNull(nextEpisode(3, null))
        assertNull(nextEpisode(3, 0))
        assertNull(nextEpisode(3, -5_000))
    }

    @Test fun nextEpisodeIsTheOneAfterTheAiredCount() {
        assertEquals(4, nextEpisode(3, 60_000)!!.episode)
    }

    @Test fun nextEpisodeUsesTheLargestWholeUnit() {
        assertEquals(NextEpisode(4, 1, AiringUnit.MINUTES), nextEpisode(3, 1_000))
        assertEquals(NextEpisode(4, 59, AiringUnit.MINUTES), nextEpisode(3, 59 * 60_000L))
        assertEquals(NextEpisode(4, 1, AiringUnit.HOURS), nextEpisode(3, 60 * 60_000L))
        assertEquals(NextEpisode(4, 23, AiringUnit.HOURS), nextEpisode(3, 23 * 3_600_000L + 59 * 60_000L))
        assertEquals(NextEpisode(4, 2, AiringUnit.DAYS), nextEpisode(3, 2 * 86_400_000L + 5 * 3_600_000L))
    }
}
