package ani.dantotsu.media.anime.selector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SourceSearchOrderTest {
    @Test
    fun nextSourceSkipsLocal() {
        val names = listOf("SiteAye", "Local", "SiteBeeee", "Relayaddon")
        assertEquals(2, SourceSearchOrder.next(names, 0))
        assertEquals(3, SourceSearchOrder.next(names, 2))
        assertNull(SourceSearchOrder.next(names, 3))
    }

    @Test
    fun nameShownIsTheSourceLoaded() {
        // the dropdown list leaves out Local, so its index 2 is "Relayaddon", not "SiteBeeee"
        val names = listOf("SiteAye", "Local", "SiteBeeee", "Relayaddon")
        val next = SourceSearchOrder.next(names, 0)!!
        assertEquals("SiteBeeee", names[next])
    }
}
