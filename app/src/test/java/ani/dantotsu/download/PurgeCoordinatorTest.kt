package ani.dantotsu.download

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * MAX MERGE M3 HOLD (correction review, blockers 1+4): the purge barrier
 * joins ACTUAL writer jobs — never infers termination from removed
 * records — covers semaphore waiters, and is single-flight.
 */
class PurgeCoordinatorTest {

    /** Fake service state with the same mutex discipline as the services. */
    private class FakeState {
        val mutex = Mutex()
        val jobs = mutableMapOf<String, Job>()
        val entered = mutableSetOf<String>()
        var purged = false
    }

    @Test
    fun `cancel runs before join before purge`() = runBlocking {
        val order = mutableListOf<String>()
        val job = launch { delay(50) }
        val outcome = PurgeCoordinator().runPurge(
            snapshotAndCancel = {
                order.add("cancel")
                job.cancel()
                PurgeSnapshot(listOf(job), setOf("a"))
            },
            settle = {
                order.add("settle")
                assertTrue(job.isCompleted)
                true
            },
            purge = { order.add("purge") }
        )
        assertEquals(PurgeOutcome.DONE, outcome)
        assertEquals(listOf("cancel", "settle", "purge"), order)
        assertTrue(job.isCompleted)
    }

    @Test
    fun `purge waits for a slow job to terminate`() = runBlocking {
        // Cancel is delivered at once, but the writer unwinds slowly in
        // NonCancellable cleanup. Purge must observe full termination.
        val job = launch {
            try {
                delay(Long.MAX_VALUE)
            } finally {
                withContext(NonCancellable) { delay(400) }
            }
        }
        delay(50)
        var purgeSawCompleted = false
        val outcome = PurgeCoordinator().runPurge(
            timeoutMs = 5_000,
            snapshotAndCancel = {
                job.cancel()
                PurgeSnapshot(listOf(job), emptySet())
            },
            settle = { true },
            purge = { purgeSawCompleted = job.isCompleted }
        )
        assertEquals(PurgeOutcome.DONE, outcome)
        assertTrue(purgeSawCompleted)
    }

    @Test
    fun `semaphore waiter is cancelled before entering download`() = runBlocking {
        // maxParallel = 1: A holds the permit, B is already launched but
        // parked in withPermit. Both must be cancelled + joined; B must
        // never enter the download section; purge runs last.
        val order = mutableListOf<String>()
        val semaphore = Semaphore(1)
        val enteredB = CompletableDeferred<Boolean>()
        val jobA = launch {
            semaphore.withPermit {
                order.add("a-enter")
                try {
                    delay(Long.MAX_VALUE)
                } finally {
                    order.add("a-done")
                }
            }
        }
        // Let A acquire the permit.
        delay(100)
        val jobB = launch {
            semaphore.withPermit {
                order.add("b-enter")
                enteredB.complete(true)
                delay(Long.MAX_VALUE)
            }
        }
        // Let B launch and park on the semaphore (present in the job map,
        // absent from any active set — the exact reported hole).
        delay(100)
        assertFalse(enteredB.isCompleted)
        val jobs = listOf(jobA, jobB)
        var purgedLast = false
        val outcome = PurgeCoordinator().runPurge(
            timeoutMs = 5_000,
            snapshotAndCancel = {
                jobs.forEach { it.cancel() }
                PurgeSnapshot(jobs, setOf("a", "b"))
            },
            settle = { true },
            purge = {
                purgedLast = jobA.isCompleted && jobB.isCompleted
                order.add("purge")
            }
        )
        assertEquals(PurgeOutcome.DONE, outcome)
        assertTrue(purgedLast)
        assertFalse(enteredB.isCompleted)
        assertEquals(listOf("a-enter", "a-done", "purge"), order)
    }

    @Test
    fun `early record removal does not imply termination`() = runBlocking {
        // The reported anime hole: the map entry is gone but the writer is
        // still unwinding. The barrier must join the JOB, not the record.
        var unwound = false
        val job = launch {
            try {
                delay(Long.MAX_VALUE)
            } finally {
                withContext(NonCancellable) {
                    delay(200)
                    unwound = true
                }
            }
        }
        delay(50)
        val records = mutableMapOf<String, Job>()
        // Record retired early (the bug): map is empty while job runs.
        records.clear()
        var purgeSawUnwound = false
        val outcome = PurgeCoordinator().runPurge(
            timeoutMs = 5_000,
            snapshotAndCancel = {
                job.cancel()
                PurgeSnapshot(listOf(job), emptySet())
            },
            settle = { true },
            purge = { purgeSawUnwound = unwound }
        )
        assertEquals(PurgeOutcome.DONE, outcome)
        assertTrue(purgeSawUnwound)
        assertTrue(unwound)
    }

    @Test
    fun `jobs-only scope is covered even when absent from queue and current`() = runBlocking {
        // A waiter lives only in downloadJobs. The snapshot must include
        // it or the purge races a live writer.
        val waiterDone = CompletableDeferred<Boolean>()
        val waiter = launch {
            try {
                delay(Long.MAX_VALUE)
            } finally {
                waiterDone.complete(true)
            }
        }
        delay(50)
        // Queue + current are EMPTY here — only the job record exists.
        val outcome = PurgeCoordinator().runPurge(
            timeoutMs = 5_000,
            snapshotAndCancel = {
                waiter.cancel()
                PurgeSnapshot(listOf(waiter), emptySet())
            },
            settle = { true },
            purge = {}
        )
        assertEquals(PurgeOutcome.DONE, outcome)
        assertTrue(waiterDone.await())
    }

    @Test
    fun `unsettled writers time out without purging`() = runBlocking {
        var purged = false
        // Genuinely stuck writer: spins without a suspension point, so
        // coroutine cancellation cannot deliver. The barrier must give up
        // bounded and report TIMEOUT instead of purging under a live writer.
        val stop = AtomicBoolean(false)
        val stuck = launch(Dispatchers.Default) {
            var x = 0
            while (!stop.get()) {
                repeat(1_000) { x += it }
            }
        }
        delay(50)
        try {
            val outcome = PurgeCoordinator().runPurge(
                timeoutMs = 200,
                snapshotAndCancel = {
                    stuck.cancel()
                    PurgeSnapshot(listOf(stuck), emptySet())
                },
                settle = { true },
                purge = { purged = true }
            )
            assertEquals(PurgeOutcome.TIMEOUT, outcome)
            assertFalse(purged)
        } finally {
            stop.set(true)
        }
    }

    @Test
    fun `failed settle check fails the purge`() = runBlocking {
        var purged = false
        val job = launch { delay(10) }
        val outcome = PurgeCoordinator().runPurge(
            snapshotAndCancel = {
                job.cancel()
                PurgeSnapshot(listOf(job), setOf("old"))
            },
            settle = { false },
            purge = { purged = true }
        )
        assertEquals(PurgeOutcome.TIMEOUT, outcome)
        assertFalse(purged)
    }

    @Test
    fun `empty queue with no live writers still purges`() = runBlocking {
        var purged = false
        val outcome = PurgeCoordinator().runPurge(
            snapshotAndCancel = { PurgeSnapshot(emptyList(), emptySet()) },
            settle = { true },
            purge = { purged = true }
        )
        assertEquals(PurgeOutcome.DONE, outcome)
        assertTrue(purged)
    }

    @Test
    fun `concurrent purges are single-flight`() = runBlocking {
        val coordinator = PurgeCoordinator()
        val physicalPurges = AtomicInteger(0)
        var secondLambdasRan = false
        val gate = CompletableDeferred<Unit>()
        val first = async {
            coordinator.runPurge(
                timeoutMs = 5_000,
                snapshotAndCancel = { PurgeSnapshot(emptyList(), emptySet()) },
                settle = { true },
                purge = {
                    physicalPurges.incrementAndGet()
                    gate.await()
                }
            )
        }
        delay(100)
        // Barrier must be held while the first purge is inside.
        assertTrue(coordinator.barrierActive)
        val second = async {
            coordinator.runPurge(
                snapshotAndCancel = {
                    secondLambdasRan = true
                    PurgeSnapshot(emptyList(), emptySet())
                },
                settle = {
                    secondLambdasRan = true
                    true
                },
                purge = {
                    secondLambdasRan = true
                    physicalPurges.incrementAndGet()
                }
            )
        }
        delay(100)
        // Still held — the second caller coalesces instead of clearing it.
        assertTrue(coordinator.barrierActive)
        gate.complete(Unit)
        assertEquals(PurgeOutcome.DONE, first.await())
        assertEquals(PurgeOutcome.DONE, second.await())
        assertEquals(1, physicalPurges.get())
        assertFalse(secondLambdasRan)
        assertFalse(coordinator.barrierActive)
    }

    @Test
    fun `barrier is held during the physical purge`() = runBlocking {
        val coordinator = PurgeCoordinator()
        var heldDuringPurge = false
        coordinator.runPurge(
            snapshotAndCancel = { PurgeSnapshot(emptyList(), emptySet()) },
            settle = { true },
            purge = { heldDuringPurge = coordinator.barrierActive }
        )
        assertTrue(heldDuringPurge)
        assertFalse(coordinator.barrierActive)
    }

    @Test
    fun `barrier clears even on timeout`() = runBlocking {
        val coordinator = PurgeCoordinator()
        // Unwinds slower than the barrier: join must time out, purge must
        // be skipped, and the barrier must still clear.
        val slow = launch {
            try {
                delay(Long.MAX_VALUE)
            } finally {
                withContext(NonCancellable) { delay(500) }
            }
        }
        delay(50)
        val outcome = coordinator.runPurge(
            timeoutMs = 50,
            snapshotAndCancel = {
                slow.cancel()
                PurgeSnapshot(listOf(slow), emptySet())
            },
            settle = { true },
            purge = { fail("must not run") }
        )
        assertEquals(PurgeOutcome.TIMEOUT, outcome)
        slow.cancel()
        assertFalse(coordinator.barrierActive)
    }

    @Test
    fun `mutex-disciplined fake service state settles truthfully`() = runBlocking {
        // End-to-end shape of the service seam: snapshot + settle both run
        // under the state mutex; join happens outside it.
        val state = FakeState()
        val worker = launch {
            delay(Long.MAX_VALUE)
        }
        state.mutex.withLock { state.jobs["k"] = worker }
        val outcome = PurgeCoordinator().runPurge(
            timeoutMs = 5_000,
            snapshotAndCancel = {
                state.mutex.withLock {
                    val jobs = state.jobs.values.toList()
                    jobs.forEach { it.cancel() }
                    PurgeSnapshot(jobs, setOf("k"))
                }
            },
            settle = { snapshot ->
                state.mutex.withLock {
                    snapshot.scopeKeys.forEach { state.jobs.remove(it) }
                    state.jobs.isEmpty()
                }
            },
            purge = {
                state.mutex.withLock { state.purged = true }
            }
        )
        assertEquals(PurgeOutcome.DONE, outcome)
        assertTrue(state.mutex.withLock { state.purged })
        assertTrue(worker.isCompleted)
    }

    @Test
    fun `shutdown stops reuse`() = runBlocking {
        val coordinator = PurgeCoordinator()
        coordinator.shutdown()
        var purged = false
        val thrown = try {
            coordinator.runPurge(
                snapshotAndCancel = { PurgeSnapshot(emptyList(), emptySet()) },
                settle = { true },
                purge = { purged = true }
            )
            null
        } catch (e: Exception) {
            e
        }
        assertTrue(thrown is CancellationException)
        assertFalse(purged)
    }
}
