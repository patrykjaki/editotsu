package ani.dantotsu.download

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

class DownloadProcessorLoopTest {

    private class Harness(scope: CoroutineScope) {
        val queue = ConcurrentLinkedQueue<String>()
        val launched = mutableListOf<String>()
        val gates = mutableMapOf<String, CompletableDeferred<Unit>>()
        var startId = 1
        val stopCalls = mutableListOf<Int>()
        var vetoNextStop = false
        var mayStop = true
        var onIdle: () -> Unit = {}

        val loop = DownloadProcessorLoop(
            poll = { queue.poll() },
            launchTask = { task ->
                launched += task
                val gate = CompletableDeferred<Unit>().also { gates[task] = it }
                scope.launch { gate.await() }
            },
            hasQueued = { queue.isNotEmpty() },
            mayStop = { mayStop },
            stopIfIdle = { id ->
                stopCalls += id
                if (vetoNextStop) { vetoNextStop = false; false } else true
            },
            currentStartId = { startId },
            idleWait = { onIdle(); yield() },
        )
    }

    @Test
    fun `task queued while another runs starts and the loop stops only when both finish`() = runBlocking {
        val h = Harness(this)
        h.queue += "ep1"
        var idleTicks = 0
        h.onIdle = {
            idleTicks++
            if (idleTicks == 1) h.queue += "ep2"         // queued while ep1 runs
            if (idleTicks == 3) h.gates["ep1"]?.complete(Unit)
            if (idleTicks == 5) h.gates["ep2"]?.complete(Unit)
        }
        withTimeout(5_000) { h.loop.run() }
        assertEquals(listOf("ep1", "ep2"), h.launched)
        assertEquals(1, h.stopCalls.size)
    }

    @Test
    fun `refused stop keeps the loop and picks up the new task`() = runBlocking {
        val h = Harness(this)
        h.vetoNextStop = true
        h.onIdle = {
            if (h.launched.isEmpty() && h.queue.isEmpty()) { h.queue += "late"; h.startId = 2 }
            h.gates.values.forEach { it.complete(Unit) }
        }
        withTimeout(5_000) { h.loop.run() }
        assertEquals(listOf("late"), h.launched)
        assertEquals(listOf(1, 2), h.stopCalls)
    }

    @Test
    fun `purge owning the lifecycle ends the loop without a stop`() = runBlocking {
        val h = Harness(this)
        h.mayStop = false
        withTimeout(5_000) { h.loop.run() }
        assertTrue(h.stopCalls.isEmpty())
    }

    @Test
    fun `every task launches once`() = runBlocking {
        val h = Harness(this)
        repeat(5) { h.queue += "t$it" }
        h.onIdle = { h.gates.values.forEach { it.complete(Unit) } }
        withTimeout(5_000) { h.loop.run() }
        assertEquals((0 until 5).map { "t$it" }, h.launched)
    }

    @Test
    fun `single processor never runs two passes at once and reruns after a late kick`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val running = AtomicInteger(0)
        val maxRunning = AtomicInteger(0)
        val passes = AtomicInteger(0)
        val firstPassEntered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val secondPassDone = CompletableDeferred<Unit>()
        val processor = SingleProcessor(scope) {
            val now = running.incrementAndGet()
            maxRunning.updateAndGet { maxOf(it, now) }
            val pass = passes.incrementAndGet()
            if (pass == 1) {
                firstPassEntered.complete(Unit)
                releaseFirst.await()
            }
            running.decrementAndGet()
            if (pass == 2) secondPassDone.complete(Unit)
        }
        processor.ensure()
        withTimeout(5_000) { firstPassEntered.await() }
        processor.ensure()   // kick while running: no second instance
        processor.ensure()
        releaseFirst.complete(Unit)
        withTimeout(5_000) { secondPassDone.await() }
        assertEquals(1, maxRunning.get())
        assertEquals(2, passes.get())
        scope.cancel()
    }
}
