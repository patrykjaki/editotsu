package ani.dantotsu.media.anime.selector

import ani.dantotsu.parsers.VideoType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Rows exactly as the Editotsu Torrents extension sends them (flat hoster list, so the display
 * text is the video title) with their internalData. Seeds/size/tracker/resolution must come from
 * the data; tags from the torrent/file names; nothing from the 👤/💾/⚙️ display line.
 */
class EditotsuInternalDataTest {

    private val singleText = "[DameDesuYo] ReZero kara Hajimeru Isekai Seikatsu - 01 (1280x720 10bit AAC) [F9A34879].mkv\n" +
        "👤 15 💾 350.12 MB ⚙️ Nyaa"
    private val singleData = EditotsuInternalData.parse(
        """{"editotsu":1,"infoHash":"e971d09c2578aefd982b7d4059e8bdd1fc3d2cf4","fileIdx":0,""" +
            """"torrentName":"[DameDesuYo] ReZero kara Hajimeru Isekai Seikatsu - 01 (1280x720 10bit AAC) [F9A34879].mkv",""" +
            """"sizeBytes":367127347,"seeders":15,"leechers":2,"tracker":"nyaa","anidbEid":176270,"numFiles":1,"source":"animetosho"}""",
    )!!.copy(resolution = 720)

    private val packText = "[EMBER] Frieren: Beyond Journey`s End (2023) (Season 1) [BDRip] [1080p Dual Audio HEVC 10 bits DDP] (Sousou no Frieren) (Batch)\n" +
        "[EMBER] Frieren - S01E05 - Phantoms of the Dead.mkv\n" +
        "👤 428 💾 1.39 GB ⚙️ Nyaa"
    private val packData = EditotsuInternalData.parse(
        """{"editotsu":1,"infoHash":"aa","fileIdx":4,""" +
            """"torrentName":"[EMBER] Frieren: Beyond Journey`s End (2023) (Season 1) [BDRip] [1080p Dual Audio HEVC 10 bits DDP] (Sousou no Frieren) (Batch)",""" +
            """"fileName":"[EMBER] Frieren (Season 1)/[EMBER] Frieren - S01E05 - Phantoms of the Dead.mkv",""" +
            """"sizeBytes":1492519321,"seeders":428,"leechers":7,"tracker":"nyaa","numFiles":28}""",
    )!!.copy(resolution = 1080)

    @Test
    fun parsesTheWholeContract() {
        val d = singleData
        assertEquals("e971d09c2578aefd982b7d4059e8bdd1fc3d2cf4", d.infoHash)
        assertEquals(0, d.fileIdx)
        assertEquals(367127347L, d.sizeBytes)
        assertEquals(15, d.seeders)
        assertEquals(2, d.leechers)
        assertEquals("nyaa", d.tracker)
        assertEquals(176270, d.anidbEid)
        assertEquals(1, d.numFiles)
        assertFalse(d.isPack)
        assertTrue(packData.isPack)
        assertEquals("350.1 MB", d.sizeText())
        assertEquals("1.39 GB", packData.sizeText())
        assertEquals("Nyaa", d.trackerLabel())
        assertEquals("720p", d.resolutionText())
    }

    @Test
    fun trackerLabelIsTheSourceIdCapitalised() {
        fun label(id: String?) = EditotsuInternalData(tracker = id).trackerLabel()
        assertEquals("Example", label("example"))
        assertEquals("ExampleTracker", label("exampleTracker"))
        assertEquals("Other", label("unknown"))
        assertEquals("Other", label("Unknown"))
        assertNull(label("  "))
        assertNull(label(null))
    }

    @Test
    fun wrongTypesFallBackPerField() {
        val d = EditotsuInternalData.parse("""{"editotsu":1,"seeders":"12","sizeBytes":-5,"tracker":3,"sizeApprox":true,"torrentName":"T"}""")!!
        assertNull(d.seeders)
        assertNull(d.sizeBytes)
        assertNull(d.tracker)
        assertTrue(d.sizeApprox)
        assertEquals("~1.00 GB", d.copy(sizeBytes = 1L shl 30).sizeText())
    }

    @Test
    fun numbersComeFromTheData() {
        // display line says something else; the data wins
        val text = singleText.replace("👤 15 💾 350.12 MB ⚙️ Nyaa", "👤 999 💾 9 GB ⚙️ Whatever")
        val f = SourceRowClassifier.parseRelease(text, data = singleData)
        assertEquals(15, f.seeders)
        assertEquals("350.1 MB", f.sizeText)
        assertEquals("Nyaa", f.tracker)
        assertEquals("Nyaa", f.indexer)
        assertEquals("720p", f.resolution)
        assertEquals(singleData.torrentName, f.releaseName)
        assertFalse(f.batch)
    }

    @Test
    fun tagsComeFromTheNames() {
        val f = SourceRowClassifier.parseRelease(packText, data = packData)
        assertEquals("EMBER", f.releaseGroup)
        assertEquals("1080p", f.resolution)
        assertEquals(428, f.seeders)
        assertEquals("1.39 GB", f.sizeText)
        assertTrue(f.batch)
        assertEquals("HEVC", f.codec)
        // same tags as parsing the two names directly (the display text adds nothing)
        val byNames = SourceRowClassifier.parseRelease(packData.torrentName!!, packData.fileName)
        assertEquals(byNames.releaseGroup, f.releaseGroup)
        assertEquals(byNames.audioFlags, f.audioFlags)
        assertEquals(byNames.subtitleFlags, f.subtitleFlags)
    }

    @Test
    fun releaseWithoutTheEmojiLine() {
        // a title that is only the release name is still a release when the data says so
        val bare = singleData.torrentName!!
        assertEquals(SourcePresentationKind.RELEASE, SourceRowClassifier.classifyPresentation(bare, singleData))
        val vm = SourceRowSummarizer.build(bare, bare, VideoType.CONTAINER, MAGNET, data = singleData)
        assertEquals(SourcePresentationKind.RELEASE, vm.presentation)
        assertEquals("DameDesuYo", vm.header)
        assertTrue(vm.meta.any { it.text == "15 seeders" })
        assertTrue(vm.meta.any { it.text == "350.1 MB" })
    }

    @Test
    fun headerIsNeverTheTracker() {
        // scene name without a [group]: the header must not become "Nyaa"
        val name = "Re ZERO Starting Life in Another World S01E01 1080p CR WEB-DL DUAL AAC2.0 H 264-VARYG"
        val data = EditotsuInternalData(torrentName = name, seeders = 4, sizeBytes = 1L shl 30, tracker = "nyaa", numFiles = 1)
        val vm = SourceRowSummarizer.build(name, name + "\n👤 4 💾 1.00 GB ⚙️ Nyaa", VideoType.CONTAINER, MAGNET, data = data)
        assertTrue(vm.header != "Nyaa")
    }

    @Test
    fun sortFacetsUseExactBytes() {
        val f = SourceRowClassifier.parseRelease(packText, data = packData)
        assertEquals(1492519321L, RowFacets.of(f, 1080, packData.sizeBytes).sizeBytes)
        // rounded nyaa size ("~" prefix) still sorts by the data's bytes
        val approx = packData.copy(sizeApprox = true)
        assertEquals(1492519321L, RowFacets.of(SourceRowClassifier.parseRelease(packText, data = approx), 1080, approx.sizeBytes).sizeBytes)
        assertEquals(428, RowFacets.of(f, 1080, packData.sizeBytes).seeders)
        assertEquals("EMBER", RowFacets.of(f, 1080, packData.sizeBytes).group)
    }

    private val blackRabbitName = "[BlackRabbit] Frieren - Beyond Journey's End (2023) - S01 v2 [Bluray-1080p][Opus 2.0][Dual Audio][AV1]"
    private val blackRabbit = EditotsuInternalData.parse(
        """{"editotsu":1,"infoHash":"05bd3a45348e1338117e89cf1e25fd76b7a30de3","fileIdx":4,""" +
            """"torrentName":"$blackRabbitName",""" +
            """"fileName":"Frieren (2023)/Frieren - Beyond Journey's End (2023) - S01E05 - Phantoms of the Dead [Bluray-1080p][Opus 2.0][AV1]-BlackRabbit.mkv",""" +
            """"sizeBytes":336967292,"seeders":42,"leechers":5,"tracker":"nekobt","numFiles":28,""" +
            """"tags":{"from":"name","group":"BlackRabbit","source":"BD","revision":"v2"},""" +
            """"media":{"from":"mediainfo","codec":"AV1","bitDepth":10,"width":1920,"height":1080,""" +
            """"audio":[{"lang":"ja","codec":"Opus","channels":2},{"lang":"en","codec":"Opus","channels":2}],""" +
            """"subtitles":[{"lang":"en","format":"ASS","title":"Full Subtitles [9volt]","forced":false},""" +
            """{"lang":"en","format":"ASS","title":"Signs & Songs [9volt]","forced":true}]}}""",
    )!!.copy(resolution = 1080)

    @Test
    fun chipsFromMediaInfo() {
        val f = SourceRowClassifier.parseRelease(blackRabbitName, data = blackRabbit)
        assertEquals("BlackRabbit", f.releaseGroup)
        assertEquals("1080p", f.resolution)
        assertEquals("AV1", f.codec)
        assertEquals("10-bit", f.bitDepth)
        assertNull(f.hdrVariant)
        assertFalse(f.hdr)
        assertEquals(listOf("JA", "EN"), f.audioLanguages)
        assertEquals("Dual", f.audioSummary)
        assertEquals("Opus", f.audioFormat)
        assertEquals(listOf("EN"), f.subtitleLanguages) // forced signs track doesn't add a language
        assertEquals("EN", f.subtitleSummary)
        assertEquals("BD", f.sourceClass)
        assertEquals("v2", f.revision)
        assertTrue(f.batch)
        assertEquals(AudioKind.DUAL, blackRabbit.audioKind())
        val vm = SourceRowSummarizer.build(blackRabbitName, blackRabbitName, VideoType.CONTAINER, MAGNET, data = blackRabbit)
        assertEquals("BlackRabbit", vm.header)
        val pills = vm.pills.map { it.text }
        assertTrue(pills.toString(), pills.contains("AV1"))
        assertTrue(pills.toString(), pills.contains("Dual"))
        assertTrue(pills.toString(), pills.any { it.contains("🇬🇧") || it == "EN" })
    }

    @Test
    fun mediaInfoWinsOverTheName() {
        // the name says HDR and HEVC; the file is SDR AVC with Japanese audio only
        val name = "[Grp] Show - 01 [1080p HEVC HDR Dual Audio]"
        val d = EditotsuInternalData.parse(
            """{"editotsu":1,"torrentName":"$name","media":{"from":"mediainfo","codec":"AVC","bitDepth":8,""" +
                """"audio":[{"lang":"ja","codec":"AAC","channels":2}],"subtitles":[{"lang":"en","forced":false},{"lang":"pt-BR","forced":false},{"lang":"es-419","forced":false}]}}""",
        )!!
        val f = SourceRowClassifier.parseRelease(name, data = d)
        assertEquals("AVC", f.codec)
        assertNull(f.hdrVariant)
        assertNull(f.audioSummary)
        assertEquals(AudioKind.SUB, d.audioKind())
        assertEquals(listOf("EN", "PT-BR", "ES-LA"), f.subtitleLanguages)
        assertEquals("AAC", f.audioFormat)
        // stored as AVC, shown as H.264
        val vm = SourceRowSummarizer.build(name, name, VideoType.CONTAINER, MAGNET, data = d)
        assertTrue(vm.pills.map { it.text }.toString(), vm.pills.any { it.text == "H.264" && it.kind == SourcePillKind.CODEC })
        assertEquals("H.264", CodecLabel.of("AVC"))
        assertEquals("HEVC", CodecLabel.of("HEVC"))
    }

    @Test
    fun mixedAudioCodecs() {
        val name = "[Grp] Show - 01 [BD 1080p]"
        val d = EditotsuInternalData.parse(
            """{"editotsu":1,"torrentName":"$name","media":{"from":"mediainfo","codec":"HEVC",""" +
                """"audio":[{"lang":"ja","codec":"FLAC","channels":2},{"lang":"en","codec":"AAC","channels":6},{"lang":"ja","codec":"FLAC","channels":2}],"subtitles":[]}}""",
        )!!
        val f = SourceRowClassifier.parseRelease(name, data = d)
        assertEquals("FLAC + AAC 5.1", f.audioFormat)
        assertEquals("Dual", f.audioSummary)
    }

    @Test
    fun nameMediaOnlyFillsGaps() {
        val name = "[Erai-raws] Show - 01 [1080p CR WEBRip HEVC AAC][MultiSub]"
        val d = EditotsuInternalData.parse(
            """{"editotsu":1,"torrentName":"$name","tracker":"nyaa","tags":{"from":"name","group":"Erai-raws","source":"WEBRip","service":"CR"},""" +
                """"media":{"from":"name","codec":"HEVC","multiSubs":true,"audioKind":"dub"}}""",
        )!!
        val f = SourceRowClassifier.parseRelease(name, data = d)
        assertEquals("Erai-raws", f.releaseGroup)
        assertEquals("HEVC", f.codec)
        assertEquals("Crunchyroll", f.sourceService)
        assertEquals("WEBRip", f.sourceClass)
        assertEquals(AudioKind.DUB, d.audioKind())
        assertEquals("Dub", f.audioSummary)
    }

    @Test
    fun rowsWithoutDataKeepTheTextParse() {
        assertEquals(SourceRowClassifier.parseRelease(packText), SourceRowClassifier.parseRelease(packText, data = null))
    }

    private companion object {
        const val MAGNET = "magnet:?xt=urn:btih:e971d09c2578aefd982b7d4059e8bdd1fc3d2cf4&index=0"
    }
}
