package ani.dantotsu.animethemes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnimeThemesSelectionTest {

    @Test
    fun testVideoScoringPrecedence() {
        val baseVideo = AnimeThemesVideoDto(
            id = 1,
            basename = "test-OP1.webm",
            filename = "test-OP1",
            resolution = 720,
            nc = false,
            subbed = false,
            source = "WEB",
            overlap = "NONE",
            tags = "WEB",
            link = "https://v.animethemes.moe/test-OP1.webm"
        )

        val ncVideo = baseVideo.copy(id = 2, nc = true)
        val bdVideo = baseVideo.copy(id = 3, source = "BD")
        val hdVideo = baseVideo.copy(id = 4, resolution = 1080)
        val subbedVideo = baseVideo.copy(id = 5, subbed = true)

        val baseScore = AnimeThemesRepository.scoreVideo(baseVideo)
        val ncScore = AnimeThemesRepository.scoreVideo(ncVideo)
        val bdScore = AnimeThemesRepository.scoreVideo(bdVideo)
        val hdScore = AnimeThemesRepository.scoreVideo(hdVideo)
        val subbedScore = AnimeThemesRepository.scoreVideo(subbedVideo)

        // NC provides +1000 boost
        assertTrue("NC video score ($ncScore) must be higher than base ($baseScore)", ncScore > baseScore)

        // BD source (+500) beats WEB (+300)
        assertTrue("BD score ($bdScore) must be higher than WEB score ($baseScore)", bdScore > baseScore)

        // 1080p (+108) beats 720p (+72)
        assertTrue("1080p score ($hdScore) must be higher than 720p score ($baseScore)", hdScore > baseScore)

        // Clean unsubbed (+200) beats hardsubbed (+0)
        assertTrue("Unsubbed score ($baseScore) must be higher than subbed score ($subbedScore)", baseScore > subbedScore)
    }

    @Test
    fun testSafeEntrySelectionWhenV1IsSpoiler() {
        val spoilerEntry = AnimeThemesEntryDto(
            id = 101,
            version = 1,
            episodes = "1-12",
            nsfw = false,
            spoiler = true,
            videos = AnimeThemesVideoConnectionDto(
                nodes = listOf(
                    AnimeThemesVideoDto(
                        id = 1001,
                        basename = "v1-spoiler.webm",
                        resolution = 1080,
                        nc = true,
                        source = "BD",
                        link = "https://v.animethemes.moe/v1.webm"
                    )
                )
            )
        )

        val safeEntry = AnimeThemesEntryDto(
            id = 102,
            version = 2,
            episodes = "13-25",
            nsfw = false,
            spoiler = false,
            videos = AnimeThemesVideoConnectionDto(
                nodes = listOf(
                    AnimeThemesVideoDto(
                        id = 1002,
                        basename = "v2-safe.webm",
                        resolution = 720,
                        nc = true,
                        source = "BD",
                        link = "https://v.animethemes.moe/v2.webm"
                    )
                )
            )
        )

        val themeDto = AnimeThemesThemeDto(
            id = 1,
            type = "OP",
            sequence = 1,
            slug = "OP1",
            song = AnimeThemesSongDto(id = 1, title = AnimeThemesTitleDto(romaji = "Test Song")),
            animethemeentries = listOf(spoilerEntry, safeEntry)
        )

        val uiModel = AnimeThemesRepository.mapThemeToUiModel(themeDto)
        val variants = AnimeThemeVideoSelector.extractVariants(uiModel)
        val selected = AnimeThemeVideoSelector.selectPreferredVideo(variants, ThemePlaybackPreferences())

        assertNotNull("Selected video must be present", selected)
        assertEquals("Safe entry video (id 1002) must be chosen", 1002, selected?.videoId)
        assertEquals("https://v.animethemes.moe/v2.webm", selected?.videoUrl)
        assertFalse(selected!!.isSpoiler)
        assertEquals(ThemeWarningType.NONE, selected.warningType)
    }

    @Test
    fun testFlaggedOnlyThemesBlockAutomaticPlayback() {
        val spoilerOnlyTheme = AnimeThemesThemeDto(
            id = 2,
            type = "ED",
            sequence = 1,
            slug = "ED1",
            song = AnimeThemesSongDto(id = 2, title = AnimeThemesTitleDto(romaji = "Spoiler Song")),
            animethemeentries = listOf(
                AnimeThemesEntryDto(
                    id = 201,
                    version = 1,
                    spoiler = true,
                    nsfw = false,
                    videos = AnimeThemesVideoConnectionDto(
                        nodes = listOf(
                            AnimeThemesVideoDto(
                                id = 2001,
                                basename = "ed1-spoiler.webm",
                                link = "https://v.animethemes.moe/ed1.webm"
                            )
                        )
                    )
                )
            )
        )

        val uiModelSpoiler = AnimeThemesRepository.mapThemeToUiModel(spoilerOnlyTheme)
        val variantsSpoiler = AnimeThemeVideoSelector.extractVariants(uiModelSpoiler)
        val selectedSpoiler = AnimeThemeVideoSelector.selectPreferredVideo(variantsSpoiler, ThemePlaybackPreferences())

        assertNotNull(selectedSpoiler)
        assertTrue(selectedSpoiler!!.isSpoiler)
        assertEquals(ThemeWarningType.SPOILER, selectedSpoiler.warningType)

        val nsfwOnlyTheme = AnimeThemesThemeDto(
            id = 3,
            type = "OP",
            sequence = 2,
            slug = "OP2",
            song = AnimeThemesSongDto(id = 3, title = AnimeThemesTitleDto(romaji = "NSFW Song")),
            animethemeentries = listOf(
                AnimeThemesEntryDto(
                    id = 301,
                    version = 1,
                    spoiler = false,
                    nsfw = true,
                    videos = AnimeThemesVideoConnectionDto(
                        nodes = listOf(
                            AnimeThemesVideoDto(
                                id = 3001,
                                basename = "op2-nsfw.webm",
                                link = "https://v.animethemes.moe/op2.webm"
                            )
                        )
                    )
                )
            )
        )

        val uiModelNsfw = AnimeThemesRepository.mapThemeToUiModel(nsfwOnlyTheme)
        val variantsNsfw = AnimeThemeVideoSelector.extractVariants(uiModelNsfw)
        val selectedNsfw = AnimeThemeVideoSelector.selectPreferredVideo(variantsNsfw, ThemePlaybackPreferences())

        assertNotNull(selectedNsfw)
        assertTrue(selectedNsfw!!.isNsfw)
        assertEquals(ThemeWarningType.NSFW, selectedNsfw.warningType)
    }

    @Test
    fun testMixedThemeAlternateVersionFlagsPreservation() {
        val safeEntry = AnimeThemesEntryDto(
            id = 401,
            version = 1,
            spoiler = false,
            nsfw = false,
            videos = AnimeThemesVideoConnectionDto(
                nodes = listOf(AnimeThemesVideoDto(id = 4001, basename = "safe.webm", link = "https://v.animethemes.moe/safe.webm"))
            )
        )
        val spoilerEntry = AnimeThemesEntryDto(
            id = 402,
            version = 2,
            spoiler = true,
            nsfw = false,
            videos = AnimeThemesVideoConnectionDto(
                nodes = listOf(AnimeThemesVideoDto(id = 4002, basename = "spoiler.webm", link = "https://v.animethemes.moe/spoiler.webm"))
            )
        )
        val nsfwEntry = AnimeThemesEntryDto(
            id = 403,
            version = 3,
            spoiler = false,
            nsfw = true,
            videos = AnimeThemesVideoConnectionDto(
                nodes = listOf(AnimeThemesVideoDto(id = 4003, basename = "nsfw.webm", link = "https://v.animethemes.moe/nsfw.webm"))
            )
        )

        val mixedTheme = AnimeThemesThemeDto(
            id = 4,
            type = "OP",
            sequence = 1,
            slug = "OP1",
            song = AnimeThemesSongDto(id = 4, title = AnimeThemesTitleDto(romaji = "Mixed Song")),
            animethemeentries = listOf(safeEntry, spoilerEntry, nsfwEntry)
        )

        val uiModel = AnimeThemesRepository.mapThemeToUiModel(mixedTheme)
        val variants = AnimeThemeVideoSelector.extractVariants(uiModel)
        val selected = AnimeThemeVideoSelector.selectPreferredVideo(variants, ThemePlaybackPreferences())

        assertNotNull("Safe default must be available for mixed theme", selected)
        assertEquals(4001, selected?.videoId)
        assertEquals(ThemeWarningType.NONE, selected?.warningType)

        assertEquals(3, variants.size)
        assertFalse(variants[0].isSpoiler || variants[0].isNsfw)
        assertTrue(variants[1].isSpoiler)
        assertTrue(variants[2].isNsfw)
    }

    @Test
    fun testArtistDisplayFormatting() {
        // 1. Direct solo artist
        val soloPerf = listOf(
            AnimeThemesPerformanceDto(
                artist = AnimeThemesArtistDto(id = 1, name = AnimeThemesArtistNameDto(main = "Eve"))
            )
        )
        assertEquals("Eve", AnimeThemesRepository.formatArtistDisplay(soloPerf))

        // 2. Artist alias
        val aliasPerf = listOf(
            AnimeThemesPerformanceDto(
                alias = "Who-ya Extended",
                artist = AnimeThemesArtistDto(id = 2, name = AnimeThemesArtistNameDto(main = "Who-ya"))
            )
        )
        assertEquals("Who-ya Extended", AnimeThemesRepository.formatArtistDisplay(aliasPerf))

        // 3. Artist performing "as" character
        val asPerf = listOf(
            AnimeThemesPerformanceDto(
                asAlias = "Shinji Ikari",
                artist = AnimeThemesArtistDto(id = 3, name = AnimeThemesArtistNameDto(main = "Megumi Ogata"))
            )
        )
        assertEquals("Megumi Ogata (as Shinji Ikari)", AnimeThemesRepository.formatArtistDisplay(asPerf))

        // 4. Band with member credits (Bocchi the Rock - Kessoku Band)
        val bandPerf = listOf(
            AnimeThemesPerformanceDto(
                relevance = 1,
                memberAs = "Nijika Ijichi",
                artist = AnimeThemesArtistDto(id = 10, name = AnimeThemesArtistNameDto(main = "Kessoku Band")),
                member = AnimeThemesArtistDto(id = 20, name = AnimeThemesArtistNameDto(main = "Sayumi Suzushiro"))
            ),
            AnimeThemesPerformanceDto(
                relevance = 2,
                memberAs = "Ikuyo Kita",
                artist = AnimeThemesArtistDto(id = 10, name = AnimeThemesArtistNameDto(main = "Kessoku Band")),
                member = AnimeThemesArtistDto(id = 21, name = AnimeThemesArtistNameDto(main = "Ikumi Hasegawa"))
            )
        )
        assertEquals("Kessoku Band", AnimeThemesRepository.formatArtistDisplay(bandPerf))

        // 5. Multiple distinct artists with relevance sorting
        val multiPerf = listOf(
            AnimeThemesPerformanceDto(
                relevance = 2,
                artist = AnimeThemesArtistDto(id = 30, name = AnimeThemesArtistNameDto(main = "AKLO"))
            ),
            AnimeThemesPerformanceDto(
                relevance = 1,
                artist = AnimeThemesArtistDto(id = 31, name = AnimeThemesArtistNameDto(main = "ALI"))
            )
        )
        assertEquals("ALI, AKLO", AnimeThemesRepository.formatArtistDisplay(multiPerf))
    }
}
