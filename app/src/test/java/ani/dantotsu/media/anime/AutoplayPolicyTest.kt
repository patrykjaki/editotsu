package ani.dantotsu.media.anime

import ani.dantotsu.media.anime.AutoplayPolicy.Decision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoplayPolicyTest {

    @Test
    fun `autoplay on and recent interaction advances`() {
        assertEquals(Decision.ADVANCE, AutoplayPolicy.decide(true, true, true))
        assertTrue(AutoplayPolicy.shouldAdvance(true, true, true))
    }

    @Test
    fun `autoplay on and idle for an hour is cancelled`() {
        assertEquals(Decision.CANCELLED, AutoplayPolicy.decide(true, true, false))
        assertFalse(AutoplayPolicy.shouldAdvance(true, true, false))
    }

    @Test
    fun `autoplay off never advances or toasts`() {
        assertEquals(Decision.NONE, AutoplayPolicy.decide(false, true, true))
        assertEquals(Decision.NONE, AutoplayPolicy.decide(false, true, false))
        assertFalse(AutoplayPolicy.shouldAdvance(false, true, true))
    }

    @Test
    fun `end far from physical end does nothing`() {
        assertEquals(Decision.NONE, AutoplayPolicy.decide(true, false, true))
        assertEquals(Decision.NONE, AutoplayPolicy.decide(true, false, false))
    }

    @Test
    fun `idle timeout is one hour`() {
        assertEquals(3_600_000L, AutoplayPolicy.IDLE_TIMEOUT_MS)
    }

    @Test
    fun `end gate fires once until re-armed`() {
        val gate = AutoplayEndGate()
        assertTrue(gate.tryConsume())
        assertFalse(gate.tryConsume())
        assertFalse(gate.tryConsume())
        gate.rearm()
        assertTrue(gate.tryConsume())
        assertFalse(gate.tryConsume())
    }
}
