package ani.dantotsu.torrent

import android.content.Context
import ani.dantotsu.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Lease/quota manager for on-disk torrent payloads.
 *
 * Safety model (REPO_REVIEW §3.0 / §3.0a / §3.0b / CP1 review):
 *  - Every torrent owns exactly one physical directory: `<cacheDir>/<infoHash>` (created by
 *    [TorrentServerManager] before download starts). The canonical invariant is enforced
 *    structurally: [acquireLease] has no caller-supplied path, and [getPayloadDirectory]
 *    rejects non-canonical relative paths (CP1-07).
 *  - All destructive decisions re-read and transition state under the SAME per-hash lock that
 *    acquisition uses; the DELETING transition is DURABLY persisted before any filesystem
 *    deletion starts, and the recursive deletion runs while still holding that lock (CP1-02/03).
 *  - Sweeps NEVER delete unclaimed paths. If store metadata is unrecoverable or persistence is
 *    unhealthy, all destructive work is skipped (fail closed).
 */
class TorrentCacheManager(
    val context: Context? = null,
    val cacheDir: File = if (context != null) File(context.cacheDir, "torrent_cache") else File("torrent_cache"),
    val store: TorrentCacheStore = TorrentCacheStore(cacheDir),
    private val timeProvider: () -> Long = { System.currentTimeMillis() },
    internal val hooks: CacheHooks? = null,
    /** Test-only override for the directory deletion primitive. Null = production behaviour. */
    internal var deleteStrategyOverride: ((File) -> Boolean)? = null
) {
    private val deletionLocks = ConcurrentHashMap<String, Any>()
    private val managerLock = Any()

    companion object {
        const val DEFAULT_RETAINED_QUOTA_BYTES = 3L * 1024L * 1024L * 1024L // 3 GB
        const val UNLIMITED_QUOTA = 0L // 0 means no automatic eviction
        const val DEFAULT_DELETING_TIMEOUT_MS = 15_000L
        const val DEFAULT_TRANSIENT_EXPIRY_MS = 86_400_000L // 24 Hours

        @Volatile
        private var instance: TorrentCacheManager? = null

        fun getInstance(context: Context): TorrentCacheManager {
            return instance ?: synchronized(this) {
                instance ?: TorrentCacheManager(context.applicationContext).also { instance = it }
            }
        }
    }

    init {
        if (!cacheDir.exists()) {
            cacheDir.mkdirs()
        }
    }

    private fun getHashLock(hash: String): Any {
        return deletionLocks.getOrPut(hash.uppercase()) { Any() }
    }

    /** Destructive work requires trustworthy metadata AND healthy persistence (CP1-03). */
    private fun destructionAllowed(): Boolean =
        store.isMetadataRecoverable && store.isPersistenceHealthy

    /**
     * Acquire a lease for `torrentHash`, claiming the canonical payload directory
     * `<cacheDir>/<infoHash>`. The caller ([TorrentServerManager]) creates/uses this directory as
     * the libtorrent save path BEFORE adding the torrent.
     */
    suspend fun acquireLease(
        torrentHash: String,
        ownerId: String
    ): TorrentCacheMetaEntry {
        val token = beginClaim(torrentHash, ownerId)
        commitClaim(token)
        return currentLeaseOrThrow(token)
    }

    /**
     * Scoped claim acquisition (CP1v3-01): durably transitions the entry to ACTIVE with this
     * attempt's owner and returns a token capturing the exact prior state. Callers MUST finish
     * with [commitClaim] on success or [abortClaim] on any failure — including exceptions thrown
     * by the torrent-add itself.
     */
    suspend fun beginClaim(rawHash: String, ownerId: String): CacheClaimToken =
        withContext(Dispatchers.IO) {
            val normHash = normalizeInfoHash(rawHash)
            hooks?.beforeAcquireLock?.invoke(normHash)
            val lock = getHashLock(normHash)

            synchronized(lock) {
                val existing = store.get(normHash)
                // Deleters hold this same lock for the whole deletion cycle, so an observed
                // DELETING here is stale bookkeeping from a crashed cycle rather than an
                // in-flight delete. POLICY A (CP1v4-05): committed DELETING is not reclaimable;
                // reconciliation must finish it first.
                if (existing?.state == TorrentCacheState.DELETING) {
                    throw CachePersistenceException(
                        "cannot claim $normHash: committed state is DELETING"
                    )
                }
                val currentOwners = (existing?.owners ?: emptySet()).toMutableSet()
                currentOwners.add(ownerId)

                val size = measureDirectorySize(getPayloadDirectory(normHash))
                val updated = TorrentCacheMetaEntry(
                    torrentHash = normHash,
                    owners = currentOwners,
                    state = TorrentCacheState.ACTIVE,
                    lastAccessSequence = store.nextSequence(),
                    lastAccessEpochMs = timeProvider(),
                    payloadRelativePath = normHash,
                    sizeBytes = size,
                    schemaVersion = 1
                )
                if (!store.putDurable(updated)) {
                    // Roll memory back to the committed snapshot; abort the claim (CP1v2-01/04).
                    if (existing != null) store.put(existing, immediateFlush = false)
                    else store.remove(normHash)
                    Logger.log("TorrentCacheManager: durable claim persist failed for $normHash")
                    throw CachePersistenceException("claim persist failed for $normHash")
                }
                CacheClaimToken(
                    hash = normHash,
                    ownerId = ownerId,
                    previousEntry = existing,
                    ownerAddedByClaim = existing?.owners?.contains(ownerId) != true
                )
            }
        }

    /** Mark a claim as committed to its live handle. The durable ACTIVE entry already exists. */
    fun commitClaim(token: CacheClaimToken) { /* durability happened in beginClaim */ }

    /**
     * Stable live-owner id for a successfully added torrent (CP1v7-02). Unique provisional add
     * owners are promoted to this deterministic id when the live handle is established, so any
     * number of overlapping successful adds converge on ONE releasable ownership identity.
     */
    /**
     * Promote a provisional claim owner to a caller-supplied LIVE owner id under the per-hash lock
     * (CP1v7-02 + CP1v8-01). The server derives the live id from its generation registry, so:
     *  - concurrent successful adds for the same live handle promote onto the SAME live id;
     *  - a delayed removal releases only the captured generation id — never a newer generation's.
     *
     * Returns `false` when promotion could not be persisted; memory is rolled back to the current
     * committed state (the provisional owner stays authoritative) and the server records the
     * provisional id in its fallback registry.
     */
    suspend fun promoteClaimToLiveOwner(
        token: CacheClaimToken,
        targetLiveOwnerId: String
    ): Boolean = withContext(Dispatchers.IO) {
            val lock = getHashLock(token.hash)
            synchronized(lock) {
                val current = store.get(token.hash)
                    ?: return@synchronized false
                if (!current.owners.contains(token.ownerId)) return@synchronized false

                val ownersAfter = (current.owners - token.ownerId) + targetLiveOwnerId
                val updated = current.copy(
                    owners = ownersAfter,
                    lastAccessSequence = store.nextSequence(),
                    lastAccessEpochMs = timeProvider()
                )
                val ok = store.putDurable(updated)
                if (!ok) {
                    // Memory back to the committed snapshot; provisional owner stays authoritative.
                    store.put(current, immediateFlush = false)
                    Logger.log("TorrentCacheManager: live-owner promotion persist failed for ${token.hash}")
                }
                ok
            }
        }

    /**
     * Undo exactly this token's mutation — OWNER-AWARE (CP1v4-03): the current entry is re-read
     * under the per-hash lock and only [CacheClaimToken.ownerId] is removed, so concurrent
     * acquisitions/state changes made between begin and abort are preserved.
     *
     * Durability (CP1v4-04): returns `true` only when the committed state no longer owns this
     * attempt. When `false` is returned the caller must assume a recovery pass is required:
     *  - failed persistence of a remaining-owners/prior-dormant restoration ⇒ memory kept aligned
     *    with the committed primary;
     *  - fresh claim with no other owners ⇒ durable DELETING transition first; filesystem deletion
     *    only after it commits; deletion failure KEEPS the DELETING claim (bytes stay accounted);
     *    post-delete metadata-removal failure restores the committed DELETING marker.
     */
    /**
     * Undo exactly this token's DELTA (CP1v4-03/CP1v5-02):
     *  - if the owner pre-dated the claim (`ownerAddedByClaim == false`), this is a no-op — the
     *    transaction introduced nothing, and current ownership is left untouched;
     *  - otherwise only [CacheClaimToken.ownerId] is removed from the CURRENT entry (re-read under
     *    the per-hash lock), so concurrent acquisitions/releases between begin and abort survive;
     *  - remaining owners ⇒ persist reduced ACTIVE durably;
     *  - no owners remain ⇒ restore prior OWNERLESS dormant state (RETAINED/EVICTABLE) when that
     *    is what existed before; owners that released mid-transaction are never resurrected — a
     *    conservative ownerless RETAINED is used instead;
     *  - fresh claim (no previous entry) ⇒ durable destructive cleanup protocol; a failed/partial
     *    deletion KEEPS the committed DELETING marker so bytes stay accounted.
     *
     * Returns `true` only when the committed state no longer owns this attempt (CP1v4-04).
     */
    suspend fun abortClaim(token: CacheClaimToken): Boolean = withContext(Dispatchers.IO) {
        val lock = getHashLock(token.hash)
        synchronized(lock) {
            val current = store.get(token.hash)
                ?: return@synchronized true // nothing claimed anymore — already consistent

            // Duplicate acquisition by an owner that already existed: nothing to undo.
            if (!token.ownerAddedByClaim) {
                return@synchronized true
            }

            val ownersAfter = current.owners - token.ownerId

            if (ownersAfter.isNotEmpty()) {
                val updated = current.copy(owners = ownersAfter)
                val ok = store.putDurable(updated)
                if (!ok) {
                    store.put(current, immediateFlush = false)
                    Logger.log("TorrentCacheManager: abort persist failed (remaining owners) for ${token.hash}")
                }
                return@synchronized ok
            }

            // This attempt was the last/only owner:
            if (token.previousEntry == null) {
                // FRESH claim → durable destructive cleanup (same protocol as other deletions).
                val deleting = current.copy(
                    owners = emptySet(),
                    state = TorrentCacheState.DELETING,
                    lastAccessSequence = store.nextSequence(),
                    lastAccessEpochMs = timeProvider()
                )
                if (!store.putDurable(deleting)) {
                    store.put(current, immediateFlush = false)
                    Logger.log("TorrentCacheManager: abort DELETING persist failed for ${token.hash}")
                    return@synchronized false
                }
                val dir = try { getPayloadDirectory(token.hash, token.hash) } catch (_: Exception) { null }
                val deleted = dir == null || !dir.exists() || runDeletion(dir)
                if (!deleted) {
                    Logger.log("TorrentCacheManager: abort partial delete kept as DELETING for ${token.hash}")
                    return@synchronized false
                }
                val (_, removalDurable) = store.removeDurable(token.hash)
                if (!removalDurable) {
                    store.put(deleting, immediateFlush = false)
                    Logger.log("TorrentCacheManager: abort metadata cleanup not durable for ${token.hash}")
                    return@synchronized false
                }
                return@synchronized true
            }

            // Pre-existing entry, now ownerless after our removal: restore prior dormant state,
            // but NEVER resurrect owners that released while this transaction ran.
            val prev = token.previousEntry
            val restoredState = when (prev.state) {
                TorrentCacheState.RETAINED, TorrentCacheState.EVICTABLE -> prev.state
                else -> TorrentCacheState.RETAINED
            }
            val restored = prev.copy(
                owners = emptySet(),
                state = restoredState,
                lastAccessSequence = store.nextSequence(),
                lastAccessEpochMs = timeProvider()
            )
            val ok = store.putDurable(restored)
            if (!ok) {
                store.put(current, immediateFlush = false)
                Logger.log("TorrentCacheManager: abort restore persist failed for ${token.hash}")
            }
            ok
        }
    }

    private fun currentLeaseOrThrow(token: CacheClaimToken): TorrentCacheMetaEntry =
        store.get(token.hash)?.takeIf { it.owners.contains(token.ownerId) }
            ?: throw CachePersistenceException("lease vanished for ${token.hash}")

    /**
     * Scoped transaction guaranteeing rollback on EVERY exceptional exit after the claim begins
     * (CP1v4-01). Rollback incompleteness surfaces as a suppressed [CachePersistenceException].
     */
    suspend fun <T> withClaim(rawHash: String, ownerId: String, block: suspend () -> T): T {
        val token = beginClaim(rawHash, ownerId)
        var committed = false
        try {
            val result = block()
            commitClaim(token)
            committed = true
            return result
        } catch (t: Throwable) {
            if (!committed) {
                // Rollback must complete even when the exiting coroutine was CANCELLED
                // (CP1v5-03): run it non-cancellably, then rethrow the original exception.
                val rolledBack = kotlinx.coroutines.withContext(
                    kotlinx.coroutines.NonCancellable + Dispatchers.IO
                ) { abortClaim(token) }
                if (!rolledBack) {
                    t.addSuppressed(
                        CachePersistenceException("cache claim rollback incomplete for ${token.hash}")
                    )
                }
            }
            throw t
        }
    }

    suspend fun releaseLease(
        torrentHash: String,
        ownerId: String,
        keepRetained: Boolean = true
    ): TorrentCacheMetaEntry? = withContext(Dispatchers.IO) {
        val normHash = normalizeInfoHash(torrentHash)
        val lock = getHashLock(normHash)

        synchronized(lock) {
            val existing = store.get(normHash) ?: return@synchronized null
            val currentOwners = existing.owners.toMutableSet()
            currentOwners.remove(ownerId)

            val nextState = if (currentOwners.isNotEmpty()) {
                TorrentCacheState.ACTIVE
            } else if (keepRetained) {
                TorrentCacheState.RETAINED
            } else {
                TorrentCacheState.EVICTABLE
            }

            val payloadDir = getPayloadDirectory(normHash)
            val size = measureDirectorySize(payloadDir)

            val updated = existing.copy(
                owners = currentOwners,
                state = nextState,
                lastAccessSequence = store.nextSequence(),
                lastAccessEpochMs = timeProvider(),
                sizeBytes = size
            )
            store.put(updated, immediateFlush = false)
            updated
        }
    }

    suspend fun markEvictable(torrentHash: String): Boolean = withContext(Dispatchers.IO) {
        val normHash = normalizeInfoHash(torrentHash)
        val lock = getHashLock(normHash)

        synchronized(lock) {
            val existing = store.get(normHash) ?: return@synchronized false
            if (existing.state == TorrentCacheState.ACTIVE && existing.owners.isNotEmpty()) {
                // Cannot mark active stream as evictable while owners exist
                return@synchronized false
            }
            val updated = existing.copy(state = TorrentCacheState.EVICTABLE)
            store.put(updated, immediateFlush = false)
            true
        }
    }

    /**
     * Delete one torrent's claimed payload. Requires an EXISTING metadata claim — ownership is
     * never synthesized from the hash (CP1-02). Holds the per-hash lock across the durable
     * DELETING transition AND deletion; active streams with owners are protected.
     */
    internal suspend fun deleteTorrentPayload(torrentHash: String): Boolean = withContext(Dispatchers.IO) {
        when (deleteClaimedPayload(torrentHash)) {
            is DeleteResult.Deleted -> true
            else -> false
        }
    }

    /**
     * Quota eviction. Planning is serialized under [managerLock]; each candidate is then deleted
     * under its own per-hash lock with a fresh state re-check, so a concurrent acquire always wins.
     */
    suspend fun evictIfNeeded(retainedQuotaBytes: Long): Long = withContext(Dispatchers.IO) {
        if (retainedQuotaBytes <= 0) {
            return@withContext 0L // Unlimited quota
        }
        if (!destructionAllowed()) return@withContext 0L

        synchronized(managerLock) {
            val dormantEntries = store.getAll().values.filter {
                it.state == TorrentCacheState.RETAINED || it.state == TorrentCacheState.EVICTABLE
            }
            var currentDormantBytes = dormantEntries.sumOf { measuredSize(it) }
            if (currentDormantBytes <= retainedQuotaBytes) {
                return@synchronized 0L
            }

            // Order candidates: EVICTABLE first (by sequence ASC), then RETAINED (by sequence ASC)
            val sortedCandidates = dormantEntries.sortedWith(
                compareBy<TorrentCacheMetaEntry> { it.state != TorrentCacheState.EVICTABLE }
                    .thenBy { it.lastAccessSequence }
            )

            var evictedBytes = 0L
            for (entry in sortedCandidates) {
                if (currentDormantBytes <= retainedQuotaBytes) break
                val result = deleteClaimedPayload(entry.torrentHash)
                if (result is DeleteResult.Deleted) {
                    evictedBytes += result.bytes
                    currentDormantBytes -= result.bytes
                } else if (result is DeleteResult.Failed) {
                    break // persistence unhealthy or FS refused; stop destroying things
                }
            }
            evictedBytes
        }
    }

    suspend fun reconcileOnStartup(activeHashes: Set<String> = emptySet()): Long = withContext(Dispatchers.IO) {
        synchronized(managerLock) {
            // Fail closed: without trustworthy metadata we must not decide what is disposable.
            if (!store.isMetadataRecoverable) {
                Logger.log("TorrentCacheManager: metadata unrecoverable; skipping destructive reconciliation")
                return@synchronized 0L
            }

            var bytesCleaned = 0L
            val normalizedActive = activeHashes.map { it.uppercase() }.toSet()

            // Step 1: Finish interrupted DELETING entries (destructive → health-gated).
            if (store.isPersistenceHealthy) {
                store.getAll().values.filter { it.state == TorrentCacheState.DELETING }.forEach { entry ->
                    bytesCleaned += finishDeleting(entry.torrentHash)
                }
            } else {
                Logger.log("TorrentCacheManager: persistence unhealthy; deferring interrupted deletions")
            }

            // Step 2: Demote stale ACTIVE entries to RETAINED (protective metadata-only change).
            val normalizedActiveFinal = normalizedActive
            store.getAll().values.filter { it.state == TorrentCacheState.ACTIVE }.forEach { entry ->
                if (!normalizedActiveFinal.contains(entry.torrentHash)) {
                    demoteToRetained(entry.torrentHash)
                }
            }

            // Step 3: NO orphan sweep of unclaimed directories. Physical paths not claimed by
            // metadata are never destroyed automatically (legacy flat-layout data included).
            store.flushSync()
            bytesCleaned
        }
    }

    suspend fun clearTorrentCache(): ClearCacheResult = withContext(Dispatchers.IO) {
        synchronized(managerLock) {
            if (!destructionAllowed()) {
                Logger.log("TorrentCacheManager: refusing destructive clear (unrecoverable/unhealthy)")
                return@synchronized ClearCacheResult(0L, 0L, 0, 0)
            }

            var freedBytes = 0L
            var skippedActiveBytes = 0L
            var removedCount = 0
            var failedCount = 0

            val allEntries = store.getAll().values.toList()

            // Only claimed payloads are considered; unclaimed directories are left untouched.
            allEntries.forEach { entry ->
                val isActiveWithOwners = entry.state == TorrentCacheState.ACTIVE && entry.owners.isNotEmpty()
                if (isActiveWithOwners) {
                    skippedActiveBytes += measuredSize(entry)
                    return@forEach
                }
                when (val result = deleteClaimedPayload(entry.torrentHash)) {
                    is DeleteResult.Deleted -> {
                        freedBytes += result.bytes
                        removedCount++
                    }
                    is DeleteResult.Failed -> failedCount++
                    else -> { /* Missing: nothing claimed on disk */ }
                }
            }

            store.flushSync()
            ClearCacheResult(
                bytesFreed = freedBytes,
                activeBytesSkipped = skippedActiveBytes,
                entriesRemoved = removedCount,
                failedEntries = failedCount
            )
        }
    }

    suspend fun cleanTransientFiles(olderThanMs: Long = DEFAULT_TRANSIENT_EXPIRY_MS): Long = withContext(Dispatchers.IO) {
        var freed = 0L
        val cutoff = timeProvider() - olderThanMs
        val targetDirs = setOfNotNull(context?.cacheDir, cacheDir)

        targetDirs.forEach { dir ->
            dir.listFiles()?.forEach { file ->
                val name = file.name
                val isTransientCandidate = name.startsWith("temp") && name.endsWith(".torrent") ||
                        name.startsWith("hls_dl_") ||
                        name.startsWith("aria_dl_") ||
                        name.startsWith("hls_parts_") ||
                        name.startsWith("temp_install_") ||
                        name.startsWith("server_sub_") ||
                        name.startsWith("online_subtitle_") ||
                        name.startsWith("local_sub_") ||
                        name.startsWith("stream_novel_") ||
                        name.startsWith("mpv_screenshot_")

                if (isTransientCandidate && file.lastModified() < cutoff) {
                    val size = if (file.isDirectory) measureDirectorySize(file) else file.length()
                    if (deleteDirectoryRecursively(file)) {
                        freed += size
                    }
                }
            }
        }
        freed
    }

    fun getStorageBreakdown(): CacheStorageBreakdown {
        val allEntries = store.getAll().values
        val activeTorrentBytes = allEntries.filter { it.state == TorrentCacheState.ACTIVE }
            .sumOf { measuredSize(it) }
        val retainedTorrentBytes = allEntries.filter { it.state != TorrentCacheState.ACTIVE }
            .sumOf { measuredSize(it) }
        val totalTorrentCacheBytes = measureDirectorySize(cacheDir)

        val rootCache = context?.cacheDir ?: cacheDir.parentFile
        var subBytes = 0L
        var networkBytes = 0L
        var imageBytes = 0L

        rootCache?.listFiles()?.forEach { file ->
            val name = file.name
            if (name.contains("sub")) {
                subBytes += measureDirectorySize(file)
            } else if (name == "network_cache") {
                networkBytes += measureDirectorySize(file)
            } else if (name == "image_manager_disk_cache" || name.contains("glide") || name.contains("coil")) {
                imageBytes += measureDirectorySize(file)
            }
        }

        val totalCache = measureDirectorySize(rootCache)

        return CacheStorageBreakdown(
            torrentCacheBytes = totalTorrentCacheBytes,
            imageCacheBytes = imageBytes,
            subtitleCacheBytes = subBytes,
            networkCacheBytes = networkBytes,
            totalCacheBytes = totalCache,
            activeTorrentBytes = activeTorrentBytes,
            retainedTorrentBytes = retainedTorrentBytes
        )
    }

    /**
     * Canonical physical location for a torrent's payload: `<cacheDir>/<INFOHASH>`.
     * The relative path MUST equal the normalized hash — non-canonical paths are rejected
     * (CP1-07); there are no legitimate nested/legacy claims in the v2 layout.
     */
    fun getPayloadDirectory(torrentHash: String, relativePath: String = torrentHash.uppercase()): File {
        val normHash = normalizeInfoHash(torrentHash)
        require(relativePath.equals(normHash, ignoreCase = true)) {
            "non-canonical payload path rejected: $relativePath (expected $normHash)"
        }
        return File(cacheDir, normHash)
    }

    /**
     * Explicit metadata repair/reset (CP1v6-01): quarantines untrusted metadata and re-initializes
     * an empty TRUSTED primary. Physical payloads are preserved as unclaimed bytes; destructive
     * ownership is never inferred from quarantined material. Returns persistence success.
     */
    fun repairMetadata(): Boolean = store.performMetadataReset()

    fun measureDirectorySize(fileOrDir: File?): Long {
        if (fileOrDir == null || !fileOrDir.exists()) return 0L
        if (fileOrDir.isFile) return fileOrDir.length()
        var size = 0L
        val children = fileOrDir.listFiles() ?: return 0L
        for (child in children) {
            size += if (child.isDirectory) measureDirectorySize(child) else child.length()
        }
        return size
    }

    // ---------- internal helpers ----------

    /** Size against the CLAIMED path only (never guesses). */
    private fun measuredSize(entry: TorrentCacheMetaEntry): Long {
        return try {
            measureDirectorySize(getPayloadDirectory(entry.torrentHash, entry.payloadRelativePath))
        } catch (_: Exception) {
            0L
        }
    }

    private fun demoteToRetained(torrentHash: String) {
        val lock = getHashLock(torrentHash.uppercase())
        synchronized(lock) {
            val fresh = store.get(torrentHash.uppercase()) ?: return
            if (fresh.state != TorrentCacheState.ACTIVE) return
            val demoted = fresh.copy(
                owners = emptySet(),
                state = TorrentCacheState.RETAINED,
                lastAccessSequence = store.nextSequence(),
                lastAccessEpochMs = timeProvider(),
                sizeBytes = measuredSize(fresh)
            )
            store.put(demoted, immediateFlush = false)
        }
    }

    /**
     * Finish an interrupted DELETING entry under its per-hash lock. The claim already exists in
     * durable metadata, so finishing it is safe even though the originating process died.
     * Returns bytes cleaned (0 if nothing to do / not allowed).
     */
    private fun finishDeleting(torrentHash: String): Long {
        if (!destructionAllowed()) return 0L
        val normHash = torrentHash.uppercase()
        val lock = getHashLock(normHash)
        synchronized(lock) {
            val entry = store.get(normHash) ?: return 0L
            if (entry.state != TorrentCacheState.DELETING) return 0L
            val dir = try {
                getPayloadDirectory(normHash, entry.payloadRelativePath)
            } catch (_: Exception) {
                return 0L
            }
            if (!dir.exists()) {
                val (_, removalDurable) = store.removeDurable(normHash)
                if (!removalDurable) {
                    // Committed primary still says DELETING; keep memory aligned (CP1v3-04).
                    store.put(entry, immediateFlush = false)
                }
                return 0L
            }
            val size = measureDirectorySize(dir)
            if (runDeletion(dir)) {
                val (_, removalDurable) = store.removeDurable(normHash)
                if (!removalDurable) {
                    // Payload gone; keep in-memory DELETING marker to match committed state.
                    store.put(entry, immediateFlush = false)
                }
                return size
            }
            return 0L
        }
    }

    /**
     * Delete the payload CLAIMED by current durable metadata for `torrentHash`.
     *
     * Protocol (all under the per-hash lock):
     *   1. require an existing claim (CP1-02 — no synthetic ownership);
     *   2. protect ACTIVE-with-owners;
     *   3. transition to DELETING and persist DURABLY; abort + revert on failure (CP1-03);
     *   4. delete the tree while still holding the lock;
     *   5. durably remove the metadata entry on success; keep the DELETING marker on partial
     *      failure so a later healthy startup finishes the job.
     */
    internal fun deleteClaimedPayload(torrentHash: String): DeleteResult {
        if (!destructionAllowed()) return DeleteResult.Failed
        val normHash = torrentHash.uppercase()
        val lock = getHashLock(normHash)
        synchronized(lock) {
            val entry = store.get(normHash) ?: return DeleteResult.Missing
            if (entry.state == TorrentCacheState.ACTIVE && entry.owners.isNotEmpty()) {
                return DeleteResult.Protected
            }
            val dir = try {
                getPayloadDirectory(normHash, entry.payloadRelativePath)
            } catch (_: Exception) {
                return DeleteResult.Missing
            }

            val previous = entry
            val deleting = previous.copy(
                state = TorrentCacheState.DELETING,
                owners = emptySet(),
                lastAccessSequence = store.nextSequence(),
                lastAccessEpochMs = timeProvider()
            )
            val persisted = store.putDurable(deleting)
            if (!persisted) {
                // Disk still holds `previous`; make memory match it again and refuse to touch files.
                store.put(previous, immediateFlush = true)
                Logger.log("TorrentCacheManager: DELETING transition not durable; aborting delete of $normHash")
                return DeleteResult.Failed
            }

            hooks?.beforePayloadDelete?.invoke(normHash)

            val size = measureDirectorySize(dir)
            val ok = !dir.exists() || runDeletion(dir)
            if (ok) {
                val (_, removalDurable) = store.removeDurable(normHash)
                if (!removalDurable) {
                    // Payload gone but cleanup not committed: restore the DELETING marker in
                    // memory (committed disk still holds it) so recovery stays consistent.
                    store.put(deleting, immediateFlush = false)
                }
                return DeleteResult.Deleted(size)
            }
            // Partial failure: keep the DELETING marker so a later healthy startup finishes.
            Logger.log("TorrentCacheManager: partial delete persisted for $normHash")
            return DeleteResult.Failed
        }
    }

    private fun runDeletion(dir: File): Boolean {
        val strategy = deleteStrategyOverride
        return if (strategy != null) strategy(dir) else deleteDirectoryWithRetry(dir, 3)
    }

    private fun deleteDirectoryRecursively(fileOrDir: File): Boolean {
        if (!fileOrDir.exists()) return true
        if (fileOrDir.isDirectory) {
            fileOrDir.listFiles()?.forEach { deleteDirectoryRecursively(it) }
        }
        return fileOrDir.delete()
    }

    private fun deleteDirectoryWithRetry(fileOrDir: File, maxAttempts: Int = 3): Boolean {
        if (!fileOrDir.exists()) return true
        val backoffs = listOf(200L, 500L, 1000L)
        for (i in 0 until maxAttempts) {
            if (deleteDirectoryRecursively(fileOrDir)) {
                return true
            }
            if (i < maxAttempts - 1) {
                try {
                    Thread.sleep(backoffs.getOrElse(i) { 500L })
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
        }
        return !fileOrDir.exists()
    }
}

/** Deterministic test hooks for the cache manager (CP1-04/CP1v2-05). All callbacks are optional. */
class CacheHooks {
    /**
     * Invoked inside the per-hash lock immediately before the filesystem deletion of the payload
     * owned by `hash`. Tests use this as a barrier to hold a deletion mid-flight.
     */
    @Volatile
    var beforePayloadDelete: ((String) -> Unit)? = null

    /**
     * Invoked by [TorrentCacheManager.acquireLease] just BEFORE it attempts to take the per-hash
     * lock (CP1v2-05). Lets tests deterministically confirm an acquisition has reached the lock
     * attempt and is blocked by an in-flight deletion.
     */
    @Volatile
    var beforeAcquireLock: ((String) -> Unit)? = null
}
