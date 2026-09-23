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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Handoff-A regressions: title media writes (`media.json`/`cover.jpg`/
 * `banner.jpg`) are owned by the download attempt's coroutine lifetime via
 * [MangaMediaWriteGate] — never a detached `GlobalScope` writer.
 *
 * The v9 defect let a title/purge physical delete run while a detached media
 * writer was still executing, resurrecting title media under a deleted title
 * after `Deleted`. Now the write runs inline in the owner coroutine, so owner
 * cancellation and scope termination join it before any delete barrier passes.
 *
 * All sequencing is latch-driven (no sleeps) over the REAL ownership and
 * cancellation seams.
 */
class MangaMediaWriteGateTest {

    private data class Owner(val key: MangaDownloadKey, val attemptId: Long)

    /**
     * A1-A4 choreography: publish owner A, start its owned media write parked
     * in NonCancellable work. Returns the job plus release/observation latches.
     */
    private suspend fun parkedOwnedWrite(
        scope: kotlinx.coroutines.CoroutineScope,
        ownership: MangaDownloadOwnership,
        owner: Owner,
        writeThrows: Exception? = null,
    ): ParkedWrite {
        val enteredWrite = CompletableDeferred<Unit>()
        val releaseWrite = CompletableDeferred<Unit>()
        var invoked = false
        var failure: Throwable? = null
        var gateResult: Boolean? = null
        val job = scope.launch {
            val myJob = coroutineContext[Job]!!
            val gen = ownership.begin(owner.key, myJob, owner.attemptId)
            gateResult = MangaMediaWriteGate.runOwnedMediaWrite(
                ownership = ownership,
                key = owner.key,
                generation = gen,
                job = myJob,
                writeTitleMedia = {
                    invoked = true
                    enteredWrite.complete(Unit)
                    writeThrows?.let { throw it }
                    withContext(NonCancellable) { releaseWrite.await() }
                },
                onWriteFailed = { failure = it },
            )
        }
        enteredWrite.await() // A1: writer started under owner A and is parked.
        return ParkedWrite(job, releaseWrite, { invoked }, { failure }, { gateResult })
    }

    private class ParkedWrite(
        val job: Job,
        val releaseWrite: CompletableDeferred<Unit>,
        val invoked: () -> Boolean,
        val failure: () -> Throwable?,
        val gateResult: () -> Boolean?,
    )

    private fun owner(title: String, ownership: MangaDownloadOwnership): Owner {
        val key = MangaDownloadKey.fromTask(title, "1")
        return Owner(key, ownership.issueAttemptId())
    }

    // A2+A3: title delete crosses the barrier only after the media writer ends.
    @Test
    fun titleDelete_waitsForOwnedMediaWrite() = runBlocking {
        val ownership = MangaDownloadOwnership()
        val queue = ConcurrentLinkedQueue<MangaDownloaderService.DownloadTask>()
        val own = owner("TitleM", ownership)
        val parked = parkedOwnedWrite(this, ownership, own)
        assertTrue(parked.invoked())
        val events = mutableListOf<String>()
        parked.job.invokeOnCompletion { events.add("write-done") }

        val cutoff = ownership.reserveMangaScope("TitleM")
        var result: MangaChapterDeleteResult? = null
        val scopeRunner = launch {
            try {
                result = MangaDownloadCancellation.cancelDeleteScopeTransaction(
                    ownership = ownership,
                    queue = queue,
                    titlePath = "TitleM",
                    removeJobRecords = { _, _ -> },
                    cutoff = cutoff,
                    deleteScope = {
                        events.add("delete-start")
                        MangaChapterDeleteResult.Deleted
                    },
                )
            } finally {
                ownership.finishMangaScope("TitleM")
            }
        }

        parked.releaseWrite.complete(Unit)
        scopeRunner.join()
        assertEquals(MangaChapterDeleteResult.Deleted, result)
        assertEquals(
            "physical title delete starts only after the media writer finished/cancelled",
            listOf("write-done", "delete-start"),
            events,
        )
        // Owner was cancelled by the barrier: no COMPLETE may follow.
        assertEquals(false, parked.gateResult())
    }

    // A4: purge has the same property at whole-type scope.
    @Test
    fun purge_waitsForOwnedMediaWrite() = runBlocking {
        val ownership = MangaDownloadOwnership()
        val queue = ConcurrentLinkedQueue<MangaDownloaderService.DownloadTask>()
        val own = owner("TitleP", ownership)
        val parked = parkedOwnedWrite(this, ownership, own)
        val events = mutableListOf<String>()
        parked.job.invokeOnCompletion { events.add("write-done") }

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

        parked.releaseWrite.complete(Unit)
        scopeRunner.join()
        assertEquals(MangaChapterDeleteResult.Deleted, result)
        assertEquals(listOf("write-done", "delete-start"), events)
        assertEquals(false, parked.gateResult())
    }

    // A5: owner cancellation before the write starts prevents any later write.
    @Test
    fun cancelledOwner_neverStartsMediaWrite() = runBlocking {
        val ownership = MangaDownloadOwnership()
        val own = owner("TitleC", ownership)
        val atLatch = CompletableDeferred<Unit>()
        val startWrite = CompletableDeferred<Unit>()
        var invoked = false
        val job = launch {
            val myJob = coroutineContext[Job]!!
            val gen = ownership.begin(own.key, myJob, own.attemptId)
            atLatch.complete(Unit)
            startWrite.await() // parked before the gate, like a pre-write attempt
            MangaMediaWriteGate.runOwnedMediaWrite(
                ownership, own.key, gen, myJob,
                writeTitleMedia = { invoked = true },
            )
        }
        atLatch.await()
        job.cancel()
        job.join()
        startWrite.complete(Unit) // too late: the attempt is already dead
        assertFalse("cancelled owner must never start a media write", invoked)
    }

    // A6: media-info failure neither creates stale COMPLETE nor bypasses checks.
    @Test
    fun writeFailure_bestEffort_validOwnerMayCommit() = runBlocking {
        val ownership = MangaDownloadOwnership()
        val own = owner("TitleE", ownership)
        val job = Job()
        val gen = ownership.begin(own.key, job, own.attemptId)
        var failure: Throwable? = null
        val ok = MangaMediaWriteGate.runOwnedMediaWrite(
            ownership, own.key, gen, job,
            writeTitleMedia = { throw IllegalStateException("cover fetch failed") },
            onWriteFailed = { failure = it },
        )
        assertNotNull("failure is reported", failure)
        assertTrue("valid owner may still commit after best-effort failure", ok)
        assertTrue(ownership.commitIfOwner(own.key, gen, job) {})
        job.cancel()
    }

    @Test
    fun writeFailure_cancelledOwner_cannotCommit() = runBlocking {
        val ownership = MangaDownloadOwnership()
        val own = owner("TitleF", ownership)
        val enteredWrite = CompletableDeferred<Unit>()
        val releaseWrite = CompletableDeferred<Unit>()
        var gateResult: Boolean? = null
        val job = launch {
            val myJob = coroutineContext[Job]!!
            val gen = ownership.begin(own.key, myJob, own.attemptId)
            gateResult = MangaMediaWriteGate.runOwnedMediaWrite(
                ownership, own.key, gen, myJob,
                writeTitleMedia = {
                    enteredWrite.complete(Unit)
                    withContext(NonCancellable) { releaseWrite.await() }
                    throw IllegalStateException("late media failure")
                },
                onWriteFailed = {},
            )
        }
        enteredWrite.await()
        // Real production cancellation path (marks the record, cancels, joins
        // behind the parked write) — not a bare job.cancel(), which alone
        // never invalidated ownership in any version.
        val canceller = launch {
            ownership.cancelAndJoin(own.key, ownership.currentCutoff()) { _, _ -> }
        }
        releaseWrite.complete(Unit)
        canceller.join()
        job.join()
        assertEquals("cancelled owner cannot pass the post-write check", false, gateResult)
        // Fresh attempts are unaffected by the failed/cancelled write.
        val freshJob = Job()
        val freshGen = ownership.begin(own.key, freshJob, ownership.issueAttemptId())
        assertTrue(ownership.commitIfOwner(own.key, freshGen, freshJob) {})
        freshJob.cancel()
    }

    // A7 source-level guard: Manga saveMediaInfo no longer calls global launchIO.
    @Test
    fun a7_saveMediaInfo_noDetachedScope() {
        var dir: File? = File(System.getProperty("user.dir"))
        var root: File? = null
        while (dir != null) {
            if (File(dir, "app/src/main/java/ani/dantotsu/download/manga/MangaDownloaderService.kt").exists()) {
                root = dir
                break
            }
            dir = dir.parentFile
        }
        val found: File = root ?: throw AssertionError("repo root not found")
        val text = File(
            found,
            "app/src/main/java/ani/dantotsu/download/manga/MangaDownloaderService.kt"
        ).readText()
        val body = functionBody(text, "fun saveMediaInfo(")
        val code = body.replace(Regex("/\\*[\\s\\S]*?\\*/"), "")
            .lines()
            .joinToString("\n") { it.substringBefore("//") }
        assertFalse("saveMediaInfo must not launch a detached scope", code.contains("launchIO"))
        assertFalse("saveMediaInfo must not touch GlobalScope", code.contains("GlobalScope"))
        assertTrue("media write stays routed via the ownership gate", text.contains("MangaMediaWriteGate"))
    }

    private fun functionBody(text: String, declaration: String): String {
        val start = text.indexOf(declaration)
        assertTrue("declaration $declaration found", start >= 0)
        val open = text.indexOf('{', start)
        var depth = 0
        var i = open
        while (i < text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return text.substring(open, i + 1)
                }
            }
            i++
        }
        fail("unbalanced braces in $declaration")
        return ""
    }
}
