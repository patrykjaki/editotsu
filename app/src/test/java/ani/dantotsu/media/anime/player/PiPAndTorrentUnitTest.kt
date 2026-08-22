package ani.dantotsu.media.anime.player

import ani.dantotsu.torrent.PrebufferLease
import ani.dantotsu.torrent.TorrentDeadlineAdapter
import ani.dantotsu.torrent.TorrentDeadlineRegistry
import ani.dantotsu.torrent.computeStreamingPiecePlan
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class PiPAndTorrentUnitTest {

    // --- 1. PiP Aspect Ratio Clamping Tests ---

    data class SimpleRational(val num: Int, val den: Int)

    private fun clampAspectRatio(num: Int, den: Int): SimpleRational {
        if (num <= 0 || den <= 0) {
            return SimpleRational(16, 9)
        }
        val floatVal = num.toFloat() / den.toFloat()
        val minVal = 1000f / 2390f
        val maxVal = 2390f / 1000f
        return when {
            floatVal < minVal -> SimpleRational(1000, 2390)
            floatVal > maxVal -> SimpleRational(2390, 1000)
            else -> SimpleRational(num, den)
        }
    }

    @Test
    fun testPipAspectRatioClamping() {
        // Standard ratios within bounds
        assertEquals(SimpleRational(16, 9), clampAspectRatio(16, 9))
        assertEquals(SimpleRational(4, 3), clampAspectRatio(4, 3))
        assertEquals(SimpleRational(21, 9), clampAspectRatio(21, 9))
        assertEquals(SimpleRational(1, 1), clampAspectRatio(1, 1))

        // Beyond max 2.39:1
        assertEquals(SimpleRational(2390, 1000), clampAspectRatio(100, 1))

        // Below min 1:2.39 (0.4184)
        assertEquals(SimpleRational(1000, 2390), clampAspectRatio(1, 100))

        // Fallbacks on invalid / zero / negative
        assertEquals(SimpleRational(16, 9), clampAspectRatio(0, 1))
        assertEquals(SimpleRational(16, 9), clampAspectRatio(-16, 9))
    }

    // --- 2. Torrent Piece Math Tests ---

    @Test
    fun testComputeStreamingPiecePlan() {
        val pieceLength = 2L * 1024 * 1024 // 2MB pieces

        // Case A: File fits completely in a single piece (fileSize = 1MB, piece 0)
        val planSingle = computeStreamingPiecePlan(
            fileOffset = 0L,
            fileSize = 1024 * 1024L,
            pieceLength = pieceLength,
            totalPieces = 10
        )
        assertEquals(listOf(0), planSingle.startupPieces)
        assertNull(planSingle.opportunisticTail) // Tail is same as startup

        // Case B: Multi-piece file starting at offset 0 (fileSize = 10MB -> 5 pieces: 0, 1, 2, 3, 4)
        val planMulti = computeStreamingPiecePlan(
            fileOffset = 0L,
            fileSize = 10 * 1024 * 1024L,
            pieceLength = pieceLength,
            totalPieces = 10
        )
        assertEquals(listOf(0, 1, 2), planMulti.startupPieces)
        assertEquals(4, planMulti.opportunisticTail)

        // Case C: Sub-file with offset in middle of torrent (offset = 5MB -> piece 2, size = 6MB -> spans pieces 2, 3, 4, 5)
        val planOffset = computeStreamingPiecePlan(
            fileOffset = 5 * 1024 * 1024L,
            fileSize = 6 * 1024 * 1024L,
            pieceLength = pieceLength,
            totalPieces = 20
        )
        assertEquals(listOf(2, 3, 4), planOffset.startupPieces)
        assertEquals(5, planOffset.opportunisticTail)

        // Case D: Two-piece file
        val planTwo = computeStreamingPiecePlan(
            fileOffset = 0L,
            fileSize = 3 * 1024 * 1024L,
            pieceLength = pieceLength,
            totalPieces = 10
        )
        assertEquals(listOf(0, 1), planTwo.startupPieces)
        assertNull(planTwo.opportunisticTail) // piece 1 is in startupPieces

        // Case E: Invalid bounds guard
        val invalidPlan = computeStreamingPiecePlan(-1L, 100L, pieceLength, 10)
        assertTrue(invalidPlan.startupPieces.isEmpty())
        assertNull(invalidPlan.opportunisticTail)
    }

    // --- 3. Torrent Deadline Registry & Concurrent Refcounting Tests ---

    class FakeTorrentDeadlineAdapter(
        val setCalls: AtomicInteger = AtomicInteger(0),
        val resetCalls: AtomicInteger = AtomicInteger(0),
        override var isValid: Boolean = true
    ) : TorrentDeadlineAdapter {
        override fun setPieceDeadline(index: Int, deadline: Int) {
            setCalls.incrementAndGet()
        }

        override fun resetPieceDeadline(index: Int) {
            resetCalls.incrementAndGet()
        }
    }

    @Test
    fun testTorrentDeadlineRegistryRefcountingAndDisposal() {
        val adapter = FakeTorrentDeadlineAdapter()
        var fakeTime = 1000L
        val registry = TorrentDeadlineRegistry("HASH_123", adapter, timeProvider = { fakeTime })

        // Register owner 1 for piece 0
        registry.registerDeadline(0, "owner_1", 1000L)
        assertEquals(1, adapter.setCalls.get())

        // Register owner 2 for same piece with tighter deadline (300ms)
        registry.registerDeadline(0, "owner_2", 300L)
        assertEquals(2, adapter.setCalls.get())

        // Unregister owner 1 -> piece 0 still held by owner 2 (deadline recomputed to owner 2's target)
        registry.unregisterOwner("owner_1")
        assertEquals(3, adapter.setCalls.get())
        assertEquals(0, adapter.resetCalls.get())

        // Unregister owner 2 -> piece 0 has 0 owners -> resetPieceDeadline called
        registry.unregisterOwner("owner_2")
        assertEquals(1, adapter.resetCalls.get())

        // Test disposal: register owner 3 for piece 1, then dispose
        registry.registerDeadline(1, "owner_3", 500L)
        assertEquals(4, adapter.setCalls.get())
        registry.dispose()
        assertEquals(2, adapter.resetCalls.get())

        // Post-disposal operations should safely no-op
        registry.registerDeadline(2, "owner_4", 500L)
        assertEquals(4, adapter.setCalls.get()) // No new set calls made after dispose
    }

    // --- 4. Identity-Safe Prebuffer Lease Lifecycle Tests ---

    @Test
    fun testIdentitySafePrebufferLeaseSlotLifecycle() {
        val activeSlots = ConcurrentHashMap<Pair<String, Int>, PrebufferLease>()
        val key = Pair("HASH_A", 0)

        val l1Closed = AtomicBoolean(false)
        val l1 = PrebufferLease("sess_1", "HASH_A", 0) { lease ->
            l1Closed.set(true)
            activeSlots.remove(key, lease)
        }
        activeSlots[key] = l1

        // Replace L1 with L2 in same slot
        val l2Closed = AtomicBoolean(false)
        val l2 = PrebufferLease("sess_2", "HASH_A", 0) { lease ->
            l2Closed.set(true)
            activeSlots.remove(key, lease)
        }
        activeSlots[key] = l2

        // A late close on L1 should NOT remove L2 from activeSlots
        l1.close()
        assertTrue(l1.isClosed)
        assertTrue(l1Closed.get())
        assertEquals(l2, activeSlots[key]) // L2 is preserved!
        assertFalse(l2.isClosed)

        // Closing L2 properly removes the slot
        l2.close()
        assertTrue(l2.isClosed)
        assertTrue(l2Closed.get())
        assertNull(activeSlots[key])
    }

    // --- 5. Composite Lease Ownership Tests ---

    @Test
    fun testCompositeLeaseOwnershipInEngine() {
        val leaseAClosed = AtomicBoolean(false)
        val leaseBClosed = AtomicBoolean(false)

        val closeableA = AutoCloseable { leaseAClosed.set(true) }
        val closeableB = AutoCloseable { leaseBClosed.set(true) }

        val fakeClient = FakeMpvClient()
        val engine = MpvPlaybackEngine(null, fakeClient)

        val reqA = PlaybackRequest(
            uri = "https://cdn.anime.com/ep1.m3u8",
            sourceLease = closeableA
        )
        val reqB = PlaybackRequest(
            uri = "https://cdn.anime.com/ep2.m3u8",
            sourceLease = closeableB
        )

        engine.loadMedia(reqA)
        Thread.sleep(100)
        assertFalse(leaseAClosed.get())

        // Loading B supersedes A; A's lease should be closed
        engine.loadMedia(reqB)
        Thread.sleep(100)
        assertTrue(leaseAClosed.get())
        assertFalse(leaseBClosed.get())

        // Releasing engine closes B's lease
        engine.release()
        Thread.sleep(100)
        assertTrue(leaseBClosed.get())
    }

    // --- 6. External Audio & Subtitle Sync Tests ---

    @Test
    fun testExternalAudioTrackModelAndMpvDispatch() {
        val externalAudio1 = ExternalAudioTrack(
            url = "https://cdn.anime.com/audio_ja.m4a",
            language = "ja",
            title = "Japanese Original"
        )
        val externalAudio2 = ExternalAudioTrack(
            url = "https://cdn.anime.com/audio_en.m4a",
            language = "en",
            title = "English Dub"
        )

        val request = PlaybackRequest(
            uri = "https://cdn.anime.com/video_only.mp4",
            externalAudioTracks = listOf(externalAudio1, externalAudio2)
        )

        assertEquals(2, request.externalAudioTracks.size)
        assertEquals("ja", request.externalAudioTracks[0].language)
        assertEquals("English Dub", request.externalAudioTracks[1].title)

        // Verify audio-add command structure
        val cmd1 = arrayOf("audio-add", externalAudio1.url, "auto", externalAudio1.title ?: "", externalAudio1.language ?: "")
        assertEquals("audio-add", cmd1[0])
        assertEquals("https://cdn.anime.com/audio_ja.m4a", cmd1[1])
        assertEquals("auto", cmd1[2])
        assertEquals("Japanese Original", cmd1[3])
        assertEquals("ja", cmd1[4])
    }

    @Test
    fun testSubtitleDelayMsConversion() {
        val delayMs = 500L
        val secondsDouble = delayMs / 1000.0
        assertEquals(0.5, secondsDouble, 0.001)

        val negativeDelayMs = -1250L
        val negativeSeconds = negativeDelayMs / 1000.0
        assertEquals(-1.25, negativeSeconds, 0.001)
    }
}

