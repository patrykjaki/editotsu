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

    @Test
    fun `tab index round-trips with media type and clamps out of range`() {
        assertEquals(0, ExtensionPages.tabIndexOf(MediaType.ANIME))
        assertEquals(1, ExtensionPages.tabIndexOf(MediaType.MANGA))
        assertEquals(2, ExtensionPages.tabIndexOf(MediaType.NOVEL))
        assertEquals(MediaType.ANIME, ExtensionPages.typeForTab(-1))
        assertEquals(MediaType.MANGA, ExtensionPages.typeForTab(1))
        assertEquals(MediaType.NOVEL, ExtensionPages.typeForTab(99))
    }

    @Test
    fun `status index maps installed and available within a type`() {
        assertEquals(ExtensionPages.STATUS_INSTALLED, ExtensionPages.statusIndexOf(installed = true))
        assertEquals(ExtensionPages.STATUS_AVAILABLE, ExtensionPages.statusIndexOf(installed = false))
        assertTrue(ExtensionPages.isInstalledStatusIndex(ExtensionPages.STATUS_INSTALLED))
        assertFalse(ExtensionPages.isInstalledStatusIndex(ExtensionPages.STATUS_AVAILABLE))

        for (type in ExtensionPages.TYPES) {
            val installedPage = ExtensionPages.pageOfStatus(type, ExtensionPages.STATUS_INSTALLED)
            val availablePage = ExtensionPages.pageOfStatus(type, ExtensionPages.STATUS_AVAILABLE)
            assertEquals(ExtensionPages.pageOf(type, installed = true), installedPage)
            assertEquals(ExtensionPages.pageOf(type, installed = false), availablePage)
            assertEquals(ExtensionPages.STATUS_INSTALLED, ExtensionPages.statusIndexOf(installedPage))
            assertEquals(ExtensionPages.STATUS_AVAILABLE, ExtensionPages.statusIndexOf(availablePage))
            assertTrue(ExtensionPages.belongsToType(installedPage.toLong(), type))
            assertTrue(ExtensionPages.belongsToType(availablePage.toLong(), type))
            for (other in ExtensionPages.TYPES) {
                if (other != type) {
                    assertFalse(ExtensionPages.belongsToType(installedPage.toLong(), other))
                    assertFalse(ExtensionPages.belongsToType(availablePage.toLong(), other))
                }
            }
        }
        assertFalse(ExtensionPages.belongsToType(-1L, MediaType.ANIME))
        assertFalse(ExtensionPages.belongsToType(ExtensionPages.PAGE_COUNT.toLong(), MediaType.NOVEL))
    }

    @Test
    fun `swipe axis classification protects vertical scrolling`() {
        val slop = 24f
        assertEquals(ExtensionPages.SwipeAxis.NONE, ExtensionPages.classifySwipeAxis(5f, 5f, slop))
        assertEquals(ExtensionPages.SwipeAxis.VERTICAL, ExtensionPages.classifySwipeAxis(10f, 15f, slop))
        assertEquals(ExtensionPages.SwipeAxis.VERTICAL, ExtensionPages.classifySwipeAxis(15f, 15f, slop))
        assertEquals(ExtensionPages.SwipeAxis.HORIZONTAL, ExtensionPages.classifySwipeAxis(30f, 10f, slop))
        assertEquals(ExtensionPages.SwipeAxis.NONE, ExtensionPages.classifySwipeAxis(20f, 10f, slop))
    }
}
