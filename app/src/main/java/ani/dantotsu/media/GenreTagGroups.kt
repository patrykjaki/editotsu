package ani.dantotsu.media

/**
 * What the genres & tags sheet shows and how a tap changes a filter. No Android types, so it is
 * unit tested (GenreTagGroupsTest).
 */
object GenreTagGroups {

    /** State of one genre or tag. A tap moves blank -> include -> exclude -> blank. */
    enum class Mark {
        NONE, INCLUDE, EXCLUDE;

        fun next(): Mark = when (this) {
            NONE -> INCLUDE
            INCLUDE -> EXCLUDE
            EXCLUDE -> NONE
        }
    }

    enum class Kind { GENRE, TAG }

    data class Entry(val name: String, val kind: Kind)

    data class Group(val title: String, val entries: List<Entry>)

    fun markOf(name: String, included: List<String>, excluded: List<String>): Mark = when (name) {
        in included -> Mark.INCLUDE
        in excluded -> Mark.EXCLUDE
        else -> Mark.NONE
    }

    /** Puts [name] in at most one of the two lists, as [mark] says. */
    fun set(name: String, mark: Mark, included: MutableList<String>, excluded: MutableList<String>) {
        included.remove(name)
        excluded.remove(name)
        when (mark) {
            Mark.INCLUDE -> included.add(name)
            Mark.EXCLUDE -> excluded.add(name)
            Mark.NONE -> Unit
        }
    }

    /**
     * Genres first, then one group per tag category in alphabetical order ("Cast / Main Cast",
     * "Theme / Action"). Tags without a known category (the categories are not fetched yet)
     * go last under [otherTitle].
     */
    fun groups(
        genres: List<String>,
        tags: List<String>,
        categories: Map<String, String>,
        genresTitle: String,
        otherTitle: String,
    ): List<Group> {
        val out = ArrayList<Group>()
        if (genres.isNotEmpty()) {
            out += Group(genresTitle, genres.sortedWith(String.CASE_INSENSITIVE_ORDER).map { Entry(it, Kind.GENRE) })
        }
        val byCategory = tags.distinct().groupBy { tag ->
            categories[tag]?.let(::categoryTitle)?.takeIf { it.isNotEmpty() }
        }
        byCategory.keys.filterNotNull().sortedWith(String.CASE_INSENSITIVE_ORDER).forEach { title ->
            out += Group(title, byCategory.getValue(title).sortedWith(String.CASE_INSENSITIVE_ORDER).map { Entry(it, Kind.TAG) })
        }
        byCategory[null]?.let { rest ->
            out += Group(otherTitle, rest.sortedWith(String.CASE_INSENSITIVE_ORDER).map { Entry(it, Kind.TAG) })
        }
        return out
    }

    /** "Theme-Action" -> "Theme / Action". */
    fun categoryTitle(raw: String): String =
        raw.split('-').map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" / ")

    /**
     * Groups that match [query]: a group whose title matches keeps all its entries, otherwise only
     * the entries whose name matches. Empty groups are dropped.
     */
    fun search(groups: List<Group>, query: String): List<Group> {
        val q = query.trim()
        if (q.isEmpty()) return groups
        return groups.mapNotNull { group ->
            if (group.title.contains(q, ignoreCase = true)) group
            else group.entries.filter { it.name.contains(q, ignoreCase = true) }
                .takeIf { it.isNotEmpty() }
                ?.let { Group(group.title, it) }
        }
    }

    /**
     * Text of the chip while something is set: the first name and how many more ("Action +2").
     * An excluded name gets a minus sign ("−Romance"). Null when nothing is set.
     */
    fun summary(included: List<String>, excluded: List<String>): String? {
        val first = included.firstOrNull() ?: excluded.firstOrNull()?.let { "\u2212$it" } ?: return null
        val more = included.size + excluded.size - 1
        return if (more > 0) "$first +$more" else first
    }

    private const val SEPARATOR = '\t'

    /** The category map as a string set, the shape the preferences store. */
    fun encodeCategories(categories: Map<String, String>): Set<String> =
        categories.mapTo(HashSet()) { (name, category) -> "$name$SEPARATOR$category" }

    fun decodeCategories(stored: Set<String>): Map<String, String> = stored.mapNotNull { line ->
        val i = line.indexOf(SEPARATOR)
        if (i <= 0 || i == line.lastIndex) null else line.substring(0, i) to line.substring(i + 1)
    }.toMap()
}
