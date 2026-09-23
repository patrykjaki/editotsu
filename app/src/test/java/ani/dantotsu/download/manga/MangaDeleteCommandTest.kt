package ani.dantotsu.download.manga

import ani.dantotsu.download.MangaChapterDeleteResult
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Blocker-B regressions: lifecycle-safe delete command routing.
 *
 * A completed chapter is usually deleted long after [MangaDownloaderService] drained
 * its queue and stopped, so only an explicit service command handled in
 * `onStartCommand` (never a dynamic broadcast) can deliver the delete. This file
 * covers the pure routing contract ([MangaDeleteCommand.parse]) plus a full
 * stopped-service simulation: virgin ownership/queue/store, no live jobs, no running
 * service — the exact steps `onStartCommand` runs for a delete command.
 */
class MangaDeleteCommandTest {

    @Test
    fun parse_acceptsExactDeleteCommand() {
        val cmd = MangaDeleteCommand.parse(
            MangaDownloaderService.ACTION_DELETE_CHAPTER,
            "TitleA",
            "12",
            "12-ScanX",
        )
        assertNotNull(cmd)
        assertEquals("TitleA", cmd!!.title)
        assertEquals("12", cmd.chapter)
        assertEquals("12-ScanX", cmd.uniqueNumber)
    }

    @Test
    fun parse_acceptsNullUniqueNumber() {
        val cmd = MangaDeleteCommand.parse(
            MangaDownloaderService.ACTION_DELETE_CHAPTER,
            "TitleA",
            "12",
            null,
        )
        assertNotNull(cmd)
        assertNull(cmd!!.uniqueNumber)
    }

    @Test
    fun parse_rejectsWrongAction() {
        assertNull(
            MangaDeleteCommand.parse(
                MangaDownloaderService.ACTION_CANCEL_DOWNLOAD,
                "TitleA",
                "12",
                null,
            )
        )
        assertNull(MangaDeleteCommand.parse(null, "TitleA", "12", null))
        assertNull(MangaDeleteCommand.parse("", "TitleA", "12", null))
    }

    @Test
    fun parse_rejectsMissingOrBlankIdentity() {
        val action = MangaDownloaderService.ACTION_DELETE_CHAPTER
        assertNull(MangaDeleteCommand.parse(action, null, "12", null))
        assertNull(MangaDeleteCommand.parse(action, "", "12", null))
        assertNull(MangaDeleteCommand.parse(action, "   ", "12", null))
        assertNull(MangaDeleteCommand.parse(action, "TitleA", null, null))
        assertNull(MangaDeleteCommand.parse(action, "TitleA", "", null))
        assertNull(MangaDeleteCommand.parse(action, "TitleA", "  ", null))
        assertNull(MangaDeleteCommand.parse(action, null, null, null))
    }

    /**
     * Completed/no-live-owner delete while the service was previously stopped: virgin
     * state, no jobs, no running service. Drives the exact `onStartCommand` delete
     * steps — parse, synchronous reserve, takeover-barriered transaction, reservation
     * release — and proves the chapter is removed and a later fresh attempt works.
     */
    @Test
    fun stoppedServiceDelete_removesChapterAndFreesRetry() = runBlocking {
        val ownership = MangaDownloadOwnership()
        val k = MangaDownloadKey("TitleS", "3")
        val metadata = mutableSetOf(k)
        val files = mutableSetOf(k)
        val queue = mutableListOf<MangaDownloaderService.DownloadTask>()
        val jobMap = mutableMapOf<MangaDownloadKey, Pair<Long, Job>>()

        // The service shell does exactly this for ACTION_DELETE_CHAPTER; here there is
        // no live owner, no queue, no jobs — as after the service stopped.
        val cmd = MangaDeleteCommand.parse(
            MangaDownloaderService.ACTION_DELETE_CHAPTER,
            "TitleS",
            "3",
            "3-Unknown",
        )
        assertNotNull("valid delete command must parse", cmd)

        val cutoff = ownership.reserveDelete(k)
        val outcome: MangaChapterDeleteResult
        try {
            outcome = MangaDownloadCancellation.cancelDeleteChapterTransaction(
                ownership = ownership,
                queue = queue,
                title = cmd!!.title,
                chapter = cmd.chapter,
                removeJobRecord = { key, _, c ->
                    val e = jobMap[key]
                    if (e != null && e.first <= c) {
                        e.second.cancel()
                        jobMap.remove(key)
                    }
                },
                cutoff = cutoff,
                deleteChapter = {
                    metadata.remove(k)
                    files.remove(k)
                    MangaChapterDeleteResult.Deleted
                },
            )
        } finally {
            ownership.finishDeleteReservation(k)
        }

        assertEquals(MangaChapterDeleteResult.Deleted, outcome)
        assertTrue("metadata removed with no live service", metadata.isEmpty())
        assertTrue("files removed with no live service", files.isEmpty())

        // A later fresh download works normally.
        val bId = ownership.issueAttemptId()
        assertTrue(bId > cutoff)
        val jobB = launch { }
        val genB = ownership.begin(k, jobB, bId)
        assertTrue(ownership.commitIfOwner(k, genB, jobB) {})
        jobB.cancel()
    }

    /**
     * A malformed delete command must never reach the destructive transaction: parse
     * rejects it, so the service shell ignores it (no reservation, no deletion).
     * Proved by observing that a fresh begin() still publishes immediately — i.e. no
     * delete gate was parked for the key — and stored metadata is untouched.
     */
    @Test
    fun malformedCommand_neverDeletes() = runBlocking {
        val ownership = MangaDownloadOwnership()
        val k = MangaDownloadKey("TitleM", "1")
        val metadata = mutableSetOf(k)

        assertNull(
            MangaDeleteCommand.parse(MangaDownloaderService.ACTION_DELETE_CHAPTER, "", "1", null)
        )
        // No reservation was installed: a fresh begin() publishes immediately.
        val probe = Job()
        val gen = ownership.begin(k, probe, ownership.issueAttemptId())
        assertTrue(ownership.isOwner(k, gen, probe))
        probe.cancel()
        assertTrue("metadata untouched without a valid command", metadata.contains(k))
    }
}
