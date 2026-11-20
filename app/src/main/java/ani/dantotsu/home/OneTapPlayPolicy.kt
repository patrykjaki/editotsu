package ani.dantotsu.home

import ani.dantotsu.media.MediaNameAdapter
import ani.dantotsu.media.anime.player.ResumeStart

/**
 * Pure decision logic for one-tap play from Continue Watching on Home.
 *
 * Uses the same stores the app already persists per show (`Selected`, `SelectedSource`,
 * `SelectedKeyStore`, `SelectedFamilyStore`, `<id>_current_ep`, and episode resume keys).
 */
object OneTapPlayPolicy {

    private val NON_SEASON_SCANLATOR = Regex(
        "^(sub|dub|raw|unknown|default|subbed|dubbed|s-sub|softsub)$",
        RegexOption.IGNORE_CASE,
    )

    /** Snapshot of the show's existing persisted playback choices. */
    data class RememberedPlayback(
        val sourceIndex: Int,
        val sourceName: String?,
        val langIndex: Int,
        val preferDub: Boolean,
        val serverName: String?,
        val videoIndex: Int,
        val exactKey: String?,
        val hasFamily: Boolean,
        val hiddenScanlators: List<String> = emptyList(),
    )

    enum class FallbackReason {
        OFFLINE,
        LOCAL_MEDIA,
        NO_REMEMBERED_SOURCE,
        SOURCES_NOT_READY,
        SOURCE_MISSING,
        NO_REMEMBERED_STREAM,
    }

    sealed interface OneTapSourcePlan {
        /** Resolve the episode list and stream from [resolvedSourceIndex]. */
        data class Resolve(
            val resolvedSourceIndex: Int,
            val isDownloadedSource: Boolean,
            val serverName: String?,
            val hasExactKey: Boolean,
            val hasFamily: Boolean,
        ) : OneTapSourcePlan

        /** Open the Watch tab / source picker instead of resolving directly. */
        data class Fallback(
            val reason: FallbackReason,
            val forcePicker: Boolean,
        ) : OneTapSourcePlan
    }

    data class EpisodeEntry(
        val key: String,
        val number: String,
        val scanlator: String? = null,
    )

    data class EpisodeSavedProgress(
        val savedMs: Long,
        val maxMs: Long?,
    )

    data class TargetEpisode(
        val key: String,
        val resuming: Boolean,
    )

    /**
     * Decides whether one-tap play can attempt direct stream resolution from the show's
     * remembered source, or should immediately fall back to the Watch tab / source picker.
     */
    fun decideSource(
        isOffline: Boolean,
        isLocalMedia: Boolean,
        sourcesReady: Boolean,
        sourceNames: List<String>,
        downloadedSourceIndices: Set<Int>,
        remembered: RememberedPlayback?,
    ): OneTapSourcePlan {
        if (isOffline) {
            return OneTapSourcePlan.Fallback(FallbackReason.OFFLINE, forcePicker = false)
        }
        if (isLocalMedia) {
            return OneTapSourcePlan.Fallback(FallbackReason.LOCAL_MEDIA, forcePicker = true)
        }
        if (remembered == null) {
            return OneTapSourcePlan.Fallback(FallbackReason.NO_REMEMBERED_SOURCE, forcePicker = true)
        }
        if (!sourcesReady || sourceNames.isEmpty()) {
            return OneTapSourcePlan.Fallback(FallbackReason.SOURCES_NOT_READY, forcePicker = true)
        }

        val resolvedIndex = if (!remembered.sourceName.isNullOrBlank()) {
            val byName = sourceNames.indexOf(remembered.sourceName)
            if (byName < 0) {
                return OneTapSourcePlan.Fallback(FallbackReason.SOURCE_MISSING, forcePicker = true)
            }
            byName
        } else {
            if (remembered.sourceIndex !in sourceNames.indices) {
                return OneTapSourcePlan.Fallback(FallbackReason.SOURCE_MISSING, forcePicker = true)
            }
            remembered.sourceIndex
        }

        if (sourceNames[resolvedIndex] == "Local") {
            return OneTapSourcePlan.Fallback(FallbackReason.LOCAL_MEDIA, forcePicker = true)
        }

        val isDownloaded = resolvedIndex in downloadedSourceIndices
        val server = remembered.serverName?.takeIf { it.isNotBlank() }
        val hasExactKey = !remembered.exactKey.isNullOrBlank()
        val hasStreamMemory = server != null || hasExactKey || remembered.hasFamily

        if (!isDownloaded && !hasStreamMemory) {
            return OneTapSourcePlan.Fallback(FallbackReason.NO_REMEMBERED_STREAM, forcePicker = true)
        }

        return OneTapSourcePlan.Resolve(
            resolvedSourceIndex = resolvedIndex,
            isDownloadedSource = isDownloaded,
            serverName = server,
            hasExactKey = hasExactKey,
            hasFamily = remembered.hasFamily,
        )
    }

    /**
     * Applies the show's saved hidden-season/scanlator filter the same way AnimeWatchFragment does.
     */
    fun filterByScanlators(
        episodes: List<EpisodeEntry>,
        hiddenScanlators: List<String>,
    ): List<EpisodeEntry> {
        if (hiddenScanlators.isEmpty() || episodes.isEmpty()) return episodes
        val options = episodes.mapNotNull { entry ->
            entry.scanlator?.trim()?.takeIf { it.isNotBlank() && !NON_SEASON_SCANLATOR.matches(it) }
        }.distinct()
        if (options.size <= 1) return episodes
        val hidden = hiddenScanlators.toSet()
        return episodes.filterNot { entry -> (entry.scanlator ?: "Unknown") in hidden }
    }

    /** True when the episode has a saved resume point and has not passed [watchPercentage]. */
    fun isLeftPartWay(progress: EpisodeSavedProgress?, watchPercentage: Float): Boolean {
        if (progress == null || progress.savedMs <= 0L) return false
        if (ResumeStart.startPosition(progress.savedMs, progress.maxMs) <= 0L) return false
        val maxMs = progress.maxMs
        if (maxMs != null && maxMs > 0L) {
            if (progress.savedMs.toFloat() / maxMs.toFloat() > watchPercentage) return false
        }
        return true
    }

    /** True when the episode's locally saved playback ratio passed [watchPercentage]. */
    fun isFinishedLocally(progress: EpisodeSavedProgress?, watchPercentage: Float): Boolean {
        if (progress == null || progress.savedMs <= 0L) return false
        val maxMs = progress.maxMs ?: return false
        if (maxMs <= 0L) return false
        return (progress.savedMs.toFloat() / maxMs.toFloat()) > watchPercentage
    }

    /**
     * Picks the episode to start for one-tap play:
     * - resumes the current episode if it was left part-way and isn't behind [userProgress]
     * - advances past a locally finished episode when [currentEpKey] is at or ahead of `progress + 1`
     * - otherwise starts `progress + 1`
     * - returns null if no matching episode exists (caller falls back to the Watch tab / picker)
     */
    fun chooseTargetEpisode(
        episodes: List<EpisodeEntry>,
        userProgress: Int?,
        currentEpKey: String?,
        watchPercentage: Float,
        savedProgress: (episodeKey: String) -> EpisodeSavedProgress?,
    ): TargetEpisode? {
        if (episodes.isEmpty()) return null
        val progress = (userProgress ?: 0).coerceAtLeast(0)
        val nextEpNum = (progress + 1).toFloat()

        fun epNumber(entry: EpisodeEntry): Float? =
            MediaNameAdapter.findEpisodeNumber(entry.number)
                ?: MediaNameAdapter.findEpisodeNumber(entry.key)

        if (!currentEpKey.isNullOrBlank()) {
            val curIndex = episodes.indexOfFirst { it.key == currentEpKey }
                .takeIf { it >= 0 }
                ?: episodes.indexOfFirst { it.number == currentEpKey }.takeIf { it >= 0 }
                ?: MediaNameAdapter.findEpisodeNumber(currentEpKey)?.let { num ->
                    episodes.indexOfFirst { epNumber(it) == num }.takeIf { it >= 0 }
                }
            if (curIndex != null) {
                val cur = episodes[curIndex]
                val curNum = epNumber(cur)
                val curSaved = savedProgress(cur.key)
                if (isLeftPartWay(curSaved, watchPercentage) &&
                    (curNum == null || curNum >= progress.toFloat())
                ) {
                    return TargetEpisode(cur.key, resuming = true)
                }
                if (isFinishedLocally(curSaved, watchPercentage) &&
                    curNum != null && curNum >= nextEpNum
                ) {
                    val after = episodes.getOrNull(curIndex + 1)
                    if (after != null) {
                        return TargetEpisode(
                            after.key,
                            resuming = isLeftPartWay(savedProgress(after.key), watchPercentage),
                        )
                    }
                }
            }
        }

        var targetIndex = episodes.indexOfFirst { epNumber(it) == nextEpNum }
        if (targetIndex < 0) {
            val minNum = episodes.mapNotNull { epNumber(it) }.minOrNull()
            val allowIndexFallback = minNum == null || minNum > 1f
            if (allowIndexFallback && progress in episodes.indices) {
                targetIndex = progress
            }
        }
        if (targetIndex < 0) return null

        val target = episodes[targetIndex]
        val targetSaved = savedProgress(target.key)
        if (isFinishedLocally(targetSaved, watchPercentage)) {
            val after = episodes.getOrNull(targetIndex + 1) ?: return null
            return TargetEpisode(
                after.key,
                resuming = isLeftPartWay(savedProgress(after.key), watchPercentage),
            )
        }

        return TargetEpisode(target.key, resuming = isLeftPartWay(targetSaved, watchPercentage))
    }

    /** True when [url] can be handed straight to ExoplayerView without an external player. */
    fun isDirectlyPlayable(url: String?, isOfflineServer: Boolean, torrentAvailable: Boolean): Boolean {
        if (isOfflineServer) return true
        val trimmed = url?.trim() ?: return false
        if (trimmed.isEmpty() || trimmed.equals("null", ignoreCase = true)) return false
        val isTorrent = trimmed.startsWith("magnet:", ignoreCase = true) ||
            trimmed.endsWith(".torrent", ignoreCase = true)
        return !isTorrent || torrentAvailable
    }
}
