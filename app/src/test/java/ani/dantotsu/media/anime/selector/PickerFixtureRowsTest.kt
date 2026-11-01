package ani.dantotsu.media.anime.selector

import ani.dantotsu.FileUrl
import ani.dantotsu.parsers.VideoServer
import ani.dantotsu.parsers.VideoType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Real Torrentio Anime rows (owner capture 2026-11-01), in the exact
 * `server.name` shape the picker gets:
 * "<tracker> - <quality>\n<torrent name>\n[<pack file name>]\n👤 💾 ⚙\n[<language line>]".
 * Expected values are written by hand from the row text.
 */
class PickerFixtureRowsTest {

    private val rows: List<String> by lazy {
        val res = javaClass.classLoader!!.getResource(FIXTURE)
            ?: throw AssertionError("fixture $FIXTURE missing")
        val list = Json.parseToJsonElement(res.readText()).jsonArray.map { it.jsonPrimitive.content }
        assertEquals("fixture row count", 30, list.size)
        list
    }

    private fun vm(i: Int) = SourceRowSummarizer.build(rows[i], rows[i], VideoType.CONTAINER, MAGNET)
    private fun title(i: Int) = vm(i).cleanSubtitle.orEmpty()
    private fun fields(i: Int) = SourceRowClassifier.parseRelease(rows[i])

    private data class Expect(
        val group: String,
        val censorship: String? = null,
        val revision: String? = null,
        val ov: Boolean = false,
        val service: String? = null,
        val audio: AudioKind? = null,
    )

    private val U = "Uncensored"
    private val C = "Censored"
    private val SUB = AudioKind.SUB
    private val DUAL = AudioKind.DUAL

    private val expected = listOf(
        /* 0 */ Expect("Solisfeeld Subs", censorship = U, audio = SUB),
        /* 1 */ Expect("ToonsHub", censorship = U, service = "ADN", audio = SUB),
        /* 2 */ Expect("ToonsHub", censorship = U, revision = "REPACK", service = "ADN", audio = SUB),
        /* 3 */ Expect("ToonsHub", censorship = U, ov = true, audio = SUB),
        /* 4 */ Expect("ToonsHub", censorship = U, service = "ADN", audio = DUAL),
        /* 5 */ Expect("ToonsHub", censorship = C, service = "Bilibili", audio = SUB),
        /* 6 */ Expect("ToonsHub", censorship = C, service = "Amazon"),
        /* 7 */ Expect("Darkmahou", censorship = U, service = "ADN"),
        /* 8 */ Expect("ToonsHub", service = "Bilibili", audio = SUB),
        /* 9 */ Expect("nnotaclue", revision = "v2", audio = SUB),
        /* 10 */ Expect("nnotaclue", audio = SUB),
        /* 11 */ Expect("Omar", censorship = U, service = "Amazon"),
        /* 12 */ Expect("ToonsHub", censorship = U, ov = true, audio = SUB),
        /* 13 */ Expect("NTb", service = "Netflix", audio = DUAL),
        /* 14 */ Expect("NTb", service = "Netflix", audio = DUAL),
        /* 15 */ Expect("NTb", service = "Netflix"),
        /* 16 */ Expect("NTb", service = "Netflix"),
        /* 17 */ Expect("NTb", service = "Netflix"),
        /* 18 */ Expect("whomst-mega", revision = "v2", audio = DUAL),
        /* 19 */ Expect("NTb", service = "Netflix", audio = DUAL),
        /* 20 */ Expect("whomst-mega", audio = DUAL),
        /* 21 */ Expect("Judas", audio = DUAL),
        /* 22 */ Expect("Trix", service = "Netflix", audio = DUAL),
        /* 23 */ Expect("Ironclad", audio = DUAL),
        /* 24 */ Expect("whomst-mini", revision = "v2", audio = DUAL),
        /* 25 */ Expect("whomst", revision = "v2", audio = DUAL),
        /* 26 */ Expect("Judas", audio = DUAL),
        /* 27 */ Expect("Trix", service = "Netflix", audio = DUAL),
        /* 28 */ Expect("whomst-mini", audio = DUAL),
        /* 29 */ Expect("whomst", audio = DUAL),
    )

    @Test
    fun expectations_cover_every_row() {
        assertEquals(rows.size, expected.size)
    }

    @Test
    fun card_title_is_the_release_group() {
        rows.indices.forEach { i -> assertEquals("row $i header", expected[i].group, vm(i).header) }
    }

    @Test
    fun release_tags_per_row() {
        rows.indices.forEach { i ->
            val f = fields(i)
            val e = expected[i]
            assertEquals("row $i censorship", e.censorship, f.censorship)
            assertEquals("row $i revision", e.revision, f.revision)
            assertEquals("row $i OV", e.ov, f.originalVideo)
            assertEquals("row $i service", e.service, f.sourceService)
            assertEquals("row $i audio kind", e.audio, AudioKindDetector.detect(rows[i]))
        }
    }

    @Test
    fun dubbed_language_line_is_dub() {
        val row = "nekoBT - 1080p\n[Group] Show - 01 [1080p]\n👤 5 💾 1 GB ⚙ nekoBT\nDubbed / 🇬🇧"
        assertEquals(AudioKind.DUB, AudioKindDetector.detect(row))
    }

    @Test
    fun clean_titles_have_no_leftovers() {
        rows.indices.forEach { i ->
            val t = title(i)
            val msg = "row $i title <$t>"
            assertFalse(msg, Regex("""\s{2,}""").containsMatchIn(t))
            assertFalse(msg, Regex("""^[\s\-_.|]|[\s\-_|]$""").containsMatchIn(t))
            assertFalse(msg, Regex("""\[[\s._|\-]*]|\([\s._|\-]*\)|\{[\s._|\-]*}""").containsMatchIn(t))
            assertFalse(msg, Regex("""[\[({][\s_|\-]|[\s_|\-][\])}]""").containsMatchIn(t))
            assertFalse(msg, Regex("""(?i)\bHDR10P\b""").containsMatchIn(t))
            assertFalse(msg, Regex("""(?i)\.(mkv|mp4)\b""").containsMatchIn(t))
        }
    }

    @Test
    fun clean_title_examples() {
        assertEquals("Seitokai ni mo Ana wa Aru! S01E01 (PT-BR)", title(7))
        assertEquals("Even the Student Council Has Its Holes S01E01 (Seitokai ni mo Ana wa Aru!)", title(2))
        assertEquals("Cyberpunk Edgerunners S02 (Cyberpunk: Edgerunners 2)", title(13))
        assertEquals("Cyberpunk - Edgerunners - S02 | Cyberpunk: Edgerunners", title(23))
        assertEquals("Cyberpunk Edgerunners (Season 2)", title(21))
    }

    @Test
    fun pack_file_name_is_not_in_the_title_but_in_details() {
        // Row 14: batch torrent; the file line names the episode file.
        val file = SourceRowClassifier.fileNameLine(rows[14])
        assertEquals("Cyberpunk.Edgerunners.S02E01.Last.Chance.for.a.Slow.Dance.2160p.NF.WEB-DL.DDP5.1.DV.HEVC-NTb.mkv", file)
        assertFalse(title(14).contains("Last Chance"))
        assertFalse(fields(14).releaseName.contains("Last.Chance"))
        assertEquals(file, CompactDetails.fileLine(rows[14]))
        // Single-file rows (seitokai) have no file line.
        assertNull(SourceRowClassifier.fileNameLine(rows[1]))
    }

    @Test
    fun title_cleanup_is_idempotent() {
        rows.indices.forEach { i ->
            val t = title(i)
            assertEquals("row $i", t, ReleaseTitleNormalizer.normalize(t))
            val f = fields(i)
            val again = SourceRowClassifier.cleanDisplayTitle(
                releaseName = t, releaseGroup = f.releaseGroup, resolution = f.resolution, codec = f.codec,
                bitDepth = f.bitDepth, sourceService = f.sourceService, hdrVariant = f.hdrVariant,
                audioSummary = f.audioSummary, batch = f.batch, audioFormat = f.audioFormat,
                subtitleSummary = f.subtitleSummary, subtitleLanguages = f.subtitleLanguages,
                sourceClass = f.sourceClass,
            )
            assertEquals("row $i cleanup of the clean title", t, again)
        }
    }

    // ---- torrent name + file name ---------------------------------------

    @Test
    fun seitokai_title_and_file_name_merge() {
        // Torrent title says [UNCENSORED]; the file name says [Real-CUGAN] instead.
        val file = "[Solisfeeld Subs] Seitokai ni mo Ana wa Aru! - 01 [2160p][Real-CUGAN][HEVC 10bit AAC][PT-BR][ES][IT].mkv"
        val textOnly = fields(0)
        val merged = SourceRowClassifier.parseRelease(rows[0], file)
        assertEquals("Uncensored", merged.censorship)
        assertEquals("Solisfeeld Subs", merged.releaseGroup)
        assertEquals(textOnly.resolution, merged.resolution)
        assertEquals(textOnly.codec, merged.codec)
        assertEquals(textOnly.releaseName, merged.releaseName)
        assertTrue(merged.subtitleLanguages.containsAll(textOnly.subtitleLanguages))
        val title = SourceRowSummarizer.build(rows[0], rows[0], VideoType.CONTAINER, MAGNET, fileName = file).cleanSubtitle.orEmpty()
        assertEquals(title(0), title)
        assertFalse(title.contains("CUGAN"))
    }

    @Test
    fun file_name_fills_missing_fields_only() {
        val row = "nekoBT - 1080p\n[Grp] Show - 01\n👤 5 💾 1 GB ⚙ nekoBT"
        val merged = SourceRowClassifier.parseRelease(row, "Show.01.1080p.NF.WEB-DL.UNCENSORED.HEVC-Other.mkv")
        assertEquals("Grp", merged.releaseGroup)
        assertEquals("HEVC", merged.codec)
        assertEquals("Netflix", merged.sourceService)
        assertEquals("Uncensored", merged.censorship)
        assertEquals("1080p", merged.resolution)
        assertEquals("[Grp] Show - 01", merged.releaseName)
        // Path segments are dropped.
        assertEquals(merged, SourceRowClassifier.parseRelease(row, "Season 1/Show.01.1080p.NF.WEB-DL.UNCENSORED.HEVC-Other.mkv"))
    }

    @Test
    fun no_or_same_file_name_is_text_only() {
        rows.indices.forEach { i ->
            val f = fields(i)
            assertEquals("row $i null", f, SourceRowClassifier.parseRelease(rows[i], null))
            assertEquals("row $i blank", f, SourceRowClassifier.parseRelease(rows[i], "  "))
        }
        val row = "nekoBT - 1080p\n[Grp] Show - 01 [1080p]\n👤 5 💾 1 GB ⚙ nekoBT"
        assertEquals(SourceRowClassifier.parseRelease(row), SourceRowClassifier.parseRelease(row, "[Grp] Show - 01 [1080p].mkv"))
    }

    @Test
    fun internal_data_needs_editotsu_1() {
        assertNull(EditotsuInternalData.parse(null))
        assertNull(EditotsuInternalData.parse(""))
        assertNull(EditotsuInternalData.parse("drm:widevine"))
        assertNull(EditotsuInternalData.parse("{not json"))
        assertNull(EditotsuInternalData.parse("""{"fileName":"a.mkv"}"""))
        assertNull(EditotsuInternalData.parse("""{"editotsu":2,"fileName":"a.mkv"}"""))
        assertNull(EditotsuInternalData.parse("""{"editotsu":"1","fileName":"a.mkv"}"""))
        assertEquals(
            EditotsuInternalData(torrentName = null, fileName = null),
            EditotsuInternalData.parse("""{"editotsu":1,"fileName":5,"torrentName":[]}"""),
        )
        assertEquals(
            EditotsuInternalData(torrentName = "T", fileName = "a.mkv"),
            EditotsuInternalData.parse("""{"editotsu":1,"infoHash":"ab","torrentName":"T","fileName":"a.mkv","x":{}}"""),
        )
    }

    // ---- remembered choice: same group, same quality ----------------------

    /** Row [i] of episode [ep]: same text, a different torrent per episode. */
    private fun server(i: Int, ep: Int = 1) = VideoServer(
        name = rows[i],
        embed = FileUrl("magnet:?xt=urn:btih:%040x".format(ep * 1000 + i)),
        extraData = mapOf(StableCandidateBuilder.PROVIDER_EXTRA_KEY to PROVIDER),
    )

    private fun candidate(i: Int, ep: Int = 1) = StableCandidateBuilder.familyCandidate(server(i, ep))
        ?: throw AssertionError("row $i has no family candidate")

    private fun storedFrom(i: Int) = candidate(i).let { FamilyPayload(it.providerPkg, it.groupKey, it.soft) }

    /** Picked row [stored] in episode 1; episode 2 offers the same 30 releases. */
    private fun nextEpisode(stored: Int): FamilyAutoResult =
        FamilyAutoSelector.select(storedFrom(stored), rows.indices.map { candidate(it, ep = 2) })

    private fun ep2(i: Int) = FamilyAutoResult.Chosen(candidate(i, ep = 2).exactKey)

    @Test
    fun remember_group_matches_card_group_on_every_row() {
        rows.indices.forEach { i ->
            val meta = StableReleaseMetadataExtractor.extract(rows[i])
            assertEquals(
                "row $i",
                StableGroupCanonicalizer.canonicalKey(fields(i).releaseGroup),
                StableGroupCanonicalizer.canonicalKey(meta.group),
            )
        }
    }

    @Test
    fun remember_reads_torrentio_tracker() {
        assertEquals("nekobt", candidate(1).soft["trackerKey"])
        assertEquals("nyaasi", candidate(2).soft["trackerKey"])
    }

    @Test
    fun remember_picks_the_same_release_next_episode() {
        // ToonsHub has 7 releases per episode (trackers, 1080p/720p, sub/dual,
        // AAC/DDP, H.264/H.265). Each of these picks comes back as itself.
        for (i in listOf(0, 1, 4, 6, 7, 8, 11, 12, 21, 22, 23, 26, 27)) {
            assertEquals("row $i", ep2(i), nextEpisode(i))
        }
    }

    @Test
    fun remember_suffix_group_ntb() {
        assertEquals("ntb", candidate(14).groupKey)
        // 4K DV HDR (13) and 4K DV (14) on NyaaSi are different releases.
        assertEquals(ep2(13), nextEpisode(13))
        assertEquals(ep2(14), nextEpisode(14))
    }

    @Test
    fun remember_same_quality_beats_same_tracker() {
        // Stored: ToonsHub 720p on NyaaSi. Next episode has no NyaaSi 720p, only
        // a nekoBT 720p and NyaaSi 1080p rows: the 720p row wins.
        val stored = storedFrom(12)
        val cands = listOf(1, 2, 3, 5).map { candidate(it, ep = 2) } +
            FamilyCandidate("nekobt-720", PROVIDER, stored.groupKey, stored.soft + ("trackerKey" to "nekobt"))
        assertEquals(FamilyAutoResult.Chosen("nekobt-720"), FamilyAutoSelector.select(stored, cands))
    }

    @Test
    fun remember_true_ties_open_the_picker() {
        // Rows 2 and 3 differ only in REPACK/OV words, which are not ranked:
        // the picker opens instead of guessing.
        assertEquals(FamilyAutoResult.Ambiguous, nextEpisode(2))
    }

    @Test
    fun remember_4k_equals_2160p() {
        val stored = FamilyPayload(PROVIDER, "ntb", mapOf("resolution" to "4k"))
        val cands = listOf(
            FamilyCandidate("a", PROVIDER, "ntb", mapOf("resolution" to "1080p")),
            FamilyCandidate("b", PROVIDER, "ntb", mapOf("resolution" to "2160p")),
        )
        assertEquals(FamilyAutoResult.Chosen("b"), FamilyAutoSelector.select(stored, cands))
    }

    @Test
    fun remember_missing_group_selects_nothing() {
        val stored = FamilyPayload(PROVIDER, "subsplease", mapOf("resolution" to "1080p"))
        assertEquals(FamilyAutoResult.None, FamilyAutoSelector.select(stored, rows.indices.map { candidate(it) }))
    }

    private companion object {
        const val FIXTURE = "picker/torrentio_rows_2026-11-01.json"
        const val MAGNET = "magnet:?xt=urn:btih:00"
        const val PROVIDER = "eu.kanade.tachiyomi.animeextension.all.torrentioanime"
    }
}
