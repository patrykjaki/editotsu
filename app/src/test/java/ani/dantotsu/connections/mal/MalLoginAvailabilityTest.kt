package ani.dantotsu.connections.mal

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Blank-ID MAL login eligibility.
 *
 * No-credential (local/source) builds resolve BuildConfig.MAL_CLIENT_ID
 * to blank; the account screen must hide/disable login with a truthful
 * explanation instead of offering an action that can only fail.
 * Official release artifacts should never hit the blank state.
 */
class MalLoginAvailabilityTest {

    @Test
    fun blankClientIdIsUnavailable() {
        assertFalse(MAL.isLoginAvailable(""))
        assertFalse(MAL.isLoginAvailable("   "))
        assertFalse(MAL.isLoginAvailable("\t\n "))
    }

    @Test
    fun nonBlankClientIdIsAvailable() {
        assertTrue(MAL.isLoginAvailable("abcdef123456"))
        assertTrue(MAL.isLoginAvailable("  abcdef123456  "))
    }

    @Test
    fun explicitOverrideDrivesAvailability() {
        // The ambient BuildConfig value is environment-dependent (local
        // credential files may inject a real ID), so delegation is proven
        // through the explicit test override instead of the ambient default.
        MAL.testClientId = ""
        assertFalse(MAL.isLoginAvailable())
        MAL.testClientId = "abcdef123456"
        assertTrue(MAL.isLoginAvailable())
        MAL.testClientId = null
    }
}
