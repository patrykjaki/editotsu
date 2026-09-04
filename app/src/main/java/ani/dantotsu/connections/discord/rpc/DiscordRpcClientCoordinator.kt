package ani.dantotsu.connections.discord.rpc

/**
 * Pure-JVM coordinator for the Discord Social SDK RPC transport: the single owner of
 * bind-registration, RPC connect, run identity, and bounded recovery.
 *
 * This is the piece the v2 review required to be extracted and host-tested (review "REQUIRED
 * TEST GAP"): the Android wrapper ([DiscordRpcClient]) is now a thin delegating adapter that only
 * performs the platform bind/connect and pumps callbacks; all coordination lives here and is
 * deterministically driven by [DiscordRpcClientCoordinatorTest].
 *
 * Threading model (review Blocker 3):
 *  - Every public method is `synchronized(lock)`, so compound transitions (run identity + bind
 *    registration + recovery generation) are atomic.
 *  - The Android adapter additionally serializes every Binder / timeout / recovery callback onto
 *    the main Looper before calling these methods. Correctness does not depend on that (thanks to
 *    the lock), but it gives a clean single-thread affinity and matches Android lifecycle rules.
 *
 * Per-run registration (review Blocker 1):
 *  - Each run tracks `bindRegistered` independently of `serviceConnected` / `rpcConnected`. A
 *    successful `bindService()==true` is paired with exactly one `unbind`, even if `onServiceConnected`
 *    never arrives, `onNullBinding` is delivered, or the establishment timeout fires.
 *
 * Cancellable recovery (review Blocker 2):
 *  - The pending recovery task is retained, cancelled on clear/shutdown/terminal failure/superseding
 *    run, and validated (recovery generation + still-wanted presence) before it executes.
 */
class DiscordRpcClientCoordinator(
    private val pid: Int,
    private val controller: DiscordRpcController,
    private val scheduler: DiscordRpcScheduler,
    private val logger: DiscordRpcLogger,
    private val binder: DiscordRpcBindDelegate,
) {

    private val lifecycle = DiscordRpcLifecycle()
    private val recovery = DiscordRpcRecoveryPolicy(DiscordRpcRecoveryPolicy.DEFAULT_MAX_ATTEMPTS)

    private class RunState {
        var gen: Int = 0
        var bindRegistered = false
        var serviceConnected = false
        var rpcConnected = false
        var connection: DiscordRpcConnection? = null
    }

    private val lock = Any()
    private var activeRun: RunState? = null
    private var recoveryGen = 0
    private var pendingRecovery: DiscordRpcScheduler.Task? = null
    private var pendingTimeout: DiscordRpcScheduler.Task? = null

    // ── Public intent API (production: main thread; tests: direct) ─────────────────────

    fun requestPresence(presence: DiscordPresence) = synchronized(lock) {
        cancelRecoveryLocked()
        controller.requestPresence(presence)
        // Only bind when no run is currently owned. Ordinary updates while BINDING/CONNECTED/READY
        // reuse the existing connection (review Blocker 1: no rebind per setPresence update).
        if (activeRun == null) startBindLocked()
    }

    fun clearPresence() = synchronized(lock) {
        cancelRecoveryLocked()
        controller.clearPresence()
    }

    fun shutdown() = synchronized(lock) {
        cancelRecoveryLocked()
        controller.shutdown()
        teardownActiveRunLocked()
    }

    // ── Binder / transport callbacks (caller must reach these on the coordinator thread) ─

    /**
     * The RPC connection is delivered **bound to this run's generation** (never from a global slot,
     * review Blocker 2). A `null` connection means `connect()` failed and is treated as a loss.
     *
     * Fail-closed disposal (review v4 Blocker 2): a delivery for no active run or a stale generation is
     * NOT silently dropped — the just-created connection is best-effort `disconnect()`ed so a late
     * `onServiceConnected` for a retired run cannot leak a live Discord RPC session. Duplicate delivery
     * for the *current* run disposes the previously-held handle before replacing it.
     */
    fun onServiceConnected(gen: Int, connection: DiscordRpcConnection?) {
        synchronized(lock) {
            val run = activeRun
            if (run == null || !lifecycle.isActiveRun(gen, run)) {
                // Stale/unknown delivery: tear down the connection fail-closed; never attach it.
                if (connection != null) runCatching { connection.disconnect() }
                return
            }
            run.serviceConnected = true
            if (connection == null) {
                logger.warn("rpc: connect() returned null/exception")
                handleLossLocked(gen)
                return
            }
            // Duplicate delivery for the current run: dispose the previous handle to avoid a leak.
            run.connection?.let { runCatching { it.disconnect() } }
            run.connection = connection
            run.rpcConnected = true
            controller.attachTransport(object : DiscordRpcTransport {
                // The connection's sendFrame already returns a real Boolean (false on dead-Binder), so
                // the local success signal is propagated faithfully (review Blocker 3).
                override fun send(frame: String): Boolean = connection.sendFrame(frame)
            })
            controller.onBinderConnected(pid)
            // (Re)arm the READY window from the RPC-connect point.
            pendingTimeout?.let { scheduler.cancel(it) }
            pendingTimeout = scheduler.postDelayed(ESTABLISH_TIMEOUT_MS) { onEstablishmentTimeout(gen) }
        }
    }

    /** ServiceConnection died/never-connected for [gen]: authoritative teardown + bounded recovery. */
    fun onServiceDisconnected(gen: Int) = handleLoss(gen)
    fun onBindingDied(gen: Int) = handleLoss(gen)
    fun onNullBinding(gen: Int) = handleLoss(gen)
    fun onClose(gen: Int) = handleLoss(gen)

    fun onFrame(gen: Int, frame: String) {
        synchronized(lock) {
            val run = activeRun ?: return
            if (!lifecycle.isActiveRun(gen, run)) return // stale run's RPC callback cannot affect current run
            val wasConnected = controller.getState() == DiscordRpcState.CONNECTED
            controller.onFrame(frame)
            // A healthy current run reaching READY resets the recovery budget so a long-lived session
            // does not permanently consume attempts (review Blocker 4), and the now-redundant
            // establishment timeout is cancelled (safe no-op otherwise).
            if (wasConnected && controller.getState() == DiscordRpcState.READY) {
                recovery.reset()
                pendingTimeout?.let { scheduler.cancel(it); pendingTimeout = null }
            }
        }
    }

    fun onEstablishmentTimeout(gen: Int) {
        synchronized(lock) {
            val run = activeRun ?: return
            if (!lifecycle.isActiveRun(gen, run)) return
            if (controller.getState() == DiscordRpcState.READY) return // already established
            handleLossLocked(gen)
        }
    }

    // ── internals ──────────────────────────────────────────────────────────────────

    private fun handleLoss(gen: Int) = synchronized(lock) { handleLossLocked(gen) }

    private fun handleLossLocked(gen: Int) {
        val run = activeRun ?: return
        if (!lifecycle.isActiveRun(gen, run)) return // stale callback cannot tear down a newer run
        val needRebind = controller.onBinderLost() // clears old-run nonce state; sets BINDING/IDLE
        // Tear down EXACTLY this run's registration/connection.
        if (run.bindRegistered) {
            runCatching { binder.unbind(gen) }
            run.bindRegistered = false
        }
        run.connection?.let { runCatching { it.disconnect() } }
        run.rpcConnected = false
        lifecycle.closeRun(run) // authoritative only if still active
        if (activeRun === run) activeRun = null
        pendingTimeout?.let { scheduler.cancel(it); pendingTimeout = null }
        if (needRebind) scheduleRecoveryLocked()
    }

    private fun startBindLocked() {
        if (controller.getState() == DiscordRpcState.CLOSING) return
        val run = RunState()
        run.gen = lifecycle.beginRun(run)
        activeRun = run
        val ok = runCatching { binder.bind(run.gen) }.getOrDefault(false)
        run.bindRegistered = ok
        if (!ok) {
            handleLossLocked(run.gen)
            return
        }
        pendingTimeout = scheduler.postDelayed(ESTABLISH_TIMEOUT_MS) { onEstablishmentTimeout(run.gen) }
    }

    private fun teardownActiveRunLocked() {
        val run = activeRun
        if (run != null) {
            if (run.bindRegistered) {
                runCatching { binder.unbind(run.gen) }
                run.bindRegistered = false
            }
            run.connection?.let { runCatching { it.disconnect() } }
            lifecycle.closeRun(run)
            activeRun = null
        }
        pendingTimeout?.let { scheduler.cancel(it); pendingTimeout = null }
        pendingRecovery?.let { scheduler.cancel(it); pendingRecovery = null }
    }

    private fun cancelRecoveryLocked() {
        pendingRecovery?.let { scheduler.cancel(it); pendingRecovery = null }
        recoveryGen += 1 // invalidate any in-flight recovery runnable
    }

    private fun scheduleRecoveryLocked() {
        // Only recover when a presence is still desired (never start while IDLE/CLOSING).
        if (controller.getState() != DiscordRpcState.BINDING) return
        // At most one pending recovery task.
        pendingRecovery?.let { scheduler.cancel(it); pendingRecovery = null }
        if (!recovery.canRetry()) {
            logger.warn("rpc: recovery abandoned after ${recovery.attempts()} attempt(s)")
            controller.reset()
            return
        }
        recoveryGen += 1
        val myGen = recoveryGen
        pendingRecovery = scheduler.postDelayed(RECOVERY_BACKOFF_MS) { runRecovery(myGen) }
    }

    private fun runRecovery(myGen: Int) {
        synchronized(lock) {
            if (myGen != recoveryGen) return        // stale task: superseded run no-op
            if (controller.getState() != DiscordRpcState.BINDING) { pendingRecovery = null; return }
            if (activeRun != null) { pendingRecovery = null; return } // newer run already active
            recovery.recordAttempt()
            pendingRecovery = null
            startBindLocked()
        }
    }

    companion object {
        const val ESTABLISH_TIMEOUT_MS = 15_000L
        const val RECOVERY_BACKOFF_MS = 2_000L
    }
}

/**
 * Platform binding seam (pure-JVM, testable). The coordinator drives bind/unbind and tags every
 * callback with the originating run generation. The RPC connection is delivered to the coordinator
 * via [DiscordRpcClientCoordinator.onServiceConnected], never from a global slot.
 */
interface DiscordRpcBindDelegate {
    /** Register the connection. Returns true iff `bindService` accepted the registration. */
    fun bind(runGen: Int): Boolean
    /** Tear down a previously-registered connection exactly once. */
    fun unbind(runGen: Int)
}

/**
 * Deterministic delay scheduler seam (pure-JVM, testable). The Android adapter backs this with a
 * main-Looper `Handler`; tests back it with a manually-drained fake.
 */
interface DiscordRpcScheduler {
    fun postDelayed(delayMs: Long, block: () -> Unit): Task
    fun cancel(task: Task)
    interface Task
}
