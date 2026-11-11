package ani.dantotsu.media.anime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SleepTimerTest {
    @Test
    fun `timed pause fires only at the deadline and once`() {
        val t = SleepTimer()
        t.setMinutes(30, nowMs = 1_000L)
        assertEquals(30 * 60_000L, t.remainingMs(1_000L))
        assertFalse(t.onDeadline(1_000L + 29 * 60_000L))
        assertTrue(t.onDeadline(1_000L + 30 * 60_000L))
        assertEquals(SleepTimer.Mode.Off, t.mode)
        assertFalse(t.onDeadline(Long.MAX_VALUE))
    }

    @Test
    fun `end of episode stops once and does not affect timed deadlines`() {
        val t = SleepTimer()
        t.setEndOfEpisode()
        assertFalse(t.onDeadline(Long.MAX_VALUE))
        assertNull(t.remainingMs(0L))
        assertTrue(t.onEpisodeEnded())
        assertFalse(t.onEpisodeEnded())
    }

    @Test
    fun `a timed timer never blocks autoplay, cancel and zero turn it off`() {
        val t = SleepTimer()
        t.setMinutes(15, 0L)
        assertFalse(t.onEpisodeEnded())
        t.cancel()
        assertEquals(SleepTimer.Mode.Off, t.mode)
        t.setMinutes(0, 0L)
        assertEquals(SleepTimer.Mode.Off, t.mode)
        assertEquals(0, SleepTimer.CHOICES_MINUTES.first())
        assertEquals(-1, SleepTimer.CHOICES_MINUTES.last())
    }

    @Test
    fun `dialog shows the running choice and whole minutes left`() {
        val t = SleepTimer()
        assertEquals(SleepTimer.CHOICES_MINUTES.indexOf(0), t.checkedChoice())
        t.setMinutes(30, nowMs = 0L)
        assertEquals(SleepTimer.CHOICES_MINUTES.indexOf(30), t.checkedChoice())
        assertEquals(30, t.remainingMinutes(0L))
        assertEquals(23, t.remainingMinutes(7 * 60_000L))
        assertEquals("59 s left shows 1", 1, t.remainingMinutes(30 * 60_000L - 59_000L))
        t.setEndOfEpisode()
        assertEquals(SleepTimer.CHOICES_MINUTES.indexOf(-1), t.checkedChoice())
        assertNull(t.remainingMinutes(0L))
    }
}
