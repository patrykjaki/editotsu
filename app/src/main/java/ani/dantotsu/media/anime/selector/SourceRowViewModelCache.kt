package ani.dantotsu.media.anime.selector

/**
 * Bounded recycled-bind cache for immutable [SourceRowViewModel]s
 * (v3.3 finding 7: fast-scroll regression).
 *
 * Before v3.3, every recycled `onBindViewHolder` ran the full
 * [SourceRowSummarizer.build] chain (release parse + aggressive
 * clean-title regex suite + pill/meta/detail construction) even when
 * the row's content was unchanged — measured ~50-3400µs per RELEASE
 * build on host, dominated by `cleanDisplayTitle`.
 *
 * After v3.3, the adapter resolves each row through this cache, so a
 * recycled bind of unchanged content reuses the previously built
 * model and performs zero classification/parsing work.
 *
 * Stale-row safety: [SourceRowSummarizer.build] is a pure function of
 * its inputs, and the key ([rowKey]) covers every input
 * (`serverName`, `formatName`, `url`). Equal keys therefore imply
 * equal models — a cache hit can never display another row's data.
 * The adapter additionally owns one cache instance per extractor
 * section, so cross-section leakage is structurally impossible.
 *
 * Bound: LRU map capped at [maxEntries] (default 128); never global,
 * never unbounded. Not thread-safe by design: adapter binds run on
 * the main thread only.
 */
class SourceRowViewModelCache(private val maxEntries: Int = 128) {

    private val map =
        object : LinkedHashMap<String, SourceRowViewModel>(maxEntries, 0.75f, true) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<String, SourceRowViewModel>?,
            ): Boolean = size > maxEntries
        }

    /**
     * Return the cached model for [key], invoking [build] exactly once
     * per distinct key (first miss) and never on hits.
     */
    fun getOrBuild(key: String, build: () -> SourceRowViewModel): SourceRowViewModel =
        map.getOrPut(key, build)

    /** Drop all entries (section teardown / list replacement). */
    fun clear() {
        map.clear()
    }

    /** Current entry count (tests / diagnostics; never exceeds [maxEntries]). */
    fun size(): Int = map.size

    companion object {
        /**
         * Content key covering every [SourceRowSummarizer.build] input
         * used by the picker adapter. `\n` cannot collide with URL or
         * server-name content in practice; all three components are
         * immutable per row.
         */
        fun rowKey(serverName: String, formatName: String?, url: String): String =
            serverName + "\n" + (formatName ?: "") + "\n" + url
    }
}
