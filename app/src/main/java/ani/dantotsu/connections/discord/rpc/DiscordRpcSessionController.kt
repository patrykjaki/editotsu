package ani.dantotsu.connections.discord.rpc

/**
 * Pure orchestration of routing, ownership, and lifecycle for the tokenless Discord presence.
 *
 * This is the single place that decides:
 *  - **Routing:** when [isEnabled] is false, the new publisher is silent AND any existing
 *    session/presence is cleared + shut down.
 *  - **Ownership:** releases are authorised only for the exact active owner token, so a stale
 *    same-kind or cross-kind owner cannot clear/overwrite a newer presence.
 *  - **Lifecycle:** when the *active final* owner releases and no newer owner exists, the presence
 *    is cleared AND the transport is shut down (CLEAR -> disconnect -> unbind). Suppression clears
 *    the presence but keeps the session + ownership so the same owner can resume when conditions
 *    improve.
 *
 * Pure: [PresenceTransport], [DiscordPresenceOwnership], and [DiscordPresenceCoordinator] are all
 * injectable, so every routing/lifecycle rule is deterministically unit-tested without a Binder.
 */
class DiscordRpcSessionController(
    private val transport: PresenceTransport,
    private val ownership: DiscordPresenceOwnership,
    private val coordinator: DiscordPresenceCoordinator,
    private val isEnabled: () -> Boolean,
) {

    fun publish(token: DiscordPresenceOwnership.OwnerToken, presence: DiscordPresence) {
        if (!isEnabled()) {
            teardown()
            return
        }
        ownership.claim(token)
        coordinator.submit(token, presence)
    }

    /**
     * Owner-release path (e.g. screen destroyed). Returns true if this token was the active owner and
     * the presence + transport were torn down. A stale token returns false and must not touch anything.
     */
    fun clear(token: DiscordPresenceOwnership.OwnerToken): Boolean {
        if (!isEnabled()) {
            teardown()
            return false
        }
        val released = ownership.release(token)
        if (released) {
            coordinator.shutdown()
            transport.clearPresence()
            transport.shutdown()
        }
        return released
    }

    /**
     * Clear the visible presence but KEEP the session + ownership (offline/incognito/adult suppression).
     * Owner-token gated (review v3): a stale token is a complete no-op — it must not clear the active
     * owner's presence nor cancel the active owner's pending update. Returns true only if this token is
     * the active owner and a clear was performed.
     */
    fun suppressClear(token: DiscordPresenceOwnership.OwnerToken): Boolean {
        if (!isEnabled()) {
            teardown()
            return false
        }
        if (!ownership.owns(token)) return false
        coordinator.cancelPending()
        transport.clearPresence()
        return true
    }

    fun shutdown() {
        if (!isEnabled()) {
            teardown()
            return
        }
        ownership.reset()
        coordinator.shutdown()
        transport.clearPresence()
        transport.shutdown()
    }

    /** Routing disabled or forced teardown: clear + shut down and drop all ownership/session state. */
    fun teardown() {
        ownership.reset()
        coordinator.shutdown()
        transport.clearPresence()
        transport.shutdown()
    }
}
