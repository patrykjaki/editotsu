package ani.dantotsu.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DonatePromptTest {
    private val day = 24L * 60 * 60 * 1000
    private val now = 100L * day

    @Test
    fun neverTwiceWithinTheGap() {
        assertFalse(DonatePrompt.shouldShow(now, now - day, roll = 99, recreated = false))
        assertTrue(DonatePrompt.shouldShow(now, now - 31 * day, roll = 99, recreated = false))
    }

    @Test
    fun firstTimeFollowsTheRoll() {
        assertTrue(DonatePrompt.shouldShow(now, 0L, roll = 70, recreated = false))
        assertFalse(DonatePrompt.shouldShow(now, 0L, roll = 69, recreated = false))
    }

    @Test
    fun notOnRecreate() {
        assertFalse(DonatePrompt.shouldShow(now, 0L, roll = 99, recreated = true))
    }
}
