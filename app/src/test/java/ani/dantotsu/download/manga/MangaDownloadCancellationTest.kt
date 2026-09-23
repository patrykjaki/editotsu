package ani.dantotsu.download.manga

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MangaDownloadCancellationTest {

    private fun task(title: String, chapter: String, attemptId: Long = 0L) =
        MangaDownloaderService.DownloadTask(
            title = title,
            chapter = chapter,
            scanlator = "Group",
            imageData = emptyList()
        ).apply { this.attemptId = attemptId }

    @Test
    fun selectTasksToCancel_isExactPhysicalKeyScoped() {
        val queue = mutableListOf(
            task("TitleA", "1"),
            task("TitleB", "1"), // same chapter label, different title => different dir
            task("TitleA", "2"),
        )
        val selected = MangaDownloadCancellation.selectTasksToCancel(queue, "TitleA", "1")
        assertEquals(listOf(task("TitleA", "1")), selected)
    }

    @Test
    fun cancelTitleACh1_leavesTitleBCh1AndTitleACh2() {
        // Required regression from review v5: cancelling TitleA/Ch1 must not remove a
        // different title sharing the chapter label, nor a different chapter of TitleA.
        val queue = mutableListOf(
            task("TitleA", "1"),
            task("TitleB", "1"),
            task("TitleA", "2"),
        )
        MangaDownloadCancellation.selectTasksToCancel(queue, "TitleA", "1").forEach { queue.remove(it) }
        assertEquals(
            listOf(task("TitleB", "1"), task("TitleA", "2")),
            queue
        )
    }

    @Test
    fun selectTasksToCancel_handlesEmptyQueue() {
        val queue = mutableListOf<MangaDownloaderService.DownloadTask>()
        assertEquals(emptyList<MangaDownloaderService.DownloadTask>(), MangaDownloadCancellation.selectTasksToCancel(queue, "X", "1"))
    }

    @Test
    fun isCancelledQueuedAttempt_classifiesByAttemptIdNotIterationOrder() {
        // The linearization point is a single captured cutoff against each attempt's
        // monotonic id — independent of ConcurrentLinkedQueue iteration/visibility timing.
        val targetKey = MangaDownloadKey("TitleA", "1")
        val cutoff = 2L

        // Out-of-order attempt ids, all same physical key; classification is purely
        // id-vs-cutoff, not position in the queue.
        val oldHighThenLow = task("TitleA", "1", attemptId = 1L)   // <= cutoff -> cancelled
        val newer = task("TitleA", "1", attemptId = 5L)            // > cutoff -> preserved
        val oldLow = task("TitleA", "1", attemptId = 2L)           // <= cutoff -> cancelled

        assertTrue(MangaDownloadCancellation.isCancelledQueuedAttempt(oldHighThenLow, targetKey, cutoff))
        assertFalse("fresh attempt (id > cutoff) must survive", MangaDownloadCancellation.isCancelledQueuedAttempt(newer, targetKey, cutoff))
        assertTrue(MangaDownloadCancellation.isCancelledQueuedAttempt(oldLow, targetKey, cutoff))

        // A different physical key is never selected, regardless of attempt id.
        val otherKey = task("TitleB", "1", attemptId = 1L)
        assertFalse(MangaDownloadCancellation.isCancelledQueuedAttempt(otherKey, targetKey, cutoff))
    }
}
