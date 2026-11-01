package ani.dantotsu.settings

import ani.dantotsu.media.MediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The controls must keep addressing the original six ViewPager pages. */
class ExtensionPagesTest {
    @Test
    fun `type and status map to the original page order`() {
        assertEquals(0, ExtensionPages.pageOf(MediaType.ANIME, installed = true))
        assertEquals(1, ExtensionPages.pageOf(MediaType.ANIME, installed = false))
        assertEquals(2, ExtensionPages.pageOf(MediaType.MANGA, installed = true))
        assertEquals(3, ExtensionPages.pageOf(MediaType.MANGA, installed = false))
        assertEquals(4, ExtensionPages.pageOf(MediaType.NOVEL, installed = true))
        assertEquals(5, ExtensionPages.pageOf(MediaType.NOVEL, installed = false))
    }

    @Test
    fun `pages round-trip to type and status`() {
        for (page in 0 until ExtensionPages.PAGE_COUNT) {
            val back = ExtensionPages.pageOf(ExtensionPages.typeOf(page), ExtensionPages.isInstalled(page))
            assertEquals(page, back)
        }
        assertTrue(ExtensionPages.isInstalled(4))
        assertFalse(ExtensionPages.isInstalled(5))
        assertEquals(MediaType.NOVEL, ExtensionPages.typeOf(5))
    }

    @Test
    fun `out of range saved page falls back inside the pager`() {
        assertEquals(MediaType.ANIME, ExtensionPages.typeOf(-3))
        assertEquals(MediaType.NOVEL, ExtensionPages.typeOf(42))
        assertFalse(ExtensionPages.isInstalled(42))
    }
}
