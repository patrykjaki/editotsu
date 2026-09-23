package ani.dantotsu.others

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdatePolicyTest {

    private val arm64 = listOf("arm64-v8a", "armeabi-v7a")
    private val x86 = listOf("x86_64", "x86")

    private val assets = listOf(
        "https://github.com/patrykjaki/editotsu/releases/download/v0.5.0-beta01/Editotsu-0.5.0-beta01-google-arm64-v8a.apk",
        "https://github.com/patrykjaki/editotsu/releases/download/v0.5.0-beta01/Editotsu-0.5.0-beta01-google-universal.apk",
        "https://github.com/patrykjaki/editotsu/releases/download/v0.5.0-beta01/Editotsu-0.5.0-beta01-fdroid-arm64-v8a.apk",
        "https://github.com/patrykjaki/editotsu/releases/download/v0.5.0-beta01/Editotsu-0.5.0-beta01-fdroid-universal.apk",
        "https://github.com/patrykjaki/editotsu/releases/download/v0.5.0-beta01/SHA256SUMS"
    )

    @Test
    fun arm64DeviceSelectsGoogleArm64() {
        assertEquals(
            assets[0],
            AppUpdatePolicy.selectGoogleApkAsset(assets, arm64)
        )
    }

    @Test
    fun nonArm64DeviceSelectsGoogleUniversal() {
        assertEquals(
            assets[1],
            AppUpdatePolicy.selectGoogleApkAsset(assets, x86)
        )
    }

    @Test
    fun arm64MissingFallsBackToUniversal() {
        val noArm64 = assets.filterNot { "arm64" in it }
        assertEquals(
            assets[1],
            AppUpdatePolicy.selectGoogleApkAsset(noArm64, arm64)
        )
    }

    @Test
    fun fdroidListedFirstStillSelectsGoogle() {
        val reordered = listOf(assets[2], assets[3], assets[4], assets[0], assets[1])
        assertEquals(
            assets[0],
            AppUpdatePolicy.selectGoogleApkAsset(reordered, arm64)
        )
    }

    @Test
    fun arbitraryOrderingIsStable() {
        val shuffled = assets.shuffled()
        assertEquals(
            AppUpdatePolicy.selectGoogleApkAsset(assets, arm64),
            AppUpdatePolicy.selectGoogleApkAsset(shuffled, arm64)
        )
    }

    @Test
    fun nonApkEntriesIgnored() {
        assertEquals(
            assets[0],
            AppUpdatePolicy.selectGoogleApkAsset(listOf(assets[4], assets[0]), arm64)
        )
    }

    @Test
    fun onlyIncompatibleApksSelectsNothing() {
        val fdroidOnly = listOf(assets[2], assets[3])
        assertNull(AppUpdatePolicy.selectGoogleApkAsset(fdroidOnly, arm64))
        assertNull(AppUpdatePolicy.selectGoogleApkAsset(emptyList(), arm64))
        assertNull(
            AppUpdatePolicy.selectGoogleApkAsset(
                listOf(assets[0].replace("-google-", "-fdroid-")),
                emptyList()
            )
        )
    }

    @Test
    fun betaLineOrdering() {
        assertTrue(AppUpdatePolicy.isUpdateAvailable("0.5.0-beta01", "0.5.0-beta02"))
        assertTrue(AppUpdatePolicy.isUpdateAvailable("0.5.0-beta09", "0.5.0-beta10"))
        assertTrue(AppUpdatePolicy.isUpdateAvailable("0.5.0-beta99", "0.5.0"))
        assertTrue(AppUpdatePolicy.isUpdateAvailable("0.5.0", "0.6.0-beta01"))
        assertTrue(AppUpdatePolicy.isUpdateAvailable("0.4.7", "0.5.0-beta01"))
    }

    @Test
    fun equalVersionsAreNotUpdates() {
        assertFalse(AppUpdatePolicy.isUpdateAvailable("0.5.0-beta01", "0.5.0-beta01"))
        assertFalse(AppUpdatePolicy.isUpdateAvailable("0.5.0", "0.5.0"))
        assertFalse(AppUpdatePolicy.isUpdateAvailable("0.5.0-beta02", "0.5.0-beta01"))
    }

    @Test
    fun legacyVersionsSeeBeta01AsNewer() {
        assertTrue(AppUpdatePolicy.isUpdateAvailable("0.1.0-alpha", "0.5.0-beta01"))
        assertTrue(AppUpdatePolicy.isUpdateAvailable("0.2.0-alpha", "0.5.0-beta01"))
        assertTrue(AppUpdatePolicy.isUpdateAvailable("0.2.1", "0.5.0-beta01"))
    }

    @Test
    fun unparseableVersionsNeverPrompt() {
        assertFalse(AppUpdatePolicy.isUpdateAvailable("nogit", "0.5.0-beta01"))
        assertFalse(AppUpdatePolicy.isUpdateAvailable("0.5.0-beta01", "weird"))
        assertFalse(AppUpdatePolicy.isUpdateAvailable("", ""))
    }

    private fun release(
        tag: String,
        prerelease: Boolean,
        createdAt: String
    ) = AppUpdatePolicy.ReleaseInfo(
        tag = tag,
        prerelease = prerelease,
        createdAt = createdAt,
        body = null,
        assetUrls = emptyList()
    )

    private val channelReleases = listOf(
        release("v0.5.0-beta01", true, "2026-09-20T10:00:00Z"),
        release("v0.4.9", false, "2026-09-21T10:00:00Z"),
        release("v0.5.0-beta02", true, "2026-09-22T10:00:00Z"),
        release("v0.6.0", false, "2026-09-23T10:00:00Z")
    )

    @Test
    fun betaAppSeesNewestPrereleaseOnly() {
        assertEquals(
            "v0.5.0-beta02",
            AppUpdatePolicy.selectRelease(channelReleases, wantPrerelease = true)?.tag
        )
    }

    @Test
    fun stableAppIgnoresPrereleases() {
        assertEquals(
            "v0.6.0",
            AppUpdatePolicy.selectRelease(channelReleases, wantPrerelease = false)?.tag
        )
    }

    @Test
    fun noReleaseForChannelSelectsNothing() {
        val stableOnly = listOf(release("v0.4.9", false, "2026-09-21T10:00:00Z"))
        assertNull(AppUpdatePolicy.selectRelease(stableOnly, wantPrerelease = true))
        assertNull(AppUpdatePolicy.selectRelease(emptyList(), wantPrerelease = false))
    }

    @Test
    fun betaIdentityFollowsPackageSuffix() {
        assertTrue(AppUpdatePolicy.isBetaApplication("ani.editotsu.beta"))
        assertFalse(AppUpdatePolicy.isBetaApplication("ani.editotsu"))
    }
}
