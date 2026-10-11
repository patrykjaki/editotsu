package ani.dantotsu.connections.anilist

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * LANE-A P0: sort-chip label resolution never throws on config skew.
 * (Resource parity 9/9 restored in strings.xml; this pins the fallback.)
 */
class SortChipLabelTest {

    private val labels9 = listOf(
        "Score", "Popular", "Trending", "New Releases", "A-Z", "Z-A",
        "Pure pain", "Favourites", "Recently Updated"
    )
    private val sorts9 = listOf(
        "SCORE_DESC", "POPULARITY_DESC", "TRENDING_DESC", "START_DATE_DESC",
        "TITLE_ENGLISH", "TITLE_ENGLISH_DESC", "SCORE",
        "FAVOURITES_DESC", "UPDATED_AT_DESC"
    )

    @Test
    fun `all nine sorts resolve including new entries`() {
        assertEquals("Favourites", sortChipLabel(labels9, sorts9, "FAVOURITES_DESC"))
        assertEquals("Recently Updated", sortChipLabel(labels9, sorts9, "UPDATED_AT_DESC"))
        assertEquals("Score", sortChipLabel(labels9, sorts9, "SCORE_DESC"))
    }

    @Test
    fun `short label array falls back to raw key instead of crashing`() {
        // The shipped P0 shape: 7 labels vs 9 sorts.
        val labels7 = labels9.take(7)
        assertEquals("FAVOURITES_DESC", sortChipLabel(labels7, sorts9, "FAVOURITES_DESC"))
        assertEquals("UPDATED_AT_DESC", sortChipLabel(labels7, sorts9, "UPDATED_AT_DESC"))
        // Matched entries still resolve.
        assertEquals("Score", sortChipLabel(labels7, sorts9, "SCORE_DESC"))
    }

    @Test
    fun `unknown sort key falls back to itself`() {
        assertEquals("NOPE_DESC", sortChipLabel(labels9, sorts9, "NOPE_DESC"))
    }
}
