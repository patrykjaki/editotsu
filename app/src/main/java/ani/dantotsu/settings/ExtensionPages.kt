package ani.dantotsu.settings

import ani.dantotsu.media.MediaType

/**
 * Page model of the extensions screen: a media type (segmented control)
 * times installed/available (chips). The ViewPager keeps its original six
 * pages in this order: installed anime, available anime, installed manga,
 * available manga, installed novels, available novels.
 */
object ExtensionPages {
    const val PAGE_COUNT = 6
    val TYPES = listOf(MediaType.ANIME, MediaType.MANGA, MediaType.NOVEL)

    fun pageOf(type: MediaType, installed: Boolean): Int =
        TYPES.indexOf(type) * 2 + if (installed) 0 else 1

    fun typeOf(page: Int): MediaType = TYPES[page.coerceIn(0, PAGE_COUNT - 1) / 2]

    fun isInstalled(page: Int): Boolean = page.coerceIn(0, PAGE_COUNT - 1) % 2 == 0
}
