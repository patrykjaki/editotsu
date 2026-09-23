package ani.dantotsu.download.manga

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Focused contract regression for the single-item Download Queue Manga cancel path.
 *
 * The service cancel receiver requires the EXACT physical-key identity (both chapter
 * and title); a title-less cancel silently no-ops at the
 * `chapter != null && title != null` guard. These tests prove [MangaQueueCancel] —
 * the exact helper the queue activity's Manga branch sends — always supplies both
 * fields from the task, preserving chapter identity while adding the title, and that
 * same-chapter different-title tasks stay isolated. Anime/Novel branches are
 * intentionally untouched (verified by diff: only the Manga branch changed).
 */
class MangaQueueCancelTest {

    private fun task(title: String, chapter: String) =
        MangaDownloaderService.DownloadTask(
            title = title,
            chapter = chapter,
            scanlator = "ScanX",
            imageData = emptyList(),
            simultaneousDownloads = 1,
        )

    @Test
    fun command_carriesExactChapterAndTitle() {
        val command = MangaQueueCancel.commandFor(task("TitleA", "12"))
        assertEquals("12", command.chapter)
        assertEquals("TitleA", command.title)
    }

    @Test
    fun command_preservesLegacyChapterIdentity() {
        // The old code sent item.uniqueId (= task.chapter for QueueItem.Manga) as
        // EXTRA_CHAPTER; the chapter half of the command is byte-identical, only the
        // required title half is added.
        val t = task("TitleA", "12")
        assertEquals(t.chapter, MangaQueueCancel.commandFor(t).chapter)
    }

    @Test
    fun command_isolatesSameChapterDifferentTitle() {
        val a = MangaQueueCancel.commandFor(task("TitleA", "12"))
        val b = MangaQueueCancel.commandFor(task("TitleB", "12"))
        assertNotEquals(
            "same chapter under another title must not share cancel identity",
            MangaDownloadKey.fromTask(a.title, a.chapter),
            MangaDownloadKey.fromTask(b.title, b.chapter),
        )
    }

    @Test
    fun command_matchesReceiverPhysicalKey() {
        // The key the service derives from the received extras must equal the key of
        // the queued task, so selectTasksToCancel/wireup hits exactly this chapter.
        val t = task("TitleA", "12")
        val command = MangaQueueCancel.commandFor(t)
        assertEquals(
            MangaDownloadKey.fromTask(t.title, t.chapter),
            MangaDownloadKey.fromTask(command.title, command.chapter),
        )
    }
}
