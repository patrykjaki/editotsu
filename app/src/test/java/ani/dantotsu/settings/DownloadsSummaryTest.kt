package ani.dantotsu.settings

import ani.dantotsu.media.MediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadsSummaryTest {

    @Test
    fun `manga progress key isolates same chapter across different titles`() {
        val a = DownloadsSummary.mangaProgressKey("Frieren", "Chapter 1")
        val b = DownloadsSummary.mangaProgressKey("Dandadan", "Chapter 1")
        assertEquals("Frieren_Chapter 1", a)
        assertNotEquals(a, b)
    }

    @Test
    fun `media cache key isolates same title across anime manga and novel`() {
        val anime = DownloadsSummary.mediaCacheKey(MediaType.ANIME, "Monogatari")
        val manga = DownloadsSummary.mediaCacheKey(MediaType.MANGA, "Monogatari")
        val novel = DownloadsSummary.mediaCacheKey(MediaType.NOVEL, "Monogatari")
        assertNotEquals(anime, manga)
        assertNotEquals(manga, novel)
        assertNotEquals(anime, novel)
    }

    @Test
    fun `segment label appends count only when active or queued work exists`() {
        assertEquals("Anime", DownloadsSummary.segmentLabel("Anime", 0))
        assertEquals("Anime", DownloadsSummary.segmentLabel("Anime", -1))
        assertEquals("Anime (3)", DownloadsSummary.segmentLabel("Anime", 3))
    }

    @Test
    fun `storage used percent handles zero and out-of-range values safely`() {
        assertEquals(0, DownloadsSummary.storageUsedPercent(0L, 0L))
        assertEquals(0, DownloadsSummary.storageUsedPercent(-10L, 5L))
        assertEquals(0, DownloadsSummary.storageUsedPercent(100L, 120L))
        assertEquals(100, DownloadsSummary.storageUsedPercent(100L, -5L))
        assertEquals(75, DownloadsSummary.storageUsedPercent(1000L, 250L))
    }

    @Test
    fun `hasQueuedWork reflects any active or queued category`() {
        assertFalse(DownloadsSummary.hasQueuedWork(0, 0, 0))
        assertTrue(DownloadsSummary.hasQueuedWork(1, 0, 0))
        assertTrue(DownloadsSummary.hasQueuedWork(0, 2, 0))
        assertTrue(DownloadsSummary.hasQueuedWork(0, 0, 1))
    }

    @Test
    fun `computeOverview sums sizes across all types and flags incomplete measurement`() {
        val partial = DownloadsSummary.computeOverview(
            animeTitleSet = setOf("Show A", "Show B"),
            mangaTitleSet = setOf("Manga A"),
            novelTitleSet = emptySet(),
            sizeByKey = mapOf(
                DownloadsSummary.mediaCacheKey(MediaType.ANIME, "Show A") to 500L,
                DownloadsSummary.mediaCacheKey(MediaType.MANGA, "Manga A") to 250L,
            ),
            activeCount = 1,
            queuedCount = 2,
        )
        assertEquals(750L, partial.totalDownloadedBytes)
        assertFalse(partial.sizesComplete)
        assertEquals(3, partial.totalTitles)
        assertEquals(3, partial.totalInProgress)

        val complete = DownloadsSummary.computeOverview(
            animeTitleSet = setOf("Show A"),
            mangaTitleSet = setOf("Manga A"),
            novelTitleSet = setOf("Novel A"),
            sizeByKey = mapOf(
                DownloadsSummary.mediaCacheKey(MediaType.ANIME, "Show A") to 1000L,
                DownloadsSummary.mediaCacheKey(MediaType.MANGA, "Manga A") to 2000L,
                DownloadsSummary.mediaCacheKey(MediaType.NOVEL, "Novel A") to 500L,
            ),
            activeCount = 0,
            queuedCount = 0,
        )
        assertEquals(3500L, complete.totalDownloadedBytes)
        assertTrue(complete.sizesComplete)
        assertEquals(1, complete.animeTitles)
        assertEquals(1, complete.mangaTitles)
        assertEquals(1, complete.novelTitles)
        assertEquals(0, complete.totalInProgress)
    }

    @Test
    fun `computeOverview on empty library reports zero bytes and complete`() {
        val empty = DownloadsSummary.computeOverview(
            animeTitleSet = emptySet(),
            mangaTitleSet = emptySet(),
            novelTitleSet = emptySet(),
            sizeByKey = emptyMap(),
            activeCount = 0,
            queuedCount = 0,
        )
        assertEquals(0L, empty.totalDownloadedBytes)
        assertTrue(empty.sizesComplete)
        assertEquals(0, empty.totalTitles)
    }
}
