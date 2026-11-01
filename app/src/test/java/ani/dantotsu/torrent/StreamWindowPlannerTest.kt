package ani.dantotsu.torrent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamWindowPlannerTest {
    private val mb = 1024L * 1024

    @Test
    fun `window size follows bytes and is bounded`() {
        assertEquals(16, StreamWindowPlanner(0, 999, 2 * mb).windowPieces)          // 32 MB / 2 MB
        assertEquals(StreamWindowPlanner.MIN_PIECES, StreamWindowPlanner(0, 999, 16 * mb).windowPieces)
        assertEquals(StreamWindowPlanner.MAX_PIECES, StreamWindowPlanner(0, 999, 256 * 1024).windowPieces)
    }

    @Test
    fun `raise is ordered nearest first with rising deadlines`() {
        val p = StreamWindowPlanner(10, 500, 2 * mb)
        val plan = p.plan(10)
        assertEquals((10..25).toList(), plan.raise.map { it.first })
        val deadlines = plan.raise.map { it.second }
        assertEquals(0, deadlines.first())
        assertEquals(deadlines.sorted(), deadlines)
        assertTrue(plan.demote.isEmpty())
    }

    @Test
    fun `seek demotes the old window and never keeps stale pieces raised`() {
        val p = StreamWindowPlanner(0, 1000, 2 * mb)
        p.plan(0)
        val seek = p.plan(500)
        assertEquals((0..15).toList(), seek.demote.sorted())
        assertEquals((500..515).toSet(), p.raisedPieces())
    }

    @Test
    fun `moving forward one piece demotes only the piece left behind`() {
        val p = StreamWindowPlanner(0, 1000, 2 * mb)
        p.plan(0)
        val next = p.plan(1)
        assertEquals(listOf(0), next.demote)
        assertEquals(16, next.raise.first { it.first == 16 }.let { it.first })
    }

    @Test
    fun `protected tail stays raised across seeks and window ends at the file`() {
        val p = StreamWindowPlanner(0, 100, 2 * mb, protectedPieces = setOf(99, 100))
        val a = p.plan(0)
        assertTrue(a.raise.any { it.first == 100 && it.second == StreamWindowPlanner.PROTECTED_DEADLINE_MS })
        val b = p.plan(95)
        assertEquals((95..100).toList(), b.raise.map { it.first })
        assertFalse(99 in b.demote || 100 in b.demote)
    }

    @Test
    fun `playhead outside the file is clamped and release demotes everything`() {
        val p = StreamWindowPlanner(20, 40, 2 * mb)
        assertEquals(20, p.plan(-5).raise.first().first)
        assertEquals(40, p.plan(999).raise.first().first)
        assertEquals(listOf(40), p.release())
        assertTrue(p.raisedPieces().isEmpty())
    }

    @Test
    fun `reannounce only after a stall and at most every 30 s per torrent`() {
        val r = ReannounceLimiter()
        assertFalse(r.shouldReannounce("a", stalledForMs = 1_000, nowMs = 0))
        assertTrue(r.shouldReannounce("a", stalledForMs = 6_000, nowMs = 10_000))
        assertFalse(r.shouldReannounce("a", stalledForMs = 9_000, nowMs = 20_000))
        assertTrue(r.shouldReannounce("b", stalledForMs = 6_000, nowMs = 20_000))
        assertTrue(r.shouldReannounce("a", stalledForMs = 40_000, nowMs = 40_001))
    }

    // Sizes from the owner's 4K log: 1.36 GB mkv, cues probe at 1363008878, cluster at 24 MB.
    private val size4k = 1_363_123_787L
    private val tail4k = setOf(648, 649, 650)

    @Test
    fun `cues probe while the head owns the stream is a side read`() {
        assertTrue(StreamWindowPlanner.isTailSideRead(1_363_008_878L, size4k, 649, tail4k, streamHasOwner = true))
        // In the last 8 MB but before the protected pieces: still a side read.
        assertTrue(StreamWindowPlanner.isTailSideRead(size4k - 7 * mb, size4k, 646, tail4k, streamHasOwner = true))
    }

    @Test
    fun `head, mid-file seeks and ownerless tail reads are ordinary reads`() {
        assertFalse(StreamWindowPlanner.isTailSideRead(0L, size4k, 0, tail4k, streamHasOwner = true))
        assertFalse(StreamWindowPlanner.isTailSideRead(24_449_597L, size4k, 11, tail4k, streamHasOwner = true))
        assertFalse(StreamWindowPlanner.isTailSideRead(size4k / 2, size4k, 325, tail4k, streamHasOwner = true))
        assertFalse(StreamWindowPlanner.isTailSideRead(1_363_008_878L, size4k, 649, tail4k, streamHasOwner = false))
    }
}
