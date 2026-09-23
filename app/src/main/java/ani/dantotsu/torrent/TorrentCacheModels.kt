package ani.dantotsu.torrent

import kotlinx.serialization.Serializable

/** Thrown when a lease/state transition could not be durably committed (CP1v2-01/02). */
class CachePersistenceException(message: String) : Exception(message)

/**
 * Scoped ownership token for an in-flight torrent-add (CP1v3-01, delta-aware per CP1v5-02).
 *
 * Rollback undoes exactly the DELTA this transaction introduced:
 *  - `ownerAddedByClaim` records whether [ownerId] was actually introduced by this attempt
 *    (duplicate acquisition by a pre-existing owner makes this `false`, and abort then performs
 *    NO ownership change);
 *  - `previousEntry == null` → fresh claim: rollback removes the entry plus claimed content when
 *    no other owners remain;
 *  - `previousEntry != null` → rollback removes only our owner; if others remain they stay ACTIVE,
 *    otherwise the prior OWNERLESS dormant state (RETAINED/EVICTABLE) is restored. Owners that
 *    released after begin are NEVER resurrected; a conservative ownerless RETAINED is used instead.
 */
data class CacheClaimToken(
    val hash: String,
    val ownerId: String,
    val previousEntry: TorrentCacheMetaEntry?,
    val ownerAddedByClaim: Boolean
)

private val INFO_HASH_REGEX = Regex("^[A-F0-9]{40}$")

/**
 * Normalize and validate a BitTorrent v1 info-hash for use as a filesystem path component
 * (CP1v2-03). Throws [IllegalArgumentException] on anything that is not exactly 40 hex digits.
 */
fun normalizeInfoHash(raw: String): String {
    val upper = raw.trim().uppercase()
    require(INFO_HASH_REGEX.matches(upper)) { "invalid info-hash: ${raw.take(80)}" }
    return upper
}

@Serializable
enum class TorrentCacheState {
    ACTIVE,
    RETAINED,
    EVICTABLE,
    DELETING,
    ORPHANED
}

@Serializable
data class TorrentCacheMetaWrapper(
    val version: Int = 1,
    val entries: List<TorrentCacheMetaEntry> = emptyList()
)

@Serializable
data class TorrentCacheMetaEntry(
    val torrentHash: String,
    val owners: Set<String> = emptySet(),
    val state: TorrentCacheState,
    val lastAccessSequence: Long,
    val lastAccessEpochMs: Long,
    val payloadRelativePath: String,
    val sizeBytes: Long,
    val schemaVersion: Int = 1
)

data class ClearCacheResult(
    val bytesFreed: Long,
    val activeBytesSkipped: Long,
    val entriesRemoved: Int,
    val failedEntries: Int = 0
)

/** Outcome of a claimed-payload deletion attempt (CP1-05). */
sealed interface DeleteResult {
    /** Payload deleted and metadata removed; [bytes] were reclaimed. */
    data class Deleted(val bytes: Long) : DeleteResult

    /** Entry is ACTIVE with owners (or otherwise protected); nothing was touched. */
    data object Protected : DeleteResult

    /** No trustworthy metadata claim exists for this hash; nothing was touched (CP1-02). */
    data object Missing : DeleteResult

    /** Deletion could not be completed durably or on-disk; metadata left in DELETING state. */
    data object Failed : DeleteResult
}

data class CacheStorageBreakdown(
    val torrentCacheBytes: Long,
    val imageCacheBytes: Long,
    val subtitleCacheBytes: Long,
    val networkCacheBytes: Long,
    val totalCacheBytes: Long,
    val activeTorrentBytes: Long,
    val retainedTorrentBytes: Long
)
