package ani.dantotsu.settings

import ani.dantotsu.media.MediaType

/**
 * Page model of the extensions screen: a media type (primary tabs) times
 * installed/available (segmented toggle). Each logical page keeps its stable
 * 0..5 id (installed anime, available anime, installed manga, available manga,
 * installed novels, available novels) for saved state and fragment tags, while
 * the ViewPager displays only the 2 status pages of the active type so swiping
 * can never cross types.
 */
object ExtensionPages {
    const val PAGE_COUNT = 6
    const val STATUS_PAGE_COUNT = 2
    const val STATUS_INSTALLED = 0
    const val STATUS_AVAILABLE = 1

    val TYPES = listOf(MediaType.ANIME, MediaType.MANGA, MediaType.NOVEL)

    enum class SwipeAxis { NONE, HORIZONTAL, VERTICAL }

    fun pageOf(type: MediaType, installed: Boolean): Int =
        TYPES.indexOf(type) * 2 + if (installed) 0 else 1

    fun typeOf(page: Int): MediaType = TYPES[page.coerceIn(0, PAGE_COUNT - 1) / 2]

    fun isInstalled(page: Int): Boolean = page.coerceIn(0, PAGE_COUNT - 1) % 2 == 0

    fun statusIndexOf(installed: Boolean): Int =
        if (installed) STATUS_INSTALLED else STATUS_AVAILABLE

    fun statusIndexOf(page: Int): Int = statusIndexOf(isInstalled(page))

    fun isInstalledStatusIndex(index: Int): Boolean = index != STATUS_AVAILABLE

    fun pageOfStatus(type: MediaType, statusIndex: Int): Int =
        pageOf(type, isInstalledStatusIndex(statusIndex))

    fun belongsToType(pageId: Long, type: MediaType): Boolean =
        pageId in 0L until PAGE_COUNT.toLong() && typeOf(pageId.toInt()) == type

    fun tabIndexOf(type: MediaType): Int = TYPES.indexOf(type).coerceAtLeast(0)

    fun typeForTab(index: Int): MediaType = TYPES[index.coerceIn(0, TYPES.lastIndex)]

    /**
     * Locks vertical drags before ViewPager2's internal horizontal RecyclerView
     * can intercept on a slightly diagonal vertical scroll (|dx| > touchSlop).
     */
    fun classifySwipeAxis(dx: Float, dy: Float, touchSlop: Float): SwipeAxis {
        val slop = touchSlop.coerceAtLeast(1f)
        return when {
            dy > slop * 0.5f && dy >= dx -> SwipeAxis.VERTICAL
            dx > slop && dx > dy -> SwipeAxis.HORIZONTAL
            else -> SwipeAxis.NONE
        }
    }
}
