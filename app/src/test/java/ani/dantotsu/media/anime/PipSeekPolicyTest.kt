package ani.dantotsu.media.anime

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * MAX MERGE M6 HOLD: PiP actions display and seek the same distance —
 * the configured value, with a 10s fallback.
 */
class PipSeekPolicyTest {

    @Test
    fun `default 10 seeks 10`() {
        assertEquals(10, pipSeekSeconds(10))
        assertEquals(10_000L, pipSeekMs(10))
    }

    @Test
    fun `configured non-10 value is honored in both label input and seek`() {
        assertEquals(30, pipSeekSeconds(30))
        assertEquals(30_000L, pipSeekMs(30))
        assertEquals(1, pipSeekSeconds(1))
        assertEquals(1_000L, pipSeekMs(1))
        assertEquals(50, pipSeekSeconds(50))
        assertEquals(50_000L, pipSeekMs(50))
    }

    @Test
    fun `non-positive config falls back to 10`() {
        assertEquals(10, pipSeekSeconds(0))
        assertEquals(10_000L, pipSeekMs(0))
        assertEquals(10, pipSeekSeconds(-5))
        assertEquals(10_000L, pipSeekMs(-5))
    }
}
