package ani.dantotsu.download.manga

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Blocker-A regressions: fresh-retry liveness through the REAL queue/service
 * orchestration seam ([MangaProcessorLoop] — the exact poll/launch/join/stop loop
 * `MangaDownloaderService` runs), not merely `begin()`.
 *
 * Both reviewer orderings, deterministically. Progress is synchronized with explicit
 * handshake latches (a launch/entry signal per phase), never with yield-counting:
 * every `await()` below is completed either by the loop reaching that phase or by
 * the test releasing a parked job.
 */
class MangaProcessorLoopTest {

    private fun task(title: String, chapter: String, attemptId: Long) =
        MangaDownloaderService.DownloadTask(
            title = title,
            chapter = chapter,
            scanlator = "",
            imageData = emptyList(),
            simultaneousDownloads = 1,
        ).apply { this.attemptId = attemptId }

    /** Controllable host: ArrayDeque queue, parked jobs, scripted veto-safe stop. */
    private class Harness {
        val queue = ArrayDeque<MangaDownloaderService.DownloadTask>()
        val launched = mutableListOf<MangaDownloaderService.DownloadTask>()
        val liveJobs = mutableListOf<CompletableJob>()
        val stopCalls = mutableListOf<Int>()
        var startId = 1
        /** stopIfIdle script: consumed per call; defaults to true (honored stop). */
        val stopScript = ArrayDeque<Boolean>()
        /** Completed on entry to joinLaunched (loop parked after draining). */
        val joinEntered = CompletableDeferred<Unit>()
        /** Completed per launch with the current launch count. */
        val launchCount = CompletableDeferred<Int>()
        /** Completed when the second task launches (re-loop pickup signal). */
        val secondLaunch = CompletableDeferred<Unit>()
        private var launches = 0

        fun loop(): MangaProcessorLoop = MangaProcessorLoop(
            hasWork = { queue.isNotEmpty() },
            poll = { queue.removeFirstOrNull() },
            launchTask = { task ->
                launched.add(task)
                launches += 1
                if (!launchCount.isCompleted) launchCount.complete(launches)
                if (launches == 2) secondLaunch.complete(Unit)
                val job = Job()
                liveJobs.add(job)
                job
            },
            joinLaunched = {
                joinEntered.complete(Unit)
                // Join every job launched so far (append-only list; joining an
                // already-completed job returns immediately, so no clearing — the
                // test releases via releaseAll(), which is idempotent).
                liveJobs.toList().forEach { it.join() }
            },
            stopIfIdle = { id ->
                stopCalls.add(id)
                if (stopScript.isEmpty()) true else stopScript.removeFirst()
            },
            currentStartId = { startId },
        )

        /** Release every parked job exactly once. */
        fun releaseAll() {
            liveJobs.toList().forEach { it.complete() }
        }
    }

    /**
     * Ordering 1: loop drains A and parks in the join; B is queued mid-join; the
     * released join must lead the SAME loop to B (no stop with work pending, no
     * stranded retry, no second loop required).
     */
    @Test
    fun queuedDuringJoin_isPickedUpBySameLoop() = runBlocking {
        val h = Harness()
        h.queue.add(task("T", "1", attemptId = 1))

        val runner = launch { h.loop().run() }
        h.joinEntered.await() // loop parked after draining A
        assertEquals(listOf("1"), h.launched.map { it.chapter })

        // B queued while the prior loop is parked after draining the queue.
        h.queue.add(task("T", "2", attemptId = 2))
        // Release A's job; the loop must re-poll B rather than stop.
        h.releaseAll()
        h.secondLaunch.await()
        assertEquals(
            "loop must pick up B queued during the join",
            listOf("1", "2"),
            h.launched.map { it.chapter },
        )

        // Drain B's job so the loop can reach idle and stop exactly once.
        h.releaseAll()
        runner.join()
        assertEquals(listOf(1), h.stopCalls)
    }

    /**
     * Ordering 2: B queued around the post-join empty-check / stop boundary. The
     * first stop is vetoed (a newer start exists); the loop must re-loop onto B and
     * stop exactly once more, carrying the LATEST start id.
     */
    @Test
    fun vetoedStop_reloopsOntoFreshWork_withLatestStartId() = runBlocking {
        val h = Harness()
        h.queue.add(task("T", "1", attemptId = 1))
        h.stopScript.addAll(listOf(false, true)) // veto once, then honor

        val runner = launch { h.loop().run() }
        h.joinEntered.await() // loop parked with A launched
        assertEquals(listOf("1"), h.launched.map { it.chapter })
        // A completes; B queued around the boundary; newer start vetoes first stop.
        h.releaseAll()
        h.queue.add(task("T", "2", attemptId = 2))
        h.startId = 2
        h.secondLaunch.await()
        assertEquals(
            "vetoed stop must re-loop onto B, not strand it",
            listOf("1", "2"),
            h.launched.map { it.chapter },
        )

        h.releaseAll()
        runner.join()
        assertTrue("veto consumed exactly once", h.stopScript.isEmpty())
        assertEquals("stops carry the latest start id", listOf(2, 2), h.stopCalls)
    }

    /**
     * Idle loop stops exactly once and launches nothing; a deterministic restart
     * (e.g. a fresh startService after teardown) drains fresh work — the restart
     * path onDestroy preserves the queue for.
     */
    @Test
    fun idleStop_thenRestartDrainsFreshWork() = runBlocking {
        val h = Harness()
        launch { h.loop().run() }.join()
        assertTrue("idle loop launches nothing", h.launched.isEmpty())
        assertEquals(listOf(1), h.stopCalls)

        // Deterministic restart (e.g. a fresh startService after teardown).
        val h2 = Harness()
        h2.queue.add(task("T", "9", attemptId = 9))
        h2.startId = 7
        val runner2 = launch { h2.loop().run() }
        assertEquals(1, h2.launchCount.await())
        assertEquals(listOf("9"), h2.launched.map { it.chapter })
        h2.releaseAll()
        runner2.join()
        assertEquals(listOf(7), h2.stopCalls)
    }

    /**
     * The stop callback fires only on a queue that survived the join empty — never
     * while work remains.
     */
    @Test
    fun stopFiresOnlyWhenQueueSurvivedJoinEmpty() = runBlocking {
        val h = Harness()
        h.queue.add(task("T", "1", attemptId = 1))
        h.queue.add(task("T", "2", attemptId = 2))

        val runner = launch { h.loop().run() }
        h.joinEntered.await() // parked with both launched
        assertEquals(listOf("1", "2"), h.launched.map { it.chapter })
        assertTrue("no stop while joins pending", h.stopCalls.isEmpty())
        h.releaseAll()
        runner.join()
        assertEquals(listOf(1), h.stopCalls)
    }
}
