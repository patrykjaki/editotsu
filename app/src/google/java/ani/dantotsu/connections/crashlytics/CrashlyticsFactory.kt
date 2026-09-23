package ani.dantotsu.connections.crashlytics

/**
 * CP4-C (REPO_REVIEW §3.7): Editotsu currently ships NO Firebase telemetry.
 * The checked-in google-services.json targets the Dantotsu-named project whose ownership is
 * NOT verified, so the Google flavor uses the same no-op stub as F-Droid. Re-introducing a
 * real telemetry backend requires an Editotsu-owned Firebase project + privacy policy update.
 */
class CrashlyticsFactory {
    companion object {
        fun createCrashlytics(): CrashlyticsInterface {
            return CrashlyticsStub()
        }
    }
}
