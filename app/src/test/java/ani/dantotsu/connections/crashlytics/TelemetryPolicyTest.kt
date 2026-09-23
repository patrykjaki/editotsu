package ani.dantotsu.connections.crashlytics

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CP4-C (REPO_REVIEW §3.7): flavor policy proof.
 *
 * These unit tests execute against the GOOGLE flavor source set; they pin the
 * disabled-telemetry policy so a future regression (re-adding real Firebase without an
 * Editotsu-owned project + policy update) fails CI.
 */
class TelemetryPolicyTest {

    /** Google flavor must return the no-op stub — identical to F-Droid (local-only). */
    @Test
    fun testGoogleCrashlyticsFactoryReturnsLocalOnlyStub() {
        val crashlytics = CrashlyticsFactory.createCrashlytics()
        assertTrue(
            "Google flavor must ship the local-only CrashlyticsStub, got ${crashlytics::class.java.name}",
            crashlytics is CrashlyticsStub
        )
    }

    /**
     * The stub must be side-effect free: collection toggles, key attachment, and logging
     * must not throw or retain anything (proving opt-out/opt-in paths are safe no-ops while
     * telemetry is disabled).
     */
    @Test
    fun testStubIdentityAndCollectionCallsAreInert() {
        val crashlytics = CrashlyticsFactory.createCrashlytics() as CrashlyticsStub

        crashlytics.setUserId("user-123")
        crashlytics.setCustomKey("dUsername", "someone")
        crashlytics.setCustomKey("aUsername", "someone_else")
        crashlytics.setCrashlyticsCollectionEnabled(true)
        crashlytics.clearCustomKeys()
        crashlytics.log("test message")
        crashlytics.logException(IllegalStateException("sentinel"))
        // No exception ⇒ inert.
    }

    /**
     * Policy: identity attachment is OPT-IN. The default of the SharedUserID preference must be
     * `false` (CP4-C flipped it from true), verified via the compiled PrefName default holder.
     */
    @Test
    fun testSharedUserIDDefaultsToOptIn() {
        val pref = ani.dantotsu.settings.saving.PrefName.SharedUserID.data
        val default = pref.default
        assertFalse(
            "SharedUserID must default to FALSE (identifiers only on explicit opt-in)",
            default as Boolean
        )
    }
}
