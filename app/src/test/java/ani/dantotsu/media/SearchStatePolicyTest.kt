package ani.dantotsu.media

import ani.dantotsu.connections.anilist.AniMangaSearchResults
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchStatePolicyTest {

    private fun emptyFilters() = AniMangaSearchResults(
        type = "ANIME",
        isAdult = false,
        results = mutableListOf(),
        hasNextPage = false,
    )

    @Test
    fun `hasActiveFilters detects every filter including sort and adult`() {
        assertFalse(SearchStatePolicy.hasActiveFilters(null))
        assertFalse(SearchStatePolicy.hasActiveFilters(emptyFilters()))
        assertFalse(SearchStatePolicy.hasActiveFilters(emptyFilters().apply { search = "Frieren" }))

        assertTrue(SearchStatePolicy.hasActiveFilters(emptyFilters().apply { sort = "SCORE_DESC" }))
        assertTrue(SearchStatePolicy.hasActiveFilters(emptyFilters().apply { isAdult = true }))
        assertTrue(SearchStatePolicy.hasActiveFilters(emptyFilters().apply { genres = mutableListOf("Action") }))
        assertTrue(SearchStatePolicy.hasActiveFilters(emptyFilters().apply { excludedTags = mutableListOf("Isekai") }))
        assertTrue(SearchStatePolicy.hasActiveFilters(emptyFilters().apply { format = "TV" }))
        assertTrue(SearchStatePolicy.hasActiveFilters(emptyFilters().apply { status = "RELEASING" }))
        assertTrue(SearchStatePolicy.hasActiveFilters(emptyFilters().apply { seasonYear = 2026 }))
        assertTrue(SearchStatePolicy.hasActiveFilters(emptyFilters().apply { startYear = 2024 }))
        assertTrue(SearchStatePolicy.hasActiveFilters(emptyFilters().apply { season = "SPRING" }))
        assertTrue(SearchStatePolicy.hasActiveFilters(emptyFilters().apply { onList = false }))
        assertTrue(SearchStatePolicy.hasActiveFilters(emptyFilters().apply { source = "MANGA" }))
        assertTrue(SearchStatePolicy.hasActiveFilters(emptyFilters().apply { countryOfOrigin = "JP" }))
    }

    @Test
    fun `clearFilters resets every filter while keeping the search query`() {
        val f = emptyFilters().apply {
            search = "Gintama"
            sort = "POPULARITY_DESC"
            genres = mutableListOf("Comedy")
            excludedGenres = mutableListOf("Horror")
            tags = mutableListOf("Parody")
            excludedTags = mutableListOf("Gore")
            format = "TV"
            status = "FINISHED"
            source = "MANGA"
            countryOfOrigin = "JP"
            season = "SPRING"
            seasonYear = 2006
            startYear = 2006
            onList = true
            isAdult = true
        }
        SearchStatePolicy.clearFilters(f)
        assertFalse(SearchStatePolicy.hasActiveFilters(f))
        assertEquals("Gintama", f.search)
        assertNull(f.sort)
        assertFalse(f.isAdult)
    }

    @Test
    fun `addHistoryEntry trims, deduplicates to newest, and evicts oldest past cap`() {
        val initial = listOf(
            SearchHistory("Oldest", 10L),
            SearchHistory("Middle", 20L),
            SearchHistory("Newest", 30L),
        )
        assertEquals(initial, SearchStatePolicy.addHistoryEntry(initial, "   ", 40L, maxSize = 3))

        // Re-searching an existing query case-insensitively bumps it to the top.
        val bumped = SearchStatePolicy.addHistoryEntry(initial, "  oldest ", 40L, maxSize = 3)
        assertEquals(listOf("oldest", "Newest", "Middle"), bumped.map { it.search })

        // Adding a fourth item with maxSize=3 keeps the new item and drops the oldest ("Middle").
        val capped = SearchStatePolicy.addHistoryEntry(bumped, "Fresh", 50L, maxSize = 3)
        assertEquals(listOf("Fresh", "oldest", "Newest"), capped.map { it.search })
    }
}
