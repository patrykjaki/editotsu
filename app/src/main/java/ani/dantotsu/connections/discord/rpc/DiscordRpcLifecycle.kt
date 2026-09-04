package ani.dantotsu.connections.discord.rpc

/**
 * Pure-JVM run-identity registry for the Discord RPC Binder lifecycle.
 *
 * Addresses review Blocker 2: a stale callback from an obsolete run must never mutate or
 * tear down the current run. Every `ServiceConnection` callback and every RPC `onFrame` /
 * `onClose` is gated through [isActiveRun], which proves **both** the generation token AND
 * the exact connection identity (PREP v5's exact identity check):
 *
 * ```
 * runGen == currentGeneration  AND  connIdentity === thisRunConn
 * ```
 *
 * [beginRun] starts a new run and makes it the active one (incrementing the generation).
 * [closeRun] authoritatively closes a run **only if it is still the active one** — a stale
 * run's close is a no-op, so it can never unbind a newer connection.
 *
 * Deliberately Android-free so it is deterministically host-testable (review Gap 7).
 */
class DiscordRpcLifecycle {

    private var generation = 0
    private var activeConn: Any? = null

    /** Begin a new run identified by [connIdentity]; returns its generation token. */
    @Synchronized
    fun beginRun(connIdentity: Any): Int {
        generation += 1
        activeConn = connIdentity
        return generation
    }

    @Synchronized
    fun currentGeneration(): Int = generation

    /** The identity of the currently active run, or null if none. */
    @Synchronized
    fun activeConnIdentity(): Any? = activeConn

    /**
     * True only if [connIdentity] is the currently active run **and** [runGen] matches the
     * current generation. Stale callbacks (wrong gen, or a superseded connection) return false.
     */
    @Synchronized
    fun isActiveRun(runGen: Int, connIdentity: Any): Boolean =
        runGen == generation && activeConn === connIdentity

    /**
     * Close [connIdentity] if and only if it is the active run. Returns true when this call
     * performed the authoritative close (so the caller may now unbind exactly that connection
     * and schedule recovery), false when the run was already superseded/stale.
     */
    @Synchronized
    fun closeRun(connIdentity: Any): Boolean {
        if (activeConn === connIdentity) {
            activeConn = null
            return true
        }
        return false
    }
}
