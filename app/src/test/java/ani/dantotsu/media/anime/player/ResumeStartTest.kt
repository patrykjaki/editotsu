package ani.dantotsu.media.anime.player

import org.junit.Assert.assertEquals
import org.junit.Test

class ResumeStartTest {
    private val ep = 24 * 60_000L

    @Test
    fun `unwatched starts at zero, partly watched continues`() {
        assertEquals(0L, ResumeStart.startPosition(0L, null))
        assertEquals(0L, ResumeStart.startPosition(0L, ep))
        assertEquals(600_000L, ResumeStart.startPosition(600_000L, ep))
        assertEquals(600_000L, ResumeStart.startPosition(600_000L, null))
    }

    @Test
    fun `finished episode starts again at zero`() {
        assertEquals(0L, ResumeStart.startPosition((ep * 0.93).toLong(), ep))
        assertEquals(0L, ResumeStart.startPosition(ep, ep))
        assertEquals(0L, ResumeStart.startPosition(ep + 5_000L, ep))
        assertEquals((ep * 0.9).toLong(), ResumeStart.startPosition((ep * 0.9).toLong(), ep))
    }
}
