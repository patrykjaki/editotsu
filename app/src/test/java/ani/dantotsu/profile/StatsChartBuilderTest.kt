package ani.dantotsu.profile

import ani.dantotsu.connections.anilist.api.MediaListOptions
import ani.dantotsu.connections.anilist.api.Query
import ani.dantotsu.connections.anilist.api.ScoreFormat
import ani.dantotsu.profile.ChartBuilder.ChartKind
import ani.dantotsu.profile.ChartBuilder.MediaType
import ani.dantotsu.profile.ChartBuilder.SectionId
import ani.dantotsu.profile.ChartBuilder.StatType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StatsChartBuilderTest {

    @Test
    fun formatMediaFormatMapsKnownEnumsAndFallsBackToSentenceCase() {
        assertEquals("TV", ChartBuilder.formatMediaFormat("TV"))
        assertEquals("TV short", ChartBuilder.formatMediaFormat("TV_SHORT"))
        assertEquals("Movie", ChartBuilder.formatMediaFormat("MOVIE"))
        assertEquals("OVA", ChartBuilder.formatMediaFormat("OVA"))
        assertEquals("ONA", ChartBuilder.formatMediaFormat("ONA"))
        assertEquals("Light novel", ChartBuilder.formatMediaFormat("NOVEL"))
        assertEquals("One-shot", ChartBuilder.formatMediaFormat("ONE_SHOT"))
        assertEquals("Custom format", ChartBuilder.formatMediaFormat("CUSTOM_FORMAT"))
    }

    @Test
    fun formatMediaStatusAdaptsToAnimeAndManga() {
        assertEquals("Watching", ChartBuilder.formatMediaStatus("CURRENT", MediaType.ANIME))
        assertEquals("Reading", ChartBuilder.formatMediaStatus("CURRENT", MediaType.MANGA))
        assertEquals("Rewatching", ChartBuilder.formatMediaStatus("REPEATING", MediaType.ANIME))
        assertEquals("Rereading", ChartBuilder.formatMediaStatus("REPEATING", MediaType.MANGA))
        assertEquals("Completed", ChartBuilder.formatMediaStatus("COMPLETED", MediaType.ANIME))
        assertEquals("Dropped", ChartBuilder.formatMediaStatus("DROPPED", MediaType.MANGA))
    }

    @Test
    fun formatCountryCodeMapsCommonCodes() {
        assertEquals("Japan", ChartBuilder.formatCountryCode("JP"))
        assertEquals("South Korea", ChartBuilder.formatCountryCode("KR"))
        assertEquals("China", ChartBuilder.formatCountryCode("CN"))
        assertEquals("Taiwan", ChartBuilder.formatCountryCode("TW"))
    }

    @Test
    fun formatLengthBucketAndSortKeyOrderNaturally() {
        assertEquals("1 ep", ChartBuilder.formatLengthBucket("1", MediaType.ANIME))
        assertEquals("2–6 eps", ChartBuilder.formatLengthBucket("2-6", MediaType.ANIME))
        assertEquals("101+ eps", ChartBuilder.formatLengthBucket("101+", MediaType.ANIME))
        assertEquals("2–6 ch", ChartBuilder.formatLengthBucket("2-6", MediaType.MANGA))
        assertEquals("Unknown", ChartBuilder.formatLengthBucket(null, MediaType.ANIME))
        assertEquals("Unknown", ChartBuilder.formatLengthBucket("unknown", MediaType.MANGA))

        val rawBuckets = listOf("101+", null, "7-16", "1", "2-6", "29-55")
        val sorted = rawBuckets.sortedBy { ChartBuilder.lengthSortKey(it) }
        assertEquals(listOf("1", "2-6", "7-16", "29-55", "101+", null), sorted)
    }

    @Test
    fun valueFormattersHandleEdgesCleanly() {
        assertEquals("0", ChartBuilder.formatDays(0))
        assertEquals("<0.1", ChartBuilder.formatDays(30))
        assertEquals("1.5", ChartBuilder.formatDays(2160))
        assertEquals("114", ChartBuilder.formatDays(114 * 1440))
        assertEquals("—", ChartBuilder.formatScore(0f))
        assertEquals("76.4", ChartBuilder.formatScore(76.42f))
        assertEquals("0h", ChartBuilder.formatHours(0.0))
        assertEquals("<1h", ChartBuilder.formatHours(0.4))
        assertEquals("1,240h", ChartBuilder.formatHours(1240.0))
        assertEquals("1.2k", ChartBuilder.formatCompactNumber(1200.0))
        assertEquals("12k", ChartBuilder.formatCompactNumber(12400.0))
        assertEquals("<1%", ChartBuilder.formatSharePercent(0.4))
        assertEquals("48%", ChartBuilder.formatSharePercent(48.2))
    }

    @Test
    fun rawScoreToDecileBinMapsFullRange() {
        assertEquals(0, ChartBuilder.rawScoreToDecileBin(0))
        assertEquals(10, ChartBuilder.rawScoreToDecileBin(1))
        assertEquals(10, ChartBuilder.rawScoreToDecileBin(10))
        assertEquals(20, ChartBuilder.rawScoreToDecileBin(11))
        assertEquals(70, ChartBuilder.rawScoreToDecileBin(68))
        assertEquals(80, ChartBuilder.rawScoreToDecileBin(80))
        assertEquals(100, ChartBuilder.rawScoreToDecileBin(91))
        assertEquals(100, ChartBuilder.rawScoreToDecileBin(100))
    }

    @Test
    fun scoreHistogramBucketsPoint100AndComputesWeightedMeanScore() {
        val scores = listOf(
            Query.StatisticsScore(count = 2, meanScore = 72f, minutesWatched = 120, chaptersRead = 0, mediaIds = emptyList(), score = 72),
            Query.StatisticsScore(count = 6, meanScore = 78f, minutesWatched = 360, chaptersRead = 0, mediaIds = emptyList(), score = 78),
            Query.StatisticsScore(count = 3, meanScore = 95f, minutesWatched = 180, chaptersRead = 0, mediaIds = emptyList(), score = 95),
            Query.StatisticsScore(count = 5, meanScore = 0f, minutesWatched = 60, chaptersRead = 0, mediaIds = emptyList(), score = 0),
        )

        val countSection = ChartBuilder.buildScoreHistogramSection(
            mediaType = MediaType.ANIME,
            statType = StatType.COUNT,
            primaryUser = "Alice",
            primaryFormat = ScoreFormat.POINT_100,
            primaryScores = scores,
            compareUser = null,
            compareFormat = null,
            compareScores = null,
        )
        assertNotNull(countSection)
        assertEquals(ChartKind.SCORE_HISTOGRAM, countSection!!.kind)
        assertEquals(10, countSection.entries.size)
        val bin80 = countSection.entries.first { it.key == "80" }
        val bin100 = countSection.entries.first { it.key == "100" }
        assertEquals(8.0, bin80.value, 0.001)
        assertEquals(3.0, bin100.value, 0.001)
        // Weighted average of (2 * 72 + 6 * 78) / 8 = 76.5
        assertEquals(76.5f, bin80.meanScore, 0.01f)
        assertEquals("Peak 80", countSection.subtitle)
    }

    @Test
    fun scoreHistogramKeepsNativeAxisForSingleUserPoint5AndNormalizesForComparison() {
        val aliceScores = listOf(
            Query.StatisticsScore(count = 4, meanScore = 80f, minutesWatched = 240, chaptersRead = 0, mediaIds = emptyList(), score = 4),
            Query.StatisticsScore(count = 2, meanScore = 100f, minutesWatched = 120, chaptersRead = 0, mediaIds = emptyList(), score = 5),
        )
        val bobScores = listOf(
            Query.StatisticsScore(count = 5, meanScore = 80f, minutesWatched = 300, chaptersRead = 0, mediaIds = emptyList(), score = 8),
        )

        val single = ChartBuilder.buildScoreHistogramSection(
            mediaType = MediaType.ANIME,
            statType = StatType.COUNT,
            primaryUser = "Alice",
            primaryFormat = ScoreFormat.POINT_5,
            primaryScores = aliceScores,
            compareUser = null,
            compareFormat = null,
            compareScores = null,
        )!!
        assertEquals(listOf("1★", "2★", "3★", "4★", "5★"), single.entries.map { it.label })
        assertEquals(4.0, single.entries[3].value, 0.001)

        val compared = ChartBuilder.buildScoreHistogramSection(
            mediaType = MediaType.ANIME,
            statType = StatType.COUNT,
            primaryUser = "Alice",
            primaryFormat = ScoreFormat.POINT_5,
            primaryScores = aliceScores,
            compareUser = "You",
            compareFormat = ScoreFormat.POINT_10,
            compareScores = bobScores,
        )!!
        assertEquals(10, compared.entries.size)
        val bin80 = compared.entries.first { it.key == "80" }
        assertEquals(4.0, bin80.value, 0.001)
        assertEquals(5.0, bin80.compareValue!!, 0.001)
    }

    @Test
    fun yearTimelineFillsShortGapsAndSkipsFillForWideSpans() {
        val shortSpan = listOf(
            ChartBuilder.YearPoint(2022, count = 10, minutesWatched = 600, chaptersRead = 0, meanScore = 75f),
            ChartBuilder.YearPoint(2020, count = 4, minutesWatched = 240, chaptersRead = 0, meanScore = 70f),
            ChartBuilder.YearPoint(0, count = 2, minutesWatched = 60, chaptersRead = 0, meanScore = 0f),
        )
        val filled = ChartBuilder.buildYearTimelineSection(
            id = SectionId.START_YEAR,
            title = "Start year",
            mediaType = MediaType.ANIME,
            statType = StatType.COUNT,
            primaryUser = "Alice",
            compareUser = null,
            primaryYears = shortSpan,
            compareYears = null,
        )!!
        assertEquals(listOf("2020", "2021", "2022"), filled.entries.map { it.label })
        assertEquals(0.0, filled.entries[1].value, 0.001)
        assertEquals("2020–2022", filled.subtitle)

        val wideSpan = listOf(
            ChartBuilder.YearPoint(1950, count = 1, minutesWatched = 60, chaptersRead = 0, meanScore = 80f),
            ChartBuilder.YearPoint(2024, count = 20, minutesWatched = 1200, chaptersRead = 0, meanScore = 78f),
        )
        val sparse = ChartBuilder.buildYearTimelineSection(
            id = SectionId.RELEASE_YEAR,
            title = "Release year",
            mediaType = MediaType.ANIME,
            statType = StatType.COUNT,
            primaryUser = "Alice",
            compareUser = null,
            primaryYears = wideSpan,
            compareYears = null,
        )!!
        assertEquals(listOf("1950", "2024"), sparse.entries.map { it.label })
    }

    @Test
    fun rankedSectionSortsDescendingMergesDisjointCompareKeysAndHandlesEmpty() {
        val empty = ChartBuilder.buildRankedSection(
            id = SectionId.GENRES,
            title = "Genres",
            mediaType = MediaType.ANIME,
            statType = StatType.COUNT,
            primaryUser = "Alice",
            compareUser = null,
            primaryRows = emptyList(),
            compareRows = null,
        )
        assertNull(empty)

        val primary = listOf(
            ChartBuilder.RawStatRow("Action", "Action", count = 15, minutesWatched = 900, chaptersRead = 0, meanScore = 75f),
            ChartBuilder.RawStatRow("Drama", "Drama", count = 30, minutesWatched = 1800, chaptersRead = 0, meanScore = 82f),
        )
        val compare = listOf(
            ChartBuilder.RawStatRow("Action", "Action", count = 20, minutesWatched = 1200, chaptersRead = 0, meanScore = 79f),
            ChartBuilder.RawStatRow("Comedy", "Comedy", count = 12, minutesWatched = 720, chaptersRead = 0, meanScore = 74f),
        )

        val merged = ChartBuilder.buildRankedSection(
            id = SectionId.GENRES,
            title = "Genres",
            mediaType = MediaType.ANIME,
            statType = StatType.COUNT,
            primaryUser = "Alice",
            compareUser = "You",
            primaryRows = primary,
            compareRows = compare,
        )!!

        assertEquals(listOf("Drama", "Action", "Comedy"), merged.entries.map { it.label })
        assertEquals(30.0, merged.entries[0].value, 0.001)
        assertEquals(0.0, merged.entries[0].compareValue!!, 0.001)
        assertEquals(0.0, merged.entries[2].value, 0.001)
        assertEquals(12.0, merged.entries[2].compareValue!!, 0.001)
    }

    @Test
    fun buildProfileStatsFlagsEmptyAnimeWhenMangaHasData() {
        val emptyStats = emptyStatistics()
        val mangaStats = emptyStatistics(count = 12, chaptersRead = 240, volumesRead = 18, meanScore = 81.5f)
        val user = Query.StatisticsUser(
            id = 1,
            name = "MangaReader",
            mediaListOptions = MediaListOptions(ScoreFormat.POINT_100, null, null, null),
            statistics = Query.StatisticsTypes(anime = emptyStats, manga = mangaStats),
        )

        val animePayload = ChartBuilder.buildProfileStats(listOf(user), MediaType.ANIME, StatType.COUNT)
        assertTrue(animePayload.isEmpty)
        assertTrue(animePayload.hasOtherMediaStats)

        val mangaPayload = ChartBuilder.buildProfileStats(listOf(user), MediaType.MANGA, StatType.COUNT)
        assertFalse(mangaPayload.isEmpty)
        assertFalse(mangaPayload.hasOtherMediaStats)
        assertEquals(12, mangaPayload.summary?.count)
        assertEquals(240, mangaPayload.summary?.episodesOrChapters)
        assertEquals(18, mangaPayload.summary?.minutesOrVolumes)
    }

    @Test
    fun niceAxisMaxRoundsToCleanIntervals() {
        assertEquals(1.0, ChartBuilder.niceAxisMax(0.0), 0.001)
        assertEquals(10.0, ChartBuilder.niceAxisMax(7.0), 0.001)
        assertEquals(20.0, ChartBuilder.niceAxisMax(14.0), 0.001)
        assertEquals(50.0, ChartBuilder.niceAxisMax(33.0), 0.001)
        assertEquals(100.0, ChartBuilder.niceAxisMax(75.0), 0.001)
        assertEquals(500.0, ChartBuilder.niceAxisMax(312.0), 0.001)
    }

    private fun emptyStatistics(
        count: Int = 0,
        meanScore: Float = 0f,
        minutesWatched: Int = 0,
        episodesWatched: Int = 0,
        chaptersRead: Int = 0,
        volumesRead: Int = 0,
    ) = Query.Statistics(
        count = count,
        meanScore = meanScore,
        standardDeviation = 0f,
        minutesWatched = minutesWatched,
        episodesWatched = episodesWatched,
        chaptersRead = chaptersRead,
        volumesRead = volumesRead,
        formats = emptyList(),
        statuses = emptyList(),
        scores = emptyList(),
        lengths = emptyList(),
        releaseYears = emptyList(),
        startYears = emptyList(),
        genres = emptyList(),
        tags = emptyList(),
        countries = emptyList(),
        voiceActors = emptyList(),
        staff = emptyList(),
        studios = emptyList(),
    )
}
