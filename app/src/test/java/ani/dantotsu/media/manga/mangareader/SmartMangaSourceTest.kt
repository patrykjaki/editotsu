package ani.dantotsu.media.manga.mangareader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * MAX MERGE M3 HOLD (Blocker 7): never fetch from an arbitrary source.
 */
class SmartMangaSourceTest {

    @Test
    fun `selected online dynamic parser is used`() {
        assertEquals(2, selectSmartMangaSourceIndex(2, true))
    }

    @Test
    fun `offline downloaded slot means unknown origin so no-op`() {
        assertNull(selectSmartMangaSourceIndex(5, false))
    }

    @Test
    fun `non-dynamic selected source means no-op`() {
        assertNull(selectSmartMangaSourceIndex(0, false))
    }

    @Test
    fun `negative index never resolves`() {
        assertNull(selectSmartMangaSourceIndex(-1, true))
    }
}
