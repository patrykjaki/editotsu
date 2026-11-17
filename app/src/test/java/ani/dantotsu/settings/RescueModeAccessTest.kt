package ani.dantotsu.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RescueModeAccessTest {

    @Test
    fun `switch is disabled without a MAL account`() {
        assertFalse(rescueSwitchEnabled(malSignedIn = false, rescueModeOn = false))
    }

    @Test
    fun `switch is enabled with a MAL account`() {
        assertTrue(rescueSwitchEnabled(malSignedIn = true, rescueModeOn = false))
    }

    @Test
    fun `an enabled switch can always be turned off`() {
        assertTrue(rescueSwitchEnabled(malSignedIn = false, rescueModeOn = true))
        assertTrue(rescueSwitchEnabled(malSignedIn = true, rescueModeOn = true))
    }
}
