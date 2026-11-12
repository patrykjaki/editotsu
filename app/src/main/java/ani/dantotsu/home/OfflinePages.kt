package ani.dantotsu.home

/** Pages of the offline shell ([NoInternet]): 0 offline anime, 1 local media, 2 offline manga. */
object OfflinePages {
    const val ANIME = 0
    const val LOCAL = 1
    const val MANGA = 2

    /**
     * Page to open: the one named by the FRAGMENT_CLASS_NAME extra when it names an
     * offline page, else the default start tab (clamped to the three pages).
     */
    fun startPage(fragmentClassName: String?, defaultTab: Int): Int = when (fragmentClassName) {
        "ani.dantotsu.download.anime.OfflineAnimeFragment" -> ANIME
        "ani.dantotsu.offline.LocalFragment" -> LOCAL
        "ani.dantotsu.download.manga.OfflineMangaFragment" -> MANGA
        else -> defaultTab.coerceIn(ANIME, MANGA)
    }
}
