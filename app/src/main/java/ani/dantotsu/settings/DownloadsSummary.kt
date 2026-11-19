package ani.dantotsu.settings

import ani.dantotsu.media.MediaType

/**
 * Pure presentation helpers for the Downloads screen ([DownloadQueueActivity]):
 * progress-key alignment with the three download services, aggregate storage/count
 * overview across Anime/Manga/Novel, and segment labels.
 */
object DownloadsSummary {

    /** Stable cache key scoped by media type so identical titles across Anime/Manga/Novel don't collide. */
    fun mediaCacheKey(type: MediaType, title: String): String = "${type.name}:$title"

    /**
     * Exact progress-map key matching `MangaDownloaderService`'s
     * `MangaServiceDataSingleton.progress["${task.title}_${task.chapter}"]`.
     */
    fun mangaProgressKey(title: String, chapter: String): String = "${title}_${chapter}"

    /** Segment label: plain name when idle, `Name (N)` when that category has active or queued work. */
    fun segmentLabel(baseLabel: String, queueCount: Int): String =
        if (queueCount > 0) "$baseLabel ($queueCount)" else baseLabel

    /** Percentage [0..100] of device storage currently used; 0 when capacity is unknown. */
    fun storageUsedPercent(totalBytes: Long, availableBytes: Long): Int {
        if (totalBytes <= 0L) return 0
        val used = (totalBytes - availableBytes).coerceIn(0L, totalBytes)
        return ((used * 100L) / totalBytes).toInt().coerceIn(0, 100)
    }

    /** True when the Clear Queue action should be exposed. */
    fun hasQueuedWork(animeQueue: Int, mangaQueue: Int, novelQueue: Int): Boolean =
        animeQueue + mangaQueue + novelQueue > 0

    data class Overview(
        val totalDownloadedBytes: Long,
        val sizesComplete: Boolean,
        val animeTitles: Int,
        val mangaTitles: Int,
        val novelTitles: Int,
        val activeCount: Int,
        val queuedCount: Int,
    ) {
        val totalTitles: Int get() = animeTitles + mangaTitles + novelTitles
        val totalInProgress: Int get() = activeCount + queuedCount
    }

    /**
     * Aggregates counts and cached byte sizes across all three download types.
     * [sizesComplete] is true once every known downloaded title has a measured size in [sizeByKey].
     */
    fun computeOverview(
        animeTitleSet: Set<String>,
        mangaTitleSet: Set<String>,
        novelTitleSet: Set<String>,
        sizeByKey: Map<String, Long>,
        activeCount: Int,
        queuedCount: Int,
    ): Overview {
        var totalBytes = 0L
        var allMeasured = true
        fun accumulate(type: MediaType, titles: Set<String>) {
            for (title in titles) {
                val measured = sizeByKey[mediaCacheKey(type, title)]
                if (measured == null) {
                    allMeasured = false
                } else {
                    totalBytes += measured.coerceAtLeast(0L)
                }
            }
        }
        accumulate(MediaType.ANIME, animeTitleSet)
        accumulate(MediaType.MANGA, mangaTitleSet)
        accumulate(MediaType.NOVEL, novelTitleSet)
        return Overview(
            totalDownloadedBytes = totalBytes,
            sizesComplete = allMeasured,
            animeTitles = animeTitleSet.size,
            mangaTitles = mangaTitleSet.size,
            novelTitles = novelTitleSet.size,
            activeCount = activeCount.coerceAtLeast(0),
            queuedCount = queuedCount.coerceAtLeast(0),
        )
    }
}
