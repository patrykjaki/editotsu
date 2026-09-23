package ani.dantotsu.download.manga

import ani.dantotsu.download.DownloadedType
import ani.dantotsu.media.MediaType

/**
 * Pure selection logic for manga download reconciliation.
 *
 * Given the current download metadata and a completeness checker, returns exactly
 * the entries that should be pruned (their persisted page set is known-incomplete).
 *
 * Rules:
 * - only manga entries are considered;
 * - only entries with a known expected [DownloadedType.pageCount] are checked, so
 *   legacy downloads (pageCount == null) are never pruned;
 * - an entry whose [isComplete] check fails is selected for removal.
 *
 * This is kept free of DocumentFile/Android access so it can be unit tested with a
 * fake completeness checker; [DownloadsManager] supplies the real checker.
 */
object MangaDownloadReconciliation {

    fun selectIncomplete(
        downloads: List<DownloadedType>,
        isComplete: (DownloadedType) -> Boolean
    ): List<DownloadedType> {
        return downloads.filter {
            it.type == MediaType.MANGA && it.pageCount != null && !isComplete(it)
        }
    }

    /**
     * Storage-aware reconciliation entry point (pure, unit-tested).
     *
     * When the SAF manga root is unavailable/unresolvable ([baseAvailable] false),
     * absence cannot be verified, so NOTHING is selected — the probe is never even
     * invoked — and all COMPLETE metadata (including CP5 pageCount entries) is
     * preserved. Only with a resolvable root does an actually-incomplete page set
     * prune its entry.
     */
    fun selectReconcileTargets(
        baseAvailable: Boolean,
        downloads: List<DownloadedType>,
        isComplete: (DownloadedType) -> Boolean
    ): List<DownloadedType> =
        if (!baseAvailable) emptyList() else selectIncomplete(downloads, isComplete)
}
