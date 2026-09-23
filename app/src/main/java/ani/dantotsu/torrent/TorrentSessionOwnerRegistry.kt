package ani.dantotsu.torrent

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Server-side live-owner lifecycle registry (CP1v8-01).
 *
 * Solves the remove→re-add ABA race: each live torrent GENERATION owns a unique identity
 * (`session_<hash>_<gen8>`), created atomically on first successful add of that generation and
 * captured-then-removed on removal. A delayed asynchronous release therefore always targets the
 * exact generation it captured and can never strip ownership from a newer generation.
 *
 * Overlapping successful adds for the SAME live handle converge onto the same generation owner
 * via [getOrCreateLiveOwner] (get-or-create semantics, never overwrite).
 */
class TorrentSessionOwnerRegistry {

    private val liveOwners = ConcurrentHashMap<String, String>()

    /** Fallback provisional owners whose stable/generation promotion failed but which remain
     *  committed owners in metadata; removal must still release them. */
    private val fallbackOwners = ConcurrentHashMap<String, MutableSet<String>>()

    // Per-hash SERVER LIFECYCLE locks (CP1v9-02): serialize the short generation-transition
    // critical sections of same-hash add/remove so the libtorrent handle generation and the
    // registry generation always transition atomically relative to each other. NOT held for the
    // lifetime of playback — only around find/create-handle → claim/promote and
    // capture-generation → remove-handle.
    private val lifecycleLocks = ConcurrentHashMap<String, Any>()

    fun lifecycleLockFor(rawHash: String): Any =
        lifecycleLocks.getOrPut(normalizeInfoHash(rawHash)) { Any() }

    /**
     * Atomically get-or-create the live generation owner for `rawHash`. Concurrent successful adds
     * for the same live handle resolve to the identical id.
     */
    fun getOrCreateLiveOwner(rawHash: String): String {
        val normHash = normalizeInfoHash(rawHash)
        return liveOwners.computeIfAbsent(normHash) {
            "session_${normHash}_" + UUID.randomUUID().toString().substring(0, 8)
        }
    }

    /**
     * Atomically capture-and-remove everything releasable for `rawHash`:
     * the current live generation owner (if any) plus any fallback provisional owners.
     * Returns the list in release order; empty ⇒ nothing to release.
     */
    fun captureForRemoval(rawHash: String): List<String> {
        val normHash = normalizeInfoHash(rawHash)
        val owners = mutableListOf<String>()
        liveOwners.remove(normHash)?.let { owners.add(it) }
        fallbackOwners.remove(normHash)?.let { owners.addAll(it) }
        return owners
    }

    /**
     * Register a provisional owner as the committed live identity after its generation-promotion
     * persist failed; it stays authoritative and must be released on removal.
     */
    fun registerFallback(rawHash: String, ownerId: String) {
        val normHash = normalizeInfoHash(rawHash)
        fallbackOwners.getOrPut(normHash) { java.util.Collections.synchronizedSet(mutableSetOf()) }
            .add(ownerId)
    }
}
