package ani.dantotsu.media

/**
 * A show's chosen source is kept by name next to its saved position. The
 * position points into the installed list, which shifts when extensions are
 * added, removed or reordered; the name does not.
 */
internal object SelectedSource {
    const val KEY = "SelectedSource-"

    /** The position to use now: where [savedName] sits in [names], else the saved position. */
    fun resolve(savedName: String?, savedIndex: Int, names: List<String>): Int =
        savedName?.let { names.indexOf(it) }?.takeIf { it >= 0 } ?: savedIndex

    /** The name to remember for [index], or null when the list cannot tell (not loaded, out of range). */
    fun nameFor(index: Int, names: List<String>, listReady: Boolean): String? =
        if (listReady) names.getOrNull(index)?.takeIf { it.isNotBlank() } else null
}
