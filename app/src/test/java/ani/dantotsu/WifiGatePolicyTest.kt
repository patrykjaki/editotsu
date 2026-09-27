package ani.dantotsu

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MAX MERGE M3 (upstream 3dbce385): Wi-Fi-only gate transport policy.
 * Cellular never satisfies the gate; Wi-Fi/Ethernet/Aware do.
 */
class WifiGatePolicyTest {

    @Test
    fun `wifi satisfies`() {
        assertTrue(wifiGateSatisfied(hasWifi = true, hasEthernet = false, hasWifiAware = false))
    }

    @Test
    fun `ethernet satisfies`() {
        assertTrue(wifiGateSatisfied(hasWifi = false, hasEthernet = true, hasWifiAware = false))
    }

    @Test
    fun `wifi-aware satisfies`() {
        assertTrue(wifiGateSatisfied(hasWifi = false, hasEthernet = false, hasWifiAware = true))
    }

    @Test
    fun `nothing satisfies nothing`() {
        assertFalse(wifiGateSatisfied(hasWifi = false, hasEthernet = false, hasWifiAware = false))
    }
}
