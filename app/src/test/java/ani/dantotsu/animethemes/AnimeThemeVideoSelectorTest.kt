package ani.dantotsu.animethemes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnimeThemeVideoSelectorTest {

    private fun createVariant(
        id: Int,
        resolution: Int? = 1080,
        source: String? = "BD",
        nc: Boolean = true,
        subbed: Boolean = false,
        overlap: String? = null,
        entryVersion: Int = 1,
        episodes: String? = null,
        isSpoiler: Boolean = false,
        isNsfw: Boolean = false
    ): ThemeVideoVariant {
        return ThemeVideoVariant(
            videoId = id,
            videoUrl = "https://v.animethemes.moe/test-$id.webm",
            resolution = resolution,
            isCreditless = nc,
            source = source,
            overlap = overlap,
            tags = null,
            isSubbed = subbed,
            entryVersion = entryVersion,
            episodes = episodes,
            isSpoiler = isSpoiler,
            isNsfw = isNsfw,
            displayLabel = "${resolution}p • $source • ${if (nc) "NC" else "Credits"}",
            warningType = when {
                isSpoiler && isNsfw -> ThemeWarningType.SPOILER_AND_NSFW
                isSpoiler -> ThemeWarningType.SPOILER
                isNsfw -> ThemeWarningType.NSFW
                else -> ThemeWarningType.NONE
            }
        )
    }

    @Test
    fun testPurity_ReceivesImmutableSnapshotWithoutAmbientState() {
        val variants = listOf(
            createVariant(1, resolution = 1080, source = "BD"),
            createVariant(2, resolution = 720, source = "BD")
        )
        val pref1080 = ThemePlaybackPreferences(resolution = ResolutionPref.P1080)
        val pref720 = ThemePlaybackPreferences(resolution = ResolutionPref.P720)

        val selected1080 = AnimeThemeVideoSelector.selectPreferredVideo(variants, pref1080)
        val selected720 = AnimeThemeVideoSelector.selectPreferredVideo(variants, pref720)

        assertEquals(1, selected1080?.videoId)
        assertEquals(2, selected720?.videoId)
    }

    @Test
    fun testAutoPreference_IdenticalToV5ScoreVideo() {
        // v5 scoreVideo calculation:
        // nc: +1000, source: BD +500, WEB +300, DVD +100, res: (res?:720)/10, !subbed: +200, overlap NONE/TRANS: +100
        val v1 = createVariant(1, resolution = 1080, source = "BD", nc = true, subbed = false, overlap = "NONE")
        val v2 = createVariant(2, resolution = 720, source = "BD", nc = true, subbed = false, overlap = null)
        val v3 = createVariant(3, resolution = 1080, source = "WEB", nc = true, subbed = false, overlap = "TRANS")
        val v4 = createVariant(4, resolution = 1080, source = "BD", nc = false, subbed = false, overlap = null)

        val score1 = AnimeThemeVideoSelector.neutralAutoRank(v1)
        val score2 = AnimeThemeVideoSelector.neutralAutoRank(v2)
        val score3 = AnimeThemeVideoSelector.neutralAutoRank(v3)
        val score4 = AnimeThemeVideoSelector.neutralAutoRank(v4)

        // v1: 1000 + 500 + 108 + 200 + 100 = 1908
        // v2: 1000 + 500 + 72 + 200 + 0 = 1772
        // v3: 1000 + 300 + 108 + 200 + 100 = 1708
        // v4: 0 + 500 + 108 + 200 + 0 = 808
        assertEquals(1908, score1)
        assertEquals(1772, score2)
        assertEquals(1708, score3)
        assertEquals(808, score4)

        val selected = AnimeThemeVideoSelector.selectPreferredVideo(listOf(v4, v3, v2, v1), ThemePlaybackPreferences())
        assertEquals(1, selected?.videoId)
    }

    @Test
    fun testOverlap_NoneOrTrans_BeatsNonPreferredOverlap() {
        val withNone = createVariant(1, resolution = 720, source = "WEB", nc = true, subbed = false, overlap = "NONE")
        val withOver = createVariant(2, resolution = 720, source = "WEB", nc = true, subbed = false, overlap = "OVER")

        val selected = AnimeThemeVideoSelector.selectPreferredVideo(listOf(withOver, withNone), ThemePlaybackPreferences())
        assertEquals(1, selected?.videoId)
    }

    @Test
    fun testSourceClassification_UsesExplicitSourceFieldNotTags() {
        val webStreamWithBdTag = ThemeVideoVariant(
            videoId = 1,
            videoUrl = "https://v.animethemes.moe/test.webm",
            resolution = 1080,
            isCreditless = true,
            source = "WEB",
            overlap = null,
            tags = "BD-Rip",
            isSubbed = false,
            entryVersion = 1,
            episodes = null,
            isSpoiler = false,
            isNsfw = false,
            displayLabel = "1080p • WEB • NC",
            warningType = ThemeWarningType.NONE
        )
        val bdStream = createVariant(2, resolution = 1080, source = "BD", nc = true, subbed = false)

        val prefBd = ThemePlaybackPreferences(source = SourcePref.BD)
        val selected = AnimeThemeVideoSelector.selectPreferredVideo(listOf(webStreamWithBdTag, bdStream), prefBd)
        assertEquals(2, selected?.videoId)
    }

    @Test
    fun testExplicitSubbedField_Propagation() {
        val subbedStream = createVariant(1, resolution = 1080, source = "BD", nc = true, subbed = true)
        val cleanStream = createVariant(2, resolution = 1080, source = "BD", nc = true, subbed = false)

        val prefNoSub = ThemePlaybackPreferences(subtitles = SubtitlesPref.NO_SUBTITLES)
        val prefSub = ThemePlaybackPreferences(subtitles = SubtitlesPref.SUBTITLED)

        assertEquals(2, AnimeThemeVideoSelector.selectPreferredVideo(listOf(subbedStream, cleanStream), prefNoSub)?.videoId)
        assertEquals(1, AnimeThemeVideoSelector.selectPreferredVideo(listOf(subbedStream, cleanStream), prefSub)?.videoId)
    }

    @Test
    fun test720pPreference_Exact720Beats1080WithAllOtherMatches() {
        val stream1080 = createVariant(1, resolution = 1080, source = "BD", nc = true, subbed = false, overlap = "NONE")
        val stream720 = createVariant(2, resolution = 720, source = "BD", nc = true, subbed = false, overlap = "NONE")

        val pref720 = ThemePlaybackPreferences(resolution = ResolutionPref.P720)
        val selected = AnimeThemeVideoSelector.selectPreferredVideo(listOf(stream1080, stream720), pref720)

        // Exact 720p must win even though 1080p has higher neutral score
        assertEquals(2, selected?.videoId)
    }

    @Test
    fun test720pPreference_LowerResolutionBeatsHigherWhen720Absent() {
        val stream1080 = createVariant(1, resolution = 1080, source = "BD", nc = true, subbed = false)
        val stream480 = createVariant(2, resolution = 480, source = "BD", nc = true, subbed = false)

        val pref720 = ThemePlaybackPreferences(resolution = ResolutionPref.P720)
        val selected = AnimeThemeVideoSelector.selectPreferredVideo(listOf(stream1080, stream480), pref720)

        // When exact 720p is absent, 480p (lower) must beat 1080p (higher)
        assertEquals(2, selected?.videoId)
    }

    @Test
    fun test1080pPreference_Exact1080BeatsLowerStreams() {
        val stream1080 = createVariant(1, resolution = 1080, source = "BD", nc = true, subbed = false)
        val stream720 = createVariant(2, resolution = 720, source = "BD", nc = true, subbed = false)

        val pref1080 = ThemePlaybackPreferences(resolution = ResolutionPref.P1080)
        val selected = AnimeThemeVideoSelector.selectPreferredVideo(listOf(stream720, stream1080), pref1080)

        assertEquals(1, selected?.videoId)
    }

    @Test
    fun test480pPreference_HighestUnder480Beats720And1080() {
        val stream1080 = createVariant(1, resolution = 1080)
        val stream720 = createVariant(2, resolution = 720)
        val stream480 = createVariant(3, resolution = 480)
        val stream360 = createVariant(4, resolution = 360)

        val pref480 = ThemePlaybackPreferences(resolution = ResolutionPref.P480_OR_LOWER)
        val selected = AnimeThemeVideoSelector.selectPreferredVideo(listOf(stream1080, stream720, stream480, stream360), pref480)

        assertEquals(3, selected?.videoId)
    }

    @Test
    fun testSourceFallback_BDPreferredOverWEB() {
        val webStream = createVariant(1, resolution = 1080, source = "WEB")
        val bdStream = createVariant(2, resolution = 1080, source = "BD")

        val prefBd = ThemePlaybackPreferences(source = SourcePref.BD)
        val selected = AnimeThemeVideoSelector.selectPreferredVideo(listOf(webStream, bdStream), prefBd)

        assertEquals(2, selected?.videoId)

        // Fallback to WEB when BD is absent
        val selectedFallback = AnimeThemeVideoSelector.selectPreferredVideo(listOf(webStream), prefBd)
        assertEquals(1, selectedFallback?.videoId)
    }

    @Test
    fun testCreditsFallback_NCvsCredits() {
        val ncStream = createVariant(1, resolution = 1080, nc = true)
        val creditedStream = createVariant(2, resolution = 1080, nc = false)

        val prefCredits = ThemePlaybackPreferences(credits = CreditsPref.WITH_CREDITS)
        assertEquals(2, AnimeThemeVideoSelector.selectPreferredVideo(listOf(ncStream, creditedStream), prefCredits)?.videoId)

        val prefNc = ThemePlaybackPreferences(credits = CreditsPref.NC)
        assertEquals(1, AnimeThemeVideoSelector.selectPreferredVideo(listOf(ncStream, creditedStream), prefNc)?.videoId)
    }

    @Test
    fun testSafetyPrecedence_OverAllUserPreferences() {
        val safe720 = createVariant(1, resolution = 720, isSpoiler = false, isNsfw = false)
        val spoiler1080 = createVariant(2, resolution = 1080, isSpoiler = true, isNsfw = false)

        // User explicitly prefers 1080p, but 1080p is a spoiler. Safety gate must choose safe 720p!
        val pref1080 = ThemePlaybackPreferences(resolution = ResolutionPref.P1080)
        val selected = AnimeThemeVideoSelector.selectPreferredVideo(listOf(safe720, spoiler1080), pref1080)

        assertEquals(1, selected?.videoId)
        assertFalse(selected!!.isSpoiler)
    }

    @Test
    fun testFlaggedOnlyTheme_SelectsBestFlaggedWithWarningPreserved() {
        val spoiler720 = createVariant(1, resolution = 720, isSpoiler = true)
        val spoiler1080 = createVariant(2, resolution = 1080, isSpoiler = true)

        val pref1080 = ThemePlaybackPreferences(resolution = ResolutionPref.P1080)
        val selected = AnimeThemeVideoSelector.selectPreferredVideo(listOf(spoiler720, spoiler1080), pref1080)

        assertEquals(2, selected?.videoId)
        assertTrue(selected!!.isSpoiler)
        assertEquals(ThemeWarningType.SPOILER, selected.warningType)
    }

    @Test
    fun testVariantDeduplication_PreservesDifferentEpisodeRanges() {
        val theme = ThemeUiModel(
            themeId = 1,
            type = "ED",
            sequence = 1,
            displayTag = "ED 1",
            songTitle = "Test Song",
            artistDisplay = "Test Artist",
            alternateVersions = listOf(
                ThemeVersionUiModel(
                    entryId = 1,
                    version = 1,
                    episodes = "1-12",
                    isNsfw = false,
                    isSpoiler = false,
                    videos = listOf(
                        ThemeVideoUiModel(
                            videoId = 101,
                            videoUrl = "url-101",
                            resolution = 1080,
                            isCreditless = true,
                            source = "BD",
                            overlap = null,
                            tags = null,
                            isSubbed = false,
                            badgeLabel = "1080p • BD • NC"
                        )
                    )
                ),
                ThemeVersionUiModel(
                    entryId = 2,
                    version = 1,
                    episodes = "13-24",
                    isNsfw = false,
                    isSpoiler = false,
                    videos = listOf(
                        ThemeVideoUiModel(
                            videoId = 102,
                            videoUrl = "url-102",
                            resolution = 1080,
                            isCreditless = true,
                            source = "BD",
                            overlap = null,
                            tags = null,
                            isSubbed = false,
                            badgeLabel = "1080p • BD • NC"
                        )
                    )
                )
            )
        )

        val variants = AnimeThemeVideoSelector.extractVariants(theme)
        assertEquals(2, variants.size)
        assertEquals("1-12", variants[0].episodes)
        assertEquals("13-24", variants[1].episodes)
    }

    @Test
    fun testVariantDeduplication_CollapsesIdenticalMetadataAndEpisodeRange() {
        val theme = ThemeUiModel(
            themeId = 1,
            type = "OP",
            sequence = 1,
            displayTag = "OP 1",
            songTitle = "Test Song",
            artistDisplay = "Test Artist",
            alternateVersions = listOf(
                ThemeVersionUiModel(
                    entryId = 1,
                    version = 1,
                    episodes = "1-12",
                    isNsfw = false,
                    isSpoiler = false,
                    videos = listOf(
                        ThemeVideoUiModel(
                            videoId = 101,
                            videoUrl = "url-101",
                            resolution = 1080,
                            isCreditless = true,
                            source = "BD",
                            overlap = null,
                            tags = null,
                            isSubbed = false,
                            badgeLabel = "1080p • BD • NC"
                        ),
                        ThemeVideoUiModel(
                            videoId = 102,
                            videoUrl = "url-102",
                            resolution = 1080,
                            isCreditless = true,
                            source = "BD",
                            overlap = null,
                            tags = null,
                            isSubbed = false,
                            badgeLabel = "1080p • BD • NC"
                        )
                    )
                )
            )
        )

        val variants = AnimeThemeVideoSelector.extractVariants(theme)
        assertEquals(1, variants.size)
    }
}
