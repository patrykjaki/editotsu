package ani.dantotsu.media

import ani.dantotsu.connections.anilist.AniMangaSearchResults

/**
 * Pure rules for the search screen: whether filters are active, resetting them, and maintaining
 * deduplicated recents with oldest-first eviction.
 */
object SearchStatePolicy {

    /** True when any filter, sort order, or 18+ toggle is set (empty query still browses results). */
    fun hasActiveFilters(filters: AniMangaSearchResults?): Boolean {
        val f = filters ?: return false
        return !f.genres.isNullOrEmpty() ||
            !f.tags.isNullOrEmpty() ||
            !f.excludedGenres.isNullOrEmpty() ||
            !f.excludedTags.isNullOrEmpty() ||
            f.format != null ||
            f.status != null ||
            f.source != null ||
            f.countryOfOrigin != null ||
            f.season != null ||
            f.seasonYear != null ||
            f.startYear != null ||
            f.onList != null ||
            f.sort != null ||
            f.isAdult
    }

    /** Clears every filter and sort on [filters] while keeping [AniMangaSearchResults.search]. */
    fun clearFilters(filters: AniMangaSearchResults) {
        filters.sort = null
        filters.genres = null
        filters.excludedGenres = null
        filters.tags = null
        filters.excludedTags = null
        filters.format = null
        filters.status = null
        filters.source = null
        filters.countryOfOrigin = null
        filters.season = null
        filters.seasonYear = null
        filters.startYear = null
        filters.onList = null
        filters.isAdult = false
    }

    /**
     * Records [rawQuery] at [now]: trims whitespace, ignores blanks, replaces any case-insensitive
     * duplicate so the latest search moves to the top, and drops the oldest entries past [maxSize].
     */
    fun addHistoryEntry(
        existing: List<SearchHistory>,
        rawQuery: String,
        now: Long,
        maxSize: Int = 25,
    ): List<SearchHistory> {
        val query = rawQuery.trim()
        if (query.isEmpty()) return existing
        val withoutDup = existing.filterNot { it.search.equals(query, ignoreCase = true) }
        return (withoutDup + SearchHistory(query, now))
            .sortedByDescending { it.time }
            .take(maxSize)
    }
}
