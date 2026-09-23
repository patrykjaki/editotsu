package ani.dantotsu.download.manga

import ani.dantotsu.download.MangaChapterDeleteResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Blocker-A regressions: the invocation → takeover-acquisition gap.
 *
 * v1 captured the cutoff synchronously but installed no barrier before its async
 * coroutine acquired `takeover`, so a fresh B (id > cutoff) could publish first and
 * then be deleted underneath (spared as fresh, yet physically deleted). v2 installs
 * the delete gate synchronously in [MangaDownloadOwnership.reserveDelete] — strictly
 * before the cutoff read — and [MangaDownloadOwnership.begin] waits on it first.
 *
 * The first test uses the handoff-mandated exact order and FAILS on v1 (B publishes
 * while the delete is still paused before acquiring anything) while passing on v2
 * (B cannot publish until the delete completes). No sleeps; only barriers/latches.
 */
class MangaDeleteReservationTest {

    private class FakeStore {
        val metadata = mutableSetOf<MangaDownloadKey>()
        val files = mutableSetOf<MangaDownloadKey>()
        val deleteCalls = mutableListOf<MangaDownloadKey>()

        fun delete(key: MangaDownloadKey): MangaChapterDeleteResult {
            deleteCalls.add(key)
            metadata.remove(key)
            files.remove(key)
            return MangaChapterDeleteResult.Deleted
        }
    }

    private fun removeJobRecordFor(
        jobMap: MutableMap<MangaDownloadKey, Pair<Long, Job>>,
    ): suspend (MangaDownloadKey, Long, Long) -> Unit = { key, _, cutoff ->
        val e = jobMap[key]
        if (e != null && e.first <= cutoff) {
            e.second.cancel()
            jobMap.remove(key)
        }
    }

    /**
     * Handoff-mandated exact order:
     * A current owner → capture delete cutoff → announce/reserve delete, but pause
     * BEFORE the destructive transaction acquires/enters cleanup → issue fresh B
     * (id > cutoff) → B attempts begin/publication → assert B cannot publish → allow
     * destructive transaction to run/delete → assert delete completes → assert B then
     * publishes and survives → assert no old cleanup can touch B afterwards.
     */
    @Test
    fun freshBeginCannotPublishBeforeDeleteAcquiresBarrier() = runBlocking {
        val ownership = MangaDownloadOwnership()
        val k = MangaDownloadKey("T", "1")
        val store = FakeStore()
        val queue = mutableListOf<MangaDownloaderService.DownloadTask>()
        val jobMap = mutableMapOf<MangaDownloadKey, Pair<Long, Job>>()

        // A current owner.
        val aId = ownership.issueAttemptId()
        store.files.add(k)
        val releaseA = CompletableDeferred<Unit>()
        val parkedA = CompletableDeferred<Unit>()
        val jobA = launch { parkedA.complete(Unit); releaseA.await() }
        val genA = ownership.begin(k, jobA, aId)
        parkedA.await()
        jobMap[k] = aId to jobA

        // Capture delete cutoff + announce the reservation, but pause BEFORE the
        // destructive transaction acquires/enters cleanup.
        val cutoff = ownership.reserveDelete(k)
        val allowDelete = CompletableDeferred<Unit>()
        val deleteOutcome = CompletableDeferred<MangaChapterDeleteResult>()
        val deleteRunner = launch {
            allowDelete.await()
            try {
                deleteOutcome.complete(
                    MangaDownloadCancellation.cancelDeleteChapterTransaction(
                        ownership = ownership,
                        queue = queue,
                        title = "T",
                        chapter = "1",
                        removeJobRecord = removeJobRecordFor(jobMap),
                        cutoff = cutoff,
                        deleteChapter = { store.delete(k) },
                    )
                )
            } finally {
                ownership.finishDeleteReservation(k)
            }
        }

        // Fresh B (id > cutoff) attempts publication while the delete is announced
        // but has not acquired anything.
        repeat(2) { yield() }
        val bId = ownership.issueAttemptId()
        assertTrue("B must be fresh (id > cutoff)", bId > cutoff)
        val jobB = Job()
        val beginB = async { ownership.begin(k, jobB, bId) }
        // The takeover lock is FREE here (A only parks; nobody holds takeover), so on
        // v1 B sails through begin() and publishes. On v2 B suspends on the gate.
        repeat(5) { yield() }
        assertFalse(
            "B cannot publish before the destructive transaction even acquires its barrier",
            beginB.isCompleted,
        )

        // Allow the destructive transaction to run/delete.
        releaseA.complete(Unit)
        allowDelete.complete(Unit)
        assertEquals(MangaChapterDeleteResult.Deleted, deleteOutcome.await())
        deleteRunner.join()
        assertEquals(listOf(k), store.deleteCalls)

        // B then publishes and survives; no old cleanup can touch B afterwards.
        val genB = beginB.await()
        assertTrue("B publishes after delete completes", ownership.isOwner(k, genB, jobB))
        val callsAfter = store.deleteCalls.size
        store.files.add(k)
        repeat(3) { yield() }
        assertEquals("no old cleanup runs afterward against B", callsAfter, store.deleteCalls.size)
        assertTrue(ownership.commitIfOwner(k, genB, jobB) {})
        assertFalse("stale A cannot commit", ownership.commitIfOwner(k, genA, jobA) {})
        jobB.cancel()
    }

    /**
     * Concurrent reservations (e.g. double-tapped Delete) share one gate until the
     * LAST destructive transaction finishes: a fresh retry waits through both.
     */
    @Test
    fun concurrentReservations_shareGateUntilLastFinishes() = runBlocking {
        val ownership = MangaDownloadOwnership()
        val k = MangaDownloadKey("T", "9")

        val c1 = ownership.reserveDelete(k)
        val c2 = ownership.reserveDelete(k)
        assertTrue("second cutoff widens or equals", c2 >= c1)

        val bId = ownership.issueAttemptId()
        assertTrue(bId > c2)
        val jobB = Job()
        val beginB = async { ownership.begin(k, jobB, bId) }
        repeat(3) { yield() }
        assertFalse("B waits while any reservation is pending", beginB.isCompleted)

        ownership.finishDeleteReservation(k) // first transaction done
        repeat(3) { yield() }
        assertFalse(
            "gate must survive until the LAST reservation finishes",
            beginB.isCompleted,
        )

        ownership.finishDeleteReservation(k) // last transaction done
        val genB = beginB.await()
        assertTrue(ownership.commitIfOwner(k, genB, jobB) {})
        jobB.cancel()
    }

    /**
     * A plain (non-destructive) cancel for the same key waits behind a pending delete
     * reservation instead of retiring the delete-protected fresh retry with its own
     * smaller cutoff.
     */
    @Test
    fun plainCancelWaitsForPendingDelete() = runBlocking {
        val ownership = MangaDownloadOwnership()
        val k = MangaDownloadKey("T", "5")
        val queue = mutableListOf<MangaDownloaderService.DownloadTask>()
        val jobMap = mutableMapOf<MangaDownloadKey, Pair<Long, Job>>()

        val aId = ownership.issueAttemptId()
        val releaseA = CompletableDeferred<Unit>()
        val parkedA = CompletableDeferred<Unit>()
        val jobA = launch { parkedA.complete(Unit); releaseA.await() }
        ownership.begin(k, jobA, aId)
        parkedA.await()
        jobMap[k] = aId to jobA

        val cutoff = ownership.reserveDelete(k)

        val cancelDone = CompletableDeferred<Unit>()
        val cancelRunner = launch {
            MangaDownloadCancellation.cancelChapterTransaction(
                ownership = ownership,
                queue = queue,
                title = "T",
                chapter = "5",
                removeJobRecord = removeJobRecordFor(jobMap),
                cutoff = cutoff,
            )
            cancelDone.complete(Unit)
        }
        repeat(3) { yield() }
        assertFalse("plain cancel must wait behind the announced delete", cancelDone.isCompleted)

        // Run the delete to completion; the waiting cancel then proceeds.
        releaseA.complete(Unit)
        MangaDownloadCancellation.cancelDeleteChapterTransaction(
            ownership = ownership,
            queue = queue,
            title = "T",
            chapter = "5",
            removeJobRecord = removeJobRecordFor(jobMap),
            cutoff = cutoff,
            deleteChapter = { MangaChapterDeleteResult.Deleted },
        )
        ownership.finishDeleteReservation(k)
        cancelRunner.join()
        assertTrue("waiting cancel completes after the delete", cancelDone.isCompleted)
    }
}
