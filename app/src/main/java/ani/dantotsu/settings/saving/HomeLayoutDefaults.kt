package ani.dantotsu.settings.saving

object HomeLayoutDefaults {
    const val CONTINUE_WATCHING = 0
    const val FAV_ANIME = 1
    const val WATCH_NEXT = 2
    const val CONTINUE_READING = 3
    const val FAV_MANGA = 4
    const val PLANNED_MANGA = 5
    const val RECOMMENDED = 6
    const val SOCIAL_FEEDS = 7
    const val LEGACY_MISSING_SEQUELS = 8

    const val SECTION_COUNT = 8
    const val FIXED_INDEX = SOCIAL_FEEDS

    val DEFAULT_VISIBILITY: List<Boolean> = listOf(
        true,  // 0 Continue Watching
        false, // 1 Favourite Anime
        true,  // 2 Watch next (on by default; replaces Planned Anime)
        true,  // 3 Continue Reading
        false, // 4 Favourite Manga
        false, // 5 Planned Manga
        true,  // 6 Recommended
        true,  // 7 Social Feeds (fixed at top)
    )

    val DEFAULT_ORDER: List<Int> = listOf(0, 1, 2, 3, 4, 5, 6)

    /**
     * Migrates a saved HomeLayout visibility list from the pre-Watch-Next schema (where slot 2
     * was Planned Anime and slot 8 was Missed Sequels) into the 8-slot layout with Watch next on.
     */
    fun migrateLegacyVisibility(saved: List<Boolean>): List<Boolean> {
        val out = MutableList(SECTION_COUNT) { idx ->
            saved.getOrElse(idx) { DEFAULT_VISIBILITY[idx] }
        }
        out[WATCH_NEXT] = true
        return out
    }

    /**
     * Normalizes visibility on read. Legacy 9-slot lists (e.g. from an older backup restore)
     * drop slot 8 (Missed Sequels) and enable slot 2 (Watch next); 8-slot lists keep the user's
     * saved toggle for every row, including Watch next.
     */
    fun normalizeVisibility(raw: List<Boolean>): List<Boolean> {
        if (raw.size > SECTION_COUNT) {
            return migrateLegacyVisibility(raw)
        }
        if (raw.size == SECTION_COUNT) return raw
        return List(SECTION_COUNT) { idx ->
            raw.getOrElse(idx) { DEFAULT_VISIBILITY[idx] }
        }
    }

    /**
     * Normalizes reorderable section order to contain 0..6 (never FIXED_INDEX=7) in the user's
     * relative order. Drops legacy slot 8 (Missed Sequels); if slot 2 was absent, places it where
     * slot 8 stood so Watch next lands in the user's layout without losing order.
     */
    fun normalizeOrder(raw: List<Int>): List<Int> {
        val reorderable = DEFAULT_ORDER
        if (raw.isEmpty()) return reorderable
        val hasWatchNext = WATCH_NEXT in raw
        val result = mutableListOf<Int>()
        for (idx in raw) {
            if (idx == LEGACY_MISSING_SEQUELS) {
                if (!hasWatchNext && WATCH_NEXT !in result) {
                    result.add(WATCH_NEXT)
                }
                continue
            }
            if (idx in reorderable && idx !in result) {
                result.add(idx)
            }
        }
        for (idx in reorderable) {
            if (idx !in result) result.add(idx)
        }
        return result
    }
}
