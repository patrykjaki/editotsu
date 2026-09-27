package ani.dantotsu.media.comments

/**
 * MAX MERGE M6 (upstream aa58a989): pure timestamp mining for comment taps.
 * Framework-free so the regex contract is unit-proved
 * (CommentTimestampTest). Rendering/click wiring stays in [CommentItem].
 */
data class TimestampHit(val start: Int, val endExclusive: Int, val timestampMs: Long)

private val TIMESTAMP_REGEX =
    Regex("""\b(?:(?:([0-1]?[0-9]|2[0-3]):)?([0-5]?[0-9]):([0-5][0-9]))\b""")
private val EPISODE_TAG_REGEX =
    Regex("""(?:\bep(?:isode)?|\be)\.?\s*(\d+)""", RegexOption.IGNORE_CASE)

fun findTimestamps(text: String): List<TimestampHit> =
    TIMESTAMP_REGEX.findAll(text).map { match ->
        val hours = match.groups[1]?.value?.toLongOrNull() ?: 0L
        val minutes = match.groups[2]?.value?.toLongOrNull() ?: 0L
        val seconds = match.groups[3]?.value?.toLongOrNull() ?: 0L
        TimestampHit(
            match.range.first,
            match.range.last + 1,
            (hours * 3600 + minutes * 60 + seconds) * 1000L
        )
    }.toList()

fun extractEpisodeTag(text: String): String? =
    EPISODE_TAG_REGEX.find(text)?.groupValues?.getOrNull(1)
