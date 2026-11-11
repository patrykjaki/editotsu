package ani.dantotsu.media.anime.selector

/** "Search sources": which source to try after one found nothing. */
object SourceSearchOrder {
    /**
     * Index into [names] of the next source after [current], skipping "Local" (it is not in
     * the source dropdown and holds only downloads). Null when there is none.
     */
    fun next(names: List<String>, current: Int): Int? =
        ((current + 1) until names.size).firstOrNull { names[it] != "Local" }
}
