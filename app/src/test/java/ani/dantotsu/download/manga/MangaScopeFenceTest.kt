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
import org.junit.Assert.fail
import org.junit.Test

/**
 * Blocker-B regressions: every manga destructive surface fenced through the
 * ownership-aware scope orchestration ([MangaDownloadCancellation.cancelDeleteScopeTransaction]
 * over [MangaDownloadOwnership] title/all-manga gates + destruction cutoffs).
 *
 * Proves, with the REAL ownership seam and fake stores:
 * - whole-title delete cannot be followed by stale COMPLETE resurrection (even for
 *   keys that had no live owner during the delete);
 * - purge-all cannot erase live/fresh manga writes and then allow recommit (fresh B
 *   waits, then commits consistently; stale A cannot);
 * - stale ownerless attempts are rejected at begin() once a scope destruction
 *   cutoff is recorded (never published, never recreating directories).
 */
class MangaScopeFenceTest {

    private class FakeTitleStore {
        val metadata = mutableSetOf<MangaDownloadKey>()
        val files = mutableSetOf<MangaDownloadKey>()
        val deleteCalls = mutableListOf<String>() // titlePath or "*"

        fun deleteTitle(titlePath: String): MangaChapterDeleteResult {
            deleteCalls.add(titlePath)
            metadata.removeAll { it.titlePath == titlePath }
            files.removeAll { it.titlePath == titlePath }
            return MangaChapterDeleteResult.Deleted
        }

        fun deleteAll(): MangaChapterDeleteResult {
            deleteCalls.add("*")
            metadata.clear()
            files.clear()
            return MangaChapterDeleteResult.Deleted
        }
    }

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
    ): suspend (String?, Long) -> Unit = { scope, cutoff ->
        val it = jobMap.iterator()
        while (it.hasNext()) {
            val (key, entry) = it.next()
            if ((scope == null || key.titlePath == scope) && entry.first <= cutoff) {
                entry.second.cancel()
                it.remove()
            }
        }
    }

    /** Production-faithful scope runner: reserve → transact → finish in `finally`. */
    private suspend fun runScopeDelete(
        ownership: MangaDownloadOwnership,
        queue: MutableList<MangaDownloaderService.DownloadTask>,
        titlePath: String?,
        jobMap: MutableMap<MangaDownloadKey, Pair<Long, Job>>,
        delete: suspend () -> MangaChapterDeleteResult,
    ): MangaChapterDeleteResult {
        val cutoff = ownership.reserveMangaScope(titlePath)
        try {
            return MangaDownloadCancellation.cancelDeleteScopeTransaction(
                ownership = ownership,
                queue = queue,
                titlePath = titlePath,
                removeJobRecords = jobSweepFor(jobMap),
                cutoff = cutoff,
                deleteScope = delete,
            )
        } finally {
            ownership.finishMangaScope(titlePath)
        }
    }

    /**
     * Whole-title delete with a live owner on one chapter and a completed
     * (ownerless) chapter: neither the live owner's stale snapshot nor any later
     * attempt at/below the cutoff can recommit COMPLETE; other titles untouched.
     */
    @Test
    fun titleDelete_noStaleResurrection_ownerlessKeysCovered() = runBlocking {
        val ownership = MangaDownloadOwnership()
        val store = FakeTitleStore()
        val queue = mutableListOf<MangaDownloaderService.DownloadTask>()
        val jobMap = mutableMapOf<MangaDownloadKey, Pair<Long, Job>>()

        val liveKey = MangaDownloadKey("TitleA", "1")
        val idleKey = MangaDownloadKey("TitleA", "2") // completed earlier, no owner
        val otherKey = MangaDownloadKey("TitleB", "1")
        listOf(liveKey, idleKey, otherKey).forEach {
            store.metadata.add(it)
            store.files.add(it)
        }

        val aId = ownership.issueAttemptId()
        val releaseA = CompletableDeferred<Unit>()
        val parkedA = CompletableDeferred<Unit>()
        val jobA = launch { parkedA.complete(Unit); releaseA.await() }
        val genA = ownership.begin(liveKey, jobA, aId)
        parkedA.await()
        jobMap[liveKey] = aId to jobA

        releaseA.complete(Unit)
        val outcome = runScopeDelete(
            ownership = ownership,
            queue = queue,
            titlePath = "TitleA",
            jobMap = jobMap,
            delete = { store.deleteTitle("TitleA") },
        )

        assertEquals(MangaChapterDeleteResult.Deleted, outcome)
        assertTrue(store.metadata.none { it.titlePath == "TitleA" })
        assertTrue(store.files.none { it.titlePath == "TitleA" })
        // Other titles untouched.
        assertTrue(store.metadata.contains(otherKey))
        assertTrue(store.files.contains(otherKey))
        // Live owner's stale COMPLETE cannot resurrect…
        assertFalse(ownership.commitIfOwner(liveKey, genA, jobA) {})
        // …nor can an ownerless key's delayed stale attempt: no per-key cutoff was
        // ever published for it, but the hardened begin() rejects it against the
        // title destruction cutoff before publication (never an owner, so commit
        // is impossible and no directory can be recreated).
        val staleJob = Job()
        try {
            ownership.begin(idleKey, staleJob, aId)
            fail("delayed stale begin after title destruction must throw")
        } catch (_: kotlinx.coroutines.CancellationException) {
            // Expected: rejected before publication.
        }
        assertTrue(staleJob.isCancelled)
        assertTrue(store.metadata.none { it.titlePath == "TitleA" })
    }

    /**
     * Purge-all with live owners on two titles plus a fresh retry issued after the
     * cutoff: the fresh retry waits on the all-manga gate, then publishes and
     * commits consistently (its own fresh files); stale owners cannot recommit.
     */
    @Test
    fun purgeAll_freshRetryWaitsThenCommits_staleCannot() = runBlocking {
        val ownership = MangaDownloadOwnership()
        val store = FakeTitleStore()
        val queue = mutableListOf<MangaDownloaderService.DownloadTask>()
        val jobMap = mutableMapOf<MangaDownloadKey, Pair<Long, Job>>()

        val k1 = MangaDownloadKey("T1", "1")
        val k2 = MangaDownloadKey("T2", "1")
        store.files.add(k1)
        store.files.add(k2)

        val aId = ownership.issueAttemptId()
        val releaseA = CompletableDeferred<Unit>()
        val parkedA = CompletableDeferred<Unit>()
        val jobA = launch { parkedA.complete(Unit); releaseA.await() }
        val genA = ownership.begin(k1, jobA, aId)
        parkedA.await()
        jobMap[k1] = aId to jobA

        // Announce purge, but pause before the transaction runs.
        val cutoff = ownership.reserveMangaScope(null)
        val allowPurge = CompletableDeferred<Unit>()
        val purgeOutcome = CompletableDeferred<MangaChapterDeleteResult>()
        val purgeRunner = launch {
            allowPurge.await()
            try {
                purgeOutcome.complete(
                    MangaDownloadCancellation.cancelDeleteScopeTransaction(
                        ownership = ownership,
                        queue = queue,
                        titlePath = null,
                        removeJobRecords = jobSweepFor(jobMap),
                        cutoff = cutoff,
                        deleteScope = { store.deleteAll() },
                    )
                )
            } finally {
                ownership.finishMangaScope(null)
            }
        }

        // Fresh B (id > cutoff) attempts publication mid-purge: must wait.
        repeat(2) { yield() }
        val bId = ownership.issueAttemptId()
        assertTrue(bId > cutoff)
        val jobB = Job()
        val beginB = async { ownership.begin(k2, jobB, bId) }
        repeat(5) { yield() }
        assertFalse("fresh B must wait on the all-manga gate", beginB.isCompleted)

        releaseA.complete(Unit)
        allowPurge.complete(Unit)
        assertEquals(MangaChapterDeleteResult.Deleted, purgeOutcome.await())
        purgeRunner.join()
        assertTrue(store.files.isEmpty())

        // B publishes after the purge, writes fresh files, commits consistently.
        val genB = beginB.await()
        store.files.add(k2)
        store.metadata.add(k2)
        assertTrue(ownership.commitIfOwner(k2, genB, jobB) {})
        // Stale A cannot recommit.
        assertFalse(ownership.commitIfOwner(k1, genA, jobA) {})
        jobB.cancel()
    }

    /**
     * begin() hardening: a stale OWNERLESS attempt (no live owner, so no per-key
     * cutoff was ever published) that slipped the scope-gate check before the gate
     * was installed is rejected at begin — before publication — once the title
     * scope destruction cutoff is recorded. A fresh attempt is unaffected.
     */
    @Test
    fun beginHardening_rejectsStaleOwnerlessAfterTitleDestroy() = runBlocking {
        val ownership = MangaDownloadOwnership()
        val k = MangaDownloadKey("TitleH", "7")
        val aId = ownership.issueAttemptId() // old, issued before the delete
        val cutoff = ownership.reserveMangaScope("TitleH")
        assertTrue(aId <= cutoff)
        try {
            ownership.noteMangaScopeDestroyed("TitleH", cutoff)
        } finally {
            ownership.finishMangaScope("TitleH")
        }

        // Delayed old begin: must fail closed before publishing ownership.
        val staleJob = Job()
        try {
            ownership.begin(k, staleJob, aId)
            fail("stale ownerless begin after title destruction must throw")
        } catch (_: kotlinx.coroutines.CancellationException) {
            // Expected: rejected before publication.
        }
        assertTrue("stale job must be cancelled", staleJob.isCancelled)
        // Never published: a fresh attempt publishes and commits normally.
        val freshJob = Job()
        val freshGen = ownership.begin(k, freshJob, ownership.issueAttemptId())
        assertTrue(ownership.commitIfOwner(k, freshGen, freshJob) {})
        freshJob.cancel()
    }

    /**
     * begin() hardening, whole-type scope: same rejection via the purge
     * destruction cutoff, for a key in an unrelated title.
     */
    @Test
    fun beginHardening_rejectsStaleOwnerlessAfterPurgeDestroy() = runBlocking {
        val ownership = MangaDownloadOwnership()
        val k = MangaDownloadKey("OtherTitle", "3")
        val aId = ownership.issueAttemptId() // old
        val cutoff = ownership.reserveMangaScope(null)
        assertTrue(aId <= cutoff)
        try {
            ownership.noteMangaScopeDestroyed(null, cutoff)
        } finally {
            ownership.finishMangaScope(null)
        }

        val staleJob = Job()
        try {
            ownership.begin(k, staleJob, aId)
            fail("stale ownerless begin after purge destruction must throw")
        } catch (_: kotlinx.coroutines.CancellationException) {
            // Expected.
        }
        assertTrue(staleJob.isCancelled)
        val freshJob = Job()
        val freshGen = ownership.begin(k, freshJob, ownership.issueAttemptId())
        assertTrue(ownership.commitIfOwner(k, freshGen, freshJob) {})
        freshJob.cancel()
    }

    /**
     * Scoped queue sweep inside the transaction removes old same-title queued tasks
     * but preserves fresh ones and other titles.
     */
    @Test
    fun scopeSweep_retiresOldQueued_preservesFreshAndOthers() = runBlocking {
        val ownership = MangaDownloadOwnership()
        val store = FakeTitleStore()
        val queue = mutableListOf<MangaDownloaderService.DownloadTask>()
        val jobMap = mutableMapOf<MangaDownloadKey, Pair<Long, Job>>()

        val aId = ownership.issueAttemptId() // old
        val oldTask = task("TitleA", "1", aId)
        val otherTask = task("TitleB", "1", aId) // same id, other title
        queue.add(oldTask)
        queue.add(otherTask)
        val cutoff = ownership.reserveMangaScope("TitleA")
        val bId = ownership.issueAttemptId() // fresh
        val freshTask = task("TitleA", "9", bId)
        queue.add(freshTask)
        try {
            MangaDownloadCancellation.cancelDeleteScopeTransaction(
                ownership = ownership,
                queue = queue,
                titlePath = "TitleA",
                removeJobRecords = jobSweepFor(jobMap),
                cutoff = cutoff,
                deleteScope = { store.deleteTitle("TitleA") },
            )
        } finally {
            ownership.finishMangaScope("TitleA")
        }

        assertEquals(
            "old same-title retired; fresh + other-title survive",
            listOf(otherTask, freshTask),
            queue,
        )
    }
}
