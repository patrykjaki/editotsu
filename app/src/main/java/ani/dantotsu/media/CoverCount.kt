package ani.dantotsu.media

/** "Episode count" in appearance; the pref stores [ordinal]. */
internal enum class CoverCountMode { COVER, BELOW, NEW;
    companion object { fun of(v: Int) = entries.getOrElse(v) { COVER } } }

/**
 * The count text shared by the cover pill and the line under the title: watched, then
 * " | out | total" while airing or " | total". Unknown parts read "~".
 */
internal fun coverProgressParts(progress: Int?, aired: Int?, total: Int?): Pair<String, String> =
    (progress?.toString() ?: "~") to
        (if (aired != null) " | $aired | ${total ?: "~"}" else " | ${total ?: "~"}")

/**
 * What the cover pill shows in the "On the cover" mode. On the list: [coverProgressParts]
 * ("2 | 9 | 11"). Not on the list: only how many are out ("7 ep"), since a "~" for watched
 * says nothing. Null hides the pill: a single-episode anime (movie), or nothing known.
 */
internal sealed interface CoverPill {
    data class Progress(val watched: String, val rest: String) : CoverPill
    data class Out(val count: Int) : CoverPill
}

internal fun coverPill(
    progress: Int?, listStatus: String?, aired: Int?, total: Int?, isAnime: Boolean,
): CoverPill? {
    if (isAnime && total == 1) return null
    if (listStatus.isNullOrEmpty()) return (aired ?: total)?.takeIf { it > 0 }?.let { CoverPill.Out(it) }
    val (w, r) = coverProgressParts(progress, aired, total)
    return CoverPill.Progress(w, r)
}

/**
 * Episodes or chapters that are out and not yet watched or read, for the badge on a cover.
 * Only for shows already started (watching, paused, rewatching): on a planned show it
 * would just repeat the episode total. Null hides the badge: not started, done with it,
 * nothing out yet, the count unknown, or nothing new.
 */
internal fun coverUnseenCount(progress: Int?, listStatus: String?, released: Int?): Int? {
    if (progress == null || released == null) return null
    if (listStatus !in STARTED) return null
    return (released - progress).takeIf { it > 0 }
}

private val STARTED = setOf("CURRENT", "PAUSED", "REPEATING")

/** Badge text: past 99 it reads "99+" so the pill stays small. */
internal fun coverCountLabel(count: Int): String = if (count > 99) "99+" else count.toString()

/** How many are out: aired so far while airing, else the total. */
internal fun Media.releasedCount(): Int? = when {
    anime != null -> anime.nextAiringEpisode?.takeIf { it > 0 } ?: anime.totalEpisodes
    manga != null -> manga.totalChapters
    else -> null
}


/** How far off the next episode is, in the largest whole unit: minutes under an hour, hours under a day, else days. */
internal enum class AiringUnit { MINUTES, HOURS, DAYS }

internal data class NextEpisode(val episode: Int, val amount: Long, val unit: AiringUnit)

/**
 * The line under the title when the count sits on the cover: the next episode and when it airs.
 * [aired] is the app's aired count (AniList's next episode - 1); [untilMs] the time to it when the
 * list was fetched. Null when the show isn't airing or the time is unknown or already past.
 */
internal fun nextEpisode(aired: Int?, untilMs: Long?): NextEpisode? {
    if (aired == null || untilMs == null || untilMs <= 0) return null
    val minutes = (untilMs + 59_999) / 60_000
    val (amount, unit) = when {
        minutes < 60 -> minutes to AiringUnit.MINUTES
        minutes < 24 * 60 -> (minutes / 60) to AiringUnit.HOURS
        else -> (minutes / (24 * 60)) to AiringUnit.DAYS
    }
    return NextEpisode(aired + 1, amount, unit)
}
