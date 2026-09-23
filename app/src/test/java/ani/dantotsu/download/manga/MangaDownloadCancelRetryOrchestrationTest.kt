package ani.dantotsu.download.manga

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
 * Deterministic, no-sleep coverage of the real cancellation-orchestration seam
 * (MangaDownloadCancellation.cancelChapterTransaction, which wraps
 * MangaDownloadOwnership.cancelAndJoin with the service queue/job-bookkeeping cleanup).
 *
 * The v10 model makes the linearization boundary attempt-aware with a per-key
 * `lastCancelledCutoff` fail-closed barrier:
 *   - an attempt whose id was issued at/before a completed cancellation cutoff is "old"
 *     and is removed from the queue, its polled job terminated, and rejected at `begin()`;
 *   - a fresh attempt (id > cutoff) survives in every lifecycle state.
 * Attempt ids are taken from [MangaDownloadOwnership.issueAttemptId] (exactly as the
 * production enqueue path does); the cutoff is captured at cancellation invocation.
 */
class MangaDownloadCancelRetryOrchestrationTest {

    private fun task(title: String, chapter: String, attemptId: Long) =
        MangaDownloaderService.DownloadTask(
            title = title,
            chapter = chapter,
            scanlator = "",
            imageData = emptyList(),
            simultaneousDownloads = 1,
        ).apply { this.attemptId = attemptId }

    private fun removeJobRecordFor(
        jobMap: MutableMap<MangaDownloadKey, Pair<Long, Job>>,
    ): suspend (MangaDownloadKey, Long, Long) -> Unit = { key, _, cutoff ->
        val e = jobMap[key]
        // Cancel AND remove every same-key record whose attempt id is at or below the
        // cancellation cutoff — including an old polled-but-not-yet-owner job.
        if (e != null && e.first <= cutoff) {
            e.second.cancel()
            jobMap.remove(key)
        }
    }

    @Test
    fun cancelRemovesPreExistingQueuedAttempt_andJobRecord() = runBlocking {
        val ownership = MangaDownloadOwnership()
        val k = MangaDownloadKey("TitleA", "1")
        val aId = ownership.issueAttemptId() // 1
        val queue = mutableListOf(task("TitleA", "1", attemptId = aId))
        val jobMap = mutableMapOf<MangaDownloadKey, Pair<Long, Job>>()

        val releaseA = CompletableDeferred<Unit>()
        val startedA = CompletableDeferred<Unit>()
        val jobA = launch { startedA.complete(Unit); releaseA.await() }
        val genA = ownership.begin(k, jobA, aId)
        startedA.await()
        jobMap[k] = aId to jobA

        val cutoff = ownership.currentCutoff() // 1 — captured at cancellation invocation
        val cancelJob = launch {
            MangaDownloadCancellation.cancelChapterTransaction(
                ownership = ownership, queue = queue, title = "TitleA", chapter = "1",
                removeJobRecord = removeJobRecordFor(jobMap), cutoff = cutoff,
            )
        }
        yield()
        releaseA.complete(Unit)
        cancelJob.join()

        assertTrue(queue.isEmpty())
        assertFalse(jobMap.containsKey(k))

        // A retry (fresh id) becomes the owner.
        val bId = ownership.issueAttemptId() // 2
        val jobB = launch { }
        val genB = ownership.begin(k, jobB, bId)
        assertTrue(ownership.commitIfOwner(k, genB, jobB) {})
        assertFalse(ownership.commitIfOwner(k, genA, jobA) {})
        jobB.cancel()
    }

    @Test
    fun cancelFullyPreventsRetryPublicationUntilTransactionDone() = runBlocking {
        val ownership = MangaDownloadOwnership()
        val k = MangaDownloadKey("T", "1")
        val aId = ownership.issueAttemptId() // 1
        val queue = mutableListOf(task("T", "1", attemptId = aId))
        val jobMap = mutableMapOf<MangaDownloadKey, Pair<Long, Job>>()

        val releaseA = CompletableDeferred<Unit>()
        val startedA = CompletableDeferred<Unit>()
        val jobA = launch { startedA.complete(Unit); releaseA.await() }
        val genA = ownership.begin(k, jobA, aId)
        startedA.await()
        jobMap[k] = aId to jobA

        val cutoff = ownership.currentCutoff() // 1 — before B is issued
        val cancelJob = launch {
            MangaDownloadCancellation.cancelChapterTransaction(
                ownership = ownership, queue = queue, title = "T", chapter = "1",
                removeJobRecord = removeJobRecordFor(jobMap), cutoff = cutoff,
            )
        }

        val bId = ownership.issueAttemptId() // 2 — fresh, after cutoff
        val jobB = Job()
        val genB = async { ownership.begin(k, jobB, bId) }
        assertFalse("retry must not publish while cancel transaction is in flight", genB.isCompleted)

        releaseA.complete(Unit)
        cancelJob.join()
        val gB = genB.await()
        assertTrue(ownership.commitIfOwner(k, gB, jobB) {})
        assertFalse(ownership.commitIfOwner(k, genA, jobA) {})
    }

    @Test
    fun cancelInFlight_enqueueRetryBeforeRelease_survivesSweep() = runBlocking {
        val ownership = MangaDownloadOwnership()
        val k = MangaDownloadKey("TitleA", "1")
        val aId = ownership.issueAttemptId() // 1
        val queue = mutableListOf(task("TitleA", "1", attemptId = aId))
        val jobMap = mutableMapOf<MangaDownloadKey, Pair<Long, Job>>()

        val releaseA = CompletableDeferred<Unit>()
        val startedA = CompletableDeferred<Unit>()
        val jobA = launch { startedA.complete(Unit); releaseA.await() }
        val genA = ownership.begin(k, jobA, aId)
        startedA.await()
        jobMap[k] = aId to jobA

        val cutoff = ownership.currentCutoff() // 1 — captured before B is issued
        val cancelJob = launch {
            MangaDownloadCancellation.cancelChapterTransaction(
                ownership = ownership, queue = queue, title = "TitleA", chapter = "1",
                removeJobRecord = removeJobRecordFor(jobMap), cutoff = cutoff,
            )
        }
        yield()
        // Fresh retry B enqueued after the cutoff.
        val bId = ownership.issueAttemptId() // 2
        val bTask = task("TitleA", "1", attemptId = bId)
        queue.add(bTask)

        releaseA.complete(Unit)
        cancelJob.join()

        assertEquals(listOf(bTask), queue) // B survives the sweep
        val jobB = launch { }
        val genB = ownership.begin(k, jobB, bId)
        assertTrue(ownership.commitIfOwner(k, genB, jobB) {})
        assertFalse("stale A cannot commit", ownership.commitIfOwner(k, genA, jobA) {})
        jobB.cancel()
    }

    @Test
    fun freshBPolledBeforeCleanup_jobRecordSurvives() = runBlocking {
        val ownership = MangaDownloadOwnership()
        val k = MangaDownloadKey("TitleA", "1")
        val aId = ownership.issueAttemptId() // 1
        val queue = mutableListOf<MangaDownloaderService.DownloadTask>()
        val jobMap = mutableMapOf<MangaDownloadKey, Pair<Long, Job>>()

        val releaseA = CompletableDeferred<Unit>()
        val startedA = CompletableDeferred<Unit>()
        val jobA = launch { startedA.complete(Unit); releaseA.await() }
        val genA = ownership.begin(k, jobA, aId)
        startedA.await()
        jobMap[k] = aId to jobA

        val cutoff = ownership.currentCutoff() // 1 — captured BEFORE B is issued
        val cancelJob = launch {
            MangaDownloadCancellation.cancelChapterTransaction(
                ownership = ownership, queue = queue, title = "TitleA", chapter = "1",
                removeJobRecord = removeJobRecordFor(jobMap), cutoff = cutoff,
            )
        }

        // Fresh B polled before cleanup; its service job recorded (attempt id 2).
        val bId = ownership.issueAttemptId() // 2 — fresh, after cutoff
        val bTask = task("TitleA", "1", attemptId = bId)
        queue.add(bTask)
        queue.remove(bTask) // poll
        val jobB = Job()
        jobMap[k] = bId to jobB
        val genB = async { ownership.begin(k, jobB, bId) } // blocks behind cancel
        assertFalse("B's begin must block behind the cancel transaction", genB.isCompleted)

        releaseA.complete(Unit)
        cancelJob.join()

        // B's fresh job record (id 2 > cutoff 1) survives the older cancellation.
        assertEquals(bId, jobMap[k]?.first)
        assertEquals(jobB, jobMap[k]?.second)

        val gB = genB.await()
        assertTrue(ownership.commitIfOwner(k, gB, jobB) {})
        assertFalse(ownership.commitIfOwner(k, genA, jobA) {})
        jobB.cancel()
    }

    /**
     * Review v9 blocker 1: attempt-id issuance and queue publication are split. An attempt
     * whose id is issued BEFORE the cancellation cutoff, but whose queue.offer() is delayed
     * until after the cancellation's cleanup sweep, must still be rejected — it can never
     * appear and become owner. The per-key lastCancelledCutoff fail-closed barrier in
     * [MangaDownloadOwnership.begin] enforces this.
     */
    @Test
    fun delayedOfferAfterCancel_oldAttemptRejected() = runBlocking {
        val ownership = MangaDownloadOwnership()
        val k = MangaDownloadKey("TitleA", "1")
        val jobMap = mutableMapOf<MangaDownloadKey, Pair<Long, Job>>()
        val queue = mutableListOf<MangaDownloaderService.DownloadTask>()

        // Issue B's attempt id, but do NOT insert B into the queue yet.
        val bId = ownership.issueAttemptId() // 1
        val bTask = task("TitleA", "1", attemptId = bId)

        // Cancellation invoked now: cutoff captures B's id.
        val cutoff = ownership.currentCutoff() // 1
        val cancelJob = launch {
            MangaDownloadCancellation.cancelChapterTransaction(
                ownership = ownership, queue = queue, title = "TitleA", chapter = "1",
                removeJobRecord = removeJobRecordFor(jobMap), cutoff = cutoff,
            )
        }
        cancelJob.join()

        // Insert B AFTER the cancellation cleanup (delayed publication).
        queue.add(bTask)
        val jobB = Job()
        jobMap[k] = bId to jobB
        val result = runCatching { ownership.begin(k, jobB, bId) }
        assertTrue("stale attempt must be rejected by begin()", result.isFailure)
        assertTrue("stale attempt's job must be cancelled", jobB.isCancelled)
        assertFalse(ownership.commitIfOwner(k, 999L, jobB) {})
    }

    /**
     * Review v9 blocker 2: an attempt polled out of the queue and having its service job
     * recorded, but NOT yet at ownership.begin(), must be actively terminated by the
     * cancellation — not merely removed from bookkeeping. The cancel transaction cancels the
     * polled job (attempt id <= cutoff) and the fail-closed begin() rejects it.
     */
    @Test
    fun oldPolledBeforeBegin_cancelledByCancellation() = runBlocking {
        val ownership = MangaDownloadOwnership()
        val k = MangaDownloadKey("TitleA", "1")
        val jobMap = mutableMapOf<MangaDownloadKey, Pair<Long, Job>>()
        val queue = mutableListOf<MangaDownloaderService.DownloadTask>()

        val aId = ownership.issueAttemptId() // 1
        val jobA = launch { }
        ownership.begin(k, jobA, aId)

        // B is polled out of the queue and its service job recorded, not yet at begin().
        val bId = ownership.issueAttemptId() // 2
        val bTask = task("TitleA", "1", attemptId = bId)
        queue.add(bTask)
        queue.remove(bTask) // poll out
        val jobB = Job()
        jobMap[k] = bId to jobB

        // Cancellation invoked: cutoff includes B's id.
        val cutoff = ownership.currentCutoff() // 2
        val cancelJob = launch {
            MangaDownloadCancellation.cancelChapterTransaction(
                ownership = ownership, queue = queue, title = "TitleA", chapter = "1",
                removeJobRecord = removeJobRecordFor(jobMap), cutoff = cutoff,
            )
        }
        cancelJob.join()

        // B's polled job must have been cancelled and its record removed.
        assertTrue("old polled job must be cancelled by cancellation", jobB.isCancelled)
        assertFalse(jobMap.containsKey(k))

        // Even if B finally reaches begin(), it is rejected.
        val result = runCatching { ownership.begin(k, jobB, bId) }
        assertTrue("stale polled attempt must be rejected at begin()", result.isFailure)
        assertFalse(ownership.commitIfOwner(k, 999L, jobB) {})
    }

    /**
     * Review v10 blocker 1: production must capture the cutoff BEFORE launching the async
     * cancellation work, so a retry requested after the user pressed cancel gets id > cutoff
     * and survives. This test pins that exact ordering (capture cutoff, then issue fresh id,
     * then run the cancellation) to prevent regressing to capturing it inside the coroutine.
     */
    @Test
    fun cutoffCapturedBeforeCancelCoroutine_freshRetrySurvives() = runBlocking {
        val ownership = MangaDownloadOwnership()
        val k = MangaDownloadKey("TitleA", "1")
        val queue = mutableListOf<MangaDownloaderService.DownloadTask>()
        val jobMap = mutableMapOf<MangaDownloadKey, Pair<Long, Job>>()

        // Cancel invoked NOW: cutoff is captured synchronously at invocation (== production's
        // pre-launch capture).
        val cutoff = ownership.currentCutoff() // 0
        // THEN a fresh retry is requested -> its id is strictly greater than the cutoff.
        val bId = ownership.issueAttemptId() // 1
        val bTask = task("TitleA", "1", attemptId = bId)
        queue.add(bTask)

        val cancelJob = launch {
            MangaDownloadCancellation.cancelChapterTransaction(
                ownership = ownership, queue = queue, title = "TitleA", chapter = "1",
                removeJobRecord = removeJobRecordFor(jobMap), cutoff = cutoff,
            )
        }
        cancelJob.join()

        // B (id 1 > cutoff 0) survives the cancellation and becomes the owner.
        assertEquals(listOf(bTask), queue)
        val jobB = launch { }
        val genB = ownership.begin(k, jobB, bId)
        assertTrue(ownership.commitIfOwner(k, genB, jobB) {})
        jobB.cancel()
    }

    /**
     * Review v10 blocker 2: a fresh owner created AFTER cancellation invocation (attempt id
     * > cutoff) must survive an older cancellation transaction. Here B takes over BEFORE the
     * cancel coroutine acquires the takeover lock, then the older cancel runs with cutoff=1;
     * B (id 2) must remain owner, its job must not be cancelled, and it can commit.
     */
    @Test
    fun freshOwnerWinsTakeoverBeforeCancelCoroutine_survives() = runBlocking {
        val ownership = MangaDownloadOwnership()
        val k = MangaDownloadKey("TitleA", "1")
        val queue = mutableListOf<MangaDownloaderService.DownloadTask>()
        val jobMap = mutableMapOf<MangaDownloadKey, Pair<Long, Job>>()

        val aId = ownership.issueAttemptId() // 1
        val jobA = launch { }
        val genA = ownership.begin(k, jobA, aId)

        // Cancel invoked now: cutoff = 1 (captured before B exists).
        val cutoff = ownership.currentCutoff() // 1

        // Fresh retry B gets id 2 (> cutoff) and becomes current owner BEFORE the cancel
        // coroutine acquires the takeover lock.
        val bId = ownership.issueAttemptId() // 2
        val jobB = launch { }
        val genB = ownership.begin(k, jobB, bId)

        val cancelJob = launch {
            MangaDownloadCancellation.cancelChapterTransaction(
                ownership = ownership, queue = queue, title = "TitleA", chapter = "1",
                removeJobRecord = removeJobRecordFor(jobMap), cutoff = cutoff,
            )
        }
        cancelJob.join()

        // B (fresh) remains the owner and was not cancelled by the older cancellation.
        assertTrue("fresh owner B must remain owner", ownership.isOwner(k, genB, jobB))
        assertFalse("fresh owner B job must not be cancelled", jobB.isCancelled)
        assertTrue(ownership.commitIfOwner(k, genB, jobB) {})
        assertFalse("stale A cannot commit", ownership.commitIfOwner(k, genA, jobA) {})
    }
}
