package ani.dantotsu.media

import ani.dantotsu.connections.anilist.AniMangaSearchResults

/**
 * Pure policy for the Search Filters sheet and its focused pickers. Zero Android dependencies so
 * every rule (facet visibility, source ordering, year grid, decade jumps, active counts, change
 * detection) is unit-tested on the JVM.
 */
object SearchFilterPolicy {

    const val DEFAULT_SORT = "POPULARITY_DESC"
    const val MIN_YEAR = 1970

    enum class Facet {
        SORT,
        GENRES_TAGS,
        ADULT,
        FORMAT,
        STATUS,
        YEAR,
        SEASON,
        SOURCE,
        COUNTRY,
        LIST,
    }

    data class SourcePartition(
        val common: List<String>,
        val more: List<String>,
    )

    data class DecadeJump(
        val decade: Int,
        val firstIndex: Int,
    )

    /**
     * Value snapshot of every filter field on [AniMangaSearchResults]. Used to detect whether the
     * user actually changed anything while the Filters sheet was open so we fire one search on
     * dismiss/apply and zero redundant searches when nothing changed.
     */
    data class FilterSnapshot(
        val sort: String?,
        val genres: List<String>,
        val excludedGenres: List<String>,
        val tags: List<String>,
        val excludedTags: List<String>,
        val format: String?,
        val status: String?,
        val season: String?,
        val seasonYear: Int?,
        val startYear: Int?,
        val source: String?,
        val countryOfOrigin: String?,
        val onList: Boolean?,
        val isAdult: Boolean,
    ) {
        fun differsFrom(filters: AniMangaSearchResults): Boolean = from(filters) != this

        companion object {
            fun from(f: AniMangaSearchResults): FilterSnapshot = FilterSnapshot(
                sort = normalizeSort(f.sort),
                genres = f.genres.orEmpty().toList(),
                excludedGenres = f.excludedGenres.orEmpty().toList(),
                tags = f.tags.orEmpty().toList(),
                excludedTags = f.excludedTags.orEmpty().toList(),
                format = f.format,
                status = f.status,
                season = f.season,
                seasonYear = f.seasonYear,
                startYear = f.startYear,
                source = f.source,
                countryOfOrigin = f.countryOfOrigin,
                onList = f.onList,
                isAdult = f.isAdult,
            )
        }
    }

    /** In-session memory: keep "More" open if the user expanded it, and remember the last decade jumped to. */
    @Volatile
    var moreExpanded: Boolean = false

    @Volatile
    var lastYearDecade: Int? = null

    fun resetSessionMemory() {
        moreExpanded = false
        lastYearDecade = null
    }

    fun normalizeSort(key: String?): String? = key?.takeUnless { it == DEFAULT_SORT }

    /** Top-row controls: Sort, Genres & tags (when data exists), and the binary Adult toggle (when enabled). */
    fun visiblePrimaryFacets(hasGenreData: Boolean, adultEnabled: Boolean): List<Facet> = buildList {
        add(Facet.SORT)
        if (hasGenreData) add(Facet.GENRES_TAGS)
        if (adultEnabled) add(Facet.ADULT)
    }

    /** High-frequency facets always visible on the sheet. Season is anime + AniList only. */
    fun visibleCommonFacets(anime: Boolean, rescue: Boolean): List<Facet> = buildList {
        add(Facet.FORMAT)
        add(Facet.STATUS)
        add(Facet.YEAR)
        if (anime && !rescue) add(Facet.SEASON)
    }

    /** Secondary facets tucked under "More". Completely hidden in Rescue mode (unsupported by Jikan). */
    fun visibleMoreFacets(rescue: Boolean, signedIn: Boolean): List<Facet> {
        if (rescue) return emptyList()
        return buildList {
            add(Facet.SOURCE)
            add(Facet.COUNTRY)
            if (signedIn) add(Facet.LIST)
        }
    }

    /** Number of active facets inside the "More" group. */
    fun activeMoreCount(filters: AniMangaSearchResults, rescue: Boolean, signedIn: Boolean): Int {
        if (rescue) return 0
        var count = 0
        if (filters.source != null) count++
        if (filters.countryOfOrigin != null) count++
        if (signedIn && filters.onList != null) count++
        return count
    }

    /** Auto-expand "More" on open if the user previously expanded it or any rare facet is active. */
    fun shouldExpandMore(
        filters: AniMangaSearchResults,
        userExpanded: Boolean,
        rescue: Boolean,
        signedIn: Boolean,
    ): Boolean {
        if (visibleMoreFacets(rescue, signedIn).isEmpty()) return false
        return userExpanded || activeMoreCount(filters, rescue, signedIn) > 0
    }

    /** Count of active filter items that apply to the current media type and mode. */
    fun applicableActiveCount(
        filters: AniMangaSearchResults,
        anime: Boolean,
        rescue: Boolean,
        signedIn: Boolean,
        adultEnabled: Boolean,
    ): Int {
        var count = 0
        if (normalizeSort(filters.sort) != null) count++
        count += filters.genres?.size ?: 0
        count += filters.excludedGenres?.size ?: 0
        if (!rescue) {
            count += filters.tags?.size ?: 0
            count += filters.excludedTags?.size ?: 0
        }
        if (filters.format != null) count++
        if (filters.status != null) count++
        val year = if (anime) filters.seasonYear else filters.startYear
        if (year != null) count++
        if (anime && !rescue && filters.season != null) count++
        if (!rescue) {
            if (filters.source != null) count++
            if (filters.countryOfOrigin != null) count++
            if (signedIn && filters.onList != null) count++
        }
        if (adultEnabled && filters.isAdult) count++
        return count
    }

    private val COMMON_ANIME_SOURCES = listOf(
        "MANGA",
        "LIGHT NOVEL",
        "ORIGINAL",
        "WEB NOVEL",
        "VISUAL NOVEL",
        "VIDEO GAME",
    )

    private val COMMON_MANGA_SOURCES = listOf(
        "ORIGINAL",
        "LIGHT NOVEL",
        "WEB NOVEL",
        "NOVEL",
        "VISUAL NOVEL",
        "VIDEO GAME",
    )

    /**
     * Partitions [allSources] into the 6 most common sources for the media type followed by the
     * rarer sources (with "OTHER" always last). Every input item appears once with no drops.
     */
    fun sourceGroups(allSources: List<String>, anime: Boolean): SourcePartition {
        val preferred = if (anime) COMMON_ANIME_SOURCES else COMMON_MANGA_SOURCES
        val byNorm = allSources.associateBy(::normalizeKey)
        val common = preferred.mapNotNull { byNorm[normalizeKey(it)] }
        val commonKeys = common.mapTo(HashSet(), ::normalizeKey)
        val rest = allSources.filter { normalizeKey(it) !in commonKeys }
        val (others, nonOthers) = rest.partition { normalizeKey(it) == "OTHER" }
        return SourcePartition(common = common, more = nonOthers + others)
    }

    fun isInMoreSources(allSources: List<String>, anime: Boolean, stored: String?): Boolean {
        if (stored == null) return false
        val target = normalizeKey(stored)
        return sourceGroups(allSources, anime).more.any { normalizeKey(it) == target }
    }

    /** Descending list of unique years from [maxYear] down to [minYear] (inclusive). */
    fun yearsDescending(maxYear: Int, minYear: Int = MIN_YEAR): List<Int> {
        if (maxYear < minYear) return emptyList()
        return (maxYear downTo minYear).toList()
    }

    fun decadeOf(year: Int): Int = (year / 10) * 10

    /** Distinct decades in [years] paired with the index of the first year in each decade. */
    fun decades(years: List<Int>): List<DecadeJump> {
        val out = ArrayList<DecadeJump>()
        var lastDecade: Int? = null
        years.forEachIndexed { index, year ->
            val decade = decadeOf(year)
            if (decade != lastDecade) {
                out += DecadeJump(decade = decade, firstIndex = index)
                lastDecade = decade
            }
        }
        return out
    }

    /** Matches a stored key (space- or underscore-separated) against [options]. */
    fun findMatchingKey(options: List<String>, stored: String?): String? {
        if (stored == null) return null
        val target = normalizeKey(stored)
        return options.firstOrNull { normalizeKey(it) == target }
    }

    /** "NOT_YET_RELEASED" -> "Not yet released"; short codes (TV, OVA, ONA) stay uppercase. */
    fun pretty(raw: String): String {
        val s = raw.replace('_', ' ')
        if (s.length <= 3) return s
        return s.lowercase().replaceFirstChar { it.uppercase() }.replace(Regex("^Tv\\b"), "TV")
    }

    private fun normalizeKey(raw: String): String = raw.replace('_', ' ').trim().uppercase()
}
