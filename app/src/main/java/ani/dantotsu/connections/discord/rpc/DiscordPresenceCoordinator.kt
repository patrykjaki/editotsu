package ani.dantotsu.connections.discord.rpc

/**
 * Latest-wins presence delivery coordinator.
 *
 * Invariants (Phase 2 review Blocker 3):
 *  - An **owner transition** (the active owner token changes) is sent immediately — a new owner is
 *    never hidden behind the previous owner's frame.
 *  - A **same-owner update** within the coalescing window replaces the pending update; the latest
 *    state is flushed at the end of the window (a real debounce, not a drop-only throttle).
 *  - When an owner transitions, any pending update for the previous owner is cancelled — a stale
 *    delayed frame can never overwrite a newer owner.
 *  - [shutdown] cancels any pending flush.
 *
 * Pure and injectable: [sender], [clock], and [scheduler] are all seams so the rules above are
 * deterministically unit-tested without a Binder or main Looper.
 */
class DiscordPresenceCoordinator(
    private val sender: (DiscordPresence) -> Unit,
    private val clock: () -> Long,
    private val scheduler: PresenceScheduler,
    private val windowMs: Long = 500L,
) {
    private data class Pending(val owner: DiscordPresenceOwnership.OwnerToken, val presence: DiscordPresence)

    @Volatile private var lastOwner: DiscordPresenceOwnership.OwnerToken? = null
    private var lastSendMs: Long = 0L
    private var pending: Pending? = null
    private var scheduled: PresenceScheduler.PresenceTask? = null

    @Synchronized
    fun submit(owner: DiscordPresenceOwnership.OwnerToken, presence: DiscordPresence) {
        if (lastOwner != owner) {
            // Owner transition: send now, drop anything pending for the previous owner.
            scheduled?.cancel()
            scheduled = null
            pending = null
            sendNow(owner, presence)
            return
        }
        // Same owner: latest-wins pending, flushed once at the end of the window.
        pending = Pending(owner, presence)
        if (scheduled == null) {
            val delay = (windowMs - (clock() - lastSendMs)).coerceAtLeast(0L)
            scheduled = scheduler.schedule(delay) { flush() }
        }
    }

    @Synchronized
    fun shutdown() {
        scheduled?.cancel()
        scheduled = null
        pending = null
        lastOwner = null
        lastSendMs = 0L
    }

    /** Cancel any pending same-owner update but keep the current owner (used for suppression clears). */
    @Synchronized
    fun cancelPending() {
        scheduled?.cancel()
        scheduled = null
        pending = null
    }

    private fun flush() {
        val p: Pending?
        synchronized(this) {
            scheduled = null
            p = pending
            pending = null
        }
        p?.let { sendNow(it.owner, it.presence) }
    }

    private fun sendNow(owner: DiscordPresenceOwnership.OwnerToken, presence: DiscordPresence) {
        lastOwner = owner
        lastSendMs = clock()
        sender(presence)
    }
}
