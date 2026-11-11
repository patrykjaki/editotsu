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

    @Test
    fun stagesRankAlphaBetaRcStable() {
        assertTrue(AppUpdatePolicy.isUpdateAvailable("0.9.0-alpha03", "0.9.0-beta01"))
        assertTrue(AppUpdatePolicy.isUpdateAvailable("0.9.0-beta29", "0.9.0-rc01"))
        assertTrue(AppUpdatePolicy.isUpdateAvailable("0.9.0-rc01", "0.9.0-rc02"))
        assertTrue(AppUpdatePolicy.isUpdateAvailable("0.9.0-rc48", "0.9.0"))
        assertTrue(AppUpdatePolicy.isUpdateAvailable("0.9.0-rc02", "1.0.0"))
        assertFalse(AppUpdatePolicy.isUpdateAvailable("0.9.0-rc01", "0.9.0-beta05"))
        assertFalse(AppUpdatePolicy.isUpdateAvailable("0.9.0", "0.9.0-rc01"))
        assertFalse(AppUpdatePolicy.isUpdateAvailable("0.9.0-rc02", "0.9.0-rc02"))
    }

    @Test
    fun rcParses() {
        val v = AppUpdatePolicy.parseVersion("v0.9.0-rc01")!!
        assertEquals(2, v.qualifierRank)
        assertEquals(1, v.qualifierNum)
        assertEquals(3, AppUpdatePolicy.parseVersion("1.0.0")!!.qualifierRank)
    }

    @Test
    fun buildIdIsIgnored() {
        assertEquals(
            AppUpdatePolicy.parseVersion("0.8.0-alpha02"),
            AppUpdatePolicy.parseVersion("0.8.0-alpha02+dev.1a2b3c4d.dirty")
        )
        assertTrue(AppUpdatePolicy.isUpdateAvailable("0.8.0-alpha02+r23.1a2b3c4d", "0.8.0-beta01"))
        assertFalse(AppUpdatePolicy.isUpdateAvailable("0.8.0-beta01+r23.1a2b3c4d", "0.8.0-beta01"))
    }

    @Test
    fun newestByVersionNotByDate() {
        // a backport tagged later must not win over a newer version
        val releases = listOf(
            release("v1.1.0", false, "2027-03-01T10:00:00Z"),
            release("v1.0.2", false, "2027-03-05T10:00:00Z")
        )
        assertEquals("v1.1.0", AppUpdatePolicy.selectRelease(releases, wantPrerelease = false)?.tag)
        val pre = listOf(
            release("v0.9.0-rc02", true, "2026-12-01T10:00:00Z"),
            release("v0.9.0-rc01", true, "2026-12-05T10:00:00Z"),
            release("v0.9.0-beta09", true, "2026-12-09T10:00:00Z")
        )
        assertEquals("v0.9.0-rc02", AppUpdatePolicy.selectRelease(pre, wantPrerelease = true)?.tag)
    }

    @Test
    fun unparseableTagsRankBelowParsedOnes() {
        val releases = listOf(
            release("nightly", true, "2027-01-01T10:00:00Z"),
            release("v0.9.0-beta01", true, "2026-11-01T10:00:00Z")
        )
        assertEquals("v0.9.0-beta01", AppUpdatePolicy.selectRelease(releases, wantPrerelease = true)?.tag)
    }

    private val BETA_ID = "ani.editotsu.beta"
    private val STABLE_ID = "ani.editotsu"

    @Test
    fun betaAppGetsInPlaceUpdateFromPrereleases() {
        val releases = listOf(
            release("v0.9.0-rc01", true, "2026-12-01T10:00:00Z"),
            release("v0.8.0-beta02", true, "2026-11-01T10:00:00Z")
        )
        val d = AppUpdatePolicy.decide(releases, "0.8.0-beta02", BETA_ID)
        assertEquals(AppUpdatePolicy.Decision.Update(releases[0], "0.9.0-rc01"), d)
    }

    @Test
    fun betaAppSeesStableReleaseAsNotice() {
        val releases = listOf(
            release("v1.0.0", false, "2027-01-10T10:00:00Z"),
            release("v0.9.0-rc02", true, "2026-12-20T10:00:00Z")
        )
        val d = AppUpdatePolicy.decide(releases, "0.9.0-rc02", BETA_ID)
        assertEquals(AppUpdatePolicy.Decision.StableNotice(releases[0], "1.0.0"), d)
        // also when an older prerelease would still be an update
        val d2 = AppUpdatePolicy.decide(releases, "0.9.0-rc01", BETA_ID)
        assertTrue(d2 is AppUpdatePolicy.Decision.StableNotice)
    }

    @Test
    fun betaAppPrefersNewerPrereleaseOverOlderStable() {
        val releases = listOf(
            release("v1.0.0", false, "2027-01-10T10:00:00Z"),
            release("v1.1.0-beta01", true, "2027-02-10T10:00:00Z")
        )
        assertEquals(
            AppUpdatePolicy.Decision.Update(releases[1], "1.1.0-beta01"),
            AppUpdatePolicy.decide(releases, "0.9.0-rc02", BETA_ID)
        )
        // a beta of the next minor is newer than the stable it follows: no notice
        assertEquals(
            AppUpdatePolicy.Decision.None,
            AppUpdatePolicy.decide(releases, "1.1.0-beta01", BETA_ID)
        )
    }

    @Test
    fun stableAppNeverGetsPrereleasesOrNotices() {
        val releases = listOf(
            release("v1.1.0-beta01", true, "2027-02-10T10:00:00Z"),
            release("v1.0.0", false, "2027-01-10T10:00:00Z")
        )
        assertEquals(AppUpdatePolicy.Decision.None, AppUpdatePolicy.decide(releases, "1.0.0", STABLE_ID))
        assertEquals(
            AppUpdatePolicy.Decision.Update(releases[1], "1.0.0"),
            AppUpdatePolicy.decide(releases, "0.9.9", STABLE_ID)
        )
    }

    @Test
    fun nothingNewerMeansNone() {
        val releases = listOf(release("v0.8.0-alpha02", true, "2026-11-01T10:00:00Z"))
        assertEquals(AppUpdatePolicy.Decision.None, AppUpdatePolicy.decide(releases, "0.8.0-alpha02+dev.1a2b3c4d", BETA_ID))
        assertEquals(AppUpdatePolicy.Decision.None, AppUpdatePolicy.decide(emptyList(), "0.8.0-alpha02", BETA_ID))
    }

    @Test
    fun downloadReceiverMatchesOnlyItsOwnId() {
        assertTrue(AppUpdatePolicy.isOwnDownload(42L, 42L))
        assertFalse(AppUpdatePolicy.isOwnDownload(42L, 43L))
        assertFalse(AppUpdatePolicy.isOwnDownload(42L, -1L))
        assertFalse(AppUpdatePolicy.isOwnDownload(-1L, -1L))
    }
}
