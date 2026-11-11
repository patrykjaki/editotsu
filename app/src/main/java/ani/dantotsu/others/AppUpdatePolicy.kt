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
 *    comparator understands our published forms (alpha < beta < rc <
 *    stable) and ignores a `+build` suffix.
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
     * prerelease, regardless of which release GitHub lists newest.
     * Within the channel the highest version wins; GitHub's `created_at`
     * (date of the tagged commit) only breaks ties and orders releases
     * whose tag does not parse, which rank below every parsed one.
     * Returns null when no release exists for the channel.
     */
    fun selectRelease(
        releases: List<ReleaseInfo>,
        wantPrerelease: Boolean
    ): ReleaseInfo? {
        return releases
            .filter { !it.tag.contains("fdroid", ignoreCase = true) }
            .filter { it.prerelease == wantPrerelease }
            .maxWithOrNull(
                compareBy<ReleaseInfo, ParsedVersion?>(nullsFirst()) { parseVersion(it.tag) }
                    .thenBy { it.createdAt }
            )
    }

    /**
     * Beta application identity check: alpha/beta build types share the
     * `.beta` applicationId suffix and follow the prerelease channel.
     */
    fun isBetaApplication(applicationId: String): Boolean =
        applicationId.endsWith(".beta", ignoreCase = true)

    /** True when a DownloadManager completion broadcast is for the update download [ownId]. */
    fun isOwnDownload(ownId: Long, receivedId: Long): Boolean = ownId != -1L && receivedId == ownId

    /** Version named by a release tag (`v1.0.0` -> `1.0.0`). */
    fun versionOf(release: ReleaseInfo): String = release.tag.trim().removePrefix("v").removePrefix("V")

    /** What the updater should show for the releases it fetched. */
    sealed interface Decision {
        data object None : Decision

        /** A newer release of this app's own channel: offer the in-place update. */
        data class Update(val release: ReleaseInfo, val version: String) : Decision

        /**
         * Beta app only: a stable release newer than this build exists. Stable ships as a
         * different package (`ani.editotsu`), so it cannot be installed over this app; show
         * a notice with a link to the release page instead.
         */
        data class StableNotice(val release: ReleaseInfo, val version: String) : Decision
    }

    fun decide(
        releases: List<ReleaseInfo>,
        currentVersion: String,
        applicationId: String
    ): Decision {
        val beta = isBetaApplication(applicationId)
        val update = selectRelease(releases, wantPrerelease = beta)
            ?.takeIf { isUpdateAvailable(currentVersion, versionOf(it)) }
        if (beta) {
            val stable = selectRelease(releases, wantPrerelease = false)
                ?.takeIf { isUpdateAvailable(currentVersion, versionOf(it)) }
            // Prefer the in-place update unless the stable release is at least as new.
            if (stable != null && (update == null || !isUpdateAvailable(versionOf(stable), versionOf(update)))) {
                return Decision.StableNotice(stable, versionOf(stable))
            }
        }
        return update?.let { Decision.Update(it, versionOf(it)) } ?: Decision.None
    }

    data class ParsedVersion(
        val major: Int,
        val minor: Int,
        val patch: Int,
        // stable 3 > rc 2 > beta 1 > alpha 0
        val qualifierRank: Int,
        val qualifierNum: Int
    ) : Comparable<ParsedVersion> {
        override fun compareTo(other: ParsedVersion): Int = compareValuesBy(
            this, other,
            { it.major }, { it.minor }, { it.patch },
            { it.qualifierRank }, { it.qualifierNum }
        )
    }

    private val VERSION_REGEX =
        Regex("""^v?(\d+)\.(\d+)\.(\d+)(?:-(alpha|beta|rc)(\d*))?$""", RegexOption.IGNORE_CASE)

    /**
     * Parses `MAJOR.MINOR.PATCH[-alphaNN|-betaNN|-rcNN]`, with an optional leading `v`.
     * A build id after `+` (`0.8.0-alpha02+dev.1a2b3c4d`) names the build, not the version,
     * and is ignored.
     */
    fun parseVersion(raw: String): ParsedVersion? {
        val m = VERSION_REGEX.matchEntire(raw.trim().substringBefore('+')) ?: return null
        val rank = when (m.groupValues[4].lowercase()) {
            "" -> 3
            "rc" -> 2
            "beta" -> 1
            else -> 0 // alpha
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
        return l > c
    }
}
