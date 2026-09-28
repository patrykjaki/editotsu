package ani.dantotsu.download

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MAX MERGE M3 HOLD (Blocker 2): purge never precedes the cancel barrier;
 * unsettled writers fail the purge instead of racing it.
 */
class PurgeCoordinatorTest {

    @Test
    fun `cancel runs before purge`() = runBlocking {
        val order = mutableListOf<String>()
        val outcome = PurgeCoordinator().runPurge(
            cancelAll = { order.add("cancel") },
            isSettled = { true },
            purge = { order.add("purge") }
        )
        assertEquals(PurgeOutcome.DONE, outcome)
        assertEquals(listOf("cancel", "purge"), order)
    }

    @Test
    fun `unsettled writers time out without purging`() = runBlocking {
        var purged = false
        val outcome = PurgeCoordinator().runPurge(
            timeoutMs = 150,
            cancelAll = {},
            isSettled = { false },
            purge = { purged = true }
        )
        assertEquals(PurgeOutcome.TIMEOUT, outcome)
        assertFalse(purged)
    }

    @Test
    fun `slow settling still purges`() = runBlocking {
        var checks = 0
        var purged = false
        val outcome = PurgeCoordinator().runPurge(
            timeoutMs = 2_000,
            cancelAll = {},
            isSettled = { ++checks >= 3 },
            purge = { purged = true }
        )
        assertEquals(PurgeOutcome.DONE, outcome)
        assertTrue(purged)
    }

    @Test
    fun `barrier flag covers the run and always clears`() = runBlocking {
        val coordinator = PurgeCoordinator()
        var seenActive = false
        val job = async {
            coordinator.runPurge(
                cancelAll = {
                    delay(200)
                },
                isSettled = { true },
                purge = {}
            )
        }
        delay(50)
        seenActive = coordinator.barrierActive
        job.await()
        assertTrue(seenActive)
        assertFalse(coordinator.barrierActive)
    }

    @Test
    fun `barrier clears even on timeout`() = runBlocking {
        val coordinator = PurgeCoordinator()
        coordinator.runPurge(
            timeoutMs = 50,
            cancelAll = {},
            isSettled = { false },
            purge = { error("must not run") }
        )
        assertFalse(coordinator.barrierActive)
    }
}
