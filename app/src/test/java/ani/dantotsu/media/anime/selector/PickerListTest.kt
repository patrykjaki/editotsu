package ani.dantotsu.media.anime.selector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PickerListTest {

    private fun stream(
        i: Int,
        header: String,
        res: Int?,
        transport: String? = "HLS",
        provider: String = "pahe",
        audio: AudioKind? = AudioKindDetector.detect(header),
        preferred: Boolean = false,
    ) = PickerRow(
        ref = RowRef(i, 0),
        provider = provider,
        presentation = SourcePresentationKind.STREAM,
        header = header,
        resolution = res,
        transport = transport,
        audioKind = audio,
        preferred = preferred,
    )

    private fun release(
        i: Int,
        group: String,
        audio: AudioKind? = null,
        exact: Boolean = false,
        family: Boolean = false,
        preferred: Boolean = false,
    ) = PickerRow(
        ref = RowRef(i, 0),
        provider = "torrentio",
        presentation = SourcePresentationKind.RELEASE,
        header = group,
        resolution = 1080,
        transport = "Torrent",
        audioKind = audio,
        preferred = preferred,
        rememberedExact = exact,
        rememberedFamily = family,
    )

    // ---- Stream grouping (AnimePahe case) ----------------------------------

    @Test
    fun sixAnimePaheRowsBecomeOneCardWithThreeQualitiesAndMp4Alternates() {
        val rows = listOf(
            stream(0, "MTBB (Sub/English)", 360),
            stream(1, "MTBB (Sub/English)", 720),
            stream(2, "MTBB (Sub/English)", 1080),
            stream(3, "MTBB (Sub/English)", 360, transport = "MP4"),
            stream(4, "MTBB (Sub/English)", 720, transport = "MP4"),
            stream(5, "MTBB (Sub/English)", 1080, transport = "MP4"),
        )
        val list = PickerListBuilder.build(rows)
        assertEquals(1, list.entries.size)
        val g = list.entries[0] as PickerEntry.StreamGroup
        assertEquals("MTBB", g.name)
        assertEquals("Sub · English", g.audioTag)
        assertEquals(listOf("1080p", "720p", "360p"), g.primary.map { it.label })
        assertEquals(listOf(RowRef(2, 0), RowRef(1, 0), RowRef(0, 0)), g.primary.map { it.ref })
        assertEquals(listOf("1080p", "720p", "360p"), g.alternates.map { it.label })
        assertEquals("MP4", g.alternateLabel)
    }

    @Test
    fun resolutionInsideTheHeaderStillMerges() {
        val rows = listOf(
            stream(0, "Kwik · MTBB 720p (61MB)", 720),
            stream(1, "Kwik · MTBB 1080p (138MB)", 1080),
        )
        val g = PickerListBuilder.group(rows).single() as PickerEntry.StreamGroup
        assertEquals("Kwik · MTBB", g.name)
        assertEquals(listOf("1080p", "720p"), g.primary.map { it.label })
        assertTrue(g.alternates.isEmpty())
    }

    @Test
    fun subAndDubStayInSeparateCards() {
        val rows = listOf(
            stream(0, "MTBB (Sub/English)", 1080),
            stream(1, "MTBB (Dub/English)", 1080),
        )
        val entries = PickerListBuilder.group(rows)
        assertEquals(2, entries.size)
        assertEquals(AudioKind.SUB, entries[0].audioKind)
        assertEquals(AudioKind.DUB, entries[1].audioKind)
    }

    // Phone data 2026-10-31: AnimePahe names its servers by audio language.
    @Test
    fun mtbbLanguageParenSplitsSubFromEnglishDub() {
        val rows = listOf(
            stream(0, "MTBB (English) - 1080p BD (HLS)", 1080),
            stream(1, "MTBB (Sub) - 1080p BD (HLS)", 1080),
            stream(2, "MTBB (English) - 720p BD (HLS)", 720),
            stream(3, "MTBB (Sub) - 720p BD (HLS)", 720),
        )
        val entries = PickerListBuilder.group(rows).map { it as PickerEntry.StreamGroup }
        assertEquals(2, entries.size)
        assertEquals(AudioKind.SUB, entries[0].audioKind)
        assertEquals("Sub", entries[0].audioTag)
        assertEquals(AudioKind.DUB, entries[1].audioKind)
        assertEquals("Dub · English", entries[1].audioTag)
        assertEquals(entries[0].name, entries[1].name)
        assertFalse(entries[0].name.contains("Sub", ignoreCase = true))
        assertFalse(entries[1].name.contains("English", ignoreCase = true))
        assertEquals(listOf(1080, 720), entries[0].primary.map { it.resolution })
        assertEquals(listOf(1080, 720), entries[1].primary.map { it.resolution })
    }

    @Test
    fun streamCardOrderDoesNotDependOnServerOrder() {
        val a = listOf(
            stream(0, "MTBB (English) - 1080p BD (HLS)", 1080),
            stream(1, "MTBB (Sub) - 360p BD (HLS)", 360),
            stream(2, "MTBB (Sub) - 1080p BD (HLS)", 1080),
            stream(3, "MTBB (English) - 360p BD (HLS)", 360),
        )
        val b = listOf(a[2], a[3], a[1], a[0])
        fun shape(rows: List<PickerRow>) = PickerListBuilder.group(rows)
            .map { it as PickerEntry.StreamGroup }
            .map { g -> g.audioKind to g.primary.map { it.ref } }
        assertEquals(shape(a), shape(b))
        assertEquals(AudioKind.SUB, shape(a).first().first)
    }

    @Test
    fun languageParenDetection() {
        assertEquals(AudioKind.DUB, AudioKindDetector.detect("MTBB (English) - 1080p BD (HLS)"))
        assertEquals(AudioKind.SUB, AudioKindDetector.detect("MTBB (Sub) - 1080p BD (HLS)"))
        assertEquals(AudioKind.SUB, AudioKindDetector.detect("Kwik (Japanese) 720p"))
        // English subtitles on a sub release stay Sub.
        assertEquals(AudioKind.SUB, AudioKindDetector.detect("MTBB (Sub/English)"))
        assertNull(AudioKindDetector.detect("English Title 1080p"))
    }

    @Test
    fun differentProvidersNeverMerge() {
        val rows = listOf(
            stream(0, "Vidstream", 1080, provider = "a"),
            stream(1, "Vidstream", 720, provider = "b"),
        )
        assertEquals(2, PickerListBuilder.group(rows).size)
    }

    @Test
    fun unknownResolutionsAreKeptAsSeparateButtonsLast() {
        val rows = listOf(
            stream(0, "Server A", null, transport = "HLS"),
            stream(1, "Server A", 720),
            stream(2, "Server A", null, transport = "MP4"),
        )
        val g = PickerListBuilder.group(rows).single() as PickerEntry.StreamGroup
        assertEquals(listOf("720p", "HLS", "MP4"), g.primary.map { it.label })
        assertTrue(g.alternates.isEmpty())
    }

    @Test
    fun torrentRowsAreNeverMergedAndKeepTheirOrder() {
        val rows = listOf(release(0, "EMBER"), release(1, "EMBER"), release(2, "Judas"))
        val entries = PickerListBuilder.build(rows).entries
        assertEquals(listOf(RowRef(0, 0), RowRef(1, 0), RowRef(2, 0)), entries.flatMap { it.refs })
    }

    // ---- Best match ----------------------------------------------------------

    @Test
    fun rememberedExactWinsAndMovesToTop() {
        val rows = listOf(release(0, "Judas"), release(1, "EMBER", exact = true, family = true))
        val list = PickerListBuilder.build(rows)
        assertEquals(BestPick(RowRef(1, 0), BestReason.REMEMBERED), list.best)
        assertEquals(0, list.bestIndex)
        assertEquals(RowRef(1, 0), list.entries[0].refs.single())
    }

    @Test
    fun singleFamilyMatchIsSameGroup() {
        val rows = listOf(release(0, "Judas"), release(1, "EMBER", family = true))
        assertEquals(BestReason.SAME_GROUP, PickerListBuilder.build(rows).best?.reason)
    }

    @Test
    fun twoEqualFamilyMatchesGiveNoBestButBothGoToTop() {
        val rows = listOf(
            release(0, "Judas"),
            release(1, "EMBER", family = true),
            release(2, "SubsPlease"),
            release(3, "EMBER", family = true),
        )
        val list = PickerListBuilder.build(rows)
        assertNull(list.best)
        assertEquals(listOf(RowRef(1, 0), RowRef(3, 0), RowRef(0, 0), RowRef(2, 0)), list.entries.flatMap { it.refs })
    }

    @Test
    fun extensionPreferredIsTheLastFallback() {
        val rows = listOf(release(0, "Judas"), release(1, "EMBER", preferred = true))
        assertEquals(BestReason.EXTENSION_PREFERRED, PickerListBuilder.build(rows).best?.reason)
    }

    @Test
    fun twoPreferredRowsGiveNoBest() {
        val rows = listOf(release(0, "A", preferred = true), release(1, "B", preferred = true))
        assertNull(PickerListBuilder.build(rows).best)
    }

    @Test
    fun aSingleRowNeverGetsABestMatchMark() {
        assertNull(PickerListBuilder.build(listOf(release(0, "EMBER", exact = true))).best)
    }

    @Test
    fun bestInsideAStreamGroupMarksTheGroup() {
        val rows = listOf(
            stream(0, "Other", 1080),
            stream(1, "MTBB (Sub/English)", 720),
            stream(2, "MTBB (Sub/English)", 1080, preferred = true),
        )
        val list = PickerListBuilder.build(rows)
        assertEquals(0, list.bestIndex)
        assertEquals("MTBB", (list.entries[0] as PickerEntry.StreamGroup).name)
    }

    @Test
    fun rememberedFamilyMissingWhenNoRowMatches() {
        val rows = listOf(release(0, "Judas"), release(1, "SubsPlease"))
        assertTrue(PickerListBuilder.build(rows, hasRememberedFamily = true).rememberedFamilyMissing)
        assertFalse(PickerListBuilder.build(rows, hasRememberedFamily = false).rememberedFamilyMissing)
        val withMatch = rows + release(2, "EMBER", family = true)
        assertFalse(PickerListBuilder.build(withMatch, hasRememberedFamily = true).rememberedFamilyMissing)
    }

    // ---- Audio filter --------------------------------------------------------

    @Test
    fun audioDetection() {
        assertEquals(AudioKind.SUB, AudioKindDetector.detect("MTBB (Sub/English)"))
        assertEquals(AudioKind.DUB, AudioKindDetector.detect("[DUBBED] AnimeGG:720p"))
        assertEquals(AudioKind.DUAL, AudioKindDetector.detect("[EMBER] Frieren [Dual Audio]"))
        assertEquals(AudioKind.DUAL, AudioKindDetector.detect("Multi-Audio Multi-Subs"))
        assertNull(AudioKindDetector.detect("SubsPlease Frieren 1080p"))
    }

    @Test
    fun filterShowsOnlyWithTwoKindsAndUnknownStaysUnderAll() {
        val entries = PickerListBuilder.group(
            listOf(
                release(0, "A", audio = AudioKind.SUB),
                release(1, "B", audio = AudioKind.DUAL),
                release(2, "C", audio = null),
            ),
        )
        assertTrue(AudioKindFilter.shouldShow(entries))
        assertEquals(listOf(AudioKind.SUB, AudioKind.DUAL), AudioKindFilter.kinds(entries))
        assertEquals(3, AudioKindFilter.count(entries, null))
        assertEquals(1, AudioKindFilter.count(entries, AudioKind.SUB))
        assertFalse(AudioKindFilter.shouldShow(entries.take(1)))
    }

    // ---- Small helpers -------------------------------------------------------

    @Test
    fun seederHealth() {
        assertEquals("6 seeds · may be slow", SeederHealth.label(6))
        assertEquals("313 seeds", SeederHealth.label(313))
        assertEquals("10 seeds", SeederHealth.label(10))
        assertEquals(1234, SeederHealth.parse("1,234 seeders"))
        assertNull(SeederHealth.parse(null))
    }

    @Test
    fun tagLineBudget() {
        val plus = { n: Int -> if (n < 10) 20 else 28 }
        assertEquals(3, TagLineBudget.fit(listOf(30, 40, 50), 4, 200, plus))
        // 30+4+40 = 74, +4 +20 = 98 <= 100; 3 tags need 128
        assertEquals(2, TagLineBudget.fit(listOf(30, 40, 50), 4, 100, plus))
        assertEquals(1, TagLineBudget.fit(listOf(90, 40), 4, 60, plus))
        assertEquals(0, TagLineBudget.fit(emptyList(), 4, 60, plus))
    }

    @Test
    fun resolutionPrefersExtensionData() {
        assertEquals(720, ResolutionLabel.resolve(720, "MTBB 1080p"))
        assertEquals(1080, ResolutionLabel.resolve(null, "MTBB 1080p"))
        assertEquals(2160, ResolutionLabel.resolve(0, "Feibanyama 4K HEVC"))
        assertNull(ResolutionLabel.resolve(null, "MTBB"))
        assertEquals("4K", ResolutionLabel.label(2160))
        assertEquals("1080p", ResolutionLabel.label(1080))
    }

    @Test
    fun transportLabel() {
        assertEquals("Torrent", TransportLabel.of("magnet:?xt=urn:btih:abc", null))
        assertEquals("Torrent", TransportLabel.of("https://x/y.torrent?pk=1", "CONTAINER"))
        assertEquals("HLS", TransportLabel.of("https://x/master.m3u8", "M3U8"))
        assertEquals("MP4", TransportLabel.of("https://x/v.mp4", "CONTAINER"))
        assertNull(TransportLabel.of("https://x", null))
    }

    @Test
    fun compactDetailsShowTheBatchFileButNeverTheLink() {
        val text = "[SubsPlease] Sousou no Frieren S2 (01-10) (1080p) [Batch]\n" +
            "Sousou no Frieren S2/[SubsPlease] Sousou no Frieren - 29 (1080p).mkv\n" +
            "👤 241 💾 13.7 GB ⚙️ NyaaSi"
        val file = CompactDetails.fileLine(text)
        assertEquals("Sousou no Frieren S2/[SubsPlease] Sousou no Frieren - 29 (1080p).mkv", file)
        val lines = CompactDetails.lines("SubsPlease", "NyaaSi", file, "[SubsPlease] Sousou no Frieren S2 (01-10) (1080p) [Batch]", "Torrent")
        assertEquals(
            listOf("Release group", "Tracker", "Type", "Plays file", "Release name"),
            lines.map { it.first },
        )
        assertEquals("[SubsPlease] Sousou no Frieren - 29 (1080p).mkv", lines[3].second)
        assertTrue(lines.none { it.second.contains("magnet") || it.second.contains("http") })
        assertNull(CompactDetails.fileLine("Single line only"))
    }

    // ---- Parser regression from the phone (Frieren S2 E1, 2026-10-31) --------

    private val shiniori =
        "4k Frieren: U kresu drogi / Sousou no Frieren (2026) [Sezon 2][4K][HEVC x265 10bit][FLAC] " +
            "[Shiniori-Raws] [ToAlien]"
    private val shinioriFile =
        "[Shiniori-Raws] Sousou no Frieren S2 - 01 (BD 3840x2160 x265 10bit FLAC).mkv"

    @Test
    fun shinioriGroupIsNotTheTrackerWhenTheFileIsOnItsOwnLine() {
        val text = "$shiniori\n$shinioriFile\n👤 6 💾 2.24 GB ⚙️ ExtTo"
        assertEquals("Shiniori-Raws", SourceRowClassifier.parseRelease(text).releaseGroup)
    }

    @Test
    fun shinioriGroupIsFoundWhenTitleAndFileShareOneLine() {
        val text = "$shiniori $shinioriFile\n👤 6 💾 2.24 GB ⚙️ ExtTo"
        assertEquals("Shiniori-Raws", SourceRowClassifier.parseRelease(text).releaseGroup)
    }

    // ---- quality highlight: only the remembered row is filled ----

    private fun filled(list: PickerList): List<RowRef> =
        list.entries.flatMap { it.refs }.filter { list.isRememberedQuality(it) }

    @Test
    fun highestQualityIsNotFilledWithoutMemory() {
        val list = PickerListBuilder.build(listOf(stream(0, "MTBB", 1080), stream(1, "MTBB", 720)))
        assertTrue(filled(list).isEmpty())
    }

    @Test
    fun rememberedLowerQualityIsTheOnlyFilledButton() {
        val rows = listOf(stream(0, "MTBB", 1080), stream(1, "MTBB", 720).copy(rememberedExact = true))
        assertEquals(listOf(RowRef(1, 0)), filled(PickerListBuilder.build(rows)))
    }

    @Test
    fun rememberedFamilyRowIsFilled() {
        val rows = listOf(stream(0, "MTBB", 1080), stream(1, "MTBB", 720).copy(rememberedFamily = true))
        assertEquals(listOf(RowRef(1, 0)), filled(PickerListBuilder.build(rows)))
    }

    @Test
    fun exactBeatsFamilyForTheFilledButton() {
        val rows = listOf(
            stream(0, "MTBB", 1080).copy(rememberedFamily = true),
            stream(1, "MTBB", 720).copy(rememberedExact = true),
        )
        assertEquals(listOf(RowRef(1, 0)), filled(PickerListBuilder.build(rows)))
    }

    @Test
    fun tiesAndExtensionPreferredFillNothing() {
        val tie = listOf(
            stream(0, "MTBB", 1080).copy(rememberedFamily = true),
            stream(1, "MTBB", 720).copy(rememberedFamily = true),
        )
        assertTrue(filled(PickerListBuilder.build(tie)).isEmpty())
        val preferred = listOf(stream(0, "MTBB", 1080), stream(1, "MTBB", 720, preferred = true))
        assertTrue(filled(PickerListBuilder.build(preferred)).isEmpty())
    }
}
