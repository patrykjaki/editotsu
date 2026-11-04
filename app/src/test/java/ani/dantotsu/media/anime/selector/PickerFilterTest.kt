package ani.dantotsu.media.anime.selector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class PickerFilterTest {

    private fun row(i: Int, f: RowFacets, audio: AudioKind? = AudioKind.SUB) = PickerRow(
        ref = RowRef(i, 0),
        provider = "torrentio",
        presentation = SourcePresentationKind.RELEASE,
        header = f.group ?: "row $i",
        resolution = f.height,
        transport = "Torrent",
        audioKind = audio,
        facets = f,
    )

    private val rows = listOf(
        row(0, RowFacets("1080p", "AVC", "SDR", "ToonsHub", seeders = 954, sizeBytes = 584_000_000, height = 1080)),
        row(1, RowFacets("720p", "AVC", "SDR", "ToonsHub", seeders = 40, sizeBytes = 300_000_000, height = 720)),
        row(2, RowFacets("4K", "HEVC", "Dolby Vision", "NTb", seeders = null, sizeBytes = 8_000_000_000, height = 2160)),
        row(3, RowFacets("1080p", "AV1", "HDR10+", "Trix", seeders = 120, sizeBytes = null, height = 1080), AudioKind.DUAL),
        row(4, RowFacets(null, null, null, null), null),
    )
    private val list = PickerListBuilder.build(rows)
    private val facets = rows.associate { it.ref to it.facets }
    private fun visibleRefs(state: FilterState, audio: AudioKind? = null) =
        PickerFilter.apply(list.entries, facets, audio, state).entries.flatMap { it.refs }

    @Test
    fun options_list_present_values_once_quality_high_first() {
        val o = PickerFilter.options(rows)
        assertEquals(listOf("4K", "1080p", "720p"), o[FilterDimension.RESOLUTION])
        assertEquals(listOf("AVC", "HEVC", "AV1"), o[FilterDimension.CODEC])
        assertEquals(listOf("ToonsHub", "NTb", "Trix"), o[FilterDimension.GROUP])
        assertTrue(PickerFilter.showButton(rows, FilterState()))
        assertFalse(PickerFilter.showButton(rows.take(1), FilterState()))
    }

    @Test
    fun a_row_matches_every_constrained_dimension_and_unknown_never_matches() {
        assertEquals(listOf(RowRef(0, 0), RowRef(3, 0)), visibleRefs(FilterState(resolutions = setOf("1080p"))))
        assertEquals(
            listOf(RowRef(0, 0)),
            visibleRefs(FilterState(resolutions = setOf("1080p"), groups = setOf("ToonsHub"))),
        )
        assertEquals(
            listOf(RowRef(0, 0), RowRef(1, 0), RowRef(3, 0)),
            visibleRefs(FilterState(groups = setOf("ToonsHub", "Trix"))),
        )
        assertEquals(listOf(RowRef(3, 0)), visibleRefs(FilterState(resolutions = setOf("1080p")), AudioKind.DUAL))
    }

    @Test
    fun visible_rows_are_a_subset_idempotent_and_sort_keeps_the_set() {
        val random = Random(7)
        val opts = PickerFilter.options(rows)
        repeat(300) {
            fun pick(d: FilterDimension) = opts.getValue(d).filter { random.nextInt(3) == 0 }.toSet()
            val state = FilterState(
                pick(FilterDimension.RESOLUTION), pick(FilterDimension.CODEC),
                pick(FilterDimension.HDR), pick(FilterDimension.GROUP),
                PickerSort.values()[random.nextInt(PickerSort.values().size)],
            )
            val audio = listOf(null, AudioKind.SUB, AudioKind.DUAL)[random.nextInt(3)]
            val once = visibleRefs(state, audio)
            assertTrue(rows.map { it.ref }.containsAll(once))
            assertEquals(once, visibleRefs(state, audio))
            PickerSort.values().forEach { s ->
                assertEquals(once.toSet(), visibleRefs(state.copy(sort = s), audio).toSet())
            }
        }
    }

    @Test
    fun sorts_put_unknown_last_and_keep_ties_in_default_order() {
        assertEquals(
            listOf(0, 3, 1, 2, 4).map { RowRef(it, 0) },
            visibleRefs(FilterState(sort = PickerSort.SEEDERS)),
        )
        assertEquals(
            listOf(2, 0, 1, 3, 4).map { RowRef(it, 0) },
            visibleRefs(FilterState(sort = PickerSort.SIZE)),
        )
        assertEquals(
            listOf(2, 0, 3, 1, 4).map { RowRef(it, 0) },
            visibleRefs(FilterState(sort = PickerSort.QUALITY)),
        )
        assertEquals(rows.map { it.ref }, visibleRefs(FilterState()))
    }

    @Test
    fun sanitize_drops_values_no_row_has() {
        val stored = FilterState(groups = setOf("SubsPlease", "Trix"), resolutions = setOf("480p"), sort = PickerSort.SIZE)
        assertEquals(FilterState(groups = setOf("Trix"), sort = PickerSort.SIZE), PickerFilter.sanitize(stored, rows))
    }

    @Test
    fun all_hidden_is_reported_and_clear_keeps_sort() {
        val state = FilterState(groups = setOf("NTb"), codecs = setOf("AVC"), sort = PickerSort.SEEDERS)
        val result = PickerFilter.apply(list.entries, facets, null, state)
        assertTrue(result.hiddenAll)
        assertEquals(FilterState(sort = PickerSort.SEEDERS), state.cleared())
    }

    @Test
    fun store_round_trip_and_bad_json() {
        val state = FilterState(groups = setOf("ToonsHub"), hdr = setOf("SDR"), sort = PickerSort.QUALITY)
        assertEquals(state, PickerFilterStore.decode(PickerFilterStore.encode(state)))
        assertEquals(FilterState(), PickerFilterStore.decode("{oops"))
        assertEquals(FilterState(), PickerFilterStore.decode(null))
        assertEquals(FilterState(sort = PickerSort.SIZE), PickerFilterStore.decode("""{"sort":"SIZE","extra":1}"""))
    }

    @Test
    fun size_text_parses() {
        assertEquals(584_520_000L, RowFacets.parseSize("584.52 MB"))
        assertEquals(1_210_000_000L, RowFacets.parseSize("1.21 GB"))
        assertEquals(null, RowFacets.parseSize("big"))
    }

    @Test
    fun fixture_rows_get_facets() {
        val text = javaClass.classLoader!!.getResource("picker/torrentio_rows_2026-11-01.json")!!.readText()
        val name = kotlinx.serialization.json.Json.parseToJsonElement(text)
            .let { (it as kotlinx.serialization.json.JsonArray)[13] }
            .let { (it as kotlinx.serialization.json.JsonPrimitive).content }
        val f = RowFacets.of(SourceRowClassifier.parseRelease(name), 2160)
        assertEquals("4K", f.resolution)
        assertEquals("NTb", f.group)
        assertEquals("HEVC", f.codec)
        assertTrue(f.seeders != null && f.sizeBytes != null)
    }

    @Test
    fun counts_are_cards_per_value() {
        val c = PickerFilter.counts(list.entries, facets)
        val toonsCards = list.entries.count { e -> e.refs.any { it == RowRef(0, 0) || it == RowRef(1, 0) } }
        assertEquals(toonsCards, c.getValue(FilterDimension.GROUP)["ToonsHub"])
        assertEquals(1, c.getValue(FilterDimension.GROUP)["NTb"])
        assertEquals(1, c.getValue(FilterDimension.CODEC)["HEVC"])
        // A card counts once per value even when two of its rows share it.
        assertTrue(c.getValue(FilterDimension.CODEC).getValue("AVC") <= 2)
        assertEquals(null, c.getValue(FilterDimension.GROUP)["Nobody"])
    }

    @Test
    fun groups_most_releases_first_and_selected_stay_visible() {
        val values = listOf("b", "a", "c", "d", "e")
        val counts = mapOf("a" to 1, "b" to 5, "c" to 3, "d" to 1, "e" to 9)
        val g = PickerFilter.groupList(values, counts, selected = setOf("d"), limit = 2)
        assertEquals(listOf("e", "b", "d"), g.shown)
        assertEquals(listOf("c", "a"), g.hidden)
        val all = PickerFilter.groupList(values, counts, emptySet(), limit = 10)
        assertEquals(listOf("e", "b", "c", "a", "d"), all.shown)
        assertTrue(all.hidden.isEmpty())
    }
}
