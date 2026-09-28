package ani.dantotsu.download

import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * MAX MERGE M3 HOLD (Blocker 2): ownership-safe purge coordination.
 *
 * Physical deletion may only happen after live writers are proven
 * terminated: cancel first, JOIN/await settlement (bounded), then purge.
 * `barrierActive` is observable while a purge runs so enqueue paths can
 * defer fresh work instead of racing the deletion. Never auto-resumes
 * anything; callers report [PurgeOutcome] and must not claim success on
 * [PurgeOutcome.Timeout]. Framework-free except coroutines, unit-proved
 * (PurgeCoordinatorTest).
 */
enum class PurgeOutcome {
    DONE,
    TIMEOUT,
}

class PurgeCoordinator {
    @Volatile
    var barrierActive = false
        private set

    suspend fun runPurge(
        timeoutMs: Long = 15_000,
        cancelAll: suspend () -> Unit,
        isSettled: () -> Boolean,
        purge: suspend () -> Unit,
    ): PurgeOutcome {
        barrierActive = true
        try {
            cancelAll()
            val settled = withTimeoutOrNull(timeoutMs) {
                while (!isSettled()) delay(100)
                true
            } ?: false
            if (!settled) return PurgeOutcome.TIMEOUT
            purge()
            return PurgeOutcome.DONE
        } finally {
            barrierActive = false
        }
    }
}
