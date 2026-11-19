package ani.dantotsu.media

import ani.dantotsu.connections.anilist.AniMangaSearchResults

/**
 * Pure rules for the search screen: whether filters are active, resetting them, and maintaining
 * deduplicated recents with oldest-first eviction.
 */
object SearchStatePolicy {

    /** Number of individual active filter items (matches the removable chips shown beside "Filters"). */
    fun activeFilterCount(filters: AniMangaSearchResults?): Int {
        val f = filters ?: return 0
        var count = 0
        if (f.sort != null) count++
        count += f.genres?.size ?: 0
        count += f.tags?.size ?: 0
        count += f.excludedGenres?.size ?: 0
        count += f.excludedTags?.size ?: 0
        if (f.format != null) count++
        if (f.status != null) count++
        if (f.seasonYear != null || f.startYear != null) count++
        if (f.season != null) count++
        if (f.onList != null) count++
        if (f.source != null) count++
        if (f.countryOfOrigin != null) count++
        if (f.isAdult) count++
        return count
    }

    /** True when any filter, sort order, or 18+ toggle is set (empty query still browses results). */
    fun hasActiveFilters(filters: AniMangaSearchResults?): Boolean = activeFilterCount(filters) > 0

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
