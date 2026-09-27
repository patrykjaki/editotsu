package ani.dantotsu.media

/**
 * M1 HOLD closure (Blocker 1): spoiler-safe compact-card genre line.
 *
 * The compact card prefers genres. When genres are absent it falls back to
 * tag names — but NEVER exposes tags AniList marked as media spoilers.
 * No preference UI, no setting: spoiler-marked tags are unconditionally
 * excluded from this surface. M3 may add configurable masking later.
 *
 * [tagsIsSpoiler] is parallel to [tags] ([Media.tagsIsSpoiler]); a missing
 * entry is treated as non-spoiler and only a positively-marked tag is
 * excluded, so a shorter flag list can never blank the line by accident.
 * Order among survivors is preserved; at most 3 names are shown.
 */
fun compactCardGenreLine(
    genres: List<String>,
    tags: List<String>,
    tagsIsSpoiler: List<Boolean>,
): String {
    if (genres.isNotEmpty()) return genres.joinToString(" • ")
    if (tags.isEmpty()) return ""
    return tags.filterIndexed { index, _ ->
        tagsIsSpoiler.getOrNull(index) != true
    }.take(3).joinToString(" • ")
}
