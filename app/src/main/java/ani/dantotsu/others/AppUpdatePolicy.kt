package ani.dantotsu.others

/**
 * Pure update policy for the Google-flavor in-app updater.
 *
 * Two deterministic pieces the old updater got wrong:
 * 1. APK selection took the FIRST `.apk` asset (GitHub API order), so an
 *    fdroid asset could be offered to Google users. Selection is now by
 *    filename flavor/ABI tokens, order-independent.
 * 2. Version comparison collapsed everything at `-` (`0.5.0-beta01` ==
 *    `0.5.0-beta02`), so prerelease lines never saw updates. The
 *    comparator understands our published forms.
 *
 * The fdroid flavor updater remains a no-op (separate file, untouched).
 */
object AppUpdatePolicy {

    /**
     * Selects the Google release APK from a release's asset URLs.
     * Deterministic regardless of API ordering; returns null when no
     * compatible Google APK exists (caller falls back to the release page).
     */
    fun selectGoogleApkAsset(
        assetUrls: List<String>,
        supportedAbis: List<String>
    ): String? {
        val abis = supportedAbis.map { it.lowercase() }.toSet()
        val googleApks = assetUrls
            .filter { it.lowercase().endsWith(".apk") }
            .filter { "-google-" in it.lowercase() }
            .sorted()
        if (googleApks.isEmpty()) return null
        if ("arm64-v8a" in abis) {
            googleApks.firstOrNull { "arm64-v8a" in it.lowercase() }?.let { return it }
        }
        return googleApks.firstOrNull { "universal" in it.lowercase() }
    }

    /**
     * A GitHub release as seen by the updater channel rule.
     */
    data class ReleaseInfo(
        val tag: String,
        val prerelease: Boolean,
        val createdAt: String,
        val body: String?,
        val assetUrls: List<String>
    )

    /**
     * Deterministic release-channel selection.
     *
     * Beta applications (`ani.editotsu.beta`) follow the prerelease
     * channel; stable applications (`ani.editotsu`) follow stable
     * releases only. A beta app never takes a stable-package APK as an
     * in-place update and a stable app never auto-installs a beta
     * prerelease — regardless of which release GitHub lists newest.
     * Returns null when no release exists for the channel (caller falls
     * back to the release page / quiet no-update).
     */
    fun selectRelease(
        releases: List<ReleaseInfo>,
        wantPrerelease: Boolean
    ): ReleaseInfo? {
        return releases
            .filter { !it.tag.contains("fdroid", ignoreCase = true) }
            .filter { it.prerelease == wantPrerelease }
            .maxByOrNull { it.createdAt }
    }

    /**
     * Beta application identity check: alpha/beta build types share the
     * `.beta` applicationId suffix and follow the prerelease channel.
     */
    fun isBetaApplication(applicationId: String): Boolean =
        applicationId.endsWith(".beta", ignoreCase = true)

    data class ParsedVersion(
        val major: Int,
        val minor: Int,
        val patch: Int,
        // stable > beta > alpha; unknown qualifier ranks lowest
        val qualifierRank: Int,
        val qualifierNum: Int
    )

    private val VERSION_REGEX =
        Regex("""^v?(\d+)\.(\d+)\.(\d+)(?:-(alpha|beta)(\d*))?$""", RegexOption.IGNORE_CASE)

    fun parseVersion(raw: String): ParsedVersion? {
        val m = VERSION_REGEX.matchEntire(raw.trim()) ?: return null
        val rank = when (m.groupValues[4].lowercase()) {
            "" -> 2
            "beta" -> 1
            "alpha" -> 0
            else -> -1
        }
        return ParsedVersion(
            major = m.groupValues[1].toInt(),
            minor = m.groupValues[2].toInt(),
            patch = m.groupValues[3].toInt(),
            qualifierRank = rank,
            qualifierNum = m.groupValues[5].toIntOrNull() ?: 0
        )
    }

    /**
     * True when [latest] is strictly newer than [current].
     * Unparseable input never prompts (safe default).
     */
    fun isUpdateAvailable(current: String, latest: String): Boolean {
        val c = parseVersion(current) ?: return false
        val l = parseVersion(latest) ?: return false
        val byTriple = compareValuesBy(
            l, c,
            { it.major }, { it.minor }, { it.patch },
            { it.qualifierRank }, { it.qualifierNum }
        )
        return byTriple > 0
    }
}
