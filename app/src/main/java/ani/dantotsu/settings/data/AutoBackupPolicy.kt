package ani.dantotsu.settings.data

import ani.dantotsu.settings.saving.internal.Location

/**
 * MAX MERGE M3 (upstream e954d9f1): pure auto-backup policy behind
 * [AutoBackupWorker]. Framework-free so cadence/retention/secret-exclusion
 * are unit-proved (AutoBackupPolicyTest).
 */
data class BackupCandidate(val name: String, val lastModified: Long)

fun autoBackupIntervalHours(index: Int): Long {
    return when (index) {
        1 -> 6L
        2 -> 12L
        3 -> 24L
        4 -> 48L
        5 -> 168L
        else -> 0L
    }
}

/**
 * SECRET RULE: the scheduled payload never carries [Location.Protected]
 * (auth tokens, lock secrets, proxy credentials), even though Protected
 * is exportable for manual encrypted export.
 */
fun autoBackupLocations(): List<Location> =
    plaintextBackupAllowlist()

/**
 * Plaintext backup allowlist (M3 HOLD Blockers 1+2): only explicitly
 * reviewed non-sensitive locations may enter an unencrypted backup —
 * General, UI, Player, Reader, NovelReader. This is an allowlist, never
 * a denylist: Protected (secrets) and ExtensionSettings (unclassified
 * third-party prefs) are excluded from BOTH quick and automatic
 * plaintext backups. Full/protected backup stays on the existing
 * password/encrypted `.sani` flow.
 */
fun plaintextBackupAllowlist(): List<Location> =
    Location.entries.filter {
        it == Location.General || it == Location.UI || it == Location.Player ||
            it == Location.Reader || it == Location.NovelReader
    }

/** Oldest-first prune beyond [maxCopies] (minimum 1 kept). */
fun selectBackupsToPrune(
    backups: List<BackupCandidate>,
    maxCopies: Int,
): List<BackupCandidate> {
    val keep = maxCopies.coerceAtLeast(1)
    if (backups.size <= keep) return emptyList()
    return backups.sortedBy { it.lastModified }.take(backups.size - keep)
}
