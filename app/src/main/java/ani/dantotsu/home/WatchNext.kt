package ani.dantotsu.home

import java.time.LocalDate
import java.time.ZoneOffset

enum class WatchNextListStatus {
    CURRENT,
    REPEATING,
    COMPLETED,
    DROPPED,
    PAUSED,
    PLANNING,
}

enum class WatchNextMediaStatus {
    RELEASING,
    FINISHED,
    NOT_YET_RELEASED,
    CANCELLED,
    HIATUS,
    UNKNOWN,
}

enum class WatchNextTier {
    RECENT_SEQUEL,
    OLDER_SEQUEL,
    RELATED_PLANNING,
}

sealed interface WatchNextReason {
    data class SequelTo(val sourceTitle: String) : WatchNextReason
    data object OnYourList : WatchNextReason
}

data class WatchNextRelation(
    val targetId: Int,
    val relationType: String,
    val isAnime: Boolean = true,
    val mediaStatus: WatchNextMediaStatus = WatchNextMediaStatus.FINISHED,
    val isAdult: Boolean = false,
)

data class CompletedAnimeEntry(
    val mediaId: Int,
    val title: String,
    val completedAtSec: Long,
    val updatedAtSec: Long = completedAtSec,
    val relations: List<WatchNextRelation> = emptyList(),
)

data class PlannedAnimeEntry(
    val mediaId: Int,
    val updatedAtSec: Long = 0L,
    val isAnime: Boolean = true,
    val mediaStatus: WatchNextMediaStatus = WatchNextMediaStatus.FINISHED,
    val isAdult: Boolean = false,
    val isPrivate: Boolean = false,
    val relations: List<WatchNextRelation> = emptyList(),
)

data class WatchNextCandidate(
    val mediaId: Int,
    val tier: WatchNextTier,
    val reason: WatchNextReason,
)

data class WatchNextVisibility(
    val containerVisible: Boolean,
    val progressVisible: Boolean,
    val recyclerVisible: Boolean,
)

object WatchNextDefaults {
    const val MAX_ITEMS: Int = 15
    const val RECENT_WINDOW_SEC: Long = 90L * 24 * 60 * 60
}

private val EXCLUDED_STATUSES = setOf(
    WatchNextListStatus.CURRENT,
    WatchNextListStatus.REPEATING,
    WatchNextListStatus.COMPLETED,
    WatchNextListStatus.DROPPED,
    WatchNextListStatus.PAUSED,
)

private val RELATED_ANCHOR_STATUSES = setOf(
    WatchNextListStatus.COMPLETED,
    WatchNextListStatus.CURRENT,
    WatchNextListStatus.REPEATING,
)

private val WATCHABLE_MEDIA_STATUSES = setOf(
    WatchNextMediaStatus.RELEASING,
    WatchNextMediaStatus.FINISHED,
)

/**
 * Resolves a completed entry's timestamp in epoch seconds, preferring [completedYear]/[completedMonth]/[completedDay]
 * when present and falling back to [updatedAtSec].
 */
fun completedTimestampSec(
    completedYear: Int?,
    completedMonth: Int?,
    completedDay: Int?,
    updatedAtSec: Long?,
): Long {
    if (completedYear != null && completedYear in 1900..9999) {
        val month = (completedMonth ?: 12).coerceIn(1, 12)
        val maxDay = LocalDate.of(completedYear, month, 1).lengthOfMonth()
        val day = (completedDay ?: maxDay).coerceIn(1, maxDay)
        return LocalDate.of(completedYear, month, day)
            .atStartOfDay(ZoneOffset.UTC)
            .toEpochSecond()
    }
    return updatedAtSec?.takeIf { it > 0L } ?: 0L
}

/**
 * Pure tier / merge / dedup builder for the Home "Watch next" row.
 *
 * Tiers in strict order, deduplicated by media id, capped at [maxItems]:
 * 1) [WatchNextTier.RECENT_SEQUEL]: sequels of anime completed within [recentWindowSec] that aren't started.
 * 2) [WatchNextTier.OLDER_SEQUEL]: sequels of older completed anime that aren't started.
 * 3) [WatchNextTier.RELATED_PLANNING]: anime on the user's planning list related to a completed/watching anime.
 *
 * Excludes anything already watching/rewatching/completed/dropped/paused, non-anime entries,
 * unreleased/cancelled/hiatus titles, adult titles when [allowAdult] is false, hidden ids, and
 * private planned entries when [hidePrivate] is true.
 */
fun buildWatchNext(
    completed: List<CompletedAnimeEntry>,
    planned: List<PlannedAnimeEntry>,
    userStatuses: Map<Int, WatchNextListStatus> = emptyMap(),
    userUpdatedAtSec: Map<Int, Long> = emptyMap(),
    nowEpochSec: Long,
    recentWindowSec: Long = WatchNextDefaults.RECENT_WINDOW_SEC,
    maxItems: Int = WatchNextDefaults.MAX_ITEMS,
    excludedMediaIds: Set<Int> = emptySet(),
    hidePrivate: Boolean = false,
    allowAdult: Boolean = false,
): List<WatchNextCandidate> {
    if (maxItems <= 0) return emptyList()

    val effectiveStatuses = userStatuses.toMutableMap()
    completed.forEach { entry ->
        effectiveStatuses.putIfAbsent(entry.mediaId, WatchNextListStatus.COMPLETED)
    }
    planned.forEach { entry ->
        effectiveStatuses.putIfAbsent(entry.mediaId, WatchNextListStatus.PLANNING)
    }

    val plannedById = planned.associateBy { it.mediaId }

    fun isCandidateEligible(
        mediaId: Int,
        isAnime: Boolean,
        mediaStatus: WatchNextMediaStatus,
        isAdult: Boolean,
    ): Boolean {
        if (!isAnime) return false
        if (mediaStatus !in WATCHABLE_MEDIA_STATUSES) return false
        if (isAdult && !allowAdult) return false
        if (mediaId in excludedMediaIds) return false
        if (effectiveStatuses[mediaId] in EXCLUDED_STATUSES) return false
        val plannedEntry = plannedById[mediaId]
        if (plannedEntry != null && hidePrivate && plannedEntry.isPrivate) return false
        return true
    }

    val sortedCompleted = completed.sortedWith(
        compareByDescending<CompletedAnimeEntry> { it.completedAtSec }
            .thenByDescending { it.updatedAtSec }
            .thenBy { it.mediaId }
    )

    val recentCutoffSec = nowEpochSec - recentWindowSec
    val tier1 = mutableListOf<WatchNextCandidate>()
    val tier2 = mutableListOf<WatchNextCandidate>()

    for (entry in sortedCompleted) {
        val isRecent = entry.completedAtSec > 0L && entry.completedAtSec >= recentCutoffSec
        val tier = if (isRecent) WatchNextTier.RECENT_SEQUEL else WatchNextTier.OLDER_SEQUEL
        val targetList = if (isRecent) tier1 else tier2

        for (rel in entry.relations) {
            if (!rel.relationType.equals("SEQUEL", ignoreCase = true)) continue
            if (!isCandidateEligible(rel.targetId, rel.isAnime, rel.mediaStatus, rel.isAdult)) continue
            targetList.add(
                WatchNextCandidate(
                    mediaId = rel.targetId,
                    tier = tier,
                    reason = WatchNextReason.SequelTo(entry.title),
                )
            )
        }
    }

    val anchorIds = effectiveStatuses
        .filterValues { it in RELATED_ANCHOR_STATUSES }
        .keys

    val anchorTimestamp = userUpdatedAtSec.toMutableMap()
    completed.forEach { entry ->
        val best = maxOf(entry.completedAtSec, entry.updatedAtSec)
        val existing = anchorTimestamp[entry.mediaId] ?: 0L
        if (best > existing) anchorTimestamp[entry.mediaId] = best
    }

    // Reverse map from completed anime relations -> planned media ids so we catch relations from either side.
    val completedRelatedToTarget = mutableMapOf<Int, Long>()
    for (entry in completed) {
        val ts = maxOf(entry.completedAtSec, entry.updatedAtSec)
        for (rel in entry.relations) {
            if (!rel.isAnime) continue
            val existing = completedRelatedToTarget[rel.targetId] ?: -1L
            if (ts > existing) completedRelatedToTarget[rel.targetId] = ts
        }
    }

    data class RankedPlanning(
        val entry: PlannedAnimeEntry,
        val anchorRecencySec: Long,
    )

    val tier3Ranked = mutableListOf<RankedPlanning>()
    for (entry in planned) {
        if (!isCandidateEligible(entry.mediaId, entry.isAnime, entry.mediaStatus, entry.isAdult)) continue
        val forwardAnchorSec = entry.relations
            .asSequence()
            .filter { it.isAnime && it.targetId in anchorIds }
            .map { anchorTimestamp[it.targetId] ?: 0L }
            .maxOrNull()
        val reverseAnchorSec = completedRelatedToTarget[entry.mediaId]
        val bestAnchorSec = listOfNotNull(forwardAnchorSec, reverseAnchorSec).maxOrNull()
        if (bestAnchorSec != null) {
            tier3Ranked.add(RankedPlanning(entry, bestAnchorSec))
        }
    }

    val tier3 = tier3Ranked
        .sortedWith(
            compareByDescending<RankedPlanning> { it.anchorRecencySec }
                .thenByDescending { it.entry.updatedAtSec }
                .thenBy { it.entry.mediaId }
        )
        .map { ranked ->
            WatchNextCandidate(
                mediaId = ranked.entry.mediaId,
                tier = WatchNextTier.RELATED_PLANNING,
                reason = WatchNextReason.OnYourList,
            )
        }

    val seen = HashSet<Int>()
    val merged = ArrayList<WatchNextCandidate>(maxItems)
    for (candidate in tier1) {
        if (merged.size >= maxItems) break
        if (seen.add(candidate.mediaId)) merged.add(candidate)
    }
    for (candidate in tier2) {
        if (merged.size >= maxItems) break
        if (seen.add(candidate.mediaId)) merged.add(candidate)
    }
    for (candidate in tier3) {
        if (merged.size >= maxItems) break
        if (seen.add(candidate.mediaId)) merged.add(candidate)
    }
    return merged
}

/**
 * Visibility contract for the Home "Watch next" row:
 * - Disabled or rescue mode: hidden in every state.
 * - Loading (`items == null`): container + skeleton visible.
 * - Resolved empty: hidden completely.
 * - Non-empty: container + recycler visible.
 */
fun watchNextRowVisibility(
    items: Collection<*>?,
    enabled: Boolean,
    rescueMode: Boolean,
): WatchNextVisibility {
    if (!enabled || rescueMode) {
        return WatchNextVisibility(
            containerVisible = false,
            progressVisible = false,
            recyclerVisible = false,
        )
    }
    if (items == null) {
        return WatchNextVisibility(
            containerVisible = true,
            progressVisible = true,
            recyclerVisible = false,
        )
    }
    if (items.isEmpty()) {
        return WatchNextVisibility(
            containerVisible = false,
            progressVisible = false,
            recyclerVisible = false,
        )
    }
    return WatchNextVisibility(
        containerVisible = true,
        progressVisible = false,
        recyclerVisible = true,
    )
}

/**
 * True when [sequelTitle] obviously continues [sourceTitle] by name ("Dr. STONE" -> "Dr. STONE: STONE WARS"),
 * so "Sequel to <source>" would only repeat the card title. Compares letters and digits, case-insensitive.
 */
fun sameSeriesName(sourceTitle: String, sequelTitle: String): Boolean {
    fun norm(t: String) = t.lowercase().filter { it.isLetterOrDigit() }
    val a = norm(sourceTitle)
    val b = norm(sequelTitle)
    if (a.isEmpty() || b.isEmpty()) return false
    val common = a.zip(b).takeWhile { (x, y) -> x == y }.size
    return common >= minOf(6, a.length, b.length)
}
