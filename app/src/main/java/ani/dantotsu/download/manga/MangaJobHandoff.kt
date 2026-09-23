package ani.dantotsu.download.manga

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

/**
 * Publish-before-start handoff for service download jobs, safe under parent
 * cancellation — with retirement deferred until bookkeeping is safe.
 *
 * History:
 * - v10: an eagerly-started child could complete before publication
 *   (false-idle window + stale record).
 * - v11/v12: LAZY creation (no execution before `start()`) plus a born-dead
 *   skip and an identity-scoped rollback for published-but-unstarted jobs.
 * - Remaining hole (independent reproduction): the `isCompleted` precheck and
 *   the `start()==false` rollback are point observations, but completion
 *   accounting (`onTaskDone`, i.e. the flight token) fired directly from the
 *   completion handler — so a child completing after the precheck but before
 *   publication took effect still retired while the record was absent. The
 *   later rollback fixed the final map state but not the observable false
 *   idle in between.
 *
 * This revision adds the explicit retirement protocol: completion may be
 * OBSERVED at any time, but the flight token retires only once the handoff
 * has reached a safe bookkeeping state — a deliberate no-publication decision
 * (born dead), an established publication, or a completed rollback. A
 * lock-free bitmask ([RetirementGate]) fires retirement exactly once, on
 * whichever of completion/safe-state comes last, in any order.
 *
 * Invariants, even under parent cancellation: no completion retirement
 * precedes publication/rollback ownership; no stale record; exactly-once
 * accounting; no false-idle state observable.
 */
class MangaJobHandoff(
    private val scope: CoroutineScope,
    private val onTaskDone: () -> Unit,
) {
    /**
     * Deferred retirement gate: exactly-once [onRetire] when BOTH completion
     * has been observed AND a safe bookkeeping state has been established, in
     * either order. Lock-free; safe to signal from the completion handler on
     * any thread ([onTaskDone] itself must be thread-safe and non-throwing).
     */
    private class RetirementGate(private val onRetire: () -> Unit) {
        // bit 0: completion observed; bit 1: safe state established.
        private val bits = AtomicInteger(0)

        fun onCompleted() {
            if (bits.getAndUpdate { it or 0b01 } == 0b10) onRetire()
        }

        fun onSafe() {
            if (bits.getAndUpdate { it or 0b10 } == 0b01) onRetire()
        }
    }

    /**
     * @param publish publishes the caller's bookkeeping record for [Job];
     *   runs before the child can execute.
     * @param remove rolls back a record published for [Job] iff it is still
     *   that job's record; runs only when the child will never start.
     * @return the child in every case (started normally, or completed without
     *   ever starting after rollback / no-publication decision).
     */
    suspend fun launch(
        publish: suspend (Job) -> Unit,
        remove: suspend (Job) -> Unit,
        body: suspend CoroutineScope.() -> Unit,
    ): Job {
        val gate = RetirementGate(onTaskDone)
        val job = scope.launch(start = CoroutineStart.LAZY, block = body)
        job.invokeOnCompletion { gate.onCompleted() }
        if (job.isCompleted) {
            // Born dead: the parent was already cancelled. Completion is
            // observed but retirement waits for the safe state, which is the
            // deliberate decision to never publish — established here.
            gate.onSafe()
            return job
        }
        try {
            publish(job)
        } catch (e: Exception) {
            // Publication failed before start: roll back anything partial,
            // then complete the never-started child and establish safety.
            // The cancel and onSafe run in `finally` so even a throwing
            // rollback cannot strand the child or wedge retirement
            // (onSafe is idempotent: retirement still fires exactly once).
            try {
                remove(job)
            } finally {
                job.cancel()
                gate.onSafe()
            }
            throw e
        }
        try {
            if (!job.start()) {
                // Cancelled between publication and start: the body (and its
                // record-removal finally) will never run, so synchronously
                // roll back the published record instead of leaving it stale.
                remove(job)
            }
        } finally {
            // Safe in all cases here: publication established, or rolled back
            // above. Retirement fires now or already did at completion.
            gate.onSafe()
        }
        return job
    }
}
