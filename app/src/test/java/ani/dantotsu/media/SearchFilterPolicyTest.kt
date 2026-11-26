package ani.dantotsu.media

import ani.dantotsu.connections.anilist.AniMangaSearchResults
import ani.dantotsu.connections.anilist.Anilist
import ani.dantotsu.media.SearchFilterPolicy.Facet
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchFilterPolicyTest {

    @After
    fun resetSession() {
        SearchFilterPolicy.resetSessionMemory()
    }

    private fun filters(type: String = "ANIME") = AniMangaSearchResults(
        type = type,
        isAdult = false,
        results = mutableListOf(),
        hasNextPage = false,
    )

    @Test
    fun `facet visibility hides season on manga and hides unsupported facets in rescue mode`() {
        assertEquals(
            listOf(Facet.FORMAT, Facet.STATUS, Facet.YEAR, Facet.SEASON),
            SearchFilterPolicy.visibleCommonFacets(anime = true, rescue = false),
        )
        assertEquals(
            listOf(Facet.FORMAT, Facet.STATUS, Facet.YEAR),
            SearchFilterPolicy.visibleCommonFacets(anime = false, rescue = false),
        )
        assertEquals(
            listOf(Facet.FORMAT, Facet.STATUS, Facet.YEAR),
            SearchFilterPolicy.visibleCommonFacets(anime = true, rescue = true),
        )

        assertEquals(
            listOf(Facet.SOURCE, Facet.COUNTRY, Facet.LIST),
            SearchFilterPolicy.visibleMoreFacets(rescue = false, signedIn = true),
        )
        assertEquals(
            listOf(Facet.SOURCE, Facet.COUNTRY),
            SearchFilterPolicy.visibleMoreFacets(rescue = false, signedIn = false),
        )
        assertTrue(SearchFilterPolicy.visibleMoreFacets(rescue = true, signedIn = true).isEmpty())

        assertEquals(
            listOf(Facet.SORT, Facet.GENRES_TAGS, Facet.ADULT),
            SearchFilterPolicy.visiblePrimaryFacets(hasGenreData = true, adultEnabled = true),
        )
        assertEquals(
            listOf(Facet.SORT),
            SearchFilterPolicy.visiblePrimaryFacets(hasGenreData = false, adultEnabled = false),
        )
    }

    @Test
    fun `sourceGroups leads with common values and preserves every source without duplicates`() {
        val animePartition = SearchFilterPolicy.sourceGroups(Anilist.source, anime = true)
        assertEquals(
            listOf("MANGA", "LIGHT NOVEL", "ORIGINAL", "WEB NOVEL", "VISUAL NOVEL", "VIDEO GAME"),
            animePartition.common,
        )
        assertEquals("OTHER", animePartition.more.last())
        val combinedAnime = animePartition.common + animePartition.more
        assertEquals(Anilist.source.size, combinedAnime.size)
        assertEquals(Anilist.source.toSet(), combinedAnime.toSet())

        val mangaPartition = SearchFilterPolicy.sourceGroups(Anilist.source, anime = false)
        assertEquals(
            listOf("ORIGINAL", "LIGHT NOVEL", "WEB NOVEL", "NOVEL", "VISUAL NOVEL", "VIDEO GAME"),
            mangaPartition.common,
        )
        assertEquals("OTHER", mangaPartition.more.last())
        val combinedManga = mangaPartition.common + mangaPartition.more
        assertEquals(Anilist.source.size, combinedManga.size)
        assertEquals(Anilist.source.toSet(), combinedManga.toSet())

        assertFalse(SearchFilterPolicy.isInMoreSources(Anilist.source, anime = true, stored = "LIGHT_NOVEL"))
        assertTrue(SearchFilterPolicy.isInMoreSources(Anilist.source, anime = true, stored = "PICTURE_BOOK"))
    }

    @Test
    fun `yearsDescending has no duplicates and decades index every decade in descending order`() {
        val years = SearchFilterPolicy.yearsDescending(maxYear = 2027, minYear = 1970)
        assertEquals(58, years.size)
        assertEquals(years.distinct(), years)
        assertEquals(2027, years.first())
        assertEquals(1970, years.last())

        val decades = SearchFilterPolicy.decades(years)
        assertEquals(listOf(2020, 2010, 2000, 1990, 1980, 1970), decades.map { it.decade })
        decades.forEach { jump ->
            assertEquals(jump.decade, SearchFilterPolicy.decadeOf(years[jump.firstIndex]))
            if (jump.firstIndex > 0) {
                assertTrue(SearchFilterPolicy.decadeOf(years[jump.firstIndex - 1]) > jump.decade)
            }
        }
    }

    @Test
    fun `shouldExpandMore auto-expands when any rare filter is active or remembered`() {
        val empty = filters()
        assertFalse(SearchFilterPolicy.shouldExpandMore(empty, userExpanded = false, rescue = false, signedIn = true))
        assertTrue(SearchFilterPolicy.shouldExpandMore(empty, userExpanded = true, rescue = false, signedIn = true))

        val withCountry = filters().apply { countryOfOrigin = "JP" }
        assertTrue(SearchFilterPolicy.shouldExpandMore(withCountry, userExpanded = false, rescue = false, signedIn = false))
        assertFalse(SearchFilterPolicy.shouldExpandMore(withCountry, userExpanded = true, rescue = true, signedIn = true))

        val withList = filters().apply { onList = true }
        assertTrue(SearchFilterPolicy.shouldExpandMore(withList, userExpanded = false, rescue = false, signedIn = true))
        assertFalse(SearchFilterPolicy.shouldExpandMore(withList, userExpanded = false, rescue = false, signedIn = false))
    }

    @Test
    fun `applicableActiveCount ignores non-applicable fields and default sort`() {
        val manga = filters(type = "MANGA").apply {
            sort = SearchFilterPolicy.DEFAULT_SORT
            season = "SPRING" // not applicable on manga
            seasonYear = 2024 // anime field, not manga
            startYear = 2023
            format = "MANGA"
            genres = mutableListOf("Action")
            tags = mutableListOf("Isekai")
            source = "ORIGINAL"
            onList = true
            isAdult = true
        }
        // Manga + AniList + signed in + adult enabled: startYear + format + Action + Isekai + source + onList + isAdult = 7
        assertEquals(
            7,
            SearchFilterPolicy.applicableActiveCount(manga, anime = false, rescue = false, signedIn = true, adultEnabled = true),
        )
        // Manga + Rescue + signed out + adult disabled: startYear + format + Action = 3
        assertEquals(
            3,
            SearchFilterPolicy.applicableActiveCount(manga, anime = false, rescue = true, signedIn = false, adultEnabled = false),
        )
    }

    @Test
    fun `FilterSnapshot treats null and empty lists and default sort as unchanged`() {
        val f = filters().apply {
            sort = null
            genres = null
            format = "TV"
        }
        val initial = SearchFilterPolicy.FilterSnapshot.from(f)

        f.sort = SearchFilterPolicy.DEFAULT_SORT
        f.genres = mutableListOf()
        assertFalse(initial.differsFrom(f))

        f.seasonYear = 2024
        assertTrue(initial.differsFrom(f))

        f.seasonYear = null
        assertFalse(initial.differsFrom(f))
    }

    @Test
    fun `findMatchingKey and pretty handle underscores and short acronyms`() {
        assertEquals("TV SHORT", SearchFilterPolicy.findMatchingKey(Anilist.animeFormats, "TV_SHORT"))
        assertEquals("TV SHORT", SearchFilterPolicy.findMatchingKey(Anilist.animeFormats, "TV SHORT"))
        assertNull(SearchFilterPolicy.findMatchingKey(Anilist.mangaFormats, "OVA"))

        assertEquals("TV", SearchFilterPolicy.pretty("TV"))
        assertEquals("OVA", SearchFilterPolicy.pretty("OVA"))
        assertEquals("TV short", SearchFilterPolicy.pretty("TV_SHORT"))
        assertEquals("Not yet released", SearchFilterPolicy.pretty("NOT_YET_RELEASED"))
        assertEquals("Light novel", SearchFilterPolicy.pretty("LIGHT_NOVEL"))
    }

    @Test
    fun `year grid shows only the chosen decade`() {
        val years = SearchFilterPolicy.yearsDescending(2027)
        assertEquals((2027 downTo 2020).toList(), SearchFilterPolicy.yearsInDecade(years, 2020))
        assertEquals((2019 downTo 2010).toList(), SearchFilterPolicy.yearsInDecade(years, 2010))
        assertEquals(years, SearchFilterPolicy.yearsInDecade(years, null))
    }
}
