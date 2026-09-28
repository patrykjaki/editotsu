package ani.dantotsu.download

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * MAX MERGE M3 HOLD (Blocker 6): transition state machine — block exactly
 * once per Wi-Fi loss, restore cleanly, never auto-resume.
 */
class WifiGateTransitionTest {

    @Test
    fun `wifi start allowed`() {
        assertEquals(
            WifiGateAction.ALLOWED,
            nextWifiGateAction(prefOn = true, satisfied = true, wasBlocked = false)
        )
    }

    @Test
    fun `ethernet start allowed`() {
        // Ethernet counts as an allowed transport (policy input).
        assertEquals(
            WifiGateAction.ALLOWED,
            nextWifiGateAction(prefOn = true, satisfied = true, wasBlocked = true)
        )
    }

    @Test
    fun `cellular start blocked`() {
        assertEquals(
            WifiGateAction.BLOCK_NOW,
            nextWifiGateAction(prefOn = true, satisfied = false, wasBlocked = false)
        )
    }

    @Test
    fun `wifi to cellular blocks exactly once`() {
        assertEquals(
            WifiGateAction.BLOCK_NOW,
            nextWifiGateAction(prefOn = true, satisfied = false, wasBlocked = false)
        )
        assertEquals(
            WifiGateAction.ALREADY_BLOCKED,
            nextWifiGateAction(prefOn = true, satisfied = false, wasBlocked = true)
        )
    }

    @Test
    fun `pref off never blocks`() {
        assertEquals(
            WifiGateAction.ALLOWED,
            nextWifiGateAction(prefOn = false, satisfied = false, wasBlocked = false)
        )
        assertEquals(
            WifiGateAction.ALLOWED,
            nextWifiGateAction(prefOn = false, satisfied = false, wasBlocked = true)
        )
    }

    @Test
    fun `wifi restored returns to allowed with no stale block`() {
        // Restored: allowed again; a subsequent loss re-blocks exactly once
        // (manual retry possible, no stale owner implied by the machine).
        assertEquals(
            WifiGateAction.ALLOWED,
            nextWifiGateAction(prefOn = true, satisfied = true, wasBlocked = true)
        )
    }
}
