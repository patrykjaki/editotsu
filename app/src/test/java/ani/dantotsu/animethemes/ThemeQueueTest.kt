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
    fun failuresRetrySameVideoThenFallbacks() {
        val t = ThemeTrack(1, "OP 1", "song", "", "1-12", "https://v/a.webm", listOf("https://v/b.webm", "https://v/c.webm"), ThemeWarningType.NONE)
        // The plan the player walks through: attempt n after the n-th failure.
        val plan = ThemeQueue.attempts(ThemeQueue.sources(t))
        assertEquals(ThemeQueue.Attempt("https://v/a.webm", 1_000L), plan.getOrNull(1))
        assertEquals(ThemeQueue.Attempt("https://v/a.webm", 3_000L), plan.getOrNull(2))
        assertEquals(ThemeQueue.Attempt("https://v/b.webm", 500L), plan.getOrNull(3))
        assertEquals(ThemeQueue.Attempt("https://v/c.webm", 500L), plan.getOrNull(4))
        assertNull(plan.getOrNull(5))
    }

    @Test
    fun earlyEndAndLabel() {
        assertTrue(ThemeQueue.endedEarly(40_000, 90_000))
        assertFalse(ThemeQueue.endedEarly(88_000, 90_000))
        assertFalse(ThemeQueue.endedEarly(5_000, 0))
        val t = ThemeTrack(1, "OP 1", "We Are!", "", null, "u", emptyList(), ThemeWarningType.NONE)
        assertEquals("OP 1 · We Are!", ThemeQueue.label(t))
        assertEquals("OP 1", ThemeQueue.label(t.copy(songTitle = "OP 1")))
    }

    private fun version(entryId: Int, v: Int, episodes: String?, spoiler: Boolean = false, vararg videos: ThemeVideoUiModel) =
        ThemeVersionUiModel(entryId, v, episodes, false, spoiler, videos.toList())

    /** OP 1 with v1 (eps 1–12), v2 (eps 13–24, spoiler, best video), v3 (no videos). */
    private fun multiVersion() = ThemeUiModel(
        themeId = 7, type = "OP", sequence = 1, displayTag = "OP 1", songTitle = "song", artistDisplay = "artist",
        alternateVersions = listOf(
            version(72, 2, "13-24", spoiler = true,
                video(21, "https://v/v2-nc.webm", nc = true), video(22, "https://v/v2.webm")),
            version(71, 1, "1-12", false,
                video(11, "https://v/v1.webm", res = 720), video(12, "https://v/v1-web.webm", res = 720, source = "WEB")),
            version(73, 3, "25", false),
        ),
    )

    @Test
    fun versionsAreListedInOrderWithOwnEpisodesAndFlags() {
        val choice = ThemeQueue.choice(multiVersion())!!
        assertEquals(listOf(1, 2), choice.versions.map { it.version })
        assertEquals(listOf("1-12", "13-24"), choice.versions.map { it.episodes })
        assertEquals(listOf(ThemeWarningType.NONE, ThemeWarningType.SPOILER), choice.versions.map { it.warning })
        assertTrue(choice.versions.all { it.versionCount == 2 })
        assertEquals("https://v/v2-nc.webm", choice.versions[1].url)
    }

    @Test
    fun fallbacksStayInsideTheVersion() {
        val choice = ThemeQueue.choice(multiVersion())!!
        assertEquals(listOf("https://v/v1-web.webm"), choice.versions[0].fallbackUrls)
        assertEquals(listOf("https://v/v2.webm"), choice.versions[1].fallbackUrls)
    }

    @Test
    fun autoAvoidsSpoilerFlaggedVersion() {
        // v2's creditless BD video ranks higher, but v2 is flagged spoiler.
        assertEquals(1, ThemeQueue.choice(multiVersion())!!.auto.version)
        assertEquals(1, ThemeQueue.track(multiVersion())!!.version)
    }

    @Test
    fun preferredVersionSkipsShieldedVersions() {
        val v1 = version(81, 1, "13-24", false, video(1, "https://v/a.webm", nc = true))
        val v2 = version(82, 2, "1-12", false, video(2, "https://v/b.webm"))
        val theme = ThemeUiModel(8, "ED", 1, "ED 1", "s", "a", listOf(v1, v2))
        val choice = ThemeQueue.choice(theme)!!
        assertEquals(1, choice.auto.version)
        val shield = { t: ThemeTrack -> ThemeQueue.shielded(true, t.episodes, 3) }
        assertEquals(2, ThemeQueue.preferredVersion(choice, shield).version)
        assertEquals(1, ThemeQueue.preferredVersion(choice) { false }.version)
        // Everything hidden: keep the selector's pick.
        assertEquals(1, ThemeQueue.preferredVersion(choice) { true }.version)
    }

    @Test
    fun playerLabelNamesTheVersion() {
        val choice = ThemeQueue.choice(multiVersion())!!
        assertEquals("OP 1 v2 · song", ThemeQueue.label(choice.versions[1]))
    }

    @Test
    fun playQueueUsesChosenVersion() {
        val data = AnimeThemesData(1, "show", listOf(multiVersion(), theme(2, "OP", 2)), emptyList())
        val shown = ThemeQueue.choices(data).map { it.auto }
        val picked = ThemeQueue.choices(data)[0].versions[1]
        val queue = ThemeQueue.playQueue(shown, picked, { false })
        assertEquals(listOf(2, 1), queue.map { it.version })
        assertEquals("https://v/v2-nc.webm", queue[0].url)
    }
}
