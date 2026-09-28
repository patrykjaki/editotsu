package ani.dantotsu.download

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger

/**
 * MAX MERGE M3 HOLD (correction review, blockers 1+4): ownership-safe,
 * single-flight purge coordination.
 *
 * A purge transaction is:
 *
 * ```text
 * snapshot ALL live writer Jobs + cancel them (service lock held)
 * → join those ACTUAL jobs OUTSIDE the lock (bounded)
 * → settle check under the lock (old scope drained, no writer remains)
 * → physical purge only then
 * → report DONE vs TIMEOUT
 * ```
 *
 * Termination is proven by joining the real [Job] objects — never by
 * observing that a bookkeeping record was removed (early record removal
 * does not imply the writer unwound). The snapshot must include every
 * live job, including semaphore waiters that have not entered the active
 * set yet.
 *
 * Single-flight: concurrent callers share one in-flight transaction (the
 * second caller's lambdas are coalesced, never run overlapping physical
 * purges). [barrierActive] (ref-counted) covers the entire effective
 * purge lifetime so enqueue paths can defer fresh work.
 *
 * Call [shutdown] when the owning service is destroyed. Framework-free
 * except coroutines; unit-proved (PurgeCoordinatorTest).
 */
data class PurgeSnapshot(
    val jobs: List<Job>,
    val scopeKeys: Set<String>,
)

enum class PurgeOutcome {
    DONE,
    TIMEOUT,
}

class PurgeCoordinator {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()
    private var inFlight: Deferred<PurgeOutcome>? = null
    private val activeCount = AtomicInteger(0)

    /** True while any purge transaction is alive (ref-counted). */
    val barrierActive: Boolean get() = activeCount.get() > 0

    suspend fun runPurge(
        timeoutMs: Long = 15_000,
        snapshotAndCancel: suspend () -> PurgeSnapshot,
        settle: suspend (PurgeSnapshot) -> Boolean,
        purge: suspend () -> Unit,
    ): PurgeOutcome {
        val deferred = mutex.withLock {
            inFlight ?: scope.async {
                activeCount.incrementAndGet()
                try {
                    // 1+2. Snapshot every live writer under the service lock
                    // and cancel them. Records stay until each job's own
                    // finally retires them — removal is never the proof.
                    val snapshot = snapshotAndCancel()
                    // 3. Join the ACTUAL jobs outside the lock (bounded).
                    val joined =
                        withTimeoutOrNull(timeoutMs) {
                            joinAll(*snapshot.jobs.toTypedArray())
                            true
                        } ?: false
                    if (!joined) return@async PurgeOutcome.TIMEOUT
                    // 4. Settle check under the lock (old scope drained).
                    if (!settle(snapshot)) return@async PurgeOutcome.TIMEOUT
                    // 5. Only then physically purge.
                    purge()
                    PurgeOutcome.DONE
                } finally {
                    activeCount.decrementAndGet()
                    mutex.withLock { inFlight = null }
                }
            }.also { inFlight = it }
        }
        return deferred.await()
    }

    /** Release the internal scope; the coordinator must not be reused after. */
    fun shutdown() {
        scope.cancel()
    }
}
