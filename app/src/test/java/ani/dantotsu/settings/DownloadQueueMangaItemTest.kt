package ani.dantotsu.settings

import ani.dantotsu.download.manga.MangaDownloaderService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class DownloadQueueMangaItemTest {

    private fun task(title: String, chapter: String) =
        MangaDownloaderService.DownloadTask(
            title = title,
            chapter = chapter,
            scanlator = "ScanX",
            imageData = emptyList(),
            simultaneousDownloads = 1,
        )

    @Test
    fun mangaQueueItem_uniqueIdMatchesServiceProgressKey() {
        val item = QueueItem.Manga(task("Title A", "Chapter 1"), isActive = true)
        assertEquals("Title A_Chapter 1", item.uniqueId)
    }

    @Test
    fun mangaQueueItem_sameChapterDifferentTitlesHaveDistinctUniqueIds() {
        val itemA = QueueItem.Manga(task("Title A", "Chapter 1"), isActive = true)
        val itemB = QueueItem.Manga(task("Title B", "Chapter 1"), isActive = false)
        assertNotEquals(itemA.uniqueId, itemB.uniqueId)
    }
}
