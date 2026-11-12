package ani.dantotsu.home

import org.junit.Assert.assertEquals
import org.junit.Test

class OfflinePagesTest {
    @Test
    fun `named offline page wins over the default tab`() {
        assertEquals(OfflinePages.MANGA, OfflinePages.startPage("ani.dantotsu.download.manga.OfflineMangaFragment", 0))
        assertEquals(OfflinePages.ANIME, OfflinePages.startPage("ani.dantotsu.download.anime.OfflineAnimeFragment", 2))
        assertEquals(OfflinePages.LOCAL, OfflinePages.startPage("ani.dantotsu.offline.LocalFragment", 0))
    }

    @Test
    fun `unknown or missing name uses the default tab clamped to the pages`() {
        assertEquals(1, OfflinePages.startPage(null, 1))
        assertEquals(2, OfflinePages.startPage("ani.dantotsu.home.HomeFragment", 7))
        assertEquals(0, OfflinePages.startPage(null, -1))
    }
}
