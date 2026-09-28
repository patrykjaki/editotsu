package ani.dantotsu.forum

/**
 * MAX MERGE M2 correction (HOLD2 Blocker 1): ONE shared AniList forum
 * category catalog. IDs verified live against https://graphql.anilist.co
 * on 2026-09-29 (threads query per categoryId returns that category).
 *
 * Browsing (ForumActivity chips), thread creation (dropdown) and the
 * create-thread default all consume this table — never hand-maintained
 * numeric copies. General (7) is the explicit create fallback.
 *
 * Unit-proved (ForumCategoriesTest).
 */
object ForumCategories {
    data class Entry(val id: Int, val label: String)

    val ENTRIES: List<Entry> = listOf(
        Entry(7, "General"),
        Entry(1, "Anime"),
        Entry(2, "Manga"),
        Entry(3, "Light Novels"),
        Entry(4, "Visual Novels"),
        Entry(5, "Release Discussion"),
        Entry(8, "News"),
        Entry(9, "Music"),
        Entry(10, "Gaming")
    )

    /** Fallback when a thread is created with no explicit category. */
    const val DEFAULT_CREATE_ID = 7

    private val byId = ENTRIES.associateBy { it.id }
    private val byLabel = ENTRIES.associateBy { it.label }

    fun idForLabel(label: String): Int? = byLabel[label]?.id

    fun labelForId(id: Int): String? = byId[id]?.label
}
