package ani.dantotsu.animethemes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeAttemptsTest {
    private val track = ThemeTrack(
        1, "OP 1", "song", "artist", "1-12",
        "https://v.animethemes.moe/a.webm", listOf("https://v.animethemes.moe/b.webm"), ThemeWarningType.NONE,
        audioUrl = "https://a.animethemes.moe/a.ogg",
    )

    @Test
    fun sourcesPutSavedThenAudioThenVideos() {
        assertEquals(
            listOf("https://v.animethemes.moe/a.webm", "https://v.animethemes.moe/b.webm"),
            ThemeQueue.sources(track),
        )
        assertEquals(
            listOf("content://downloads/1", "https://a.animethemes.moe/a.ogg", "https://v.animethemes.moe/a.webm", "https://v.animethemes.moe/b.webm"),
            ThemeQueue.sources(track, audioOnly = true, saved = "content://downloads/1"),
        )
        // No audio file: audio-only falls back to the video.
        assertEquals("https://v.animethemes.moe/a.webm", ThemeQueue.sources(track.copy(audioUrl = null), audioOnly = true).first())
    }

    @Test
    fun savedCopyIsTriedOnceThenNetworkThreeTimes() {
        val plan = ThemeQueue.attempts(listOf("content://downloads/1", "https://v/a.webm", "https://v/b.webm"))
        assertEquals(
            listOf(
                ThemeQueue.Attempt("content://downloads/1", 0L),
                ThemeQueue.Attempt("https://v/a.webm", 500L),
                ThemeQueue.Attempt("https://v/a.webm", 1_000L),
                ThemeQueue.Attempt("https://v/a.webm", 3_000L),
                ThemeQueue.Attempt("https://v/b.webm", 500L),
            ),
            plan,
        )
    }

    @Test
    fun rateLimitWaitsAsLongAsAsked() {
        val a = ThemeQueue.Attempt("https://v/a.webm", 1_000L)
        assertEquals(30_000L, ThemeQueue.delayFor(a, ThemeReachResult.RateLimited(30)))
        assertEquals(1_000L, ThemeQueue.delayFor(a, ThemeReachResult.Unreachable))
        assertEquals(1_000L, ThemeQueue.delayFor(a, null))
    }

    @Test
    fun audioUrlsAreRecognised() {
        assertTrue(ThemeQueue.isAudio("https://a.animethemes.moe/x.ogg"))
        assertTrue(ThemeQueue.isAudio("https://a.animethemes.moe/x.OGG?t=1"))
        assertFalse(ThemeQueue.isAudio("https://v.animethemes.moe/x.webm"))
    }

    @Test
    fun episodeCoverageReadsFreeFormRanges() {
        assertEquals(true, ThemeQueue.coversEpisode("1-12", 12))
        assertEquals(false, ThemeQueue.coversEpisode("1-12", 13))
        assertEquals(true, ThemeQueue.coversEpisode("2-5, 8-11", 9))
        assertEquals(false, ThemeQueue.coversEpisode("2-5, 8-11", 6))
        assertEquals(true, ThemeQueue.coversEpisode("13-", 40))
        assertEquals(true, ThemeQueue.coversEpisode("1000", 1000))
        assertNull(ThemeQueue.coversEpisode(null, 1))
        assertNull(ThemeQueue.coversEpisode("Movie", 1))
    }

    private fun choice(id: Int, ending: Boolean, vararg episodes: String?): ThemeChoice {
        val versions = episodes.mapIndexed { i, ep ->
            ThemeTrack(id, (if (ending) "ED " else "OP ") + id, "song $id", "", ep, "u$id-$i", emptyList(), ThemeWarningType.NONE,
                entryId = id * 10 + i, version = i + 1, versionCount = episodes.size, isEnding = ending)
        }
        return ThemeChoice(versions, versions.first(), ending)
    }

    @Test
    fun nowPlayingPicksTheVersionCoveringTheEpisode() {
        val choices = listOf(choice(1, false, "1-12"), choice(2, false, "13-24"), choice(3, true, "2-5", "6-12"))
        assertEquals(2, ThemeQueue.nowPlaying(choices, ending = false, episode = 14)?.themeId)
        assertEquals(3 * 10 + 1, ThemeQueue.nowPlaying(choices, ending = true, episode = 7)?.entryId)
        assertNull(ThemeQueue.nowPlaying(choices, ending = true, episode = 1))
    }

    @Test
    fun nowPlayingUsesTheOnlyThemeWhenEpisodesAreUnknown() {
        assertEquals(1, ThemeQueue.nowPlaying(listOf(choice(1, false, null)), ending = false, episode = 5)?.themeId)
        assertNull(ThemeQueue.nowPlaying(listOf(choice(1, false, null), choice(2, false, null)), ending = false, episode = 5))
    }

    @Test
    fun withShowTagsEveryVersion() {
        val c = ThemeQueue.withShow(choice(1, false, "1-6", "7-12"), 42, "Show")
        assertTrue(c.versions.all { it.mediaId == 42 && it.showTitle == "Show" })
        assertEquals(42, c.auto.mediaId)
    }

    @Test
    fun trackCarriesAudioAndKind() {
        val video = ThemeVideoUiModel(1, "https://v/x.webm", 1080, true, "BD", "NONE", null, false, "", audioUrl = "https://a/x.ogg")
        val theme = ThemeUiModel(1, "ED", 1, "ED 1", "s", "a", listOf(ThemeVersionUiModel(10, 1, "1-12", false, false, listOf(video))))
        val t = ThemeQueue.track(theme)!!
        assertEquals("https://a/x.ogg", t.audioUrl)
        assertTrue(t.isEnding)
    }
}
