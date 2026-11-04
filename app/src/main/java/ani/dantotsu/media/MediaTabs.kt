package ani.dantotsu.media

/**
 * Tabs of the details screen. The saved value per show (`Selected.window`, also in
 * backups) keeps its old meaning: 0 Info, 1 Watch/Read, 2 Comments. Themes was added
 * later and is saved as 3; pages are in the order the bar shows them.
 */
object MediaTabs {
    const val INFO = 0
    const val MAIN = 1
    const val COMMENTS = 2
    const val THEMES = 3

    /** Saved values of the tabs shown, in bar order: Info, Watch/Read, Themes (anime), Comments. */
    fun windows(anime: Boolean, comments: Boolean): List<Int> =
        listOf(INFO, MAIN) + (if (anime) listOf(THEMES) else emptyList()) + (if (comments) listOf(COMMENTS) else emptyList())

    /** Page of a saved [window]; a tab that is not shown falls back to Watch/Read. */
    fun pageOf(window: Int, windows: List<Int>): Int =
        windows.indexOf(window).takeIf { it >= 0 } ?: windows.indexOf(MAIN)

    fun windowAt(page: Int, windows: List<Int>): Int = windows.getOrElse(page) { MAIN }
}
