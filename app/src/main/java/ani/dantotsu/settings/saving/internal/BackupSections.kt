package ani.dantotsu.settings.saving.internal

/**
 * What a backup contains, in the groups the backup dialog offers.
 *
 * Settings are always included: they also hold the extension repo lists,
 * so a restore can reinstall extensions from the same repos.
 */
enum class BackupSection {
    Settings,
    Extensions,
    History,
    Logins,
}

object BackupSections {
    /** Default shared preferences (Aniyomi/Tachiyomi store): hidden and pinned sources, installer. */
    const val APP_PREFS = "app_prefs"

    /** LNReader plugin key-value storage. */
    const val NOVEL_PLUGIN_STORAGE = "lnreader_plugin_storage"

    /**
     * Installed extensions at backup time, one String entry per package:
     * key = package name, value = "<type>|<name>|<versionName>". Restore never
     * writes it; it lists what still has to be installed.
     */
    const val INSTALLED_EXTENSIONS = "installed_extensions"

    /** Payload keys that are not a [Location] and not a `source_*` file. */
    val EXTRA_KEYS = setOf(APP_PREFS, NOVEL_PLUGIN_STORAGE, INSTALLED_EXTENSIONS)

    /** Restore adds these keys to what is there instead of replacing the whole file. */
    val MERGE_ON_RESTORE = setOf(Location.Irrelevant.name, APP_PREFS, NOVEL_PLUGIN_STORAGE)

    fun locationsFor(sections: Set<BackupSection>): List<Location> = buildList {
        add(Location.General)
        add(Location.UI)
        add(Location.Player)
        add(Location.Reader)
        add(Location.NovelReader)
        if (BackupSection.Extensions in sections) add(Location.ExtensionSettings)
        if (BackupSection.History in sections) add(Location.Irrelevant)
        if (BackupSection.Logins in sections) add(Location.Protected)
    }

    /**
     * History keys (the Irrelevant file) that are left out: caches, one-off
     * flags, notification state, comment-login tokens, and storage paths whose
     * access grant does not survive a reinstall.
     */
    private val HISTORY_SKIPPED_KEYS = setOf(
        "RescueMode",
        "PendingDeletions",
        "OfflineMode",
        "DownloadsKeys",
        "NovelLastExtCheck",
        "ImageUrl",
        "HasUpdatedPrefs",
        "GenresList",
        "TagsListIsAdult",
        "TagsListNonAdult",
        "FirstComment",
        "CommentAuthResponse",
        "CommentTokenExpiry",
        "LogToFile",
        "RecentGlobalNotification",
        "CommentNotificationStore",
        "SubscriptionNotificationStore",
        "UnreadCommentNotifications",
        "DownloadsDir",
        "LocalDir",
        "RefreshStatus",
        "reload",
        "backup_missing_extensions",
        "home_page_cache",
        "user_status_cache",
    )

    private val HISTORY_SKIPPED_PREFIXES = listOf(
        "banner_",
        "media_title_",
        "media_cover_",
        "media_color_",
    )

    fun keepHistoryKey(key: String): Boolean =
        key !in HISTORY_SKIPPED_KEYS && HISTORY_SKIPPED_PREFIXES.none { key.startsWith(it) }

    /**
     * The MAL token is stored encrypted with an Android Keystore key that only
     * this install has, so the stored value is useless after a reinstall. The
     * backup carries the decrypted token under this key instead (only in the
     * password-encrypted logins file); restore encrypts it again with the new
     * install's key.
     */
    const val MAL_TOKEN_BACKUP = "MALTokenBackup"

    /** Login keys left out: install-bound ciphertext and half-finished OAuth state. */
    val PROTECTED_SKIPPED_KEYS = setOf("MALEncryptedToken", "MALAuthSession", "MALCodeChallenge", "MALToken")

    fun keepProtectedKey(key: String): Boolean = key !in PROTECTED_SKIPPED_KEYS

    /** Counters the app recomputes on start. */
    private val APP_PREFS_SKIPPED_KEYS = setOf("animeext_updates_count", "ext_updates_count")

    fun keepAppPrefKey(key: String): Boolean = key !in APP_PREFS_SKIPPED_KEYS

    fun extensionEntry(type: String, name: String, versionName: String): String =
        "$type|$name|$versionName"

    /** Display names of backed-up extensions whose package is not installed now. */
    fun missingExtensions(backedUp: Map<String, String>, installedPackages: Set<String>): List<String> =
        backedUp.filterKeys { it !in installedPackages }
            .map { (pkg, entry) -> entry.split('|').getOrNull(1)?.takeIf { it.isNotBlank() } ?: pkg }
            .sorted()
}
