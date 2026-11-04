package ani.dantotsu.media.anime.selector

import ani.dantotsu.settings.saving.PrefManager
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Facts a row is filtered and sorted by. Null = unknown. */
data class RowFacets(
    val resolution: String? = null,
    val codec: String? = null,
    /** "Dolby Vision", "HDR10+", "HDR10", "HDR" or "SDR". */
    val hdr: String? = null,
    val group: String? = null,
    val seeders: Int? = null,
    val sizeBytes: Long? = null,
    /** Vertical pixels for the quality sort. */
    val height: Int? = null,
) {
    companion object {
        /** [sizeBytes] from the source's data when it has one; otherwise parsed from the size text */
        fun of(fields: SourceRowClassifier.ReleaseFields, height: Int?, sizeBytes: Long? = null): RowFacets = RowFacets(
            resolution = ResolutionLabel.label(height),
            codec = fields.codec,
            hdr = fields.hdrVariant ?: if (fields.hdr) "HDR" else "SDR",
            group = fields.releaseGroup,
            seeders = fields.seeders,
            sizeBytes = sizeBytes ?: parseSize(fields.sizeText),
            height = height,
        )

        private val SIZE = Regex("""(?i)^\s*([\d.,]+)\s*([KMGT]?)i?B\s*$""")

        fun parseSize(text: String?): Long? {
            val m = SIZE.find(text ?: return null) ?: return null
            val n = m.groupValues[1].replace(",", ".").toDoubleOrNull() ?: return null
            val mult = when (m.groupValues[2].uppercase()) {
                "K" -> 1e3; "M" -> 1e6; "G" -> 1e9; "T" -> 1e12; else -> 1.0
            }
            return (n * mult).toLong()
        }
    }
}

enum class PickerSort { DEFAULT, SEEDERS, SIZE, QUALITY }

enum class FilterDimension { RESOLUTION, CODEC, HDR, GROUP }

@Serializable
data class FilterState(
    val resolutions: Set<String> = emptySet(),
    val codecs: Set<String> = emptySet(),
    val hdr: Set<String> = emptySet(),
    val groups: Set<String> = emptySet(),
    val sort: PickerSort = PickerSort.DEFAULT,
) {
    val hasFilters: Boolean
        get() = resolutions.isNotEmpty() || codecs.isNotEmpty() || hdr.isNotEmpty() || groups.isNotEmpty()

    fun values(d: FilterDimension): Set<String> = when (d) {
        FilterDimension.RESOLUTION -> resolutions
        FilterDimension.CODEC -> codecs
        FilterDimension.HDR -> hdr
        FilterDimension.GROUP -> groups
    }

    fun with(d: FilterDimension, v: Set<String>): FilterState = when (d) {
        FilterDimension.RESOLUTION -> copy(resolutions = v)
        FilterDimension.CODEC -> copy(codecs = v)
        FilterDimension.HDR -> copy(hdr = v)
        FilterDimension.GROUP -> copy(groups = v)
    }

    fun toggled(d: FilterDimension, value: String): FilterState {
        val cur = values(d)
        return with(d, if (value in cur) cur - value else cur + value)
    }

    fun cleared(): FilterState = FilterState(sort = sort)
}

/** Result of filtering: what to show, out of how many cards. */
data class FilteredEntries(val entries: List<PickerEntry>, val total: Int) {
    val hiddenAll: Boolean get() = total > 0 && entries.isEmpty()
}

object PickerFilter {
    private fun valueOf(f: RowFacets, d: FilterDimension): String? = when (d) {
        FilterDimension.RESOLUTION -> f.resolution
        FilterDimension.CODEC -> f.codec
        FilterDimension.HDR -> f.hdr
        FilterDimension.GROUP -> f.group
    }

    /** Distinct values present per dimension, in a stable order (quality high first, else first seen). */
    fun options(rows: List<PickerRow>): Map<FilterDimension, List<String>> =
        FilterDimension.values().associateWith { d ->
            val present = rows.mapNotNull { valueOf(it.facets, d) }.distinct()
            if (d == FilterDimension.RESOLUTION) {
                present.sortedByDescending { v -> rows.first { it.facets.resolution == v }.facets.height ?: 0 }
            } else {
                present
            }
        }

    /** Per dimension and value: how many cards (releases) have a row with that value. */
    fun counts(entries: List<PickerEntry>, facets: Map<RowRef, RowFacets>): Map<FilterDimension, Map<String, Int>> =
        FilterDimension.values().associateWith { d ->
            val out = HashMap<String, Int>()
            entries.forEach { e ->
                e.refs.mapNotNull { facets[it]?.let { f -> valueOf(f, d) } }.toSet()
                    .forEach { v -> out[v] = (out[v] ?: 0) + 1 }
            }
            out
        }

    /**
     * Release groups for the filter sheet: most releases first (ties by name), the
     * first [limit] shown plus any selected ones; [hidden] is what "+N more" opens.
     */
    data class GroupList(val shown: List<String>, val hidden: List<String>)

    fun groupList(values: List<String>, counts: Map<String, Int>, selected: Set<String>, limit: Int = 6): GroupList {
        val ordered = values.sortedWith(
            compareByDescending<String> { counts[it] ?: 0 }.thenBy { it.lowercase() }
        )
        val head = ordered.take(limit)
        val tail = ordered.drop(limit)
        return GroupList(head + tail.filter { it in selected }, tail.filterNot { it in selected })
    }

    /** The Filter button is worth showing when some dimension has two or more values. */
    fun showButton(rows: List<PickerRow>, state: FilterState): Boolean =
        state.hasFilters || options(rows).values.any { it.size >= 2 }

    /** Drops stored values no current row has (a stored filter never hides everything by itself). */
    fun sanitize(state: FilterState, rows: List<PickerRow>): FilterState {
        val opts = options(rows)
        return FilterDimension.values().fold(state) { s, d ->
            s.with(d, s.values(d).filterTo(LinkedHashSet()) { it in opts.getValue(d) })
        }
    }

    fun matches(f: RowFacets, state: FilterState): Boolean = FilterDimension.values().all { d ->
        val selected = state.values(d)
        selected.isEmpty() || valueOf(f, d)?.let { it in selected } == true
    }

    /**
     * Audio chip and filters, then sort. [entries] is already in the default
     * order. A card is shown when one of its rows matches. Sorts are stable;
     * unknown values go last.
     */
    fun apply(
        entries: List<PickerEntry>,
        facets: Map<RowRef, RowFacets>,
        audio: AudioKind?,
        state: FilterState,
    ): FilteredEntries {
        val byAudio = AudioKindFilter.apply(entries, audio)
        val filtered = if (!state.hasFilters) byAudio else byAudio.filter { e ->
            e.refs.any { ref -> facets[ref]?.let { matches(it, state) } == true }
        }
        return FilteredEntries(sort(filtered, facets, state.sort), total = entries.size)
    }

    fun sort(entries: List<PickerEntry>, facets: Map<RowRef, RowFacets>, sort: PickerSort): List<PickerEntry> {
        if (sort == PickerSort.DEFAULT) return entries
        fun key(e: PickerEntry): Long? = e.refs.mapNotNull { facets[it] }.mapNotNull { f ->
            when (sort) {
                PickerSort.SEEDERS -> f.seeders?.toLong()
                PickerSort.SIZE -> f.sizeBytes
                PickerSort.QUALITY -> f.height?.toLong()
                PickerSort.DEFAULT -> null
            }
        }.maxOrNull()
        return entries.sortedWith(compareBy<PickerEntry> { if (key(it) == null) 1 else 0 }
            .thenByDescending { key(it) ?: 0L })
    }
}

/** Filter and sort remembered per show. */
object PickerFilterStore {
    private val json = Json { ignoreUnknownKeys = true }

    fun key(mediaId: Int): String = "PickerFilter-v1-$mediaId"

    fun load(mediaId: Int): FilterState {
        val raw = PrefManager.getNullableCustomVal(key(mediaId), null, String::class.java) ?: return FilterState()
        return decode(raw)
    }

    fun save(mediaId: Int, state: FilterState) {
        if (state == FilterState()) {
            PrefManager.setCustomVal(key(mediaId), null as String?)
        } else {
            PrefManager.setCustomVal(key(mediaId), encode(state))
        }
    }

    fun encode(state: FilterState): String = json.encodeToString(FilterState.serializer(), state)

    fun decode(raw: String?): FilterState =
        raw?.let { runCatching { json.decodeFromString(FilterState.serializer(), it) }.getOrNull() } ?: FilterState()
}
