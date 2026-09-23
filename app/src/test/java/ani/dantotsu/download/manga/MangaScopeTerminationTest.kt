package ani.dantotsu.download.manga

import ani.dantotsu.download.MangaChapterDeleteResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Handoff-A regressions: scope termination waits for Cancelling-but-incomplete
 * owners, not just active non-cancelled ones.
 *
 * The old snapshot (`isActive && !cancelled`) excluded a published owner whose
 * plain chapter cancel had begun: `job.cancel()` sets `isActive == false`
 * while the coroutine still executes synchronous/non-cancellable work
 * (`isCompleted == false`), and the earlier cancel correctly waits in `join()`.
 * A concurrent scope transaction then missed it (and could also miss its
 * not-yet-published service record) and started physical deletion early.
 *
 * The corrected snapshot ([MangaDownloadOwnership.unfinishedMangaKeys]:
 * `currentJob?.isCompleted == false`, read off immutable records) includes it,
 * so every scope transaction cancel+joins it before returning / deleting.
 *
 * Choreography per test (no sleeps, no spins):
 * - publish old owner A parked in NonCancellable work;
 * - synchronously cancel its job (deterministic Cancelling state);
 * - start a REAL plain cancelAndJoin (marks the record, parks in join);
 * - run the scope transaction concurrently (it parks in its own join);
 * - release the work; both joins return; assert the delete/return waited.
 *
 * Progress never depends on scheduling: whichever transaction joins first,
 * both join the same job before the physical step, so the event order is
 * identical under every interleave.
 */
class MangaScopeTerminationTest {

    private data class CancellingOwner(
        val key: MangaDownloadKey,
        val attemptId: Long,
        val generation: Long,
        val job: Job,
        val releaseWork: CompletableDeferred<Unit>,
        val plainCanceller: Job,
    )

    /**
     * Publish old owner A executing NonCancellable work, drive it into the
     * Cancelling-but-incomplete state, and start a real plain cancel that parks
     * in its termination join.
     */
    private suspend fun publishCancellingOwner(
        ownership: MangaDownloadOwnership,
        scope: kotlinx.coroutines.CoroutineScope,
        title: String,
        chapter: String,
    ): CancellingOwner {
        val key = MangaDownloadKey.fromTask(title, chapter)
        val aId = ownership.issueAttemptId()
        val enteredWork = CompletableDeferred<Unit>()
        val releaseWork = CompletableDeferred<Unit>()
        val jobA = scope.launch {
            enteredWork.complete(Unit)
            withContext(NonCancellable) { releaseWork.await() }
        }
        val genA = ownership.begin(key, jobA, aId)
        enteredWork.await()
        // Plain chapter cancel begins (real transaction): marks the record
        // cancelled and parks in the termination join behind releaseWork.
        val plainCanceller = scope.launch {
            ownership.cancelAndJoin(key, ownership.currentCutoff()) { _, _ -> }
        }
        // Deterministic Cancelling state (synchronous transition); the join
        // stays parked because the work ignores cancellation.
        jobA.cancel()
        assertFalse("precondition: job cancelling", jobA.isActive)
        assertFalse("precondition: job incomplete", jobA.isCompleted)
        return CancellingOwner(key, aId, genA, jobA, releaseWork, plainCanceller)
    }

    /**
     * A1. Title delete with a cancelling-but-incomplete old owner and no service
     * record: the physical delete callback must not start until A completes.
     */
    @Test
    fun titleDelete_cancellingOwner_deleteWaitsForCompletion() = runBlocking {
        val ownership = MangaDownloadOwnership()
        val queue = ConcurrentLinkedQueue<MangaDownloaderService.DownloadTask>()
        val owner = publishCancellingOwner(ownership, this, "TitleC", "1")
        val events = mutableListOf<String>()
        owner.job.invokeOnCompletion { events.add("old-terminated") }

        val cutoff = ownership.reserveMangaScope("TitleC")
        var result: MangaChapterDeleteResult? = null
        val scopeRunner = launch {
            try {
                result = MangaDownloadCancellation.cancelDeleteScopeTransaction(
                    ownership = ownership,
                    queue = queue,
                    titlePath = "TitleC",
                    removeJobRecords = { _, _ -> },
                    cutoff = cutoff,
                    deleteScope = {
                        events.add("delete-start")
                        MangaChapterDeleteResult.Deleted
                    },
                )
            } finally {
                ownership.finishMangaScope("TitleC")
            }
        }

        owner.releaseWork.complete(Unit)
        owner.plainCanceller.join()
        scopeRunner.join()
        assertEquals(MangaChapterDeleteResult.Deleted, result)
        assertEquals(
            "physical delete starts only after the cancelling owner completed",
            listOf("old-terminated", "delete-start"),
            events,
        )
        assertFalse(ownership.commitIfOwner(owner.key, owner.generation, owner.job) {})
    }

    /**
     * A2. Purge-all with the same cancelling-owner state.
     */
    @Test
    fun purgeAll_cancellingOwner_deleteWaitsForCompletion() = runBlocking {
        val ownership = MangaDownloadOwnership()
        val queue = ConcurrentLinkedQueue<MangaDownloaderService.DownloadTask>()
        val owner = publishCancellingOwner(ownership, this, "TitleP", "1")
        val events = mutableListOf<String>()
        owner.job.invokeOnCompletion { events.add("old-terminated") }

        val cutoff = ownership.reserveMangaScope(null)
        var result: MangaChapterDeleteResult? = null
        val scopeRunner = launch {
            try {
                result = MangaDownloadCancellation.cancelDeleteScopeTransaction(
                    ownership = ownership,
                    queue = queue,
                    titlePath = null,
                    removeJobRecords = { _, _ -> },
                    cutoff = cutoff,
                    deleteScope = {
                        events.add("delete-start")
                        MangaChapterDeleteResult.Deleted
                    },
                )
            } finally {
                ownership.finishMangaScope(null)
            }
        }

        owner.releaseWork.complete(Unit)
        owner.plainCanceller.join()
        scopeRunner.join()
        assertEquals(MangaChapterDeleteResult.Deleted, result)
        assertEquals(
            "physical purge starts only after the cancelling owner completed",
            listOf("old-terminated", "delete-start"),
            events,
        )
        assertFalse(ownership.commitIfOwner(owner.key, owner.generation, owner.job) {})
    }

    /**
     * A3. Clear All must not return while the old published owner remains
     * incomplete.
     */
    @Test
    fun clearAll_cancellingOwner_transactionWaitsForCompletion() = runBlocking {
        val ownership = MangaDownloadOwnership()
        val queue = ConcurrentLinkedQueue<MangaDownloaderService.DownloadTask>()
        val owner = publishCancellingOwner(ownership, this, "TitleC", "1")

        val cutoff = ownership.reserveMangaScope(null)
        val txRunner = launch {
            try {
                MangaDownloadCancellation.cancelAllMangaTransaction(
                    ownership = ownership,
                    queue = queue,
                    cutoff = cutoff,
                    removeJobRecords = {},
                )
            } finally {
                ownership.finishMangaScope(null)
            }
        }

        owner.releaseWork.complete(Unit)
        owner.plainCanceller.join()
        txRunner.join()
        assertTrue(
            "Clear All returned only after the old owner terminated",
            owner.job.isCompleted,
        )
        assertFalse(ownership.commitIfOwner(owner.key, owner.generation, owner.job) {})
    }

    /**
     * A4. Maintenance fence: hasLiveMangaOwner stays true while the owner is
     * cancelling but incomplete, and becomes false only after completion.
     */
    @Test
    fun fence_cancellingOwner_countsAsLiveUntilCompleted() = runBlocking {
        val ownership = MangaDownloadOwnership()
        val owner = publishCancellingOwner(ownership, this, "TitleF", "1")

        assertTrue(
            "cancelling-but-incomplete owner fences maintenance deletion",
            ownership.hasLiveMangaOwner("TitleF"),
        )
        owner.releaseWork.complete(Unit)
        owner.job.join()
        owner.plainCanceller.join()
        assertFalse(
            "completed owner no longer fences maintenance deletion",
            ownership.hasLiveMangaOwner("TitleF"),
        )
    }
}
