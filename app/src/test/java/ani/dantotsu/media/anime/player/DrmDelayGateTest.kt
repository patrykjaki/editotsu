package ani.dantotsu.media.anime.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MAX MERGE M4 correction (HOLD4 B6): the delay capability gate. When the
 * bound backend cannot apply delays, the Sync UI must refuse before any
 * displayed value mutates — a no-op must never be reported as applied.
 */
class DrmDelayGateTest {

    private fun displayedAfter(
        supported: Boolean,
        current: Long,
        requested: Long,
    ): Long {
        // Mirrors PlayerSubtitleManager.setSubtitleDelay: mutate only when
        // the gate passes.
        var displayed = current
        if (resolveDelayApplicable(supported, requested, displayed)) {
            displayed = requested
        }
        return displayed
    }

    @Test
    fun `supported backend applies normally`() {
        assertEquals(200L, displayedAfter(supported = true, current = 100L, requested = 200L))
        assertEquals(0L, displayedAfter(supported = true, current = 100L, requested = 0L))
    }

    @Test
    fun `unsupported backend cannot report a no-op as applied`() {
        // Attempted change is refused; the displayed value stays current.
        assertEquals(100L, displayedAfter(supported = false, current = 100L, requested = 200L))
        assertEquals(100L, displayedAfter(supported = false, current = 100L, requested = 0L))
    }

    @Test
    fun `unsupported backend allows representable reset`() {
        // Already at zero: reset is a no-op the UI may acknowledge.
        assertEquals(0L, displayedAfter(supported = false, current = 0L, requested = 0L))
    }

    @Test
    fun `gate is pure and direct`() {
        assertTrue(resolveDelayApplicable(true, 200L, 100L))
        assertFalse(resolveDelayApplicable(false, 200L, 100L))
        assertTrue(resolveDelayApplicable(false, 0L, 0L))
    }
}
