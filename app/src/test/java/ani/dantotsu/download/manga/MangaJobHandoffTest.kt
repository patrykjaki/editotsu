package ani.dantotsu.download.manga

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * Independent-review regression: cancellation-safe publish-before-start job
 * handoff ([MangaJobHandoff]).
 *
 * v10 closed the live-scope fast-child window with LAZY creation, but parent
 * cancellation around publication/start still broke it: a born-cancelled child
 * fired completion accounting before publication (then published a stale
 * record), and a child cancelled between publication and `start()` never ran
 * its body — so its body-local `finally` never removed the published record.
 * v12 deferred retirement behind an atomic gate, but retirement could still
 * fire from the completion handler after the precheck and before publication
 * took effect. Now retirement fires only once BOTH completion is observed AND
 * a safe bookkeeping state holds, in either order.
 * These tests force those orderings against the REAL handoff seam:
 * - B1 already-cancelled scope: never publishes, nothing to roll back,
 *   accounting exactly once, no body execution;
 * - B2 cancel after publication, before start: `start()` cannot run the body,
 *   the published record is rolled back, accounting exactly once;
 * - B3 live fast child still cannot complete before publication (preserved);
 * - B4 throwing publication rolls back partial publication, exact accounting.
 *
 * No sleeps; latch/ordering-driven only.
 */
class MangaJobHandoffTest {

    private fun handoff(
        scope: CoroutineScope,
        onDone: () -> Unit,
    ) = MangaJobHandoff(scope, onDone)

    /** Production-shaped record map: identity-scoped removal, like the service. */
    private class RecordMap {
        val records = mutableMapOf<String, Job>()
        suspend fun publish(key: String, job: Job) {
            records[key] = job
        }
        suspend fun remove(key: String, job: Job) {
            val e = records[key]
            if (e === job) records.remove(key)
        }
    }

    /**
     * B3 (preserved): the critical live ordering — an immediately-completing
     * child records `published` strictly before anything else, every run. (No
     * mid-flight assertions: a suspension-free body may run inline inside
     * start(). The final order below is scheduling-independent.)
     */
    @Test
    fun fastChild_publicationPrecedesCompletionAccounting() = runBlocking {
        val events = mutableListOf<String>()
        val job = handoff(this) { events.add("done") }.launch(
            publish = { events.add("published") },
            remove = { fail("nothing published yet; nothing to roll back") },
            body = { events.add("body") }, // completes immediately when started
        )
        job.join()
        assertEquals(
            "publication strictly precedes body and completion accounting",
            listOf("published", "body", "done"),
            events,
        )
    }

    /**
     * Parked child: publication happens while the body is still parked;
     * completion accounting only after release. No false-idle state
     * (done && !published) is observable at any rendezvous point.
     */
    @Test
    fun parkedChild_noFalseIdleAtAnyRendezvous() = runBlocking {
        val events = mutableListOf<String>()
        val bodyStarted = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val job = handoff(this) { events.add("done") }.launch(
            publish = { events.add("published") },
            remove = { fail("live handoff never rolls back") },
            body = {
                events.add("body-started")
                bodyStarted.complete(Unit)
                release.await()
                events.add("body-done")
            },
        )
        // Rendezvous 1: launched and parked — published, body started, not done.
        bodyStarted.await()
        assertEquals(listOf("published", "body-started"), events)
        release.complete(Unit)
        job.join()
        // Rendezvous 2: completed — full order preserved.
        assertEquals(listOf("published", "body-started", "body-done", "done"), events)
    }

    /**
     * B1: already-cancelled scope. The child is born completed: accounting
     * fires at registration, but the record is NEVER published (there is
     * nothing whose body-finally could retire it), nothing is rolled back,
     * the body never executes, and accounting is exact.
     */
    @Test
    fun b1_bornCancelled_neverPublishes_exactAccounting() = runBlocking {
        val dead = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        dead.cancel()
        val records = RecordMap()
        var done = 0
        var removed = false
        val job = handoff(dead) { done++ }.launch(
            publish = {
                records.publish("k", it)
                fail("born-dead child must never publish")
            },
            remove = {
                removed = true
                fail("nothing published; nothing to roll back")
            },
            body = { fail("born-dead body must never execute") },
        )
        job.join()
        assertTrue("born-dead job is completed", job.isCompleted)
        assertTrue("no stale record", records.records.isEmpty())
        assertFalse("no rollback without publication", removed)
        assertEquals("accounting fires exactly once", 1, done)
    }

    /**
     * B2: parent cancellation after publication, before start. `start()`
     * cannot run the body; the published record is synchronously rolled back
     * (identity-scoped); accounting fires exactly once; no stale record, no
     * false idle.
     */
    @Test
    fun b2_cancelBetweenPublishAndStart_rollsBack() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val records = RecordMap()
        var done = 0
        var removed: Job? = null
        val handoff = handoff(scope) { done++ }
        val job = handoff.launch(
            publish = { j ->
                records.publish("k", j)
                scope.cancel() // parent dies after publication, before start
            },
            remove = { j ->
                removed = j
                records.remove("k", j)
            },
            body = { fail("cancelled-before-start body must never execute") },
        )
        job.join()
        assertEquals("rolled-back job returned", job, removed)
        assertTrue("no stale record remains", records.records.isEmpty())
        assertEquals("accounting fires exactly once", 1, done)
        assertTrue(job.isCompleted)
    }

    /**
     * B4: throwing publication rolls back even a partially published record:
     * exact accounting, no body execution, error propagates, nothing survives.
     */
    @Test
    fun b4_publishThrows_rollsBackPartial_exactAccounting() = runBlocking {
        val records = RecordMap()
        var done = 0
        var bodyRan = false
        try {
            handoff(this) { done++ }.launch(
                publish = { j ->
                    records.publish("k", j) // partial publication…
                    throw IllegalStateException("record publication failed")
                },
                remove = { j -> records.remove("k", j) },
                body = { bodyRan = true },
            )
            fail("publication error must propagate")
        } catch (e: IllegalStateException) {
            assertEquals("record publication failed", e.message)
        }
        assertFalse("unstarted body must never execute", bodyRan)
        assertTrue("partial publication rolled back", records.records.isEmpty())
        assertEquals("accounting fires exactly once", 1, done)
    }

    /**
     * B0: cancellation after the precheck, before publication takes effect.
     * The completion handler fires while publication is still parked — but
     * retirement must wait for the safe state. Then publication completes,
     * `start()` cannot run the body, the record rolls back, and only then
     * does accounting retire, exactly once. The critical assertion is the
     * ORDER of retirement (last), not only the final map/counter state.
     */
    @Test
    fun b0_cancelBetweenPrecheckAndPublication_defersRetirement() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val events = mutableListOf<String>()
        val records = mutableMapOf<String, Job>()
        var seenJob: Job? = null
        val enteredPublish = CompletableDeferred<Unit>()
        val releasePublish = CompletableDeferred<Unit>()
        val runner = async {
            MangaJobHandoff(scope) { events.add("done") }.launch(
                publish = { j ->
                    seenJob = j
                    enteredPublish.complete(Unit)
                    releasePublish.await() // park pre-record: check passed, nothing exposed
                    events.add("published")
                    records["k"] = j
                },
                remove = { j ->
                    events.add("removed")
                    if (records["k"] === j) records.remove("k")
                },
                body = { fail("cancelled-before-start body must never execute") },
            )
        }
        // Publish entered ⟹ the isCompleted precheck passed (code order).
        enteredPublish.await()
        // Parent dies while publication is parked: completion occurs now.
        scope.cancel()
        val job: Job = seenJob ?: throw AssertionError("handoff never reached publication")
        assertTrue("completion occurred while publication parked", job.isCompleted)
        // …but accounting must NOT have retired while publication/rollback is
        // unresolved: no false-idle state is observable at this rendezvous.
        assertEquals(
            "no retirement before the safe state",
            emptyList<String>(),
            events.toList(),
        )
        releasePublish.complete(Unit)
        val returned = runner.await()
        returned.join()
        assertEquals("returned job completed", true, returned.isCompleted)
        // Publication completed, start() failed, rollback ran — and only then
        // did accounting retire, exactly once.
        assertEquals(listOf("published", "removed", "done"), events.toList())
        assertTrue("no stale record remains", records.isEmpty())
    }
    @Test
    fun wiring_serviceUsesHandoffWithRollback() {
        val text = File(
            repoRoot(),
            "app/src/main/java/ani/dantotsu/download/manga/MangaDownloaderService.kt"
        ).readText()
        val code = text.replace(Regex("/\\*[\\s\\S]*?\\*/"), "")
            .lines()
            .joinToString("\n") { it.substringBefore("//") }
        assertTrue(code.contains("MangaJobHandoff(serviceScope"))
        assertTrue(code.contains("jobHandoff.launch("))
        val launch = code.substringAfter("jobHandoff.launch(")
        assertTrue("publish callback wired", launch.contains("publish ="))
        assertTrue("rollback callback wired", launch.contains("remove ="))
    }

    private fun repoRoot(): File {
        var dir: File? = File(System.getProperty("user.dir"))
        while (dir != null) {
            if (File(dir, "app/src/main/java/ani/dantotsu/download/manga/MangaDownloaderService.kt").exists()) {
                return dir
            }
            dir = dir.parentFile
        }
        throw AssertionError("repo root not found from user.dir")
    }
}
