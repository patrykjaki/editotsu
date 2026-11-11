package ani.dantotsu.settings.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcessRestartTest {

    @Test
    fun reopensTheScreenWithoutAppLock() {
        assertTrue(ProcessRestart.reopensScreen(appLockSet = false))
    }

    @Test
    fun startsAtTheLockScreenWhenAppLockIsSet() {
        // The unlock state is lost with the process; reopening settings directly would skip the lock.
        assertFalse(ProcessRestart.reopensScreen(appLockSet = true))
    }
}
