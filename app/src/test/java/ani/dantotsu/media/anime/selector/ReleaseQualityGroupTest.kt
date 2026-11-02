package ani.dantotsu.media.anime.selector

import ani.dantotsu.FileUrl
import ani.dantotsu.parsers.Video
import ani.dantotsu.parsers.VideoExtractor
import ani.dantotsu.parsers.VideoServer
import ani.dantotsu.parsers.VideoType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** One release offered in several qualities is one card with a button per quality. */
class ReleaseQualityGroupTest {

    @Test
    fun key_ignores_resolution_and_crc_only() {
        val k1080 = ReleaseQualityKey.of("[SubsPlease] Sakamoto Days - 05 (1080p) [F2A1B3C4].mkv")
        assertEquals(k1080, ReleaseQualityKey.of("[SubsPlease] Sakamoto Days - 05 (720p) [0B9D7E21].mkv"))
        assertEquals(k1080, ReleaseQualityKey.of("[SubsPlease] Sakamoto Days - 05 (480p) [99AA00BB].mkv"))
        assertEquals(
            ReleaseQualityKey.of("[Erai-raws] Sakamoto Days - 05 [1080p CR WEB-DL AVC AAC][MultiSub][1A2B3C4D]"),
            ReleaseQualityKey.of("[Erai-raws] Sakamoto Days - 05 [720p CR WEB-DL AVC AAC][MultiSub][5E6F7A8B]"),
        )
        // other episode, other group, other codec: other release
        assertNotEquals(k1080, ReleaseQualityKey.of("[SubsPlease] Sakamoto Days - 06 (1080p) [F2A1B3C4].mkv"))
        assertNotEquals(k1080, ReleaseQualityKey.of("[ASW] Sakamoto Days - 05 (1080p) [F2A1B3C4].mkv"))
        assertNotEquals(
            ReleaseQualityKey.of("[Erai-raws] Show - 05 [1080p HEVC]"),
            ReleaseQualityKey.of("[Erai-raws] Show - 05 [720p AVC]"),
        )
        assertNull(ReleaseQualityKey.of("[SubsPlease] Sakamoto Days - 05 [F2A1B3C4].mkv"))
        // x264 / x265 are not resolutions
        assertNotEquals(ReleaseQualityKey.of("Show S01E05 1080p WEB x264-GRP"), ReleaseQualityKey.of("Show S01E05 1080p WEB x265-GRP"))
    }

    private fun row(i: Int, res: Int?, key: String? = "subsplease show 05", seeds: Int = 0, exact: Boolean = false, family: Boolean = false) =
        PickerRow(
            ref = RowRef(i, 0),
            provider = "editotsu",
            presentation = SourcePresentationKind.RELEASE,
            header = "SubsPlease",
            resolution = res,
            transport = "Torrent",
            audioKind = AudioKind.SUB,
            rememberedExact = exact,
            rememberedFamily = family,
            facets = RowFacets(seeders = seeds, height = res, resolution = ResolutionLabel.label(res)),
            qualityKey = key,
        )

    @Test
    fun three_qualities_make_one_card_singles_stay() {
        val other = row(3, 1080, key = "asw show 05")
        val rows = listOf(row(0, 720), row(1, 1080), other, row(2, 480))
        val list = PickerListBuilder.build(rows)
        assertEquals(2, list.entries.size)
        val card = list.entries[0] as PickerEntry.Release
        assertEquals(RowRef(1, 0), card.row.ref) // highest shown
        assertEquals(listOf(1080, 720, 480), card.qualities.map { it.resolution })
        assertEquals(setOf(RowRef(0, 0), RowRef(1, 0), RowRef(2, 0)), card.refs.toSet())
        val single = list.entries[1] as PickerEntry.Release
        assertEquals(other, single.row)
        assertTrue(single.others.isEmpty())
        assertTrue(single.qualities.isEmpty())
    }

    @Test
    fun remembered_quality_is_the_card_and_the_best_match() {
        val rows = listOf(row(0, 1080), row(1, 720, exact = true), row(2, 480))
        val list = PickerListBuilder.build(rows)
        val card = list.entries.single() as PickerEntry.Release
        assertEquals(RowRef(1, 0), card.row.ref)
        assertEquals(RowRef(1, 0), list.best?.ref)
        assertEquals(0, list.bestIndex)
        assertTrue(list.isRememberedQuality(RowRef(1, 0)))
    }

    private fun stream(i: Int, res: Int, exact: Boolean = false, preferred: Boolean = false) = PickerRow(
        ref = RowRef(i, 0), provider = "pahe", presentation = SourcePresentationKind.STREAM,
        header = "Kwik ${res}p", resolution = res, transport = "HLS", audioKind = AudioKind.SUB,
        rememberedExact = exact, preferred = preferred,
    )

    @Test
    fun every_card_fills_one_quality_the_one_a_card_tap_plays() {
        // nothing remembered: highest, on stream and release cards alike
        val plain = PickerListBuilder.build(listOf(stream(0, 720), stream(1, 1080), row(2, 480), row(3, 1080)))
        val streamCard = plain.entries.first { it is PickerEntry.StreamGroup }
        val releaseCard = plain.entries.first { it is PickerEntry.Release }
        assertEquals(RowRef(1, 0), plain.defaultQuality(streamCard))
        assertEquals(RowRef(3, 0), plain.defaultQuality(releaseCard))
        // remembered: that quality, wherever it is in the list
        val remembered = PickerListBuilder.build(listOf(stream(0, 720, exact = true), stream(1, 1080)))
        assertEquals(RowRef(0, 0), remembered.defaultQuality(remembered.entries.single()))
        // an extension-preferred row is not a remembered quality: still the highest
        val preferred = PickerListBuilder.build(listOf(row(0, 1080), row(1, 720), row(2, 480).copy(preferred = true)))
        assertEquals(RowRef(0, 0), preferred.defaultQuality(preferred.entries.single()))
    }

    @Test
    fun reposts_and_unknown_resolutions_keep_their_own_card() {
        val rows = listOf(row(0, 1080), row(1, 720), row(2, 1080), row(3, null), row(4, 1080, key = null))
        val entries = PickerListBuilder.build(rows).entries
        assertEquals(4, entries.size)
        assertEquals(listOf(1080, 720), (entries[0] as PickerEntry.Release).qualities.map { it.resolution })
        assertEquals(listOf(RowRef(2, 0), RowRef(3, 0), RowRef(4, 0)), entries.drop(1).map { (it as PickerEntry.Release).row.ref })
    }

    @Test
    fun filter_keeps_the_card_when_one_quality_matches() {
        val rows = listOf(row(0, 1080), row(1, 720), row(2, 480))
        val list = PickerListBuilder.build(rows)
        val facets = rows.associate { it.ref to it.facets }
        val state = FilterState().toggled(FilterDimension.RESOLUTION, "720p")
        assertEquals(1, PickerFilter.apply(list.entries, facets, null, state).entries.size)
    }

    private fun ext(name: String, hash: Char) = object : VideoExtractor() {
        override val server = VideoServer(
            name, FileUrl("magnet:?xt=urn:btih:" + hash.toString().repeat(40)),
            mapOf(StableCandidateBuilder.PROVIDER_EXTRA_KEY to "p"),
        )
        init { videos = listOf(Video(null, VideoType.CONTAINER, FileUrl("magnet:?xt=urn:btih:" + hash.toString().repeat(40)))) }
        override suspend fun extract() = throw UnsupportedOperationException()
    }

    @Test
    fun builder_gives_subsplease_qualities_one_key_and_keeps_exact_keys() {
        val links = listOf(
            ext("[SubsPlease] Sakamoto Days - 05 (1080p) [F2A1B3C4].mkv\n👤 900 💾 1.4 GB ⚙️ Nyaa", 'a'),
            ext("[SubsPlease] Sakamoto Days - 05 (720p) [0B9D7E21].mkv\n👤 300 💾 700 MB ⚙️ Nyaa", 'b'),
            ext("[SubsPlease] Sakamoto Days - 05 (480p) [99AA00BB].mkv\n👤 40 💾 350 MB ⚙️ Nyaa", 'c'),
        )
        val rows = PickerRowsBuilder().build(links, null, null, "x")
        assertEquals(1, rows.map { it.qualityKey }.distinct().size)
        assertTrue(rows.first().qualityKey != null)
        val card = PickerListBuilder.build(rows).entries.single() as PickerEntry.Release
        assertEquals(listOf(1080, 720, 480), card.qualities.map { it.resolution })
        // a quality button plays its own row, so it keys exactly as that row does today
        val keys = links.map { StableCandidateBuilder.exactKey(StableCandidateBuilder.providerOf(it.server), it.server) }
        assertEquals(3, keys.distinct().size)
    }
}
