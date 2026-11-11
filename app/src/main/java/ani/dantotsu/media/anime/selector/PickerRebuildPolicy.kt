package ani.dantotsu.media.anime.selector

/** How long the picker waits to rebuild its list after an extractor arrives. */
object PickerRebuildPolicy {
    /** Extractors arriving within this window are applied in one rebuild. */
    const val DELAY_MS = 150L

    /** The first list shows at once; later rebuilds are batched. */
    fun delayMs(nothingShownYet: Boolean): Long = if (nothingShownYet) 0L else DELAY_MS
}
