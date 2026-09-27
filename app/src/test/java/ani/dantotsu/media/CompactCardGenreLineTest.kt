package ani.dantotsu.media

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * M1 HOLD closure (Blocker 1): spoiler-marked tags must never reach the
 * compact-card fallback line. No preference UI involved — exclusion is
 * unconditional; M3 may add configurable masking later.
 */
class CompactCardGenreLineTest {

    @Test
    fun `genres win over tags`() {
        assertEquals(
            "Action • Adventure",
            compactCardGenreLine(
                genres = listOf("Action", "Adventure"),
                tags = listOf("Time Travel", "War"),
                tagsIsSpoiler = listOf(false, false)
            )
        )
    }

    @Test
    fun `non-spoiler tags fill fallback`() {
        assertEquals(
            "Island • Sea • Adventure",
            compactCardGenreLine(
                genres = emptyList(),
                tags = listOf("Island", "Sea", "Adventure"),
                tagsIsSpoiler = listOf(false, false, false)
            )
        )
    }

    @Test
    fun `spoiler tags are excluded`() {
        assertEquals(
            "Island • Sea",
            compactCardGenreLine(
                genres = emptyList(),
                tags = listOf("Island", "Death", "Sea"),
                tagsIsSpoiler = listOf(false, true, false)
            )
        )
    }

    @Test
    fun `mixed list preserves order among survivors and caps at 3`() {
        assertEquals(
            "A • C • E",
            compactCardGenreLine(
                genres = emptyList(),
                tags = listOf("A", "B", "C", "D", "E", "F"),
                tagsIsSpoiler = listOf(false, true, false, true, false, false)
            )
        )
    }

    @Test
    fun `all-spoiler list renders empty`() {
        assertEquals(
            "",
            compactCardGenreLine(
                genres = emptyList(),
                tags = listOf("Death", "Betrayal"),
                tagsIsSpoiler = listOf(true, true)
            )
        )
    }

    @Test
    fun `empty genres and empty tags render empty so recycled bind clears stale text`() {
        assertEquals(
            "",
            compactCardGenreLine(
                genres = emptyList(),
                tags = emptyList(),
                tagsIsSpoiler = emptyList()
            )
        )
    }

    @Test
    fun `shorter spoiler flag list never blanks non-spoiler tags`() {
        assertEquals(
            "Island • Sea",
            compactCardGenreLine(
                genres = emptyList(),
                tags = listOf("Island", "Sea"),
                tagsIsSpoiler = emptyList()
            )
        )
    }
}
