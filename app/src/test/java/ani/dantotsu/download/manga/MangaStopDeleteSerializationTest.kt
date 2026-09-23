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
 * Deterministic, no-sleep regressions for the P1 Stop/Delete-vs-COMPLETE race.
 *
 * Every test drives the REAL production seam
 * ([MangaDownloadCancellation.cancelDeleteChapterTransaction] over
 * [MangaDownloadOwnership.cancelAndJoin]'s per-key takeover barrier) with an injected
 * synchronous fake for the destructive step (metadata + physical deletion). The fake
 * completes fully before it returns — exactly the contract production's
 * `DownloadsManager.removeMangaChapterBlocking` upholds — so ordering proven here
 * transfers to production: deletion runs after old-owner termination and before a
 * fresh same-key `begin()` can publish, all under one takeover lock.
 *
 * Physical identity in every test is the production [MangaDownloadKey]
 * (sanitized title + sanitized chapter; scanlator is NOT part of the key).
 */
class MangaStopDeleteSerializationTest {

    /** In-memory fake for chapter metadata presence + chapter-directory presence. */
    private class FakeChapterStore {
        val metadata = mutableSetOf<MangaDownloadKey>()
        val files = mutableSetOf<MangaDownloadKey>()
        val deleteCalls = mutableListOf<MangaDownloadKey>()
        val events = mutableListOf<String>()

        fun delete(key: MangaDownloadKey): MangaChapterDeleteResult {
            deleteCalls.add(key)
            metadata.remove(key)
            files.remove(key)
            events.add("delete-done:$key")
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
     * Production-faithful delete runner: synchronous invocation-time reservation
     * ([MangaDownloadOwnership.reserveDelete]) first, then the takeover-barriered
     * transaction, then reservation release in a `finally` — exactly the
     * `MangaDownloaderService` protocol. [reserved] receives the captured cutoff once
     * the reservation is installed, letting tests issue fresh attempts strictly after
     * invocation, deterministically.
     */
    private suspend fun runDelete(
        ownership: MangaDownloadOwnership,
        queue: MutableList<MangaDownloaderService.DownloadTask>,
        title: String,
        chapter: String,
        jobMap: MutableMap<MangaDownloadKey, Pair<Long, Job>>,
        delete: suspend () -> MangaChapterDeleteResult,
        reserved: CompletableDeferred<Long>? = null,
    ): MangaChapterDeleteResult {
        val key = MangaDownloadKey.fromTask(title, chapter)
        val cutoff = ownership.reserveDelete(key)
        reserved?.complete(cutoff)
        try {
            return MangaDownloadCancellation.cancelDeleteChapterTransaction(
                ownership = ownership,
                queue = queue,
                title = title,
                chapter = chapter,
                removeJobRecord = removeJobRecordFor(jobMap),
                cutoff = cutoff,
                deleteChapter = delete,
            )
        } finally {
            ownership.finishDeleteReservation(key)
        }
    }

    /**
     * Test 1 — stale COMPLETE cannot resurrect after Stop/Delete.
     *
     * Old attempt A owns the key and holds a page snapshot that WOULD validate
     * (exact expected numbered set, nonzero). A is deterministically parked after the
     * snapshot but before the COMPLETE commit; the real cancel/delete orchestration
     * then wins; only afterwards is A released to attempt its commit.
     */
    @Test
    fun staleCompleteCannotResurrectAfterStopDelete() = runBlocking {
        val ownership = MangaDownloadOwnership()
        val k = MangaDownloadKey("TitleA", "1")
        val store = FakeChapterStore()
        val queue = mutableListOf<MangaDownloaderService.DownloadTask>()
        val jobMap = mutableMapOf<MangaDownloadKey, Pair<Long, Job>>()

        val aId = ownership.issueAttemptId()
        queue.add(task("TitleA", "1", attemptId = aId))
        store.files.add(k) // partial/in-progress files present

        // A becomes the owner, then parks (snapshot taken, commit not yet attempted).
        val releaseA = CompletableDeferred<Unit>()
        val parkedA = CompletableDeferred<Unit>()
        val jobA = launch { parkedA.complete(Unit); releaseA.await() }
        val genA = ownership.begin(k, jobA, aId)
        parkedA.await()
        jobMap[k] = aId to jobA

        // The snapshot A holds would validate as complete.
        val expectedCount = 3
        val snapshot = listOf(
            PageFile("000.jpg", 10L),
            PageFile("001.jpg", 10L),
            PageFile("002.jpg", 10L),
        )
        assertTrue(
            "snapshot must be a valid complete set before delete",
            MangaDownloadValidator.isChapterComplete(snapshot, expectedCount),
        )

        // Real Stop/Delete transaction wins while A is parked.
        val outcome = runDelete(
            ownership = ownership,
            queue = queue,
            title = "TitleA",
            chapter = "1",
            jobMap = jobMap,
            delete = { store.delete(k) },
        )
        assertEquals(MangaChapterDeleteResult.Deleted, outcome)

        // Required mid-state: A invalidated + cancelled/joined, metadata + files gone.
        assertFalse(
            "stale A must no longer be owner after delete",
            ownership.isOwner(k, genA, jobA),
        )
        assertTrue("stale A job must be cancelled by the transaction", jobA.isCancelled)
        releaseA.complete(Unit)
        jobA.join()
        assertTrue("chapter metadata must be absent", store.metadata.none { it == k })
        assertTrue("chapter files must be absent", store.files.none { it == k })
        assertEquals(listOf(k), store.deleteCalls)

        // Release A: its late COMPLETE attempt must fail and leave metadata absent.
        var committed = false
        val ok = ownership.commitIfOwner(k, genA, jobA) {
            store.metadata.add(k)
            committed = true
        }
        assertFalse("stale A must not commit after delete", ok)
        assertFalse("stale A commit body must not run", committed)
        assertTrue("metadata must remain absent", store.metadata.none { it == k })
    }

    /**
     * Test 2 — old delete cannot erase fresh retry B.
     *
     * A exists; delete captures the cutoff; B (id > cutoff) attempts same-key
     * publication while the transaction — including a gated destructive step — is in
     * flight. B must block until the old deletion completes, then own, write, and
     * commit with no further old cleanup touching its files.
     */
    @Test
    fun oldDeleteCannotEraseFreshRetry() = runBlocking {
        val ownership = MangaDownloadOwnership()
        val k = MangaDownloadKey("T", "1")
        val store = FakeChapterStore()
        val queue = mutableListOf<MangaDownloaderService.DownloadTask>()
        val jobMap = mutableMapOf<MangaDownloadKey, Pair<Long, Job>>()

        val aId = ownership.issueAttemptId()
        store.files.add(k)
        val releaseA = CompletableDeferred<Unit>()
        val parkedA = CompletableDeferred<Unit>()
        val jobA = launch { parkedA.complete(Unit); releaseA.await() }
        val genA = ownership.begin(k, jobA, aId)
        parkedA.await()
        jobMap[k] = aId to jobA

        // Gated destructive step: entered under the barrier, completes only when told.
        val deleteEntered = CompletableDeferred<Unit>()
        val releaseDelete = CompletableDeferred<Unit>()
        val reservedCutoff = CompletableDeferred<Long>()
        val deleteJob = launch {
            runDelete(
                ownership = ownership,
                queue = queue,
                title = "T",
                chapter = "1",
                jobMap = jobMap,
                delete = {
                    deleteEntered.complete(Unit)
                    releaseDelete.await()
                    store.delete(k)
                },
                reserved = reservedCutoff,
            )
        }

        // Reservation installed synchronously at invocation, before B exists.
        val cutoff = reservedCutoff.await() // captured before B exists

        // Fresh retry B issued after the cutoff, attempts publication mid-transaction.
        yield()
        deleteEntered.await()
        val bId = ownership.issueAttemptId()
        assertTrue("B must be fresh (id > cutoff)", bId > cutoff)
        val jobB = Job()
        val beginB = async { ownership.begin(k, jobB, bId) }
        yield()
        assertFalse(
            "B cannot become owner until destructive cleanup completes",
            beginB.isCompleted,
        )

        // Let A terminate, then let the old deletion complete.
        releaseA.complete(Unit)
        yield()
        assertFalse(
            "B still blocked while destructive step is gated",
            beginB.isCompleted,
        )
        releaseDelete.complete(Unit)
        deleteJob.join()

        // Old deletion completed strictly before B's ownership publication.
        val genB = beginB.await()
        val deleteIdx = store.events.indexOf("delete-done:$k")
        assertTrue("old deletion must have run", deleteIdx >= 0)
        store.events.add("b-owner:$k")
        assertTrue(
            "old directory deletion must complete before B ownership publication",
            deleteIdx < store.events.indexOf("b-owner:$k"),
        )

        // B writes/owns fresh files; no old cleanup runs afterwards against them.
        val deletesAfterB = store.deleteCalls.size
        store.files.add(k)
        store.metadata.add(k)
        assertTrue("B owns fresh files", store.files.contains(k))
        yield()
        assertEquals(
            "no old cleanup may run afterward against B's files",
            deletesAfterB,
            store.deleteCalls.size,
        )
        assertTrue("B's fresh files must survive", store.files.contains(k))

        // B can commit normally; stale A cannot.
        assertTrue(ownership.commitIfOwner(k, genB, jobB) {})
        assertFalse("stale A cannot commit", ownership.commitIfOwner(k, genA, jobA) {})
        jobB.cancel()
        jobA.cancel()
    }

    /**
     * Test 3 — no-current-owner delete.
     *
     * A queued/completed chapter with no live owner is still removed (metadata +
     * files) and the transaction terminates cleanly; a later fresh attempt works.
     */
    @Test
    fun noCurrentOwnerDeleteRemovesMetadataAndFiles() = runBlocking {
        val ownership = MangaDownloadOwnership()
        val k = MangaDownloadKey("TitleN", "7")
        val store = FakeChapterStore()
        store.metadata.add(k)
        store.files.add(k)
        val queue = mutableListOf<MangaDownloaderService.DownloadTask>()
        val jobMap = mutableMapOf<MangaDownloadKey, Pair<Long, Job>>()

        val outcome = runDelete(
            ownership = ownership,
            queue = queue,
            title = "TitleN",
            chapter = "7",
            jobMap = jobMap,
            delete = { store.delete(k) },
        )
        assertEquals(MangaChapterDeleteResult.Deleted, outcome)

        assertTrue(store.metadata.none { it == k })
        assertTrue(store.files.none { it == k })
        assertEquals(listOf(k), store.deleteCalls)

        // A later fresh attempt can become owner and commit normally.
        val bId = ownership.issueAttemptId()
        val jobB = launch { }
        val genB = ownership.begin(k, jobB, bId)
        assertTrue(ownership.commitIfOwner(k, genB, jobB) {})
        jobB.cancel()
    }

    /**
     * Test 4 — physical-key isolation.
     *
     * Deleting chapter "1" of TitleA must not touch chapter "1" of TitleB, nor any
     * other chapter of TitleA.
     */
    @Test
    fun physicalKeyIsolation() = runBlocking {
        val ownership = MangaDownloadOwnership()
        val target = MangaDownloadKey("TitleA", "1")
        val otherTitle = MangaDownloadKey("TitleB", "1")
        val otherChapter = MangaDownloadKey("TitleA", "2")
        val store = FakeChapterStore()
        listOf(target, otherTitle, otherChapter).forEach {
            store.metadata.add(it)
            store.files.add(it)
        }
        val queue = mutableListOf<MangaDownloaderService.DownloadTask>()
        val jobMap = mutableMapOf<MangaDownloadKey, Pair<Long, Job>>()

        val outcome = runDelete(
            ownership = ownership,
            queue = queue,
            title = "TitleA",
            chapter = "1",
            jobMap = jobMap,
            delete = { store.delete(target) },
        )
        assertEquals(MangaChapterDeleteResult.Deleted, outcome)

        assertTrue(store.metadata.none { it == target })
        assertTrue(store.files.none { it == target })
        assertTrue("other title, same chapter must survive", store.metadata.contains(otherTitle))
        assertTrue("other title files must survive", store.files.contains(otherTitle))
        assertTrue("same title, other chapter must survive", store.metadata.contains(otherChapter))
        assertTrue("same title, other chapter files must survive", store.files.contains(otherChapter))
        assertEquals(listOf(target), store.deleteCalls)
    }

    /**
     * Test 5 — old queued/polled attempts remain safe under the delete transaction.
     *
     * An old queued attempt (id <= cutoff) is retired by the delete; a fresh retry
     * enqueued after the cutoff survives it and can publish/commit afterwards.
     */
    @Test
    fun oldQueuedAttemptsRetired_freshRetrySurvivesDelete() = runBlocking {
        val ownership = MangaDownloadOwnership()
        val k = MangaDownloadKey("TitleA", "1")
        val store = FakeChapterStore()
        val queue = mutableListOf<MangaDownloaderService.DownloadTask>()
        val jobMap = mutableMapOf<MangaDownloadKey, Pair<Long, Job>>()

        val aId = ownership.issueAttemptId()
        val aTask = task("TitleA", "1", attemptId = aId)
        queue.add(aTask)
        val releaseA = CompletableDeferred<Unit>()
        val parkedA = CompletableDeferred<Unit>()
        val jobA = launch { parkedA.complete(Unit); releaseA.await() }
        val genA = ownership.begin(k, jobA, aId)
        parkedA.await()
        jobMap[k] = aId to jobA

        val reservedCutoff = CompletableDeferred<Long>()
        val deleteJob = launch {
            runDelete(
                ownership = ownership,
                queue = queue,
                title = "TitleA",
                chapter = "1",
                jobMap = jobMap,
                delete = { store.delete(k) },
                reserved = reservedCutoff,
            )
        }
        // Fresh retry B enqueued strictly after the invocation-time reservation.
        reservedCutoff.await()
        // Fresh retry B enqueued after the cutoff, mid-transaction.
        val bId = ownership.issueAttemptId()
        val bTask = task("TitleA", "1", attemptId = bId)
        queue.add(bTask)

        releaseA.complete(Unit)
        deleteJob.join()

        // Old queued attempt retired, fresh retry survives; old owner cannot commit.
        assertEquals(listOf(bTask), queue)
        assertFalse(jobMap.containsKey(k))
        assertFalse("stale A cannot commit", ownership.commitIfOwner(k, genA, jobA) {})
        val jobB = launch { }
        val genB = ownership.begin(k, jobB, bId)
        assertTrue(ownership.commitIfOwner(k, genB, jobB) {})
        jobB.cancel()
    }
}
