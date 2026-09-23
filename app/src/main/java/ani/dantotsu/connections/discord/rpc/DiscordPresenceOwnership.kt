package ani.dantotsu.connections.discord.rpc

/**
 * Single-slot presence ownership keyed by **instance/source identity**, not just media kind.
 *
 * A naive `"anime"`/`"manga"` owner lets a stale second instance of the SAME kind (e.g. an old
 * [ani.dantotsu.media.anime.player.PlayerDiscordManager] A torn down after a newer B published) clear
 * B's presence. Each real owner therefore receives a unique [OwnerToken] (kind + generation id); a
 * release succeeds only if its exact token is still the active owner.
 *
 * Pure logic (no Android deps) so the Phase 2 owner-transition rules are deterministically unit-tested.
 */
class DiscordPresenceOwnership {

    enum class OwnerKind { ANIME, MANGA }

    data class OwnerToken(val kind: OwnerKind, val id: Long)

    @Volatile private var current: OwnerToken? = null
    private var nextId: Long = 1L

    @Synchronized
    fun createToken(kind: OwnerKind): OwnerToken {
        val token = OwnerToken(kind, nextId)
        nextId += 1
        return token
    }

    @Synchronized
    fun claim(token: OwnerToken) {
        current = token
    }

    @Synchronized
    fun owns(token: OwnerToken): Boolean = current == token

    /**
     * Release [token]. Returns true only if [token] is still the active owner (so the caller is
     * authorised to tear the presence down). A stale token (one that already lost ownership to a
     * newer source — same kind or not) returns false and must NOT clear the presence.
     */
    @Synchronized
    fun release(token: OwnerToken): Boolean {
        if (current != token) return false
        current = null
        return true
    }

    @Synchronized
    fun reset() {
        current = null
    }
}
