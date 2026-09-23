package ani.dantotsu.torrent

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock

/**
 * Single-writer metadata store for the torrent cache.
 *
 * Durability contract (REPO_REVIEW §3.0b + CP1 review fixes + CP1v2-01 commit rule):
 *  - All disk writes (sync or debounced) are serialized through one [writeLock].
 *  - Persistence is tmp-file + atomic move; the previous good file is never deleted first.
 *  - COMMIT RULE: a valid primary is the last committed state and wins whenever it parses.
 *    The `.tmp` is uncommitted material and is used only as recovery when the primary is
 *    missing/unreadable; a stale tmp alongside a valid primary is discarded.
 *  - [flushSync] reports persistence success; failed writes flip [isPersistenceHealthy] to false
 *    and retain the tmp so recovery remains possible (CP1-03).
 *  - If no valid metadata can be loaded while files exist, [isMetadataRecoverable] is false and
 *    callers MUST NOT perform destructive reconciliation (fail-closed).
 */
class TorrentCacheStore(
    private val baseDir: File,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val debounceMs: Long = 250L
) {
    private val metaFile = File(baseDir, "torrent_cache_meta.json")
    private val metaTmpFile = File(baseDir, "torrent_cache_meta.json.tmp")
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = false
        encodeDefaults = true
    }

    private val entries = ConcurrentHashMap<String, TorrentCacheMetaEntry>()
    private val sequenceCounter = AtomicLong(1L)
    private val writeLock = ReentrantLock()
    private val scope = CoroutineScope(ioDispatcher)
    private var debounceJob: Job? = null

    @Volatile
    var isMetadataRecoverable: Boolean = true
        private set

    @Volatile
    var isPersistenceHealthy: Boolean = true
        private set

    /**
     * Test-only deterministic failure injection: invoked before each persistence attempt; when it
     * returns true the write fails AFTER the tmp has been written (models move-time failure).
     */
    @Volatile
    internal var writeFailurePolicyForTesting: (() -> Boolean)? = null

    init {
        loadFromDisk()
    }

    private fun parseMeta(content: String): TorrentCacheMetaWrapper? {
        return try {
            json.decodeFromString(TorrentCacheMetaWrapper.serializer(), content)
        } catch (_: Exception) {
            null
        }
    }

    private fun adopt(wrapper: TorrentCacheMetaWrapper) {
        entries.clear()
        wrapper.entries.forEach { entry ->
            entries[entry.torrentHash.uppercase()] = entry
        }
        val maxSeq = entries.values.maxOfOrNull { it.lastAccessSequence } ?: 0L
        sequenceCounter.set(maxOf(maxSeq + 1, 1L))
    }

    private fun loadFromDisk() {
        if (!baseDir.exists()) {
            baseDir.mkdirs()
        }

        // Commit rule (CP1v2-01 + CP1v5-01): a VALID PRIMARY is the last committed state and the
        // ONLY source that may authorize destructive DELETING recovery. The .tmp is uncommitted
        // material — it may be newer (crash between tmp-write and move) or represent an ABORTED
        // transition (move failed, caller reverted).
        //   - primary parses            → committed; adopted; stale tmp discarded.
        //   - primary missing/unreadable, tmp parses → tmp is adopted into MEMORY for accounting
        //     only, but `isMetadataRecoverable` stays FALSE: uncommitted snapshots never authorize
        //     destructive reconciliation (fail closed).
        val primaryContent = if (metaFile.exists()) {
            try {
                metaFile.readText(Charsets.UTF_8)
            } catch (_: Exception) {
                null
            }
        } else null
        val primaryWrapper = primaryContent?.let { parseMeta(it) }

        val tmpContent = if (metaTmpFile.exists()) {
            try {
                metaTmpFile.readText(Charsets.UTF_8)
            } catch (_: Exception) {
                null
            }
        } else null
        val tmpWrapper = tmpContent?.let { parseMeta(it) }

        when {
            primaryWrapper != null -> {
                adopt(primaryWrapper)
                isMetadataRecoverable = true
                // Stale/uncommitted tmp material is discarded once a committed primary exists.
                writeLock.lock()
                try {
                    if (metaTmpFile.exists()) metaTmpFile.delete()
                } finally {
                    writeLock.unlock()
                }
            }
            tmpWrapper != null -> {
                // Primary missing/unreadable: adopt the tmp snapshot for ACCOUNTING only.
                // It is uncommitted by definition — it must not authorize destructive recovery
                // (a valid-looking tmp can carry an ABORTED DELETING transition; CP1v5-01).
                adopt(tmpWrapper)
                isMetadataRecoverable = false
                android.util.Log.w(
                    "TorrentCacheStore",
                    "metadata recovered from uncommitted .tmp; destructive reconciliation disabled"
                )
            }
            metaFile.exists() || metaTmpFile.exists() -> {
                // Files exist but nothing parses: fail closed rather than treating payloads as
                // unowned/deletable.
                entries.clear()
                isMetadataRecoverable = false
            }
            else -> {
                // Fresh install / nothing persisted yet.
                entries.clear()
                isMetadataRecoverable = true
            }
        }
    }

    fun nextSequence(): Long = sequenceCounter.getAndIncrement()

    fun get(torrentHash: String): TorrentCacheMetaEntry? {
        return entries[torrentHash.uppercase()]
    }

    fun getAll(): Map<String, TorrentCacheMetaEntry> {
        return HashMap(entries)
    }

    /** In-memory upsert with debounced durability (compatibility API). */
    fun put(entry: TorrentCacheMetaEntry, immediateFlush: Boolean = false) {
        upsert(entry)
        if (immediateFlush) {
            flushSync()
        } else {
            scheduleDebouncedFlush()
        }
    }

    /**
     * Durable upsert: persists synchronously and reports success (CP1-03). Destructive state
     * machines MUST use this and abort their filesystem work on `false`.
     */
    fun putDurable(entry: TorrentCacheMetaEntry): Boolean {
        upsert(entry)
        return flushSync()
    }

    /** In-memory removal with debounced durability (compatibility API). */
    fun remove(torrentHash: String, immediateFlush: Boolean = false): TorrentCacheMetaEntry? {
        val removed = removeInMemory(torrentHash)
        if (removed != null) {
            if (immediateFlush) {
                flushSync()
            } else {
                scheduleDebouncedFlush()
            }
        }
        return removed
    }

    /** Durable removal: persists synchronously and reports whether the removal was durable. */
    fun removeDurable(torrentHash: String): Pair<TorrentCacheMetaEntry?, Boolean> {
        val removed = removeInMemory(torrentHash)
        val durable = flushSync()
        return Pair(removed, durable)
    }

    private fun upsert(entry: TorrentCacheMetaEntry) {
        val norm = entry.copy(torrentHash = entry.torrentHash.uppercase())
        entries[norm.torrentHash] = norm
        sequenceCounter.updateAndGet { current -> maxOf(current, norm.lastAccessSequence + 1) }
    }

    private fun removeInMemory(torrentHash: String): TorrentCacheMetaEntry? {
        return entries.remove(torrentHash.uppercase())
    }

    private fun scheduleDebouncedFlush() {
        synchronized(this) {
            debounceJob?.cancel()
            debounceJob = scope.launch {
                delay(debounceMs)
                flushSync()
            }
        }
    }

    /**
     * Persist current in-memory state now. Cancels any pending debounced write first (the newest
     * snapshot supersedes it), serializes against other writers, and reports success.
     */
    fun flushSync(): Boolean {
        // Trust gate (CP1v6-01): an untrusted/accounting-only snapshot must never be serialized
        // into a trusted primary by ordinary writes. Only an explicit [performMetadataReset]
        // restores writability after metadata loss.
        if (!isMetadataRecoverable) {
            android.util.Log.w("TorrentCacheStore", "flushSync refused: metadata unrecoverable")
            return false
        }
        synchronized(this) {
            debounceJob?.cancel()
            debounceJob = null
        }
        writeLock.lock()
        try {
            return writeToDisk()
        } finally {
            writeLock.unlock()
        }
    }

    /**
     * Explicit repair path (CP1v6-01): quarantines the unreadable/uncommitted metadata files,
     * preserves ALL physical payload directories as unclaimed bytes (never deleted here), and
     * re-initializes an EMPTY trusted primary. Destructive ownership is never inferred from the
     * quarantined material.
     */
    fun performMetadataReset(): Boolean {
        writeLock.lock()
        try {
            val stamp = System.currentTimeMillis()
            try {
                if (metaFile.exists()) {
                    metaFile.renameTo(File(baseDir, "torrent_cache_meta.corrupt-$stamp.json"))
                }
                if (metaTmpFile.exists()) {
                    metaTmpFile.renameTo(File(baseDir, "torrent_cache_meta.corrupt-$stamp.json.tmp"))
                }
            } catch (_: Exception) {}
            entries.clear()
            sequenceCounter.set(1L)
            isMetadataRecoverable = true
            val ok = writeToDisk()
            return ok
        } finally {
            writeLock.unlock()
        }
    }

    private fun writeToDisk(): Boolean {
        // Caller must hold writeLock.
        try {
            if (!baseDir.exists()) {
                baseDir.mkdirs()
            }
            val wrapper = TorrentCacheMetaWrapper(
                version = 1,
                entries = entries.values.toList()
            )
            val jsonString = json.encodeToString(TorrentCacheMetaWrapper.serializer(), wrapper)
            metaTmpFile.writeText(jsonString, Charsets.UTF_8)

            // TEST HOOK (CP1v3-03): evaluated AFTER the tmp snapshot was fully written and BEFORE
            // it replaces the primary — the exact crash/failure boundary of the protocol. A fail
            // here leaves a valid uncommitted tmp on disk, exactly like a real move-time crash.
            if (writeFailurePolicyForTesting?.invoke() == true) {
                isPersistenceHealthy = false
                android.util.Log.e("TorrentCacheStore", "injected move-time persistence failure")
                return false
            }

            val moved = try {
                Files.move(
                    metaTmpFile.toPath(),
                    metaFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE
                )
                true
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                // Fall back to a plain replace; still never deletes the target up-front.
                Files.move(
                    metaTmpFile.toPath(),
                    metaFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING
                )
                true
            } catch (e: Exception) {
                android.util.Log.e("TorrentCacheStore", "meta move failed: ${e.message}")
                false
            }
            if (moved) {
                if (metaTmpFile.exists()) {
                    metaTmpFile.delete()
                }
                isPersistenceHealthy = true
                return true
            }
            // Move failed: keep the tmp so loadFromDisk can recover it later.
            isPersistenceHealthy = false
            return false
        } catch (e: Exception) {
            e.printStackTrace()
            isPersistenceHealthy = false
            return false
        }
    }
}
