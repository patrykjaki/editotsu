package ani.dantotsu.download.manga

import ani.dantotsu.download.MangaChapterDeleteResult
import ani.dantotsu.download.decideMangaChapterDeleteOutcome
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Blocker-D regressions: the destructive delete result contract.
 *
 * Production deletes physical-first and maps the outcome through
 * [decideMangaChapterDeleteOutcome]: an existing required directory that could not
 * be deleted is [MangaChapterDeleteResult.Failed] with metadata preserved — and the
 * service then reports failure instead of purging UI as success. Also pins the
 * media-info scope proof: title-level files (`media.json`, `cover.jpg`,
 * `banner.jpg`) can never validate a chapter, so the detached `saveMediaInfo`
 * writer (title directory scope) cannot resurrect or corrupt chapter state.
 */
class MangaDeleteOutcomeTest {

    // decideMangaChapterDeleteOutcome matrix.

    @Test
    fun outcome_nothingPresent_isAlreadyAbsent() {
        assertEquals(
            MangaChapterDeleteResult.AlreadyAbsent,
            decideMangaChapterDeleteOutcome(
                metadataRemoved = false,
                dirExisted = false,
                dirDeleted = false,
            ),
        )
    }

    @Test
    fun outcome_metadataOnlyRemoved_isDeleted() {
        assertEquals(
            MangaChapterDeleteResult.Deleted,
            decideMangaChapterDeleteOutcome(
                metadataRemoved = true,
                dirExisted = false,
                dirDeleted = false,
            ),
        )
    }

    @Test
    fun outcome_dirDeleted_isDeleted() {
        assertEquals(
            MangaChapterDeleteResult.Deleted,
            decideMangaChapterDeleteOutcome(
                metadataRemoved = false,
                dirExisted = true,
                dirDeleted = true,
            ),
        )
        assertEquals(
            MangaChapterDeleteResult.Deleted,
            decideMangaChapterDeleteOutcome(
                metadataRemoved = true,
                dirExisted = true,
                dirDeleted = true,
            ),
        )
    }

    @Test
    fun outcome_existingDirNotDeleted_isFailed() {
        // Even when metadata was removed, an existing directory that survived is a
        // failure — production therefore deletes physical-first so this state keeps
        // valid metadata instead of discarding it.
        assertEquals(
            MangaChapterDeleteResult.Failed("chapter directory still present after delete"),
            decideMangaChapterDeleteOutcome(
                metadataRemoved = false,
                dirExisted = true,
                dirDeleted = false,
            ),
        )
        assertEquals(
            MangaChapterDeleteResult.Failed("chapter directory still present after delete"),
            decideMangaChapterDeleteOutcome(
                metadataRemoved = true,
                dirExisted = true,
                dirDeleted = false,
            ),
        )
    }

    // Transaction-level failure behavior (physical-first fake: failure keeps metadata).

    private class FakeStore(var failPhysical: Boolean) {
        val metadata = mutableSetOf<MangaDownloadKey>()
        val files = mutableSetOf<MangaDownloadKey>()
        val deleteCalls = mutableListOf<MangaDownloadKey>()

        fun delete(key: MangaDownloadKey): MangaChapterDeleteResult {
            deleteCalls.add(key)
            // Physical-first: on physical failure nothing is removed.
            if (failPhysical) {
                return MangaChapterDeleteResult.Failed("injected physical failure")
            }
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

    private suspend fun runDelete(
        ownership: MangaDownloadOwnership,
        queue: MutableList<MangaDownloaderService.DownloadTask>,
        title: String,
        chapter: String,
        jobMap: MutableMap<MangaDownloadKey, Pair<Long, Job>>,
        delete: suspend () -> MangaChapterDeleteResult,
    ): MangaChapterDeleteResult {
        val key = MangaDownloadKey.fromTask(title, chapter)
        val cutoff = ownership.reserveDelete(key)
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
     * Failed physical delete: the transaction reports Failed, valid metadata AND
     * files are preserved (physical-first), the stale owner still cannot commit, and
     * a fresh retry issued afterwards can publish, write, and commit normally.
     */
    @Test
    fun failedDelete_preservesStateAndFreesRetry() = runBlocking {
        val ownership = MangaDownloadOwnership()
        val k = MangaDownloadKey("TitleF", "2")
        val store = FakeStore(failPhysical = true)
        store.metadata.add(k)
        store.files.add(k)
        val queue = mutableListOf<MangaDownloaderService.DownloadTask>()
        val jobMap = mutableMapOf<MangaDownloadKey, Pair<Long, Job>>()

        val aId = ownership.issueAttemptId()
        val releaseA = CompletableDeferred<Unit>()
        val parkedA = CompletableDeferred<Unit>()
        val jobA = launch { parkedA.complete(Unit); releaseA.await() }
        val genA = ownership.begin(k, jobA, aId)
        parkedA.await()
        jobMap[k] = aId to jobA

        releaseA.complete(Unit)
        val outcome = runDelete(
            ownership = ownership,
            queue = queue,
            title = "TitleF",
            chapter = "2",
            jobMap = jobMap,
            delete = { store.delete(k) },
        )

        assertEquals(
            MangaChapterDeleteResult.Failed("injected physical failure"),
            outcome,
        )
        assertTrue("valid metadata preserved on physical failure", store.metadata.contains(k))
        assertTrue("files preserved on physical failure", store.files.contains(k))
        assertFalse("stale A cannot commit after failed delete", ownership.commitIfOwner(k, genA, jobA) {})

        // Fresh retry after failure: publishes, writes, commits (recovery path).
        val bId = ownership.issueAttemptId()
        val jobB = launch { }
        val genB = ownership.begin(k, jobB, bId)
        store.failPhysical = false
        store.files.add(k)
        repeat(2) { yield() }
        assertEquals(1, store.deleteCalls.size)
        assertTrue(ownership.commitIfOwner(k, genB, jobB) {})
        jobB.cancel()
    }

    /**
     * Successful delete reports Deleted and removes both; AlreadyAbsent (nothing was
     * ever there) is an acceptable terminal outcome, not a failure.
     */
    @Test
    fun successAndAbsent_outcomes() = runBlocking {
        val ownership = MangaDownloadOwnership()
        val queue = mutableListOf<MangaDownloaderService.DownloadTask>()
        val jobMap = mutableMapOf<MangaDownloadKey, Pair<Long, Job>>()

        val k = MangaDownloadKey("TitleO", "1")
        val store = FakeStore(failPhysical = false)
        store.metadata.add(k)
        store.files.add(k)
        assertEquals(
            MangaChapterDeleteResult.Deleted,
            runDelete(ownership, queue, "TitleO", "1", jobMap) { store.delete(k) },
        )
        assertTrue(store.metadata.isEmpty())
        assertTrue(store.files.isEmpty())

        val absent = FakeStore(failPhysical = false)
        val emptyKey = MangaDownloadKey("TitleO", "9")
        val absentOutcome = runDelete(ownership, queue, "TitleO", "9", jobMap) {
            if (absent.metadata.contains(emptyKey) || absent.files.contains(emptyKey)) {
                MangaChapterDeleteResult.Deleted
            } else {
                MangaChapterDeleteResult.AlreadyAbsent
            }
        }
        assertEquals(MangaChapterDeleteResult.AlreadyAbsent, absentOutcome)
    }

    // Media-info scope proof pins (option B): title-level files never validate.

    @Test
    fun titleLevelFilesAlone_neverComplete() {
        val titleFiles = listOf(
            PageFile("media.json", 100L),
            PageFile("cover.jpg", 100L),
            PageFile("banner.jpg", 100L),
        )
        assertFalse(MangaDownloadValidator.isChapterComplete(titleFiles, 3))
        assertFalse(MangaDownloadValidator.isChapterComplete(titleFiles, 1))
    }

    @Test
    fun titleLevelFilesMixedWithPages_areIgnored() {
        val mixed = listOf(
            PageFile("000.jpg", 10L),
            PageFile("001.jpg", 10L),
            PageFile("media.json", 100L),
            PageFile("cover.jpg", 100L),
            PageFile("banner.jpg", 100L),
        )
        // Exact page set still validates; stray title-level files are ignored…
        assertTrue(MangaDownloadValidator.isChapterComplete(mixed, 2))
        // …and an incomplete page set is still rejected despite their presence.
        assertFalse(MangaDownloadValidator.isChapterComplete(mixed, 3))
    }
}
