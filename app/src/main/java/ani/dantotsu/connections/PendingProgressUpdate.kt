package ani.dantotsu.connections

import ani.dantotsu.connections.anilist.api.FuzzyDate
import kotlinx.serialization.Serializable

/**
 * A list edit that still has to reach AniList and/or MAL.
 *
 * Stored with java serialization; fields added later must keep a JVM default
 * (false / 0) that means the old behaviour, so entries written by older
 * builds still read as "AniList pending, MAL not involved".
 */
@Serializable
data class PendingProgressUpdate(
    val mediaId: Int,
    val idMAL: Int?,
    val isAnime: Boolean,
    val progress: Int,
    val status: String,
    val score: Int? = null,
    val rewatch: Int? = null,
    val notes: String? = null,
    val isPrivate: Boolean = false,
    val startDate: FuzzyDate? = null,
    val endDate: FuzzyDate? = null,
    val customLists: List<String>? = null,
    /** AniList already has this edit; only the MAL part is left. */
    val anilistDone: Boolean = false,
    /** MAL still needs this edit. */
    val malPending: Boolean = false,
    /** Sync runs in which a send was tried and failed. */
    val failedAttempts: Int = 0,
) : java.io.Serializable {
    val isComplete: Boolean get() = anilistDone && !malPending

    companion object {
        private const val serialVersionUID = 2L
    }
}

/** Pure rules for progress updates and the pending queue. */
object PendingProgressRules {
    /** A queued entry is dropped after this many failed sync runs (bad data never succeeds). */
    const val MAX_FAILED_ATTEMPTS = 10

    /** Only a forward update is sent; an equal or lower episode/chapter is ignored. */
    fun shouldSend(target: Int, current: Int?): Boolean = target > (current ?: -1)

    data class Decision(
        /** Set media.userProgress and show "Setting progress to N". */
        val advanceLocal: Boolean,
        /** Entry to put into the pending queue, or null. */
        val queue: PendingProgressUpdate?,
    )

    /**
     * Result of one progress update.
     *
     * Normal mode: AniList is the primary tracker, so local progress advances
     * only when AniList accepted it. Rescue mode: AniList is not called (it is
     * always queued, as before) and MAL is the primary.
     * Whatever failed is queued; MAL "ok" also covers "nothing to send"
     * (no MAL login or no MAL id).
     */
    fun decide(
        base: PendingProgressUpdate,
        rescueMode: Boolean,
        anilistOk: Boolean,
        malOk: Boolean,
    ): Decision {
        return if (rescueMode) {
            Decision(
                advanceLocal = malOk,
                queue = base.copy(anilistDone = false, malPending = !malOk),
            )
        } else {
            Decision(
                advanceLocal = anilistOk,
                queue = if (anilistOk && malOk) null
                else base.copy(anilistDone = anilistOk, malPending = !malOk),
            )
        }
    }

    /**
     * Put [new] into [existing]. One entry per media: an older entry's extra
     * fields (score, notes, dates from the list editor) are kept, progress
     * never goes backwards, and a part still owed by either entry stays owed.
     */
    fun enqueue(
        existing: List<PendingProgressUpdate>,
        new: PendingProgressUpdate,
    ): List<PendingProgressUpdate> {
        val old = existing.firstOrNull { it.mediaId == new.mediaId }
            ?: return existing + new
        val merged = new.copy(
            progress = maxOf(old.progress, new.progress),
            score = new.score ?: old.score,
            rewatch = new.rewatch ?: old.rewatch,
            notes = new.notes ?: old.notes,
            startDate = new.startDate ?: old.startDate,
            endDate = new.endDate ?: old.endDate,
            customLists = new.customLists ?: old.customLists,
            isPrivate = new.isPrivate || old.isPrivate,
            anilistDone = old.anilistDone && new.anilistDone,
            malPending = old.malPending || new.malPending,
            failedAttempts = 0,
        )
        return existing.filterNot { it.mediaId == new.mediaId } + merged
    }

    /**
     * After a direct update to [progress] succeeded, a queued entry for the
     * same media must not later send a lower value and move the tracker back.
     */
    fun raiseQueuedProgress(
        existing: List<PendingProgressUpdate>,
        mediaId: Int,
        progress: Int,
    ): List<PendingProgressUpdate> = existing.map {
        if (it.mediaId == mediaId && it.progress < progress) it.copy(progress = progress) else it
    }

    /**
     * Write back the result of a sync run. [results] maps each entry the run
     * worked on to what is left of it (null = fully synced). Entries added or
     * replaced while the run was going are kept as they are.
     */
    fun applySyncResults(
        current: List<PendingProgressUpdate>,
        results: Map<PendingProgressUpdate, PendingProgressUpdate?>,
    ): List<PendingProgressUpdate> = current.mapNotNull { entry ->
        if (results.containsKey(entry)) results[entry] else entry
    }

    /** What is left of [update] after one sync run; null when nothing is left or it gave up. */
    fun afterSyncRun(
        update: PendingProgressUpdate,
        anilistOk: Boolean?,
        malOk: Boolean?,
    ): PendingProgressUpdate? {
        var u = update
        if (anilistOk == true) u = u.copy(anilistDone = true)
        if (malOk == true) u = u.copy(malPending = false)
        if (u.isComplete) return null
        val failed = anilistOk == false || malOk == false
        if (failed) u = u.copy(failedAttempts = u.failedAttempts + 1)
        return if (u.failedAttempts >= MAX_FAILED_ATTEMPTS) null else u
    }
}
