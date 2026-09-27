package ani.dantotsu.download

import ani.dantotsu.download.DownloadsManager.Companion.compareName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MAX MERGE M3 (upstream 5ffa682b): file-name canonicalization and the
 * read-only tier matching contract. Destructive selection stays
 * exact-family-only (uncovered here by design — no fuzzy).
 */
class DownloadMatchingTest {

    @Test
    fun `valid name strips reserved chars`() {
        assertEquals("A_B", "A/B".findValidName())
        assertEquals("AB", "A|B".findValidName())
        assertEquals("A B", "A  B".findValidName())
    }

    @Test
    fun `valid name collapses whitespace and trims`() {
        assertEquals("One Piece", "  One   Piece  ".findValidName())
        assertEquals("", "   ".findValidName())
        assertEquals("", (null as String?).findValidName())
    }

    @Test
    fun `sanitized names match case-insensitively`() {
        assertTrue("One Piece".compareName("one piece"))
        assertTrue("One Piece".compareName("ONE  PIECE"))
        assertTrue("Title/Name".compareName("title_name"))
        // Documented boundary: slash canonicalizes to underscore, not
        // space — below the 95 fuzzy threshold, so no match.
        assertFalse("Title/Name".compareName("title name"))
    }

    @Test
    fun `distinct titles do not collapse`() {
        assertFalse("One Piece".compareName("One Punch Man"))
        assertFalse("Naruto".compareName("Boruto"))
    }

    @Test
    fun `dedup-suffixed names stay distinct`() {
        // "Title (1)" must not silently equal "Title (2)".
        assertFalse("Title (1)".compareName("Title (2)"))
    }
}
