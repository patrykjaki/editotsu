package ani.dantotsu.torrent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import java.util.concurrent.atomic.AtomicInteger

class TorrentCacheUnitTest {

    private lateinit var tempDir: File
    private var simulatedTime = 1_700_000_000_000L

    /** Deterministic valid 40-hex info-hash fixture derived from a readable seed (CP1v2-03). */
    private fun th(seed: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(seed.toByteArray())
        return digest.take(20).joinToString("") { "%02X".format(it) }
    }

    @Before
    fun setup() {
        tempDir = Files.createTempDirectory("torrent_cache_test").toFile()
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    // ---------- core store behaviour ----------

    @Test
    fun testDurablePersistenceAndAtomicSave() = runBlocking {
        val store1 = TorrentCacheStore(tempDir)
        val entry = TorrentCacheMetaEntry(
            torrentHash = th("ABCDEF123456"),
            owners = setOf("owner_1"),
            state = TorrentCacheState.ACTIVE,
            lastAccessSequence = store1.nextSequence(),
            lastAccessEpochMs = 12345L,
            payloadRelativePath = th("ABCDEF123456"),
            sizeBytes = 1024L
        )
        assertTrue(store1.putDurable(entry))

        val store2 = TorrentCacheStore(tempDir)
        val restored = store2.get(th("ABCDEF123456"))
        assertNotNull(restored)
        assertEquals(th("ABCDEF123456"), restored?.torrentHash)
        assertEquals(TorrentCacheState.ACTIVE, restored?.state)
        assertTrue(restored?.owners?.contains("owner_1") == true)
    }

    @Test
    fun testLogicalSequenceOrdering() = runBlocking {
        val store = TorrentCacheStore(tempDir)
        val seq1 = store.nextSequence()
        val seq2 = store.nextSequence()
        val seq3 = store.nextSequence()
        assertTrue(seq1 < seq2)
        assertTrue(seq2 < seq3)
    }

    @Test
    fun testSequenceCounterSurvivesRestart() = runBlocking {
        val store1 = TorrentCacheStore(tempDir)
        store1.put(
            TorrentCacheMetaEntry(
                torrentHash = th("TEST1"),
                owners = emptySet(),
                state = TorrentCacheState.RETAINED,
                lastAccessSequence = 42L,
                lastAccessEpochMs = simulatedTime,
                payloadRelativePath = th("TEST1"),
                sizeBytes = 100L
            ),
            immediateFlush = true
        )
        val store2 = TorrentCacheStore(tempDir)
        assertTrue(store2.nextSequence() > 42L)
    }

    // ---------- lease lifecycle ----------

    @Test
    fun testIdempotentOwnerLeaseTracking() = runBlocking {
        val manager = TorrentCacheManager(null, tempDir, timeProvider = { simulatedTime })
        val h = th("HASH1")
        val entry1 = manager.acquireLease(h, "owner_A")
        assertEquals(1, entry1.owners.size)
        assertTrue(entry1.owners.contains("owner_A"))

        val entry2 = manager.acquireLease(h, "owner_A")
        assertEquals(1, entry2.owners.size)

        val entry3 = manager.acquireLease(h, "owner_B")
        assertEquals(2, entry3.owners.size)
        assertEquals(TorrentCacheState.ACTIVE, entry3.state)

        val entry4 = manager.releaseLease(h, "owner_A", keepRetained = true)
        assertEquals(1, entry4?.owners?.size)
        assertEquals(TorrentCacheState.ACTIVE, entry4?.state)

        val entry5 = manager.releaseLease(h, "owner_B", keepRetained = true)
        assertEquals(0, entry5?.owners?.size)
        assertEquals(TorrentCacheState.RETAINED, entry5?.state)
    }

    @Test
    fun testDeletingStateConcurrencyIsolation() = runBlocking {
        val manager = TorrentCacheManager(null, tempDir, timeProvider = { simulatedTime })
        val h = th("HASH_DEL")
        val payload = manager.getPayloadDirectory(h, h)
        payload.mkdirs()
        File(payload, "video.mkv").writeBytes(ByteArray(100))

        manager.acquireLease(h, "owner_1")
        manager.releaseLease(h, "owner_1", keepRetained = false)

        assertTrue(manager.deleteTorrentPayload(h))
        assertFalse(payload.exists())
        assertNull(manager.store.get(h))
    }

    @Test
    fun testAcquireLeaseOnCommittedDeletingAborts() = runBlocking {
        // A committed DELETING entry cannot be silently reclaimed: reclaim must be durable, and a
        // durable write against committed DELETING is refused by design (CP1v2-01).
        val store = TorrentCacheStore(tempDir)
        val dir = File(tempDir, th("STUCK_DELETE"))
        dir.mkdirs()
        File(dir, "temp.bin").writeBytes(ByteArray(100))
        store.put(
            TorrentCacheMetaEntry(
                torrentHash = th("STUCK_DELETE"),
                owners = emptySet(),
                state = TorrentCacheState.DELETING,
                lastAccessSequence = store.nextSequence(),
                lastAccessEpochMs = simulatedTime,
                payloadRelativePath = th("STUCK_DELETE"),
                sizeBytes = 100L
            ),
            immediateFlush = true
        )

        val manager = TorrentCacheManager(null, tempDir, store, timeProvider = { simulatedTime })
        var threw = false
        try {
            manager.acquireLease(th("STUCK_DELETE"), "new_owner")
        } catch (_: CachePersistenceException) {
            threw = true
        }
        assertTrue(threw)
        assertEquals(TorrentCacheState.DELETING, store.get(th("STUCK_DELETE"))?.state)

        // Recovery finishes the interrupted deletion; afterwards claiming works again.
        manager.reconcileOnStartup(activeHashes = emptySet())
        assertFalse(dir.exists())
        val acquired = manager.acquireLease(th("STUCK_DELETE"), "new_owner")
        assertEquals(TorrentCacheState.ACTIVE, acquired.state)
    }

    // ---------- quota / eviction ----------

    @Test
    fun testActiveTorrentImmunityUnderQuotaPressure() = runBlocking {
        val manager = TorrentCacheManager(null, tempDir, timeProvider = { simulatedTime })

        val activeDir = manager.getPayloadDirectory(th("ACTIVE_HASH"), th("ACTIVE_HASH"))
        activeDir.mkdirs()
        File(activeDir, "stream.mkv").writeBytes(ByteArray(5000))
        manager.acquireLease(th("ACTIVE_HASH"), "live_player")

        val retainedDir = manager.getPayloadDirectory(th("RETAINED_HASH"), th("RETAINED_HASH"))
        retainedDir.mkdirs()
        File(retainedDir, "dormant.mkv").writeBytes(ByteArray(5000))
        manager.acquireLease(th("RETAINED_HASH"), "old_player")
        manager.releaseLease(th("RETAINED_HASH"), "old_player", keepRetained = true)

        val freed = manager.evictIfNeeded(retainedQuotaBytes = 1000L)
        assertTrue(freed > 0)

        assertTrue("Active payload must be preserved", activeDir.exists())
        assertEquals(TorrentCacheState.ACTIVE, manager.store.get(th("ACTIVE_HASH"))?.state)
        assertFalse("Retained payload must be evicted", retainedDir.exists())
    }

    @Test
    fun testConcurrentAcquireReleaseEvictStress() = runBlocking {
        val manager = TorrentCacheManager(null, tempDir, timeProvider = { simulatedTime })
        val jobs = (1..50).map { i ->
            async(Dispatchers.IO) {
                val hash = th("CONCURRENT_$i")
                val dir = manager.getPayloadDirectory(hash, hash)
                dir.mkdirs()
                File(dir, "data.bin").writeBytes(ByteArray(100))

                manager.acquireLease(hash, "owner_$i")
                manager.releaseLease(hash, "owner_$i", keepRetained = true)
                manager.evictIfNeeded(retainedQuotaBytes = 5000L)
            }
        }
        jobs.awaitAll()
        Unit
    }

    // ---------- startup reconciliation ----------

    @Test
    fun testStartupReconciliationWithInterruptedDeletion() = runBlocking {
        val store = TorrentCacheStore(tempDir)
        val payload = File(tempDir, th("INTERRUPTED_HASH"))
        payload.mkdirs()
        File(payload, "chunk.dat").writeBytes(ByteArray(500))

        store.put(
            TorrentCacheMetaEntry(
                torrentHash = th("INTERRUPTED_HASH"),
                owners = emptySet(),
                state = TorrentCacheState.DELETING,
                lastAccessSequence = store.nextSequence(),
                lastAccessEpochMs = simulatedTime,
                payloadRelativePath = th("INTERRUPTED_HASH"),
                sizeBytes = 500L
            ),
            immediateFlush = true
        )

        val manager = TorrentCacheManager(null, tempDir, store, timeProvider = { simulatedTime })
        val cleaned = manager.reconcileOnStartup(activeHashes = emptySet())
        assertTrue(cleaned > 0)
        assertFalse(payload.exists())
        assertNull(manager.store.get(th("INTERRUPTED_HASH")))
    }

    @Test
    fun testStartupReconciliationPreservesActiveSessions() = runBlocking {
        val manager = TorrentCacheManager(null, tempDir, timeProvider = { simulatedTime })
        val h = th("ACTIVE_SESSION")
        val dirA = manager.getPayloadDirectory(h, h)
        dirA.mkdirs()
        File(dirA, "live.mkv").writeBytes(ByteArray(100))

        manager.acquireLease(h, "session_owner")

        manager.reconcileOnStartup(activeHashes = setOf(h))
        assertTrue(dirA.exists())
        assertEquals(TorrentCacheState.ACTIVE, manager.store.get(h)?.state)
    }

    @Test
    fun testStaleActiveDemotedOnStartup() = runBlocking {
        val store = TorrentCacheStore(tempDir)
        val h = th("KILLED_PROCESS_HASH")
        val dir = File(tempDir, h)
        dir.mkdirs()
        File(dir, "ep.mkv").writeBytes(ByteArray(500))

        store.put(
            TorrentCacheMetaEntry(
                torrentHash = h,
                owners = setOf("dead_process"),
                state = TorrentCacheState.ACTIVE,
                lastAccessSequence = 10L,
                lastAccessEpochMs = simulatedTime,
                payloadRelativePath = h,
                sizeBytes = 500L
            ),
            immediateFlush = true
        )

        val manager = TorrentCacheManager(null, tempDir, store, timeProvider = { simulatedTime })
        manager.reconcileOnStartup(activeHashes = emptySet())

        val entry = store.get(h)
        assertNotNull(entry)
        assertEquals(TorrentCacheState.RETAINED, entry?.state)
        assertTrue(entry?.owners?.isEmpty() == true)
        assertTrue((entry?.lastAccessSequence ?: 0L) > 10L)
    }

    // ---------- transient sweeper ----------

    @Test
    fun testTransientSweeperAgeThreshold() = runBlocking {
        val manager = TorrentCacheManager(null, tempDir, timeProvider = { simulatedTime })
        val oldTemp = File(tempDir, "temp_old.torrent")
        oldTemp.writeBytes(ByteArray(50))
        oldTemp.setLastModified(simulatedTime - 100_000_000L)

        val newTemp = File(tempDir, "temp_new.torrent")
        newTemp.writeBytes(ByteArray(50))
        newTemp.setLastModified(simulatedTime - 1000L)

        val freed = manager.cleanTransientFiles(olderThanMs = 86_400_000L)
        assertTrue(freed > 0)
        assertFalse(oldTemp.exists())
        assertTrue(newTemp.exists())
    }

    // ---------- manual clear ----------

    @Test
    fun testManualClearCacheSkipsActive() = runBlocking {
        val manager = TorrentCacheManager(null, tempDir, timeProvider = { simulatedTime })
        val hAct = th("ACTIVE_EP")
        val activeDir = manager.getPayloadDirectory(hAct, hAct)
        activeDir.mkdirs()
        File(activeDir, "live.mp4").writeBytes(ByteArray(2000))
        manager.acquireLease(hAct, "live_stream")

        val hDorm = th("DORMANT_EP")
        val dormantDir = manager.getPayloadDirectory(hDorm, hDorm)
        dormantDir.mkdirs()
        File(dormantDir, "old.mp4").writeBytes(ByteArray(3000))
        manager.acquireLease(hDorm, "old_stream")
        manager.releaseLease(hDorm, "old_stream", keepRetained = true)

        val result = manager.clearTorrentCache()
        assertTrue(result.bytesFreed > 0)
        assertTrue(result.activeBytesSkipped > 0)
        assertTrue(activeDir.exists())
        assertFalse(dormantDir.exists())
    }

    // ---------- §3.0c real-layout contract tests ----------

    @Test
    fun testContractMultiFileForeignRootSurvivesReconcile() = runBlocking {
        val hash = th("multifile")
        val payloadRoot = File(tempDir, hash)
        val showDir = File(payloadRoot, "Some.Show.S01")
        showDir.mkdirs()
        File(showDir, "ep1.mkv").writeBytes(ByteArray(4096))
        File(showDir, "ep2.mkv").writeBytes(ByteArray(4096))

        val store = TorrentCacheStore(tempDir)
        store.put(
            TorrentCacheMetaEntry(
                torrentHash = hash,
                owners = setOf("dead_session"),
                state = TorrentCacheState.ACTIVE,
                lastAccessSequence = store.nextSequence(),
                lastAccessEpochMs = simulatedTime,
                payloadRelativePath = hash,
                sizeBytes = 8192L
            ),
            immediateFlush = true
        )
        val manager = TorrentCacheManager(null, tempDir, store, timeProvider = { simulatedTime })

        manager.reconcileOnStartup(activeHashes = emptySet())

        assertTrue(showDir.exists())
        assertTrue(File(showDir, "ep1.mkv").exists())
        assertEquals(TorrentCacheState.RETAINED, store.get(hash)?.state)

        val result = manager.clearTorrentCache()
        assertTrue(result.bytesFreed >= 8192L)
        assertFalse(showDir.exists())
    }

    @Test
    fun testContractFlatSingleFileAccountedAndEvictable() = runBlocking {
        val hash = th("flatfile")
        val payloadRoot = File(tempDir, hash)
        payloadRoot.mkdirs()
        val movie = File(payloadRoot, "movie.mkv")
        movie.writeBytes(ByteArray(2048))

        val manager = TorrentCacheManager(null, tempDir, timeProvider = { simulatedTime })
        val lease = manager.acquireLease(hash, "session_$hash")
        assertTrue(lease.sizeBytes >= 2048L)
        manager.releaseLease(hash, "session_$hash", keepRetained = true)

        val freed = manager.evictIfNeeded(retainedQuotaBytes = 100L)
        assertTrue(freed >= 2048L)
        assertFalse(payloadRoot.exists())
        assertNull(manager.store.get(hash))
    }

    @Test
    fun testContractUnclaimedDirectoriesAreNeverDestroyed() = runBlocking {
        val legacyShow = File(tempDir, "Legacy.Unmapped.Show.S01")
        legacyShow.mkdirs()
        File(legacyShow, "data.mkv").writeBytes(ByteArray(1024))

        val manager = TorrentCacheManager(null, tempDir, timeProvider = { simulatedTime })
        val claimedDir = manager.getPayloadDirectory(th("CLAIMED_EVICT"), th("CLAIMED_EVICT"))
        claimedDir.mkdirs()
        File(claimedDir, "old.bin").writeBytes(ByteArray(4096))
        manager.acquireLease(th("CLAIMED_EVICT"), "old")
        manager.releaseLease(th("CLAIMED_EVICT"), "old", keepRetained = true)

        manager.reconcileOnStartup(activeHashes = emptySet())
        assertTrue(legacyShow.exists())

        val freed = manager.evictIfNeeded(retainedQuotaBytes = 100L)
        assertTrue(freed >= 4096L)
        assertFalse(claimedDir.exists())
        assertTrue(legacyShow.exists())

        manager.clearTorrentCache()
        assertTrue(legacyShow.exists())
    }

    @Test
    fun testContractCorruptedMetadataDisablesDestruction() = runBlocking {
        val survivor = File(tempDir, "Whatever.Torrent.Root")
        survivor.mkdirs()
        File(survivor, "keep.mkv").writeBytes(ByteArray(999))

        File(tempDir, "torrent_cache_meta.json").writeText("{ this is not json !!!")

        val store = TorrentCacheStore(tempDir)
        assertFalse(store.isMetadataRecoverable)

        val manager = TorrentCacheManager(null, tempDir, store, timeProvider = { simulatedTime })

        assertEquals(0L, manager.reconcileOnStartup(activeHashes = emptySet()))
        assertTrue(survivor.exists())

        val cleared = manager.clearTorrentCache()
        assertEquals(0L, cleared.bytesFreed)
        assertTrue(survivor.exists())

        assertEquals(0L, manager.evictIfNeeded(retainedQuotaBytes = 100L))
        assertTrue(survivor.exists())

        assertFalse(manager.deleteTorrentPayload(th("WHATEVER")))
        assertTrue(survivor.exists())
    }

    /**
     * CP1v5-01: a tmp snapshot is UNCOMMITTED. When the primary is absent, the tmp may be adopted
     * into memory for ACCOUNTING but must NOT authorize destructive reconciliation.
     */
    @Test
    fun testContractTmpRecoveredWhenPrimaryMissingIsNonDestructive() = runBlocking {
        val hash = th("TMP_RECOVER")
        val payload = File(tempDir, hash)
        payload.mkdirs()
        File(payload, "keep.mkv").writeBytes(ByteArray(400))

        val store1 = TorrentCacheStore(tempDir)
        store1.put(
            TorrentCacheMetaEntry(
                torrentHash = hash,
                owners = emptySet(),
                state = TorrentCacheState.RETAINED,
                lastAccessSequence = store1.nextSequence(),
                lastAccessEpochMs = simulatedTime,
                payloadRelativePath = hash,
                sizeBytes = 400L
            ),
            immediateFlush = true
        )
        val primary = File(tempDir, "torrent_cache_meta.json")
        val tmp = File(tempDir, "torrent_cache_meta.json.tmp")
        val content = primary.readText()
        primary.delete()
        tmp.writeText(content)

        val store2 = TorrentCacheStore(tempDir)
        assertFalse("Uncommitted tmp must not authorize destruction", store2.isMetadataRecoverable)
        assertNotNull("Tmp still usable for accounting", store2.get(hash))

        val manager = TorrentCacheManager(null, tempDir, store2, timeProvider = { simulatedTime })
        assertEquals(0L, manager.reconcileOnStartup(activeHashes = emptySet()))
        assertTrue("Payload must survive non-destructive recovery", payload.exists())
    }

    /** CP1v5-01 required test: corrupt primary + valid aborted DELETING tmp ⇒ no deletion. */
    @Test
    fun testCpV501CorruptPrimaryWithAbortedDeletingTmpDoesNotDelete() = runBlocking {
        val hash = th("cpv501")
        val payload = File(tempDir, hash)
        payload.mkdirs()
        File(payload, "alive.mkv").writeBytes(ByteArray(650))

        // Committed primary RETAINED becomes unreadable/corrupt…
        File(tempDir, "torrent_cache_meta.json").writeText("{ corrupt !!!")

        // …while an ABORTED DELETING tmp (move failed) remains fully valid on disk.
        val deletingJson =
            """{"version":1,"entries":[{"torrentHash":"$hash","owners":[],"state":"DELETING",""" +
            """"lastAccessSequence":999,"lastAccessEpochMs":${simulatedTime},"payloadRelativePath":"$hash",""" +
            """"sizeBytes":0,"schemaVersion":1}]}"""
        File(tempDir, "torrent_cache_meta.json.tmp").writeText(deletingJson)

        val store = TorrentCacheStore(tempDir)
        assertFalse(store.isMetadataRecoverable)

        val manager = TorrentCacheManager(null, tempDir, store, timeProvider = { simulatedTime })
        assertEquals(0L, manager.reconcileOnStartup(activeHashes = emptySet()))
        assertTrue("Resurrected uncommitted DELETING must not delete the payload", payload.exists())

        // Destructive manual paths stay disabled as well.
        val cleared = manager.clearTorrentCache()
        assertEquals(0L, cleared.bytesFreed)
        assertTrue(payload.exists())
    }

    /** CP1v5-01 required test: unreadable primary + valid DELETING tmp keeps reconciliation off. */
    @Test
    fun testCpV501UnreadablePrimaryKeepsReconciliationDisabled() = runBlocking {
        val hash = th("cpv501b")
        val payload = File(tempDir, hash)
        payload.mkdirs()
        File(payload, "f.bin").writeBytes(ByteArray(64))

        // Primary exists but is binary garbage (unreadable), tmp parses with DELETING.
        File(tempDir, "torrent_cache_meta.json").writeBytes(byteArrayOf(0, 1, 2, 3, 4, 5))
        val deletingJson =
            """{"version":1,"entries":[{"torrentHash":"$hash","owners":[],"state":"DELETING",""" +
            """"lastAccessSequence":5,"lastAccessEpochMs":${simulatedTime},"payloadRelativePath":"$hash",""" +
            """"sizeBytes":0,"schemaVersion":1}]}"""
        File(tempDir, "torrent_cache_meta.json.tmp").writeText(deletingJson)

        val store = TorrentCacheStore(tempDir)
        assertFalse(store.isMetadataRecoverable)

        val manager = TorrentCacheManager(null, tempDir, store, timeProvider = { simulatedTime })
        assertEquals(0L, manager.reconcileOnStartup(activeHashes = emptySet()))
        assertTrue(payload.exists())
    }

    @Test
    fun testContractPathTraversalRejected() {
        val manager = TorrentCacheManager(null, tempDir, timeProvider = { simulatedTime })
        try {
            manager.getPayloadDirectory(th("SAFE_HASH"), "../../evil")
            fail("Path traversal must be rejected")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun testContractNonCanonicalPayloadPathRejected() {
        val manager = TorrentCacheManager(null, tempDir, timeProvider = { simulatedTime })
        try {
            manager.getPayloadDirectory(th("SAFE_HASH"), th("SAFE_HASH") + "/nested")
            fail("Nested non-canonical path must be rejected")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun testContractAcquiredLeasesAreAlwaysCanonical() = runBlocking {
        val manager = TorrentCacheManager(null, tempDir, timeProvider = { simulatedTime })
        val seed = th("canonical")
        val lease = manager.acquireLease(seed.lowercase(), "owner")
        assertEquals(seed, lease.torrentHash)
        assertEquals(seed, lease.payloadRelativePath)
        assertEquals(
            File(tempDir, seed).absolutePath,
            manager.getPayloadDirectory(seed).absolutePath
        )
    }

    // ---------- CP1 round-1 regression tests ----------

    /** Unclaimed directory + no metadata ⇒ deletion refuses and data survives. */
    @Test
    fun testCp02DeleteWithoutClaimRejected() = runBlocking {
        val orphan = File(tempDir, th("ORPHAN_HASH"))
        orphan.mkdirs()
        File(orphan, "movie.mkv").writeBytes(ByteArray(1234))

        val manager = TorrentCacheManager(null, tempDir, timeProvider = { simulatedTime })
        val deleted = manager.deleteTorrentPayload(th("ORPHAN_HASH"))

        assertFalse(deleted)
        assertTrue(orphan.exists())
        assertNull(manager.store.get(th("ORPHAN_HASH")))
    }

    /**
     * Failed durable DELETING transition aborts before any filesystem deletion and restores the
     * prior in-memory state. Failure is injected at the after-tmp/before-move boundary.
     */
    @Test
    fun testCp03WriteFailureFailsClosedBeforeDeletion() = runBlocking {
        val manager = TorrentCacheManager(null, tempDir, timeProvider = { simulatedTime })
        val h = th("FAILFLUSH_HASH")
        val dir = manager.getPayloadDirectory(h, h)
        dir.mkdirs()
        File(dir, "keep.mkv").writeBytes(ByteArray(800))
        manager.acquireLease(h, "o")
        manager.releaseLease(h, "o", keepRetained = true)

        manager.store.writeFailurePolicyForTesting = { true } // fail every persistence attempt

        val deleted = manager.deleteTorrentPayload(h)
        assertFalse(deleted)
        assertTrue(dir.exists())
        assertEquals(TorrentCacheState.RETAINED, manager.store.get(h)?.state)

        manager.store.writeFailurePolicyForTesting = null
        assertTrue(manager.store.flushSync())
        assertTrue(manager.store.isPersistenceHealthy)
        assertTrue(manager.deleteTorrentPayload(h))
        assertFalse(dir.exists())
    }

    /**
     * Same-hash acquisition reaches the lock attempt (beforeAcquireLock barrier), then blocks
     * behind an in-flight deletion and completes only after it — never observing half-deleted data.
     */
    @Test
    fun testCp04aAcquireBlocksDuringSameHashDeletion() = runBlocking {
        val entered = CountDownLatch(1)
        val releaseGate = CountDownLatch(1)
        val acquireReachedLock = CountDownLatch(1)
        val hooks = CacheHooks().apply {
            beforePayloadDelete = { hash ->
                if (hash == th("RACE_A")) {
                    entered.countDown()
                    releaseGate.await(10, TimeUnit.SECONDS)
                }
            }
            beforeAcquireLock = { hash ->
                if (hash == th("RACE_A")) acquireReachedLock.countDown()
            }
        }
        val manager = TorrentCacheManager(null, tempDir, timeProvider = { simulatedTime }, hooks = hooks)

        val h = th("RACE_A")
        val dir = manager.getPayloadDirectory(h, h)
        dir.mkdirs()
        File(dir, "data.bin").writeBytes(ByteArray(256))
        manager.acquireLease(h, "first")
        manager.releaseLease(h, "first", keepRetained = false)

        val delJob = launch(Dispatchers.IO) { manager.deleteTorrentPayload(h) }
        assertTrue(entered.await(5, TimeUnit.SECONDS))

        val acqJob = launch(Dispatchers.IO) { manager.acquireLease(h, "late_owner") }
        assertTrue("Acquire must reach the lock attempt", acquireReachedLock.await(5, TimeUnit.SECONDS))
        assertFalse("Acquire must block until deletion completes", acqJob.isCompleted)

        releaseGate.countDown()
        withTimeoutOrNull(10_000) { delJob.join(); acqJob.join() }

        assertTrue(delJob.isCompleted)
        assertTrue(acqJob.isCompleted)
        assertFalse(dir.exists())
        val lease = manager.store.get(h)
        assertEquals(TorrentCacheState.ACTIVE, lease?.state)
        assertTrue(lease?.owners?.contains("late_owner") == true)
    }

    /** Different hashes remain independent while another deletion is paused mid-flight. */
    @Test
    fun testCp04bDifferentHashesRemainIndependent() = runBlocking {
        val enteredA = CountDownLatch(1)
        val releaseGateA = CountDownLatch(1)
        val hooks = CacheHooks().apply {
            beforePayloadDelete = { hash ->
                if (hash == th("SLOW_A")) {
                    enteredA.countDown()
                    releaseGateA.await(10, TimeUnit.SECONDS)
                }
            }
        }
        val manager = TorrentCacheManager(null, tempDir, timeProvider = { simulatedTime }, hooks = hooks)

        val hA = th("SLOW_A")
        val dirA = manager.getPayloadDirectory(hA, hA)
        dirA.mkdirs()
        File(dirA, "a.bin").writeBytes(ByteArray(64))
        manager.acquireLease(hA, "o")
        manager.releaseLease(hA, "o", keepRetained = false)

        val delJob = launch(Dispatchers.IO) { manager.deleteTorrentPayload(hA) }
        assertTrue(enteredA.await(5, TimeUnit.SECONDS))

        val otherDone = CountDownLatch(1)
        var otherLease: TorrentCacheMetaEntry? = null
        launch(Dispatchers.IO) {
            otherLease = manager.acquireLease(th("UNRELATED_B"), "owner_b")
            otherDone.countDown()
        }
        assertTrue(otherDone.await(2, TimeUnit.SECONDS))
        assertEquals(TorrentCacheState.ACTIVE, otherLease?.state)

        releaseGateA.countDown()
        withTimeoutOrNull(10_000) { delJob.join() }
        assertTrue(delJob.isCompleted)
        assertFalse(dirA.exists())
    }

    /** Concurrent debounced/durable writers converge to one consistent newest snapshot. */
    @Test
    fun testCp04cConcurrentWritersConvergeToLatestSnapshot() = runBlocking {
        val store = TorrentCacheStore(tempDir, debounceMs = 40L)
        val hammerJobs = (1..8).map { t ->
            async(Dispatchers.IO) {
                repeat(25) { i ->
                    val hash = th("WRITER_${t}_$i")
                    when ((t + i) % 3) {
                        0 -> store.put(
                            TorrentCacheMetaEntry(
                                torrentHash = hash,
                                owners = setOf("o$t"),
                                state = TorrentCacheState.ACTIVE,
                                lastAccessSequence = store.nextSequence(),
                                lastAccessEpochMs = simulatedTime,
                                payloadRelativePath = hash,
                                sizeBytes = i.toLong()
                            )
                        )
                        1 -> store.remove(hash)
                        else -> store.put(
                            TorrentCacheMetaEntry(
                                torrentHash = hash,
                                owners = emptySet(),
                                state = TorrentCacheState.RETAINED,
                                lastAccessSequence = store.nextSequence(),
                                lastAccessEpochMs = simulatedTime,
                                payloadRelativePath = hash,
                                sizeBytes = i.toLong()
                            ),
                            immediateFlush = true
                        )
                    }
                }
            }
        }
        hammerJobs.awaitAll()

        assertTrue(store.flushSync())

        val expected = store.getAll()
        val reloaded = TorrentCacheStore(tempDir)
        val actual = reloaded.getAll()

        assertEquals(expected.keys, actual.keys)
        expected.forEach { (hash, entry) ->
            val restored = actual[hash]
            assertNotNull(restored)
            assertEquals(entry.state, restored?.state)
            assertEquals(entry.lastAccessSequence, restored?.lastAccessSequence)
        }
    }

    /** Failed filesystem deletions are reported honestly by clearTorrentCache. */
    @Test
    fun testCp05FailedDeleteReportedInClear() = runBlocking {
        val failingStrategy: (File) -> Boolean = { false }
        val manager = TorrentCacheManager(
            null, tempDir, timeProvider = { simulatedTime },
            deleteStrategyOverride = failingStrategy
        )
        val h = th("STUCK_CLEAR")
        val dir = manager.getPayloadDirectory(h, h)
        dir.mkdirs()
        File(dir, "x.bin").writeBytes(ByteArray(512))
        manager.acquireLease(h, "o")
        manager.releaseLease(h, "o", keepRetained = true)

        val bad = manager.clearTorrentCache()
        assertEquals(0L, bad.bytesFreed)
        assertEquals(0, bad.entriesRemoved)
        assertEquals(1, bad.failedEntries)
        assertTrue(dir.exists())
        assertEquals(TorrentCacheState.DELETING, manager.store.get(h)?.state)

        manager.deleteStrategyOverride = null
        val cleaned = manager.reconcileOnStartup(activeHashes = emptySet())
        assertTrue(cleaned > 0)
        assertFalse(dir.exists())

        val good = manager.clearTorrentCache()
        assertEquals(0, good.failedEntries)
    }

    // ---------- CP1v2 crash-commit regression tests ----------

    /**
     * Commit rule (CP1v2-01): a valid PRIMARY wins; an uncommitted newer tmp (even DELETING) is
     * discarded. Finishing the interrupted deletion is correct because no lease survived the crash.
     */
    @Test
    fun testCpV201CommittedPrimaryWinsOverUncommittedTmp() = runBlocking {
        val payload = File(tempDir, th("cp01"))
        payload.mkdirs()
        File(payload, "precious.mkv").writeBytes(ByteArray(700))

        val oldPrimaryJson =
            """{"version":1,"entries":[{"torrentHash":"${th("cp01")}","owners":[],"state":"DELETING",""" +
            """"lastAccessSequence":10,"lastAccessEpochMs":1000,"payloadRelativePath":"${th("cp01")}",""" +
            """"sizeBytes":700,"schemaVersion":1}]}"""
        File(tempDir, "torrent_cache_meta.json").writeText(oldPrimaryJson)

        val newerTmpJson =
            """{"version":1,"entries":[{"torrentHash":"${th("cp01")}","owners":["reclaiming_owner"],"state":"ACTIVE",""" +
            """"lastAccessSequence":11,"lastAccessEpochMs":2000,"payloadRelativePath":"${th("cp01")}",""" +
            """"sizeBytes":700,"schemaVersion":1}]}"""
        File(tempDir, "torrent_cache_meta.json.tmp").writeText(newerTmpJson)

        val store3 = TorrentCacheStore(tempDir)
        assertEquals(TorrentCacheState.DELETING, store3.get(th("cp01"))?.state)

        val manager = TorrentCacheManager(null, tempDir, store3, timeProvider = { simulatedTime })
        val cleaned = manager.reconcileOnStartup(activeHashes = emptySet())
        assertTrue(cleaned > 0)
        assertFalse(payload.exists())
        assertNull(store3.get(th("cp01")))
        assertFalse(File(tempDir, "torrent_cache_meta.json.tmp").exists())
    }

    /**
     * CP1v2-01 Policy A: a committed DELETING entry is NOT reclaimable by acquisition — it refuses
     * until reconciliation finishes the interrupted deletion (safe because no lease survives such
     * a crash). No persistence attempt occurs, so no failure injection applies here.
     */
    @Test
    fun testCpV201CommittedDeletingNotClaimableUntilReconciled() = runBlocking {
        val payload = File(tempDir, th("cpv201b"))
        payload.mkdirs()
        File(payload, "data.mkv").writeBytes(ByteArray(300))

        val store = TorrentCacheStore(tempDir)
        store.put(
            TorrentCacheMetaEntry(
                torrentHash = th("cpv201b"),
                owners = emptySet(),
                state = TorrentCacheState.DELETING,
                lastAccessSequence = store.nextSequence(),
                lastAccessEpochMs = simulatedTime,
                payloadRelativePath = th("cpv201b"),
                sizeBytes = 300L
            ),
            immediateFlush = true
        )

        val manager = TorrentCacheManager(null, tempDir, store, timeProvider = { simulatedTime })
        var threw = false
        try {
            manager.acquireLease(th("cpv201b"), "late_owner")
        } catch (_: CachePersistenceException) {
            threw = true
        }
        assertTrue(threw)
        assertEquals(TorrentCacheState.DELETING, store.get(th("cpv201b"))?.state)

        // Reconciliation finishes the interrupted deletion; claiming works again afterwards.
        manager.reconcileOnStartup(activeHashes = emptySet())
        assertFalse(payload.exists())
        val acquired = manager.acquireLease(th("cpv201b"), "late_owner")
        assertEquals(TorrentCacheState.ACTIVE, acquired.state)
    }


    /**
     * CP1v2-02: the claim is durably persisted BEFORE any payload byte can exist, so a crash right
     * after bytes land leaves an ACCOUNTED directory that clear can reclaim exactly.
     */
    @Test
    fun testCpV202ClaimIsDurableBeforePayloadBytes() = runBlocking {
        val hash = th("cpv202")
        val manager = TorrentCacheManager(null, tempDir, timeProvider = { simulatedTime })

        manager.acquireLease(hash, "session_$hash")

        val reloaded = TorrentCacheStore(tempDir)
        assertNotNull(reloaded.get(hash))
        assertEquals(TorrentCacheState.ACTIVE, reloaded.get(hash)?.state)

        val payloadDir = manager.getPayloadDirectory(hash)
        payloadDir.mkdirs()
        File(payloadDir, "ep.mkv").writeBytes(ByteArray(4096))
        val after = TorrentCacheStore(tempDir)
        assertEquals(TorrentCacheState.ACTIVE, after.get(hash)?.state)

        manager.releaseLease(hash, "session_$hash", keepRetained = true)
        val result = manager.clearTorrentCache()
        assertTrue(result.bytesFreed >= 4096L)
        assertFalse(File(tempDir, hash).exists())
    }

    /** CP1v2-02 failure side: an unpersistable claim creates neither metadata nor directory. */
    @Test
    fun testCpV202FailedClaimCreatesNothing() = runBlocking {
        val failingDir = Files.createTempDirectory("cpv202_fail").toFile()
        val failingManager = TorrentCacheManager(
            null, failingDir, timeProvider = { simulatedTime }
        )
        failingManager.store.writeFailurePolicyForTesting = { true }
        try {
            failingManager.acquireLease(th("cpv202_fail"), "o")
            fail("expected CachePersistenceException")
        } catch (_: CachePersistenceException) {
        }
        assertFalse(File(failingManager.cacheDir, th("cpv202_fail")).exists())
        assertNull(failingManager.store.get(th("cpv202_fail")))
        failingDir.deleteRecursively()
        Unit
    }

    /** CP1v2-03: the info-hash itself is validated at the filesystem boundary. */
    @Test
    fun testCpV203MaliciousInfoHashesRejected() {
        val manager = TorrentCacheManager(null, tempDir, timeProvider = { simulatedTime })
        val cacheDirPath = tempDir.absolutePath

        for (malicious in listOf("../../outside", "A/B", "A\\\\B", "", "ABC", "X".repeat(41))) {
            try {
                val resolved = manager.getPayloadDirectory(malicious)
                fail("Expected rejection for '$malicious' but got $resolved")
            } catch (_: IllegalArgumentException) {
            }
        }

        val ok = manager.getPayloadDirectory(th("valid"))
        assertTrue(ok.absolutePath.startsWith(cacheDirPath))
        assertEquals(File(tempDir, th("valid")).absolutePath, ok.absolutePath)
    }

    /**
     * CP1v2-04: failed metadata cleanup after successful deletion keeps the DELETING marker in
     * memory+disk until a recovery pass removes it durably.
     */
    @Test
    fun testCpV204RemoveDurableFailureKeepsDeletingMarker() = runBlocking {
        val manager = TorrentCacheManager(null, tempDir, timeProvider = { simulatedTime })
        val hash = th("cpv204")
        val dir = manager.getPayloadDirectory(hash, hash)
        dir.mkdirs()
        File(dir, "x.bin").writeBytes(ByteArray(128))
        manager.acquireLease(hash, "o")
        manager.releaseLease(hash, "o", keepRetained = false)

        val calls = AtomicInteger(0)
        manager.store.writeFailurePolicyForTesting = { calls.incrementAndGet() == 2 }

        val result = manager.deleteTorrentPayload(hash)
        assertTrue(result)
        assertFalse(dir.exists())

        assertEquals(TorrentCacheState.DELETING, manager.store.get(hash)?.state)
        val diskStore = TorrentCacheStore(tempDir)
        assertEquals(TorrentCacheState.DELETING, diskStore.get(hash)?.state)

        manager.store.writeFailurePolicyForTesting = null
        assertTrue(manager.store.flushSync())
        manager.reconcileOnStartup(activeHashes = emptySet())
        assertNull(manager.store.get(hash))
    }

    // ---------- CP1v3 review fixes ----------

    /** CP1v3-01 case 1: fresh claim rolled back completely after add-failure. */
    @Test
    fun testCpV301FreshClaimRollbackRemovesEverything() = runBlocking {
        val manager = TorrentCacheManager(null, tempDir, timeProvider = { simulatedTime })
        val hash = th("cpv301a")
        val token = manager.beginClaim(hash, "session_$hash")

        // Simulate partial writes by the failed add attempt:
        val dir = manager.getPayloadDirectory(hash)
        dir.mkdirs()
        File(dir, "partial.part").writeBytes(ByteArray(64))

        manager.abortClaim(token)

        assertNull(manager.store.get(hash))
        assertFalse(dir.exists())
    }

    /** CP1v3-01 case 2: pre-existing RETAINED entry restored verbatim after add-failure. */
    @Test
    fun testCpV301ExistingRetainedRestoredOnAbort() = runBlocking {
        val manager = TorrentCacheManager(null, tempDir, timeProvider = { simulatedTime })
        val hash = th("cpv301b")
        val previous = TorrentCacheMetaEntry(
            torrentHash = hash,
            owners = emptySet(),
            state = TorrentCacheState.RETAINED,
            lastAccessSequence = manager.store.nextSequence(),
            lastAccessEpochMs = simulatedTime,
            payloadRelativePath = hash,
            sizeBytes = 4096L
        )
        manager.store.put(previous, immediateFlush = true)

        val token = manager.beginClaim(hash, "session_$hash")
        assertEquals(TorrentCacheState.ACTIVE, manager.store.get(hash)?.state)

        manager.abortClaim(token)
        val restored = manager.store.get(hash)
        assertEquals(TorrentCacheState.RETAINED, restored?.state)
        assertTrue(restored?.owners?.isEmpty() == true)
    }

    /** CP1v3-01 case 3: pre-existing ACTIVE with ANOTHER owner survives; only our owner removed. */
    @Test
    fun testCpV301ExistingActiveOtherOwnerPreservedOnAbort() = runBlocking {
        val manager = TorrentCacheManager(null, tempDir, timeProvider = { simulatedTime })
        val hash = th("cpv301c")

        // Another live session owns this torrent.
        manager.acquireLease(hash, "other_session")

        val token = manager.beginClaim(hash, "session_attempt")
        assertEquals(setOf("other_session", "session_attempt"), manager.store.get(hash)?.owners)

        manager.abortClaim(token)
        val restored = manager.store.get(hash)
        assertEquals(TorrentCacheState.ACTIVE, restored?.state)
        assertEquals(setOf("other_session"), restored?.owners)
    }

    /**
     * CP1v3-04: finishDeleting's already-absent-payload path must also honor durability of the
     * metadata removal — memory keeps the committed DELETING marker until a healthy pass commits.
     */
    @Test
    fun testCpV304AbsentDirRemovalDurabilityKeepsMarker() = runBlocking {
        val hash = th("cpv304")

        // Committed DELETING whose payload is ALREADY gone (crash after delete, before cleanup).
        val primaryJson =
            """{"version":1,"entries":[{"torrentHash":"$hash","owners":[],"state":"DELETING",""" +
            """"lastAccessSequence":7,"lastAccessEpochMs":${simulatedTime},"payloadRelativePath":"$hash",""" +
            """"sizeBytes":0,"schemaVersion":1}]}"""
        File(tempDir, "torrent_cache_meta.json").writeText(primaryJson)
        val store = TorrentCacheStore(tempDir)
        assertEquals(TorrentCacheState.DELETING, store.get(hash)?.state)

        val manager = TorrentCacheManager(null, tempDir, store, timeProvider = { simulatedTime })
        manager.store.writeFailurePolicyForTesting = { true } // removal persist will fail

        assertEquals(0L, manager.reconcileOnStartup(activeHashes = emptySet()))
        assertEquals("Marker must stay aligned with committed state", TorrentCacheState.DELETING, manager.store.get(hash)?.state)

        // Healthy pass removes the stale marker durably.
        manager.store.writeFailurePolicyForTesting = null
        assertTrue(manager.store.flushSync())
        manager.reconcileOnStartup(activeHashes = emptySet())
        assertNull(manager.store.get(hash))
    }

    // ---------- CP1v4 review fixes ----------

    /** CP1v4-01: withClaim rolls back a FRESH claim when the scoped block throws. */
    @Test
    fun testCpV401ScopeRollsBackFreshClaimOnException() = runBlocking {
        val manager = TorrentCacheManager(null, tempDir, timeProvider = { simulatedTime })
        val hash = th("cpv401a")

        var threw = false
        try {
            manager.withClaim(hash, "session_$hash") {
                throw IllegalStateException("download exploded")
            }
        } catch (e: IllegalStateException) {
            threw = true
        }
        assertTrue(threw)
        assertNull(manager.store.get(hash))
        assertFalse(File(tempDir, hash).exists())
    }

    /** CP1v4-01: withClaim restores a PRE-EXISTING dormant claim when the block throws. */
    @Test
    fun testCpV401ScopeRestoresExistingRetainedOnException() = runBlocking {
        val manager = TorrentCacheManager(null, tempDir, timeProvider = { simulatedTime })
        val hash = th("cpv401b")
        val previous = TorrentCacheMetaEntry(
            torrentHash = hash,
            owners = emptySet(),
            state = TorrentCacheState.RETAINED,
            lastAccessSequence = manager.store.nextSequence(),
            lastAccessEpochMs = simulatedTime,
            payloadRelativePath = hash,
            sizeBytes = 2048L
        )
        manager.store.put(previous, immediateFlush = true)

        try {
            manager.withClaim(hash, "session_$hash") {
                throw IllegalStateException("add failed after claim")
            }
            fail("expected IllegalStateException")
        } catch (_: IllegalStateException) {
        }

        val restored = manager.store.get(hash)
        assertEquals(TorrentCacheState.RETAINED, restored?.state)
        assertTrue(restored?.owners?.isEmpty() == true)
    }

    /** CP1v4-01: successful scope commits the claim (stays ACTIVE). */
    @Test
    fun testCpV401ScopeKeepsClaimCommittedOnSuccess() = runBlocking {
        val manager = TorrentCacheManager(null, tempDir, timeProvider = { simulatedTime })
        val hash = th("cpv401c")
        val result = manager.withClaim(hash, "session_$hash") { "ok" }
        assertEquals("ok", result)
        assertEquals(TorrentCacheState.ACTIVE, manager.store.get(hash)?.state)
    }

    /**
     * CP1v4-02: fresh abort with FAILING filesystem deletion must KEEP the DELETING claim (bytes
     * stay accounted), report incomplete rollback, and let reconciliation finish later.
     */
    @Test
    fun testCpV402FreshAbortDeleteFailureKeepsDeletingClaim() = runBlocking {
        val manager = TorrentCacheManager(
            null, tempDir, timeProvider = { simulatedTime },
            deleteStrategyOverride = { false }
        )
        val hash = th("cpv402a")
        val token = manager.beginClaim(hash, "session_$hash")
        val dir = manager.getPayloadDirectory(hash)
        dir.mkdirs()
        File(dir, "partial.part").writeBytes(ByteArray(77))

        val rolledBack = manager.abortClaim(token)
        assertFalse(rolledBack)

        val marker = manager.store.get(hash)
        assertNotNull(marker)
        assertEquals(TorrentCacheState.DELETING, marker?.state)
        assertTrue(dir.exists())

        manager.deleteStrategyOverride = null
        val cleaned = manager.reconcileOnStartup(activeHashes = emptySet())
        assertTrue(cleaned > 0)
        assertFalse(dir.exists())
        assertNull(manager.store.get(hash))
    }

    /** CP1v4-02: post-delete metadata-removal failure during fresh abort keeps the marker too. */
    @Test
    fun testCpV402FreshAbortRemovalFailureKeepsMarkerAndReportsFalse() = runBlocking {
        val manager = TorrentCacheManager(null, tempDir, timeProvider = { simulatedTime })
        val hash = th("cpv402b")
        val token = manager.beginClaim(hash, "session_$hash")
        val dir = manager.getPayloadDirectory(hash)
        dir.mkdirs()
        File(dir, "x.bin").writeBytes(ByteArray(32))

        val calls = AtomicInteger(0)
        manager.store.writeFailurePolicyForTesting = { calls.incrementAndGet() == 2 }

        val rolledBack = manager.abortClaim(token)
        assertFalse(rolledBack)
        assertEquals(TorrentCacheState.DELETING, manager.store.get(hash)?.state)
        assertFalse(dir.exists()) // bytes are gone; only the marker remains

        manager.store.writeFailurePolicyForTesting = null
        assertTrue(manager.store.flushSync())
        manager.reconcileOnStartup(activeHashes = emptySet())
        assertNull(manager.store.get(hash))
    }

    /** CP1v4-03 case 1: abort removes ONLY the token's owner; a concurrent acquirer survives. */
    @Test
    fun testCpV403AbortIsOwnerAwareRetainedPrevWithNewAcquirer() = runBlocking {
        val manager = TorrentCacheManager(null, tempDir, timeProvider = { simulatedTime })
        val hash = th("cpv403a")
        val previous = TorrentCacheMetaEntry(
            torrentHash = hash,
            owners = emptySet(),
            state = TorrentCacheState.RETAINED,
            lastAccessSequence = manager.store.nextSequence(),
            lastAccessEpochMs = simulatedTime,
            payloadRelativePath = hash,
            sizeBytes = 100L
        )
        manager.store.put(previous, immediateFlush = true)

        val token = manager.beginClaim(hash, "attempt_A")
        manager.acquireLease(hash, "player_B")

        manager.abortClaim(token)

        val after = manager.store.get(hash)
        assertEquals(TorrentCacheState.ACTIVE, after?.state)
        assertEquals(setOf("player_B"), after?.owners)
    }

    /** CP1v4-03 case 2: abort preserves BOTH the prior owner and an intervening acquirer. */
    @Test
    fun testCpV403AbortIsOwnerAwareActivePrevWithExtraAcquirer() = runBlocking {
        val manager = TorrentCacheManager(null, tempDir, timeProvider = { simulatedTime })
        val hash = th("cpv403b")
        manager.acquireLease(hash, "player_B")

        val token = manager.beginClaim(hash, "attempt_A")
        manager.acquireLease(hash, "player_C")

        manager.abortClaim(token)

        val after = manager.store.get(hash)
        assertEquals(TorrentCacheState.ACTIVE, after?.state)
        assertEquals(setOf("player_B", "player_C"), after?.owners)
    }

    /**
     * CP1v4-04: rollback durability failure is OBSERVABLE (returns false). Memory keeps the
     * current state (forward-heal on next successful write) instead of pretending success.
     */
    @Test
    fun testCpV404RollbackDurabilityFailureIsObservable() = runBlocking {
        val manager = TorrentCacheManager(null, tempDir, timeProvider = { simulatedTime })
        val hash = th("cpv404")
        val previous = TorrentCacheMetaEntry(
            torrentHash = hash,
            owners = emptySet(),
            state = TorrentCacheState.RETAINED,
            lastAccessSequence = manager.store.nextSequence(),
            lastAccessEpochMs = simulatedTime,
            payloadRelativePath = hash,
            sizeBytes = 50L
        )
        manager.store.put(previous, immediateFlush = true)

        val token = manager.beginClaim(hash, "attempt_A")

        manager.store.writeFailurePolicyForTesting = { true }
        val rolledBack = manager.abortClaim(token)
        assertFalse("Failed rollback must be observable", rolledBack)
        assertTrue(manager.store.get(hash)?.owners?.contains("attempt_A") == true)

        // Recovery: writes succeed again → the caller can retry the rollback successfully.
        manager.store.writeFailurePolicyForTesting = null
        assertTrue(manager.abortClaim(token))
        assertEquals(TorrentCacheState.RETAINED, manager.store.get(hash)?.state)
    }

    // ---------- CP1v5 review fixes ----------

    /**
     * CP1v5-02 case 1: duplicate acquisition by an owner that ALREADY existed — abort is a no-op
     * and preserves all pre-existing owners (the transaction introduced nothing).
     */
    @Test
    fun testCpV502AbortNoOpWhenOwnerPreExisted() = runBlocking {
        val manager = TorrentCacheManager(null, tempDir, timeProvider = { simulatedTime })
        val hash = th("cpv502a")

        // Pre-existing owners: session_HASH and player_B.
        val seeded = manager.acquireLease(hash, "session_$hash")
        manager.acquireLease(hash, "player_B")
        assertEquals(setOf("session_$hash", "player_B"), seeded.owners.union(setOf("player_B")))

        val token = manager.beginClaim(hash, "session_$hash") // duplicate owner
        assertFalse(token.ownerAddedByClaim)

        assertTrue(manager.abortClaim(token))

        val after = manager.store.get(hash)
        assertEquals(TorrentCacheState.ACTIVE, after?.state)
        assertEquals(setOf("session_$hash", "player_B"), after?.owners)
    }

    /** CP1v5-02 case 2: an owner that RELEASES mid-transaction is never resurrected by abort. */
    @Test
    fun testCpV502AbortDoesNotResurrectReleasedOwners() = runBlocking {
        val manager = TorrentCacheManager(null, tempDir, timeProvider = { simulatedTime })
        val hash = th("cpv502b")
        manager.acquireLease(hash, "player_B")

        val token = manager.beginClaim(hash, "attempt_A")
        assertEquals(setOf("player_B", "attempt_A"), manager.store.get(hash)?.owners)

        // player_B legitimately releases while attempt A is in progress.
        manager.releaseLease(hash, "player_B", keepRetained = true)

        assertTrue(manager.abortClaim(token))

        val after = manager.store.get(hash)
        assertEquals("Released owner must not be resurrected", TorrentCacheState.RETAINED, after?.state)
        assertTrue(after?.owners?.isEmpty() == true)
    }

    /** CP1v5-02 case 3: same-owner duplicate begin/abort is idempotent for fresh claims. */
    @Test
    fun testCpV502DuplicateBeginAbortIdempotentFreshClaim() = runBlocking {
        val manager = TorrentCacheManager(null, tempDir, timeProvider = { simulatedTime })
        val hash = th("cpv502c")

        val t1 = manager.beginClaim(hash, "session_$hash")
        assertTrue(t1.ownerAddedByClaim)

        val t2 = manager.beginClaim(hash, "session_$hash") // duplicate
        assertFalse(t2.ownerAddedByClaim)

        assertTrue(manager.abortClaim(t2)) // no-op: owner pre-existed
        assertEquals(TorrentCacheState.ACTIVE, manager.store.get(hash)?.state)

        // First token owns the delta: aborting it removes the claim entirely (fresh claim).
        assertTrue(manager.abortClaim(t1))
        assertNull(manager.store.get(hash))
    }

    /** CP1v5-02 case 4: existing-owner abort plus intervening new owner keeps everything intact. */
    @Test
    fun testCpV502ExistingOwnerAbortPreservesInterveningAcquirer() = runBlocking {
        val manager = TorrentCacheManager(null, tempDir, timeProvider = { simulatedTime })
        val hash = th("cpv502d")

        manager.acquireLease(hash, "session_$hash")
        manager.acquireLease(hash, "player_B")

        val token = manager.beginClaim(hash, "session_$hash") // duplicate owner
        assertFalse(token.ownerAddedByClaim)

        manager.acquireLease(hash, "player_C") // intervening new owner

        assertTrue(manager.abortClaim(token))

        val after = manager.store.get(hash)
        assertEquals(setOf("session_$hash", "player_B", "player_C"), after?.owners)
        assertEquals(TorrentCacheState.ACTIVE, after?.state)
    }

    /** CP1v5-03: withClaim rolls back even when the block exits via coroutine CANCELLATION. */
    @Test
    fun testCpV503WithClaimRollsBackOnCancellation() = runBlocking {
        val manager = TorrentCacheManager(null, tempDir, timeProvider = { simulatedTime })
        val hash = th("cpv503")
        val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val hang = kotlinx.coroutines.CompletableDeferred<Unit>()

        val job = launch(Dispatchers.IO) {
            try {
                manager.withClaim(hash, "session_$hash") {
                    entered.complete(Unit)
                    hang.await() // suspension point where cancellation lands
                }
            } catch (_: Exception) {
                // expected cancellation path
            }
        }
        entered.await()

        job.cancel()
        job.join()

        assertNull("Cancelled scope must roll back its durable claim", manager.store.get(hash))
        assertFalse(File(tempDir, hash).exists())
    }

    // ---------- CP1v6 review fixes ----------

    /**
     * CP1v6-01: an untrusted/accounting-only snapshot must NEVER be promoted into a trusted
     * primary by ordinary writes. A later durable claim for an unrelated hash must fail while
     * unrecoverable, and after restart the aborted DELETING payload must still survive.
     */
    @Test
    fun testCpV601UntrustedSnapshotCannotBecomeTrustedPrimary() = runBlocking {
        val hashA = th("cpv601a")
        val hashB = th("cpv601b")
        val payloadA = File(tempDir, hashA)
        payloadA.mkdirs()
        File(payloadA, "alive.mkv").writeBytes(ByteArray(500))

        // Untrusted state on disk: corrupt primary + valid tmp containing A=DELETING.
        File(tempDir, "torrent_cache_meta.json").writeText("{ corrupt !!!")
        val deletingJson =
            """{"version":1,"entries":[{"torrentHash":"$hashA","owners":[],"state":"DELETING",""" +
            """"lastAccessSequence":9,"lastAccessEpochMs":${simulatedTime},"payloadRelativePath":"$hashA",""" +
            """"sizeBytes":0,"schemaVersion":1}]}"""
        File(tempDir, "torrent_cache_meta.json.tmp").writeText(deletingJson)

        val store = TorrentCacheStore(tempDir)
        assertFalse(store.isMetadataRecoverable)
        assertEquals(TorrentCacheState.DELETING, store.get(hashA)?.state) // accounting-only

        // Ordinary durable write for unrelated B must be REFUSED while unrecoverable…
        var threw = false
        val manager = TorrentCacheManager(null, tempDir, store, timeProvider = { simulatedTime })
        try {
            manager.acquireLease(hashB, "session_$hashB")
        } catch (_: CachePersistenceException) {
            threw = true
        }
        assertTrue(threw)

        // …and neither metadata file may have been rewritten into a trusted primary
        // containing the untrusted snapshot: both stay byte-for-byte as the failure left them.
        assertEquals(
            "Untrusted primary must not be replaced by a promoted snapshot",
            "{ corrupt !!!",
            File(tempDir, "torrent_cache_meta.json").readText()
        )
        assertEquals(deletingJson, File(tempDir, "torrent_cache_meta.json.tmp").readText())

        // Restart: still accounting-only; reconciliation refuses; A's payload survives.
        val reloadedStore = TorrentCacheStore(tempDir)
        assertFalse(reloadedStore.isMetadataRecoverable)
        val manager2 = TorrentCacheManager(null, tempDir, reloadedStore, timeProvider = { simulatedTime })
        assertEquals(0L, manager2.reconcileOnStartup(activeHashes = emptySet()))
        assertTrue("Payload A must survive", payloadA.exists())

        // Explicit repair restores a TRUSTED EMPTY primary; payloads remain as unclaimed bytes.
        assertTrue(manager2.repairMetadata())
        assertTrue(reloadedStore.isMetadataRecoverable)
        assertTrue(reloadedStore.getAll().isEmpty())

        // And a fresh claim now works durably against the repaired primary.
        val lease = manager2.acquireLease(hashB, "session_$hashB")
        assertEquals(TorrentCacheState.ACTIVE, lease.state)
    }

    /** CP1v6-01: direct flushSync is refused while unrecoverable and leaves files untouched. */
    @Test
    fun testCpV601FlushSyncRefusedWhileUnrecoverable() = runBlocking {
        val hash = th("cpv601flush")
        val store1 = TorrentCacheStore(tempDir)
        store1.put(
            TorrentCacheMetaEntry(
                torrentHash = hash,
                owners = emptySet(),
                state = TorrentCacheState.RETAINED,
                lastAccessSequence = store1.nextSequence(),
                lastAccessEpochMs = simulatedTime,
                payloadRelativePath = hash,
                sizeBytes = 5L
            ),
            immediateFlush = true
        )

        // Corrupt the primary on disk; construct the new store over corrupted files.
        File(tempDir, "torrent_cache_meta.json").writeText("{ corrupt !!!")
        val store2 = TorrentCacheStore(tempDir)
        assertFalse(store2.isMetadataRecoverable)

        assertFalse(store2.flushSync())
        assertEquals(
            "Primary must be untouched while unrecoverable",
            "{ corrupt !!!",
            File(tempDir, "torrent_cache_meta.json").readText()
        )
    }

    /**
     * CP1v6-02 (Option A): overlapping same-hash transactions have independent ownership.
     * B begins fresh after A began (both introduce distinct owners), B COMMITS, then A aborts —
     * B's live ownership survives and the entry never transitions to DELETING.
     */
    @Test
    fun testCpV602OverlappingTransactionsIndependentOwnership() = runBlocking {
        val manager = TorrentCacheManager(null, tempDir, timeProvider = { simulatedTime })
        val hash = th("cpv602")

        val tokenA = manager.beginClaim(hash, "attempt_A")
        val tokenB = manager.beginClaim(hash, "attempt_B")
        assertEquals(setOf("attempt_A", "attempt_B"), manager.store.get(hash)?.owners)

        // B succeeds: its provisional owner is promoted to the LIVE generation owner.
        val registry = TorrentSessionOwnerRegistry()
        val liveB = registry.getOrCreateLiveOwner(hash)
        assertTrue(manager.promoteClaimToLiveOwner(tokenB, liveB))
        assertEquals(setOf("attempt_A", liveB), manager.store.get(hash)?.owners)

        // A later fails and aborts: only attempt_A disappears — B's live ownership survives.
        assertTrue(manager.abortClaim(tokenA))

        val after = manager.store.get(hash)
        assertEquals(setOf(liveB), after?.owners)
        assertEquals(TorrentCacheState.ACTIVE, after?.state)
    }

    /** CP1v6-02 reverse ordering: first commits, second aborts — committed owner survives. */
    @Test
    fun testCpV602ReverseOrderCommitThenAbort() = runBlocking {
        val manager = TorrentCacheManager(null, tempDir, timeProvider = { simulatedTime })
        val hash = th("cpv602r")

        val registry = TorrentSessionOwnerRegistry()
        val liveOwner = registry.getOrCreateLiveOwner(hash)
        val tokenA = manager.beginClaim(hash, "first_attempt")
        assertTrue(manager.promoteClaimToLiveOwner(tokenA, liveOwner))
        assertEquals(setOf(liveOwner), manager.store.get(hash)?.owners)

        val tokenB = manager.beginClaim(hash, "second_attempt")
        assertTrue(manager.abortClaim(tokenB))

        assertEquals(setOf(liveOwner), manager.store.get(hash)?.owners)
        assertEquals(TorrentCacheState.ACTIVE, manager.store.get(hash)?.state)
    }

    /**
     * CP1v7-02 required case: BOTH overlapping same-hash adds succeed. Stable-owner promotion must
     * converge metadata onto exactly ONE live owner ({session_<hash>}), and releasing that owner
     * leaves the entry ownerless/RETAINED.
     */
    @Test
    fun testCpV701BothAddsCommitConvergeToSingleStableOwner() = runBlocking {
        val manager = TorrentCacheManager(null, tempDir, timeProvider = { simulatedTime })
        val hash = th("cpv701")

        val tokenA = manager.beginClaim(hash, "prov_A")
        val tokenB = manager.beginClaim(hash, "prov_B")
        assertEquals(setOf("prov_A", "prov_B"), manager.store.get(hash)?.owners)

        // Both adds converge onto the SAME generation owner (get-or-create semantics).
        val registry = TorrentSessionOwnerRegistry()
        val genOwner = registry.getOrCreateLiveOwner(hash)
        assertTrue(manager.promoteClaimToLiveOwner(tokenA, genOwner))
        assertTrue(manager.promoteClaimToLiveOwner(tokenB, genOwner))

        val afterBoth = manager.store.get(hash)
        assertEquals(
            "Metadata must contain ONLY the live generation owner after both commits",
            setOf(genOwner),
            afterBoth?.owners
        )
        assertEquals(TorrentCacheState.ACTIVE, afterBoth?.state)

        // Normal removal path releases the captured generation owner once ⇒ ownerless RETAINED.
        assertEquals(listOf(genOwner), registry.captureForRemoval(hash))
        manager.releaseLease(hash, genOwner, keepRetained = true)
        val released = manager.store.get(hash)
        assertEquals(TorrentCacheState.RETAINED, released?.state)
        assertTrue(released?.owners?.isEmpty() == true)
    }

    /** CP1v7-02: promotion persistence failure keeps the provisional committed owner + reports false. */
    @Test
    fun testCpV702PromotionFailureRetainsProvisionalOwnerAndReportsFalse() = runBlocking {
        val manager = TorrentCacheManager(null, tempDir, timeProvider = { simulatedTime })
        val hash = th("cpv702")

        val token = manager.beginClaim(hash, "prov_X")
        manager.store.writeFailurePolicyForTesting = { true }

        val liveOwner = "session_" + hash + "_GEN1"
        assertFalse(manager.promoteClaimToLiveOwner(token, liveOwner))

        // Provisional owner remains the committed live owner (safe for the live torrent).
        assertTrue(manager.store.get(hash)?.owners?.contains("prov_X") == true)

        manager.store.writeFailurePolicyForTesting = null
        assertTrue(manager.promoteClaimToLiveOwner(token, liveOwner))
        assertEquals(setOf(liveOwner), manager.store.get(hash)?.owners)
    }

    // ---------- CP1v8 review fixes ----------

    /**
     * CP1v8-01 ABA lifecycle regression, modeled with real threads and a barrier so the OLD
     * removal's cache-owner release is paused while the NEW generation establishes:
     *
     *   G1 live → removal captures G1 and pauses before releasing
     *           → new add claims prov₂, registry creates G2, promote
     *           → resume old release (releases G1)
     *   ⇒ G2 ownership remains ACTIVE; clear/eviction see the new torrent as protected.
     */
    @Test
    fun testCpV801OldDelayedReleaseCannotUnprotectNewGeneration() = runBlocking {
        val manager = TorrentCacheManager(null, tempDir, timeProvider = { simulatedTime })
        val registry = TorrentSessionOwnerRegistry()
        val hash = th("cpv801")

        val payloadDir = manager.getPayloadDirectory(hash)
        payloadDir.mkdirs()
        File(payloadDir, "live.mkv").writeBytes(ByteArray(4096))

        // ---- Generation 1 becomes live ----
        val provG1 = manager.beginClaim(hash, "session_${hash}_gen1prov")
        val gen1Owner = registry.getOrCreateLiveOwner(hash)          // session_<hash>_<g1>
        assertTrue(manager.promoteClaimToLiveOwner(provG1, gen1Owner))
        assertEquals(setOf(gen1Owner), manager.store.get(hash)?.owners)

        // ---- Removal of G1 starts: capture atomically, then PAUSE before releasing ----
        val captured = registry.captureForRemoval(hash)
        assertEquals(listOf(gen1Owner), captured)

        val removerPaused = CountDownLatch(1)
        val releaseGate = CountDownLatch(1)
        val removerJob = launch(Dispatchers.IO) {
            // This is the delayed async step from removeTorrent's launched coroutine.
            removerPaused.countDown()
            releaseGate.await(10, TimeUnit.SECONDS)
            captured.forEach { owner ->
                manager.releaseLease(hash, owner, keepRetained = true)
            }
        }
        assertTrue(removerPaused.await(5, TimeUnit.SECONDS))

        // ---- New add of the SAME hash establishes generation 2 while release is paused ----
        val provG2 = manager.beginClaim(hash, "session_${hash}_gen2prov")
        val gen2Owner = registry.getOrCreateLiveOwner(hash)          // map was emptied ⇒ NEW id
        org.junit.Assert.assertNotEquals(gen1Owner, gen2Owner)
        assertTrue(manager.promoteClaimToLiveOwner(provG2, gen2Owner))

        // Both generations momentarily coexist as owners (old one pending release).
        assertEquals(setOf(gen1Owner, gen2Owner), manager.store.get(hash)?.owners)

        // ---- Old removal finally releases ITS captured generation only ----
        releaseGate.countDown()
        withTimeoutOrNull(10_000) { removerJob.join() }
        assertTrue(removerJob.isCompleted)

        // New generation's ownership survives; entry stays ACTIVE/protected.
        val after = manager.store.get(hash)
        assertEquals(TorrentCacheState.ACTIVE, after?.state)
        assertEquals(setOf(gen2Owner), after?.owners)

        // Clear sees ACTIVE-with-owners ⇒ skips the new torrent's bytes entirely.
        val cleared = manager.clearTorrentCache()
        assertEquals(0L, cleared.bytesFreed)
        assertTrue(payloadDir.exists())
        File(payloadDir, "live.mkv").delete()

        // Sanity: once the new generation legitimately goes dormant, quota reclaims it.
        manager.releaseLease(hash, gen2Owner, keepRetained = false)
        assertFalse(manager.store.get(hash)?.owners?.contains(gen2Owner) == true)
    }

    /** CP1v8 fallback: promotion-failure provisional owners are captured for removal. */
    @Test
    fun testCpV801PromotionFallbackOwnersAreCapturedForRemoval() = runBlocking {
        val registry = TorrentSessionOwnerRegistry()
        val hash = th("cpv801fb")

        registry.registerFallback(hash, "session_${hash}_provX")

        val captured = registry.captureForRemoval(hash)
        assertEquals(listOf("session_${hash}_provX"), captured)
        assertTrue(registry.captureForRemoval(hash).isEmpty()) // drained exactly once
    }

    /**
     * CP1v9-02: HANDLE-GENERATION decision is lifecycle-atomic per hash.
     *
     * Models TorrentServerManager's real transitions with a fake handle flag:
     *   REMOVE: synchronized(lifecycle) { capture G1; fakeHandle=false; [paused] }
     *   ADD:    must WAIT on the same lifecycle lock; when admitted it observes
     *           fakeHandle==false (no stale handle), gets a FRESH generation G2 via
     *           get-or-create, and promotes onto G2 — never a mixture of old handle + new
     *           generation or new handle + old generation.
     * Delayed G1 cache release afterwards is harmless.
     */
    @Test
    fun testCpV901AddWaitsForRemovalGenerationTransition() = runBlocking {
        val manager = TorrentCacheManager(null, tempDir, timeProvider = { simulatedTime })
        val registry = TorrentSessionOwnerRegistry()
        val hash = th("cpv901")
        // Models the server's per-hash lifecycle critical section (ReentrantLock so the modeled
        // suspend-based cache operations may run inside it).
        val lifecycleTransition = ReentrantLock()

        // Blocking bridges so cache operations can run inside the modeled lifecycle lock without
        // hitting Kotlin's suspension-in-critical-section restriction (test-only convenience).
        fun acquireB(h: String, o: String) = runBlocking { manager.acquireLease(h, o) }
        fun promoteB(h: String, prov: String, live: String) =
            runBlocking {
                manager.promoteClaimToLiveOwner(
                    CacheClaimToken(h, prov, previousEntry = null, ownerAddedByClaim = true),
                    live
                )
            }
        fun releaseB(h: String, o: String) = runBlocking { manager.releaseLease(h, o, keepRetained = true) }

        var fakeHandleLive = false

        // ---- Generation 1 established through the lifecycle section (like addTorrent) ----
        val g1: String
        lifecycleTransition.withLock {
            acquireB(hash, "prov_gen1")
            g1 = registry.getOrCreateLiveOwner(hash)
            promoteB(hash, "prov_gen1", g1)
            fakeHandleLive = true
        }
        val payloadDir = manager.getPayloadDirectory(hash)
        payloadDir.mkdirs()
        File(payloadDir, "g1.mkv").writeBytes(ByteArray(2048))

        // ---- Removal starts and pauses INSIDE its lifecycle transition ----
        val removerInTransition = CountDownLatch(1)
        val finishRemoval = CountDownLatch(1)
        var removedHandleDuringTransition = false

        val remover = launch(Dispatchers.IO) {
            lifecycleTransition.withLock {
                val captured = registry.captureForRemoval(hash)   // G1 invalidated for new adds
                fakeHandleLive = false                            // handle removal happens here
                removedHandleDuringTransition = true
                removerInTransition.countDown()
                finishRemoval.await(10, TimeUnit.SECONDS)         // paused mid-transition
                captured.forEach { owner ->
                    releaseB(hash, owner)
                }
            }
        }
        assertTrue(removerInTransition.await(5, TimeUnit.SECONDS))
        assertTrue(removedHandleDuringTransition)

        // ---- Same-hash add attempts to bind WHILE removal holds the transition ----
        var addSawStaleHandleAtBind: Boolean? = null
        var addBoundOwner: String? = null
        val addJob = launch(Dispatchers.IO) {
            lifecycleTransition.withLock {
                addSawStaleHandleAtBind = fakeHandleLive          // observed AT bind time
                acquireB(hash, "prov_gen2")
                val g2 = registry.getOrCreateLiveOwner(hash)
                promoteB(hash, "prov_gen2", g2)
                addBoundOwner = g2
            }
        }

        // Add cannot complete its binding while removal owns the transition.
        assertFalse("Add must wait for the lifecycle transition", addJob.isCompleted)

        finishRemoval.countDown()
        withTimeoutOrNull(10_000) { remover.join(); addJob.join() }

        // The add bound AFTER the old handle was invalidated ⇒ saw no stale handle…
        assertFalse("Add must not adopt a stale handle", addSawStaleHandleAtBind == true)
        // …and obtained a NEW generation distinct from the removed one.
        org.junit.Assert.assertNotEquals(g1, addBoundOwner)

        // Delayed release of G1 afterwards is harmless to the new generation.
        manager.releaseLease(hash, g1, keepRetained = true)

        val after = manager.store.get(hash)
        assertEquals(TorrentCacheState.ACTIVE, after?.state)
        assertEquals(setOf(addBoundOwner), after?.owners)

        // Clear/eviction see the new torrent as protected.
        val cleared = manager.clearTorrentCache()
        assertEquals(0L, cleared.bytesFreed)
        assertTrue(payloadDir.exists())
    }

    /**
     * CP1v10-01 failure-path regression (production removeTorrent ordering):
     *
     *   synchronized(lifecycle) {
     *       cleanup that may throw
     *       handle removal that may throw
     *       captureForRemoval  ← COMMITS the generation transition LAST
     *   }
     *
     * A synchronous removal failure must leave G1 registered as the current generation and the
     * cache metadata untouched; a retried removal then captures/releases G1 normally.
     */
    @Test
    fun testCpV1010RemovalFailureKeepsGenerationRegisteredForRetry() = runBlocking {
        val manager = TorrentCacheManager(null, tempDir, timeProvider = { simulatedTime })
        val registry = TorrentSessionOwnerRegistry()
        val hash = th("cpv1010")

        // ---- G1 established as current registry + cache owner ----
        var handleRemovalSucceeds = false   // injects the synchronous removal failure
        val g1: String
        val lifecycleLock = registry.lifecycleLockFor(hash)

        manager.acquireLease(hash, "prov_gen1")
        synchronized(lifecycleLock) {
            g1 = registry.getOrCreateLiveOwner(hash)
            runBlocking {
                manager.promoteClaimToLiveOwner(
                    CacheClaimToken(hash, "prov_gen1", previousEntry = null, ownerAddedByClaim = true),
                    g1
                )
            }
        }

        fun modeledRemoveTorrent(): Boolean {
            return try {
                synchronized(lifecycleLock) {
                    // Step 1: synchronous cleanup / handle removal — may fail.
                    check(handleRemovalSucceeds) { "simulated sessionManager.remove failure" }

                    // Step 2: COMMIT generation transition only after success.
                    val captured = registry.captureForRemoval(hash)
                    captured.forEach { owner ->
                        runBlocking { manager.releaseLease(hash, owner, keepRetained = true) }
                    }
                    captured.isNotEmpty()
                }
            } catch (_: IllegalStateException) {
                false
            }
        }

        // ---- Failed removal attempt: registry + cache ownership must be untouched ----
        assertFalse(modeledRemoveTorrent())

        assertEquals(
            "G1 must remain the CURRENT registered generation after a failed removal",
            g1,
            registry.getOrCreateLiveOwner(hash)
        )
        val metaAfterFailure = manager.store.get(hash)
        assertNotNull(metaAfterFailure)
        assertEquals(TorrentCacheState.ACTIVE, metaAfterFailure?.state)
        assertEquals(setOf(g1), metaAfterFailure?.owners)

        // No new generation was accidentally committed by the failed attempt.
        assertEquals(g1, registry.getOrCreateLiveOwner(hash))

        // ---- Retried removal succeeds: capture/release G1 ⇒ ownerless RETAINED ----
        handleRemovalSucceeds = true
        assertTrue(modeledRemoveTorrent())

        val released = manager.store.get(hash)
        assertEquals("Released generation becomes ownerless/RETAINED", TorrentCacheState.RETAINED, released?.state)
        assertTrue(released?.owners?.isEmpty() == true)
    }
}
