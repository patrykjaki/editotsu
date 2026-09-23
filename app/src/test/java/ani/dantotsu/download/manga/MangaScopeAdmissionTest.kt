package ani.dantotsu.download.manga

import ani.dantotsu.download.MangaChapterDeleteResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
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
 * Handoff-A regressions: scope invocation is linearized with begin admission
 * AND service-job publication through ONE admission protocol
 * ([MangaDownloadOwnership.withScopeAdmission]).
 *
 * The old shape (`awaitMangaScope` lookup, then much later a cutoff check +
 * publish; transaction snapshotting owners and noting the cutoff in two steps)
 * let an old attempt pass the gate pre-reservation and publish after the
 * one-time owner snapshot but before the cutoff note — an unobserved old owner
 * that neither the queue sweep (already polled) nor the job-record sweep
 * (record not yet published) could see.
 *
 * Now begin's check+publish and the transaction's snapshot+note are mutually
 * exclusive, so an old attempt (id <= C) either publishes BEFORE the snapshot
 * (observed, then cancel+joined) or checks AFTER the note (rejected before
 * publication). Fresh attempts (id > C) still gate-wait and proceed.
 *
 * All sequencing is latch/UNDISPATCHED-driven, never sleeps or timing
 * thresholds. Uses the REAL ownership + cancellation seams and the production
 * queue type.
 */
class MangaScopeAdmissionTest {

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

    /**
     * Park an old attempt AFTER it passed the scope-gate lookup but BEFORE owner
     * publication: hold admission in the test, then start its begin
     * UNDISPATCHED (no gate installed yet, so it runs synchronously to the
     * admission park). Returns the pending begin plus a releaser.
     */
    private suspend fun parkAdmittedSlip(
        ownership: MangaDownloadOwnership,
        key: MangaDownloadKey,
        job: Job,
        attemptId: Long,
        holderScope: CoroutineScope,
    ): Pair<Deferred<Long>, () -> Unit> {
        val holdEntered = CompletableDeferred<Unit>()
        val releaseHold = CompletableDeferred<Unit>()
        val holder = holderScope.launch {
            ownership.withScopeAdmission {
                holdEntered.complete(Unit)
                releaseHold.await()
            }
        }
        holdEntered.await()
        // UNDISPATCHED runs synchronously to the admission park: deterministically
        // parked after the gate lookup, before publication.
        val pending = holderScope.async(start = CoroutineStart.UNDISPATCHED) {
            ownership.begin(key, job, attemptId)
        }
        assertFalse("old attempt must park at admission", pending.isCompleted)
        return pending to {
            releaseHold.complete(Unit)
        }
    }

    /**
     * A1. Old attempt admitted pre-reservation publishes immediately before the
     * Clear All snapshot (the exact interleaving that escaped snapshot-then-note).
     * The transaction must observe it, cancel+join it, and return with it dead
     * and unable to commit.
     */
    @Test
    fun clearAll_admittedSlipPublishedBeforeSnapshot_isJoined() = runBlocking {
        val ownership = MangaDownloadOwnership()
        val queue = ConcurrentLinkedQueue<MangaDownloaderService.DownloadTask>()
        val jobMap = mutableMapOf<MangaDownloadKey, Pair<Long, Job>>()

        val key = MangaDownloadKey.fromTask("T", "1")
        val aId = ownership.issueAttemptId()
        val jobA = Job()
        val (pendingA, releaseA) = parkAdmittedSlip(ownership, key, jobA, aId, this)

        // Clear All invoked while A is admitted-but-unpublished.
        val cutoff = ownership.reserveMangaScope(null)
        assertTrue(aId <= cutoff)

        // Release A first: it publishes before the transaction's snapshot section
        // (nothing else contends for admission).
        releaseA()
        val genA = pendingA.await()
        jobMap[key] = aId to jobA

        var swept = false
        try {
            MangaDownloadCancellation.cancelAllMangaTransaction(
                ownership = ownership,
                queue = queue,
                cutoff = cutoff,
                removeJobRecords = { c ->
                    jobSweepFor(jobMap)(c)
                    swept = true
                },
            )
        } finally {
            ownership.finishMangaScope(null)
        }

        assertTrue("job sweep ran", swept)
        assertTrue("slip was observed (only the transaction cancels it)", jobA.isCancelled)
        assertTrue(
            "transaction joined the slip (termination barrier, not fire-and-forget)",
            jobA.isCompleted,
        )
        assertTrue(jobMap.isEmpty())
        assertFalse(ownership.commitIfOwner(key, genA, jobA) {})
        assertFalse(ownership.isOwner(key, genA, jobA))
    }

    /**
     * Shared A2/A3 choreography: admitted slip publishes before a destructive
     * scope snapshot; the physical delete callback must not start until the old
     * attempt has terminated (event-ordered proof).
     */
    private suspend fun admittedSlipThenScopeDelete(
        ownership: MangaDownloadOwnership,
        titlePath: String?,
        scopeTitle: String,
    ): Pair<MangaChapterDeleteResult, List<String>> {
        val queue = ConcurrentLinkedQueue<MangaDownloaderService.DownloadTask>()
        val key = MangaDownloadKey.fromTask(scopeTitle, "1")
        val aId = ownership.issueAttemptId()
        val jobA = Job()
        val events = mutableListOf<String>()
        jobA.invokeOnCompletion { events.add("old-terminated") }

        val (pendingA, releaseA) = parkAdmittedSlip(
            ownership, key, jobA, aId,
            CoroutineScope(Dispatchers.Default),
        )
        val cutoff = ownership.reserveMangaScope(titlePath)
        releaseA()
        val genA = pendingA.await()

        val outcome = try {
            MangaDownloadCancellation.cancelDeleteScopeTransaction(
                ownership = ownership,
                queue = queue,
                titlePath = titlePath,
                removeJobRecords = { _, _ -> },
                cutoff = cutoff,
                deleteScope = {
                    events.add("delete-start")
                    MangaChapterDeleteResult.Deleted
                },
            )
        } finally {
            ownership.finishMangaScope(titlePath)
        }
        assertFalse(ownership.commitIfOwner(key, genA, jobA) {})
        return outcome to events
    }

    /**
     * A2. Same pre-reservation admission slip for whole-title delete: physical
     * deletion starts only after the old attempt terminated.
     */
    @Test
    fun titleDelete_admittedSlip_deleteWaitsForTermination() = runBlocking {
        val ownership = MangaDownloadOwnership()
        val (outcome, events) = admittedSlipThenScopeDelete(ownership, "TitleD", "TitleD")
        assertEquals(MangaChapterDeleteResult.Deleted, outcome)
        assertEquals(
            "physical delete starts only after the old attempt terminated",
            listOf("old-terminated", "delete-start"),
            events,
        )
    }

    /**
     * A3. Same for purge-all (whole-type scope).
     */
    @Test
    fun purgeAll_admittedSlip_deleteWaitsForTermination() = runBlocking {
        val ownership = MangaDownloadOwnership()
        val (outcome, events) = admittedSlipThenScopeDelete(ownership, null, "TitleP")
        assertEquals(MangaChapterDeleteResult.Deleted, outcome)
        assertEquals(
            "physical purge starts only after the old attempt terminated",
            listOf("old-terminated", "delete-start"),
            events,
        )
    }

    /**
     * A4. Service job-publication ordering: the download coroutine is ready to
     * run (LAZY: exactly "launched but not yet executing") while its
     * bookkeeping publication is delayed past the whole transaction. Neither
     * the owner snapshot nor the job-record sweep can see it — yet it still
     * cannot be missed: the cutoff note rejects it at begin, so it can never
     * own or commit.
     */
    @Test
    fun clearAll_launchedButUnrecordedOldJob_cannotBeMissed() = runBlocking {
        val ownership = MangaDownloadOwnership()
        val queue = ConcurrentLinkedQueue<MangaDownloaderService.DownloadTask>()
        val jobMap = mutableMapOf<MangaDownloadKey, Pair<Long, Job>>()

        val key = MangaDownloadKey.fromTask("T", "4")
        val aId = ownership.issueAttemptId()
        val cutoff = ownership.reserveMangaScope(null)
        assertTrue(aId <= cutoff)

        // The "service coroutine": created (ready to run) but recording delayed.
        var rejected = false
        var committed = false
        val svc = launch(start = CoroutineStart.LAZY) {
            val myJob = coroutineContext[Job]!!
            try {
                val gen = ownership.begin(key, myJob, aId)
                committed = ownership.commitIfOwner(key, gen, myJob) {}
            } catch (_: CancellationException) {
                rejected = true
            }
        }

        // Whole transaction runs while the coroutine is unstarted AND unrecorded.
        try {
            MangaDownloadCancellation.cancelAllMangaTransaction(
                ownership = ownership,
                queue = queue,
                cutoff = cutoff,
                removeJobRecords = jobSweepFor(jobMap),
            )
        } finally {
            ownership.finishMangaScope(null)
        }

        // The service parent now publishes the (stale) bookkeeping record late,
        // exactly like launchDownloadTask recording after launch — and only then
        // lets the coroutine execute.
        jobMap[key] = aId to svc
        svc.start()
        svc.join()

        assertTrue("cutoff note rejects what both sweeps could not see", rejected)
        assertFalse("missed job can never commit", committed)
        assertTrue(svc.isCancelled)
    }

    /**
     * A5. Fresh attempt (id > C) begun during a destructive title scope waits on
     * the gate, then publishes strictly after the physical delete and commits
     * normally.
     */
    @Test
    fun titleDelete_freshBeginWaitsOutDelete_thenCommits() = runBlocking {
        val ownership = MangaDownloadOwnership()
        val queue = ConcurrentLinkedQueue<MangaDownloaderService.DownloadTask>()
        val events = mutableListOf<String>()

        val cutoff = ownership.reserveMangaScope("TitleF")
        val bId = ownership.issueAttemptId()
        assertTrue(bId > cutoff)
        val key = MangaDownloadKey.fromTask("TitleF", "5")
        val jobB = Job()
        // UNDISPATCHED parks deterministically on the installed gate.
        val beginB = async(start = CoroutineStart.UNDISPATCHED) {
            val gen = ownership.begin(key, jobB, bId)
            events.add("b-published")
            gen
        }
        assertFalse("fresh B must wait on the title gate", beginB.isCompleted)

        val outcome = try {
            MangaDownloadCancellation.cancelDeleteScopeTransaction(
                ownership = ownership,
                queue = queue,
                titlePath = "TitleF",
                removeJobRecords = { _, _ -> },
                cutoff = cutoff,
                deleteScope = {
                    events.add("delete-start")
                    MangaChapterDeleteResult.Deleted
                },
            )
        } finally {
            ownership.finishMangaScope("TitleF")
        }

        assertEquals(MangaChapterDeleteResult.Deleted, outcome)
        val genB = beginB.await()
        assertEquals(
            "B publishes strictly after the physical delete",
            listOf("delete-start", "b-published"),
            events,
        )
        assertTrue(ownership.commitIfOwner(key, genB, jobB) {})
        jobB.cancel()
    }
}
