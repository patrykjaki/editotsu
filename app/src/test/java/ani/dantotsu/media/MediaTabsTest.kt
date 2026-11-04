package ani.dantotsu.media

import org.junit.Assert.assertEquals
import org.junit.Test

class MediaTabsTest {
    @Test
    fun animeTabsInBarOrder() {
        assertEquals(listOf(0, 1, 3, 2), MediaTabs.windows(anime = true, comments = true))
        assertEquals(listOf(0, 1, 3), MediaTabs.windows(anime = true, comments = false))
        assertEquals(listOf(0, 1, 2), MediaTabs.windows(anime = false, comments = true))
    }

    @Test
    fun savedValuesKeepTheirMeaning() {
        val anime = MediaTabs.windows(anime = true, comments = true)
        assertEquals(3, MediaTabs.pageOf(MediaTabs.COMMENTS, anime))
        assertEquals(2, MediaTabs.pageOf(MediaTabs.THEMES, anime))
        assertEquals(MediaTabs.COMMENTS, MediaTabs.windowAt(3, anime))
        // Comments saved, then turned off: Watch.
        assertEquals(1, MediaTabs.pageOf(MediaTabs.COMMENTS, MediaTabs.windows(anime = true, comments = false)))
        // Themes saved on an anime, opened on a manga-like list: Read.
        assertEquals(1, MediaTabs.pageOf(MediaTabs.THEMES, MediaTabs.windows(anime = false, comments = false)))
    }
}
