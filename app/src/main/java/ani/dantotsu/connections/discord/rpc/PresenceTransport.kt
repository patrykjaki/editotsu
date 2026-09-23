package ani.dantotsu.connections.discord.rpc

/**
 * Minimal transport seam the Discord RPC session controller drives. Decouples the pure
 * session/ownership/routing logic from the concrete [DiscordRpcClient] (which needs a live Binder
 * and the main Looper), so the controller and coordinator are deterministically unit-testable with fakes.
 */
interface PresenceTransport {
    fun setPresence(presence: DiscordPresence)
    fun clearPresence()
    fun shutdown()
}

/**
 * Scheduling seam used by [DiscordPresenceCoordinator] to coalesce same-owner updates.
 * Production uses a main-Looper [android.os.Handler]; tests inject a manual scheduler.
 */
interface PresenceScheduler {
    fun schedule(delayMs: Long, action: () -> Unit): PresenceTask

    interface PresenceTask {
        fun cancel()
    }
}
