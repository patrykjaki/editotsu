package ani.dantotsu.download.manga

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Handoff-A regressions: Manga Clear All is ONE linearizable cutoff transaction
 * ([MangaDownloadCancellation.cancelAllMangaTransaction] over
 * [MangaDownloadOwnership]), never snapshot/clear/re-add.
 *
 * One invocation-time cutoff C drives the queued sweep, the live-owner
 * cancel+join, and the job-record sweep. Proves, with the REAL ownership seam,
 * the production queue type (`ConcurrentLinkedQueue`), and latch-driven
 * (never sleep/timing) sequencing:
 * 1. fresh B (id > C) offered while the transaction runs survives and then
 *    processes normally;
 * 2. old A (id <= C) offered after the initial sweep fails closed at the poll
 *    filter and at begin (never publishes, never commits);
 * 3. a live old owner (id <= C) is cancelled AND joined (termination barrier);
 * 4. a fresh B parked on the cancel-all gate proceeds after release, strictly
 *    after the sweep (event-ordered proof it waited).
 *
 * Cancellation only: no test here touches files or metadata stores.
 */
class MangaClearAllTransactionTest {

    private fun task(title: String, chapter: String, attemptId: Long) =
        MangaDownloaderService.DownloadTask(
            title = title,
            chapter = chapter,
            scanlator = "",
            imageData = emptyList(),
            simultaneousDownloads = 1,
        ).apply { this.attemptId = attemptId }

    private fun jobSweepFor(
        jobMap: MutableMap<MangaDownloadKey, Pair<Long, Job>>,
    ): suspend (Long) -> Unit = { cutoff ->
        val it = jobMap.iterator()
        while (it.hasNext()) {
            val (_, entry) = it.next()
            if (entry.first <= cutoff) {
                entry.second.cancel()
                it.remove()
            }
        }
    }

    /** Production-faithful clear runner: reserve → transact → finish in `finally`. */
    private suspend fun runClearAll(
        ownership: MangaDownloadOwnership,
        queue: MutableCollection<MangaDownloaderService.DownloadTask>,
        cutoff: Long,
        removeJobRecords: suspend (Long) -> Unit,
    ) {
        try {
            MangaDownloadCancellation.cancelAllMangaTransaction(
                ownership = ownership,
                queue = queue,
                cutoff = cutoff,
                removeJobRecords = removeJobRecords,
            )
        } finally {
            ownership.finishMangaScope(null)
        }
    }

    /**
     * 1. Fresh attempts survive the transaction however they race it: queued
     * before the sweep (kept by the per-element predicate) and offered while the
     * transaction is paused mid-flight (never visited by the sweep). Survivors
     * then publish and commit normally.
     */
    @Test
    fun clearAll_freshOffersSurviveWheneverTheyRace() = runBlocking {
        val ownership = MangaDownloadOwnership()
        val queue = ConcurrentLinkedQueue<MangaDownloaderService.DownloadTask>()
        val jobMap = mutableMapOf<MangaDownloadKey, Pair<Long, Job>>()

        val aId = ownership.issueAttemptId() // old
        queue.add(task("T", "1", aId))

        // Invocation-time protocol, exactly like the service runner: gate install
        // strictly before the cutoff read.
        val cutoff = ownership.reserveMangaScope(null)
        // Fresh attempt issued after the cutoff but queued BEFORE the sweep runs.
        val preId = ownership.issueAttemptId()
        assertTrue(preId > cutoff)
        queue.add(task("T", "2", preId))

        val enteredRecords = CompletableDeferred<Unit>()
        val releaseTx = CompletableDeferred<Unit>()
        val tx = launch {
            runClearAll(
                ownership = ownership,
                queue = queue,
                cutoff = cutoff,
                removeJobRecords = { c ->
                    jobSweepFor(jobMap)(c)
                    enteredRecords.complete(Unit)
                    releaseTx.await() // pause mid-transaction, sweep already done
                },
            )
        }
        enteredRecords.await()
        assertEquals(
            "old swept, pre-queued fresh kept",
            listOf("2"),
            queue.map { it.chapter },
        )

        // Fresh B offered concurrently while the transaction is still running.
        val bId = ownership.issueAttemptId()
        assertTrue(bId > cutoff)
        queue.add(task("T", "3", bId))

        releaseTx.complete(Unit)
        tx.join()

        assertEquals(
            "fresh offers survive however they race the sweep",
            listOf("2", "3"),
            queue.map { it.chapter },
        )
        // Survivors process normally afterwards.
        for ((chapter, id) in listOf("2" to preId, "3" to bId)) {
            val key = MangaDownloadKey.fromTask("T", chapter)
            val job = Job()
            val gen = ownership.begin(key, job, id)
            assertTrue(ownership.commitIfOwner(key, gen, job) {})
            job.cancel()
        }
    }

    /**
     * 2. Old A (id <= C) whose queue offer was delayed until after the initial
     * sweep: the poll filter drops it, and the hardened begin() rejects it
     * before publication — it can never own or commit.
     */
    @Test
    fun clearAll_delayedOldOffer_failsClosedAtPollAndBegin() = runBlocking {
        val ownership = MangaDownloadOwnership()
        val queue = ConcurrentLinkedQueue<MangaDownloaderService.DownloadTask>()

        val aId = ownership.issueAttemptId() // old id issued BEFORE the cutoff…
        val cutoff = ownership.reserveMangaScope(null)
        assertTrue(aId <= cutoff)
        runClearAll(ownership, queue, cutoff, removeJobRecords = {})

        // …but A's queue offer was delayed until after the sweep.
        val lateTask = task("T", "9", aId)
        queue.add(lateTask)

        // Poll filter (production poll-lambda logic): stale attempts are dropped
        // instead of launching doomed jobs for them.
        assertTrue(ownership.isClearAllStale(lateTask.attemptId))
        var next = queue.poll()
        while (next != null && ownership.isClearAllStale(next.attemptId)) {
            next = queue.poll()
        }
        assertEquals("delayed old offer drained by the poll filter", null, next)
        assertTrue(queue.isEmpty())

        // Backstop: even if polled, begin rejects before publication…
        val key = MangaDownloadKey.fromTask("T", "9")
        val staleJob = Job()
        try {
            ownership.begin(key, staleJob, aId)
            fail("delayed old begin after Clear All must throw")
        } catch (_: CancellationException) {
            // Expected: rejected before publication.
        }
        assertTrue(staleJob.isCancelled)
        // …so commit is impossible (never an owner).
        assertFalse(ownership.commitIfOwner(key, -1L, staleJob) {})
    }

    /**
     * 3. A live old owner (id <= C) is cancelled AND joined by the transaction:
     * when the transaction returns, the owner's job has fully terminated
     * (termination barrier, not fire-and-forget cancel), and it can no longer
     * commit.
     */
    @Test
    fun clearAll_liveOldOwner_cancelledAndJoined() = runBlocking {
        val ownership = MangaDownloadOwnership()
        val queue = ConcurrentLinkedQueue<MangaDownloaderService.DownloadTask>()
        val jobMap = mutableMapOf<MangaDownloadKey, Pair<Long, Job>>()

        val key = MangaDownloadKey.fromTask("T", "1")
        val aId = ownership.issueAttemptId()
        // Parked owner job: completes ONLY via cancellation (release never comes).
        val parkedA = CompletableDeferred<Unit>()
        val jobA = launch {
            parkedA.complete(Unit)
            CompletableDeferred<Unit>().await()
        }
        val genA = ownership.begin(key, jobA, aId)
        parkedA.await()
        jobMap[key] = aId to jobA

        val cutoff = ownership.reserveMangaScope(null)
        assertTrue(aId <= cutoff)
        runClearAll(ownership, queue, cutoff, jobSweepFor(jobMap))

        assertTrue("live old owner cancelled", jobA.isCancelled)
        assertTrue(
            "transaction joined the owner (termination barrier)",
            jobA.isCompleted,
        )
        assertTrue("owner job record retired", jobMap.isEmpty())
        assertFalse(ownership.commitIfOwner(key, genA, jobA) {})
    }

    /**
     * 4. A fresh B that reaches begin() while the transaction holds the
     * cancel-all gate parks there (provably, via UNDISPATCHED start + not
     * completed), then publishes strictly after the sweep once released, and
     * commits normally.
     */
    @Test
    fun clearAll_freshBeginWaitsForGate_thenProcessesNormally() = runBlocking {
        val ownership = MangaDownloadOwnership()
        val queue = ConcurrentLinkedQueue<MangaDownloaderService.DownloadTask>()
        val events = mutableListOf<String>()

        val cutoff = ownership.reserveMangaScope(null)
        val allowTx = CompletableDeferred<Unit>()
        val tx = launch {
            allowTx.await()
            runClearAll(
                ownership = ownership,
                queue = queue,
                cutoff = cutoff,
                removeJobRecords = {
                    events.add("sweep-done")
                },
            )
            events.add("gate-released")
        }

        // Fresh B issued after the cutoff. UNDISPATCHED start runs it
        // synchronously up to its first suspension — the cancel-all gate park —
        // so non-completion afterwards deterministically proves it is waiting.
        val bId = ownership.issueAttemptId()
        assertTrue(bId > cutoff)
        val key = MangaDownloadKey.fromTask("T", "5")
        val jobB = Job()
        val beginB = async(start = CoroutineStart.UNDISPATCHED) {
            val gen = ownership.begin(key, jobB, bId)
            events.add("b-published")
            gen
        }
        assertFalse("fresh B must wait on the cancel-all gate", beginB.isCompleted)

        allowTx.complete(Unit)
        tx.join()
        val genB = beginB.await()
        assertEquals(
            "B publishes strictly after the sweep and release",
            listOf("sweep-done", "gate-released", "b-published"),
            events,
        )
        assertTrue(ownership.commitIfOwner(key, genB, jobB) {})
        jobB.cancel()
    }
}
