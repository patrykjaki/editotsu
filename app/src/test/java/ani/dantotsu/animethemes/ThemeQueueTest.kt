package ani.dantotsu.animethemes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeQueueTest {

    private fun video(id: Int, url: String, res: Int = 1080, nc: Boolean = false, source: String = "BD") =
        ThemeVideoUiModel(id, url, res, nc, source, "NONE", null, false, "")

    private fun theme(
        id: Int,
        type: String,
        seq: Int,
        episodes: String? = "1-12",
        spoiler: Boolean = false,
        videos: List<ThemeVideoUiModel> = listOf(video(id * 10, "https://v.animethemes.moe/$type$seq.webm")),
    ) = ThemeUiModel(
        themeId = id,
        type = type,
        sequence = seq,
        displayTag = "$type $seq",
        songTitle = "song $id",
        artistDisplay = "artist $id",
        alternateVersions = listOf(ThemeVersionUiModel(id * 100, 1, episodes, false, spoiler, videos)),
    )

    @Test
    fun tracksListOpeningsThenEndings() {
        val data = AnimeThemesData(
            1, "show",
            openings = listOf(theme(1, "OP", 1), theme(2, "OP", 2)),
            endings = listOf(theme(3, "ED", 1)),
        )
        assertEquals(listOf("OP 1", "OP 2", "ED 1"), ThemeQueue.tracks(data).map { it.tag })
    }

    @Test
    fun themeWithoutVideosIsDropped() {
        val data = AnimeThemesData(1, "show", listOf(theme(1, "OP", 1, videos = emptyList())), emptyList())
        assertTrue(ThemeQueue.tracks(data).isEmpty())
    }

    @Test
    fun creditlessIsChosenAndOthersBecomeFallbacks() {
        val t = theme(
            1, "OP", 1,
            videos = listOf(
                video(1, "https://v.animethemes.moe/a.webm", nc = false),
                video(2, "https://v.animethemes.moe/b.webm", nc = true),
                video(3, "https://v.animethemes.moe/c.webm", res = 720, nc = true, source = "WEB"),
            ),
        )
        val track = ThemeQueue.track(t)!!
        assertEquals("https://v.animethemes.moe/b.webm", track.url)
        assertEquals(
            listOf("https://v.animethemes.moe/c.webm", "https://v.animethemes.moe/a.webm"),
            track.fallbackUrls,
        )
    }

    @Test
    fun firstEpisodeParsesFreeFormField() {
        assertEquals(1, ThemeQueue.firstEpisode("1-12"))
        assertEquals(2, ThemeQueue.firstEpisode("2-13, 24"))
        assertEquals(1000, ThemeQueue.firstEpisode("1000"))
        assertEquals(13, ThemeQueue.firstEpisode("13-"))
        assertNull(ThemeQueue.firstEpisode(""))
        assertNull(ThemeQueue.firstEpisode(null))
        assertNull(ThemeQueue.firstEpisode("Movie"))
    }

    @Test
    fun shieldHidesThemesPastTheNextEpisode() {
        assertFalse(ThemeQueue.shielded(false, "50-74", 3))
        assertFalse(ThemeQueue.shielded(true, "1-12", null))
        assertFalse(ThemeQueue.shielded(true, "4-12", 3)) // plays in the episode the user watches next
        assertTrue(ThemeQueue.shielded(true, "5-12", 3))
        assertTrue(ThemeQueue.shielded(true, "14-25", null))
        assertFalse(ThemeQueue.shielded(true, null, 0))
    }

    @Test
    fun episodesLabel() {
        assertEquals("Eps 1–47", ThemeQueue.episodesLabel("1-47"))
        assertEquals("Ep 1000", ThemeQueue.episodesLabel("1000"))
        assertEquals("Eps 2–13, 24", ThemeQueue.episodesLabel("2-13, 24"))
        assertNull(ThemeQueue.episodesLabel(" "))
    }

    @Test
    fun playQueueKeepsTappedShieldedThemeOnly() {
        val data = AnimeThemesData(
            1, "show",
            openings = listOf(theme(1, "OP", 1, "1-12"), theme(2, "OP", 2, "13-24")),
            endings = listOf(theme(3, "ED", 1, "1-12"), theme(4, "ED", 2, "13-24")),
        )
        val all = ThemeQueue.tracks(data)
        val shield = { t: ThemeTrack -> ThemeQueue.shielded(true, t.episodes, 2) }
        val tapped = all.first { it.tag == "ED 2" }
        assertEquals(listOf("OP 1", "ED 1", "ED 2"), ThemeQueue.playQueue(all, tapped, shield).map { it.tag })
    }

    @Test
    fun retryOnlyServerErrorsWithBackoff() {
        assertEquals(1000L, ThemeQueue.retryDelayMs(503, 1, null))
        assertEquals(2000L, ThemeQueue.retryDelayMs(503, 2, null))
        assertEquals(4000L, ThemeQueue.retryDelayMs(502, 3, null))
        assertEquals(6000L, ThemeQueue.retryDelayMs(429, 4, null))
        assertNull(ThemeQueue.retryDelayMs(503, 5, null))
        assertEquals(3000L, ThemeQueue.retryDelayMs(503, 1, "3"))
        assertEquals(6000L, ThemeQueue.retryDelayMs(503, 1, "120"))
        assertNull(ThemeQueue.retryDelayMs(403, 1, null))
        assertNull(ThemeQueue.retryDelayMs(404, 1, null))
    }
}
