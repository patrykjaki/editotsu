package ani.dantotsu.download.manga

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MangaDownloadOwnershipTest {

    private fun key(title: String, chapter: String) = MangaDownloadKey(title, chapter)

    @Test
    fun normalFlow_ownerCanCommit() {
        val ownership = MangaDownloadOwnership()
        val k = key("T", "1")
        val job = Job()
        val generation = runBlocking { ownership.begin(k, job, 1L) }
        assertTrue(runBlocking { ownership.commitIfOwner(k, generation, job) { } })
    }

    @Test
    fun staleAfterInvalidate_cannotCommit() {
        val ownership = MangaDownloadOwnership()
        val k = key("T", "1")
        val job = Job()
        val generation = runBlocking { ownership.begin(k, job, 1L) }
        runBlocking { ownership.invalidate(k) }
        assertFalse(runBlocking { ownership.commitIfOwner(k, generation, job) { } })
        assertFalse(runBlocking { ownership.isOwner(k, generation, job) })
    }

    @Test
    fun retryBumpsGeneration_invalidatingOldJob() {
        val ownership = MangaDownloadOwnership()
        val k = key("T", "1")
        val oldJob = Job()
        val oldGen = runBlocking { ownership.begin(k, oldJob, 1L) }
        val newJob = Job()
        val newGen = runBlocking { ownership.begin(k, newJob, 2L) } // retry
        assertFalse(runBlocking { ownership.commitIfOwner(k, oldGen, oldJob) { } })
        assertTrue(runBlocking { ownership.commitIfOwner(k, newGen, newJob) { } })
    }

    @Test
    fun staleOwnerCannotCommit_integrationWithGate() {
        // "validated, before commit" overtaken by cancel/retry => addDownload must not run.
        val ownership = MangaDownloadOwnership()
        val k = key("T", "1")
        val job = Job()
        val generation = runBlocking { ownership.begin(k, job, 1L) }
        var committed = false
        runBlocking { ownership.invalidate(k) }
        runBlocking { ownership.commitIfOwner(k, generation, job) { committed = true } }
        assertFalse("stale job must not write COMPLETE", committed)
    }

    @Test
    fun twoIndependentSameChapterDifferentTitle_doNotInvalidate() {
        val ownership = MangaDownloadOwnership()
        val a = key("TitleA", "1")
        val b = key("TitleB", "1") // same chapter label, different title => different dir
        val jobA = Job(); val ga = runBlocking { ownership.begin(a, jobA, 1L) }
        val jobB = Job(); val gb = runBlocking { ownership.begin(b, jobB, 1L) }
        assertTrue(runBlocking { ownership.commitIfOwner(a, ga, jobA) { } })
        assertTrue(runBlocking { ownership.commitIfOwner(b, gb, jobB) { } })
    }

    @Test
    fun sameTitleSameChapterDifferentScanlator_shareKeyAndSerialize() {
        // Physical layout has NO scanlator segment, so two scanlators of one
        // title+chapter share the directory and MUST NOT run as independent owners.
        val ownership = MangaDownloadOwnership()
        val k = key("T", "1")
        val jobA = Job(); val genA = runBlocking { ownership.begin(k, jobA, 1L) } // Group A
        val jobB = Job(); val genB = runBlocking { ownership.begin(k, jobB, 2L) } // Group B
        // Only the latest attempt is owner; the earlier one is stale.
        assertFalse(runBlocking { ownership.commitIfOwner(k, genA, jobA) { } })
        assertTrue(runBlocking { ownership.commitIfOwner(k, genB, jobB) { } })
    }

    @Test
    fun cancelInvalidatesOnlyIntendedTitleSameChapterLabel() {
        val ownership = MangaDownloadOwnership()
        val a = key("TitleA", "1")
        val b = key("TitleB", "1")
        val jobA = Job(); val genA = runBlocking { ownership.begin(a, jobA, 1L) }
        val jobB = Job(); val genB = runBlocking { ownership.begin(b, jobB, 1L) }
        runBlocking { ownership.invalidate(a) } // cancel only TitleA's chapter 1
        assertFalse(runBlocking { ownership.commitIfOwner(a, genA, jobA) { } })
        assertTrue(runBlocking { ownership.commitIfOwner(b, genB, jobB) { } })
    }

    @Test
    fun retryBarrierWaitsForPreviousAttemptBeforeOwning() = runBlocking {
        val ownership = MangaDownloadOwnership()
        val k = key("T", "1")
        val released = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        // Attempt A is "in a write" (suspended) until released.
        val jobA = launch { started.complete(Unit); released.await() }
        val genA = ownership.begin(k, jobA, 1L)
        started.await()

        // Retry B begins while A is still writing.
        val jobB = launch { }
        val beginB = async { ownership.begin(k, jobB, 2L) }
        // B cannot become owner until A has fully terminated (join barrier).
        assertFalse(beginB.isCompleted)
        released.complete(Unit) // release A
        val genB = beginB.await() // B proceeds only after A terminated

        // A cannot commit (stale); B alone can.
        assertFalse(ownership.commitIfOwner(k, genA, jobA) { })
        assertTrue(ownership.commitIfOwner(k, genB, jobB) { })
    }

    @Test
    fun threeWayOverlappingTakeover_isLinearizable() = runBlocking {
        val ownership = MangaDownloadOwnership()
        val k = key("T", "1")
        val releaseA = CompletableDeferred<Unit>()
        val startedA = CompletableDeferred<Unit>()
        val jobA = launch { startedA.complete(Unit); releaseA.await() }
        val genA = ownership.begin(k, jobA, 1L)
        startedA.await()

        // B begins retry (waits for A). UNDISPATCHED makes beginB start executing
        // synchronously up to its first suspension: begin() acquires the (free) takeover
        // lock and then suspends on join(A). So by the time we continue, B holds the
        // takeover mutex for key k.
        val jobB = launch { }
        val beginB = async(start = CoroutineStart.UNDISPATCHED) { ownership.begin(k, jobB, 2L) }
        // C begins another retry. Because B holds the takeover lock, C's begin cannot
        // acquire it yet — C is queued strictly after B (proven, not scheduler-luck).
        val jobC = launch { }
        val beginC = async { ownership.begin(k, jobC, 3L) }
        assertFalse(beginC.isCompleted)

        releaseA.complete(Unit) // A ends -> B takes over -> C then takes over
        val genB = beginB.await()
        val genC = beginC.await()

        // Exactly one final owner: C. A and B are stale.
        assertFalse(ownership.commitIfOwner(k, genA, jobA) { })
        assertFalse(ownership.commitIfOwner(k, genB, jobB) { })
        assertTrue(ownership.commitIfOwner(k, genC, jobC) { })
        jobB.cancel(); jobC.cancel()
    }

    @Test
    fun cancelAfterRetryBegins_invalidatesNewOwner() = runBlocking {
        val ownership = MangaDownloadOwnership()
        val k = key("T", "1")
        val releaseA = CompletableDeferred<Unit>()
        val startedA = CompletableDeferred<Unit>()
        val jobA = launch { startedA.complete(Unit); releaseA.await() }
        ownership.begin(k, jobA, 1L)
        startedA.await()

        // Retry B begins (and becomes owner once A ends).
        val jobB = launch { }
        val beginB = async { ownership.begin(k, jobB, 2L) }
        assertFalse(beginB.isCompleted)
        releaseA.complete(Unit)
        val genB = beginB.await()

        // A cancel request that executes AFTER the retry began must still win.
        ownership.invalidate(k)
        assertFalse(
            "late cancel must prevent the retry from committing",
            ownership.commitIfOwner(k, genB, jobB) { }
        )
        jobB.cancel()
    }

    @Test
    fun cancelBeforeRetry_retryProceedsFresh() = runBlocking {
        val ownership = MangaDownloadOwnership()
        val k = key("T", "1")
        val releaseA = CompletableDeferred<Unit>()
        val startedA = CompletableDeferred<Unit>()
        val jobA = launch { startedA.complete(Unit); releaseA.await() }
        ownership.begin(k, jobA, 1L)
        startedA.await()

        // Cancel requested before the retry is queued.
        ownership.invalidate(k)
        releaseA.complete(Unit)

        // Retry then becomes a fresh owner (cancelled flag reset).
        val jobB = launch { }
        val genB = ownership.begin(k, jobB, 2L)
        assertTrue(
            "retry after cancel must own and be able to commit",
            ownership.commitIfOwner(k, genB, jobB) { }
        )
        jobB.cancel()
    }

    @Test
    fun keyMatchesPhysicalDirectory_normalizesReservedChars() {
        // The on-disk folder uses title.findValidName()/chapter.findValidName(), which
        // replaces '/' with '_' and strips reserved chars; the ownership key must match.
        assertEquals(MangaDownloadKey("Foo_Bar", "1_2"), MangaDownloadKey.fromTask("Foo/Bar", "1/2"))
    }

    @Test
    fun cancelAndJoin_terminatesCurrentJob_andPreventsStaleCommit() = runBlocking {
        val ownership = MangaDownloadOwnership()
        val k = key("T", "1")
        val releaseA = CompletableDeferred<Unit>()
        val startedA = CompletableDeferred<Unit>()
        // A is the in-flight owner, sitting in a synchronous-equivalent write barrier.
        val jobA = launch { startedA.complete(Unit); releaseA.await() }
        val genA = ownership.begin(k, jobA, 1L)
        startedA.await()

        val cancelled = async { ownership.cancelAndJoin(k, ownership.currentCutoff()) }
        assertFalse(
            "cancelAndJoin must wait for the in-flight owner to fully terminate",
            cancelled.isCompleted
        )
        releaseA.complete(Unit)
        cancelled.await() // returns only after A's write has stopped

        // A is no longer owner; its directory mutations are done. A retry may proceed.
        val jobB = launch { }
        val genB = ownership.begin(k, jobB, 2L)
        assertTrue(ownership.commitIfOwner(k, genB, jobB) { })
        assertFalse("stale owner cannot commit after cancel+join", ownership.commitIfOwner(k, genA, jobA) { })
        jobB.cancel()
    }
}
