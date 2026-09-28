package ani.dantotsu.forum

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MAX MERGE M2 correction (Blockers 1+4): pagination is serialized and
 * refreshes are latest-wins.
 */
class ForumPagedGateTest {

    @Test
    fun `page one plus hasNext near bottom requests page two exactly once`() {
        val gate = ForumPagedGate()
        val gen = gate.startRefresh()
        assertTrue(gate.finish(gen, 1, true))
        assertEquals(2, gate.currentPage)
        val first = gate.requestNext()
        assertEquals(PagedRequest(gen, 2), first)
        // While page 2 is loading, no duplicate request.
        assertNull(gate.requestNext())
        assertTrue(gate.finish(gen, 2, true))
        assertEquals(3, gate.currentPage)
    }

    @Test
    fun `repeated near-bottom callbacks while loading never duplicate`() {
        val gate = ForumPagedGate()
        val gen = gate.startRefresh()
        gate.finish(gen, 1, true)
        gate.requestNext()
        repeat(5) { assertNull(gate.requestNext()) }
    }

    @Test
    fun `no next page means no request`() {
        val gate = ForumPagedGate()
        val gen = gate.startRefresh()
        gate.finish(gen, 1, false)
        assertNull(gate.requestNext())
    }

    @Test
    fun `refresh resets to page one`() {
        val gate = ForumPagedGate()
        val first = gate.startRefresh()
        gate.finish(first, 1, true)
        gate.requestNext()
        gate.finish(first, 2, true)
        assertEquals(3, gate.currentPage)
        val second = gate.startRefresh()
        assertEquals(1, gate.currentPage)
        assertTrue(gate.hasNextPage)
        assertTrue(gate.loading)
        assertTrue(gate.finish(second, 1, false))
        assertEquals(2, gate.currentPage)
        assertNull(gate.requestNext())
    }

    @Test
    fun `stale result touches nothing`() {
        val gate = ForumPagedGate()
        val old = gate.startRefresh()
        gate.finish(old, 1, true)
        gate.requestNext()
        val fresh = gate.startRefresh()
        // Slow old page-2 response arrives after the refresh.
        assertFalse(gate.finish(old, 2, false))
        // State still belongs to the fresh generation.
        assertEquals(1, gate.currentPage)
        assertTrue(gate.hasNextPage)
        assertTrue(gate.loading)
        // Fresh page 1 applies normally.
        assertTrue(gate.finish(fresh, 1, true))
        assertEquals(2, gate.currentPage)
    }

    @Test
    fun `stale failure does not unlock a fresh load`() {
        val gate = ForumPagedGate()
        val old = gate.startRefresh()
        gate.startRefresh()
        gate.fail(old)
        assertTrue(gate.loading)
    }

    @Test
    fun `initial load and resume overlap cannot skip or double advance`() {
        val gate = ForumPagedGate()
        val initial = gate.startRefresh()
        // onResume fires while the initial request is in flight.
        val resumed = gate.startRefresh()
        // Initial response arrives late: discarded, no page advance.
        assertFalse(gate.finish(initial, 1, true))
        assertEquals(1, gate.currentPage)
        // Resumed response applies exactly once.
        assertTrue(gate.finish(resumed, 1, true))
        assertEquals(2, gate.currentPage)
    }

    @Test
    fun `empty current page still advances past pathological servers`() {
        val gate = ForumPagedGate()
        val gen = gate.startRefresh()
        assertTrue(gate.finish(gen, 1, true))
        // Page 2 returns empty but claims more: advance anyway.
        assertTrue(gate.finish(gen, 2, true))
        assertEquals(3, gate.currentPage)
    }
}
