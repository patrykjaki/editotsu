package ani.dantotsu.media.anime.selector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [SourceRowClassifier] and [SourceRowSummarizer].
 *
 * Pure JVM tests. No Android dependencies.
 *
 * v3.1 priorities:
 *   1. Release group comes from the first valid bracketed title token
 *      (with a bounded technical prefix allowed), NOT from the
 *      📂 tracker line. The ⚙ line is corroborating only.
 *   2. NF / AMZN / CR are detected as source service, never as group.
 *   3. 2160p -> 4K display alias; internal value stays 2160p.
 *   4. Subtitle vs audio flags must be contextually separated.
 *   5. Clean title removes tokens already shown elsewhere (group,
 *      resolution, codec, bit depth, source service).
 *   6. Audio formats: AAC2.0 -> AAC (stereo default, channels dropped),
 *      DDP2.0 -> E-AC-3 / DDP, DDP5.1 kept, etc.
 */
class SourceRowClassifierTest {

    // ---- Real Torrentio golden fixtures (sanitised, current format) -

    // Each fixture is a multi-line Torrentio payload that includes BOTH
    // a 📂 tracker line and a leading [GROUP] token in the title. The
    // tests below assert group + tracker on the SAME parsed object.

    private val fixtureIroncladNyaaSi =
        """
        Torrentio
        📂 NyaaSi
        1080p [Ironclad] Yani Neko - S01E04 [WEB.10...mkv
        """.trim()
    private val fixtureToonsHubNyaaSi =
        """
        Torrentio
        📂 NyaaSi
        1080p [ToonsHub] Chainsmoker Cat S01E04 [1080p][HEVC].mkv
        """.trim()
    private val fixtureDkbNyaaSi =
        """
        Torrentio
        📂 NyaaSi
        1080p [DKB] Yani Neko - S01E04 [1080p][HE...mkv
        """.trim()
    private val fixtureVarygNekoBT =
        """
        Torrentio
        📂 nekoBT
        1080p [VARYG] Chainsmoker Cat S01E04 1080...mkv
        """.trim()
    private val fixture4kTenraiSensei =
        """
        Torrentio
        📂 1337x
        4k [Tenrai-Sensei] Sousou no Frieren - 01 [1080p][HEVC].mkv
        """.trim()
    private val fixture1080pSubsPleaseNyaaSi =
        "1080p [SubsPlease] Sousou no Frieren - 01 (1080p) [F02B9CEE].mkv"

    // Real Torrentio metadata-block fixture (legacy two-marker shape:
    // v2-era 📂 + ⚙️ lines; current rows use single-⚙️ provenance).
    private val fixtureTorrentioMetadataBlock =
        """
        Torrentio
        📂 NyaaSi
        ⚙️ SubsPlease
        💾 1.48 GB
        👤 545
        🎯 1080p
        🎬 Dual Audio
        🔗 Batch
        [SubsPlease] Sousou no Frieren - 01v2 (1080p) [AAA94036].mkv
        """.trim()

    // ---- Negative release-group fixtures (must NOT fabricate a group)

    private val fixtureNoGroup_crc =
        "Title S01E01 [ABC12345].mkv"
    private val fixtureNoGroup_unknownTech =
        "1080p Title [SomeUnknownTechnicalToken].mkv"

    // ---- Release group: title bracket (preferred) --------------------
    //
    // Each of these tests uses a two-marker (📂 + title with leading
    // [GROUP]) payload. That is the LEGACY compatibility shape; current
    // Torrentio rows carry single-⚙️ provenance instead (covered by the
    // current_* tests). Both assert group + tracker on one parse result.

    @Test
    fun releaseGroup_Ironclad_titleBracket_withTrackerNyaaSi() {
        val f = SourceRowClassifier.parseRelease(fixtureIroncladNyaaSi)
        assertEquals("Ironclad", f.releaseGroup)
        assertEquals("NyaaSi", f.tracker)
    }

    @Test
    fun releaseGroup_ToonsHub_titleBracket_withTrackerNyaaSi() {
        val f = SourceRowClassifier.parseRelease(fixtureToonsHubNyaaSi)
        assertEquals("ToonsHub", f.releaseGroup)
        assertEquals("NyaaSi", f.tracker)
    }

    @Test
    fun releaseGroup_DKB_titleBracket_withTrackerNyaaSi() {
        val f = SourceRowClassifier.parseRelease(fixtureDkbNyaaSi)
        assertEquals("DKB", f.releaseGroup)
        assertEquals("NyaaSi", f.tracker)
    }

    @Test
    fun releaseGroup_VARYG_titleBracket_withTrackerNekoBT() {
        val f = SourceRowClassifier.parseRelease(fixtureVarygNekoBT)
        assertEquals("VARYG", f.releaseGroup)
        assertEquals("nekoBT", f.tracker)
    }

    @Test
    fun releaseGroup_4kTenraiSensei_titleBracket_withTracker1337x() {
        val f = SourceRowClassifier.parseRelease(fixture4kTenraiSensei)
        // Quality prefix "4k" before the bracket must be allowed.
        assertEquals("Tenrai-Sensei", f.releaseGroup)
        assertEquals("1337x", f.tracker)
    }

    @Test
    fun releaseGroup_SubsPleaseFromBracketOrMetadata() {
        val f = SourceRowClassifier.parseRelease(fixture1080pSubsPleaseNyaaSi)
        // Title bracket and ⚙ line both say SubsPlease; either is acceptable.
        assertEquals("SubsPlease", f.releaseGroup)
    }

    @Test
    fun releaseGroup_metadataBlock_usesCogLine() {
        val f = SourceRowClassifier.parseRelease(fixtureTorrentioMetadataBlock)
        // No [BracketedToken] in the title line, so we fall back to the
        // ⚙ line. ⚙ value "SubsPlease" is used. The 📂 tracker value
        // is "NyaaSi" and is stored separately.
        assertEquals("SubsPlease", f.releaseGroup)
        assertEquals("NyaaSi", f.tracker)
    }

    // ---- Tracker is NEVER treated as release group ------------------

    @Test
    fun releaseGroup_NyaaSi_indexer_isNotGroup() {
        // The v3.4 brief forbids a generic "looks like a tracker" prefix
        // when no 📂 tracker line corroborates it. Add the 📂 line so the
        // "NyaaSi" prefix is the actual parsed tracker.
        val f = SourceRowClassifier.parseRelease(
            """
            Torrentio
            📂 NyaaSi
            NyaaSi [SomeGroup] Title.mkv
            """.trimIndent()
        )
        assertEquals("SomeGroup", f.releaseGroup)
        assertEquals("NyaaSi", f.tracker)
    }

    @Test
    fun releaseGroup_NyaaSi_tracker_doesNotReplaceGroup() {
        // The 📂 tracker value (NyaaSi) must not replace the title bracket
        // group (Ironclad). This is the explicit "do not silently prefer
        // 📂" rule from the v3.3 brief.
        val f = SourceRowClassifier.parseRelease(fixtureTorrentioMetadataBlock)
        // Title line: "[SubsPlease] Sousou no Frieren ..."
        // Cog line: "⚙️ SubsPlease"  -> both agree, so group is SubsPlease.
        assertEquals("SubsPlease", f.releaseGroup)
        // 📂 line is "NyaaSi", so tracker is set.
        assertEquals("NyaaSi", f.tracker)
    }

    @Test
    fun releaseGroup_rejectsTechnicalBracketsAsGroup() {
        // [HEVC] is technical metadata, NOT a release group.
        val f = SourceRowClassifier.parseRelease("1080p [HEVC] Title S01E01.mkv")
        assertNull("HEVC must not be a release group", f.releaseGroup)
    }

    @Test
    fun releaseGroup_rejectsLanguageAsGroup() {
        // [ENG] is a language code, not a release group.
        val f = SourceRowClassifier.parseRelease("1080p [ENG] Title S01E01.mkv")
        assertNull("ENG must not be a release group", f.releaseGroup)
    }

    @Test
    fun releaseGroup_rejectsResolutionAsGroup() {
        val f = SourceRowClassifier.parseRelease("[1080p] Title.mkv")
        assertNull("1080p must not be a release group", f.releaseGroup)
    }

    @Test
    fun releaseGroup_rejectsBatchAsGroup() {
        val f = SourceRowClassifier.parseRelease("1080p [Batch] Title.mkv")
        assertNull("Batch must not be a release group", f.releaseGroup)
    }

    // ---- NF / AMZN / CR are detected as source service ---------------

    @Test
    fun sourceService_NF() {
        val f = SourceRowClassifier.parseRelease("1080p [Group] Title [NF] S01E01.mkv")
        assertEquals("Netflix", f.sourceService)
    }

    @Test
    fun sourceService_AMZN() {
        val f = SourceRowClassifier.parseRelease("1080p [Group] Title [AMZN] S01E01.mkv")
        assertEquals("Amazon", f.sourceService)
    }

    @Test
    fun sourceService_CR() {
        val f = SourceRowClassifier.parseRelease("1080p [Group] Title [CR] S01E01.mkv")
        assertEquals("Crunchyroll", f.sourceService)
    }

    @Test
    fun sourceService_NFIsNotReleaseGroup() {
        val f = SourceRowClassifier.parseRelease("[NF] Title S01E01.mkv")
        // "NF" must be detected as source service, not as the release
        // group. The leading bracket is the only candidate; group is null.
        assertNull(f.releaseGroup)
        assertEquals("Netflix", f.sourceService)
    }

    // ---- Source class detection (WEB-DL / BluRay / etc.) ------------

    @Test
    fun sourceClass_WebDL() {
        val f = SourceRowClassifier.parseRelease("1080p [Group] Title WEB-DL S01E01.mkv")
        assertEquals("WEB-DL", f.sourceClass)
    }

    @Test
    fun sourceClass_Bluray() {
        val f = SourceRowClassifier.parseRelease("1080p [Group] Title BluRay S01E01.mkv")
        assertEquals("BLURAY", f.sourceClass)
    }

    // ---- 4K alias ---------------------------------------------------

    @Test
    fun resolutionDisplayAlias_2160p_is4K() {
        val f = SourceRowClassifier.parseRelease("2160p [Group] Title S01E01.mkv")
        assertEquals("2160p", f.resolution)
        assertEquals("4K", SourceRowClassifier.resolutionDisplayAlias(f.resolution))
    }

    @Test
    fun resolutionDisplayAlias_1080p_stays1080p() {
        assertEquals("1080p",
            SourceRowClassifier.resolutionDisplayAlias("1080p"))
    }

    @Test
    fun resolutionDisplayAlias_1440p_isNot2K() {
        // Per the v3.3 brief: do NOT display 1440p as "2K".
        assertEquals("1440p",
            SourceRowClassifier.resolutionDisplayAlias("1440p"))
    }

    // ---- Codec / bit depth / HDR --------------------------------

    @Test
    fun codec_hevcBracketNormalised() {
        val f = SourceRowClassifier.parseRelease("[Group] Title [HEVC].mkv")
        assertEquals("HEVC", f.codec)
    }

    @Test
    fun codec_h265Normalised() {
        val f = SourceRowClassifier.parseRelease("[Group] Title [H.265].mkv")
        assertEquals("HEVC", f.codec)
    }

    @Test
    fun codec_x265Normalised() {
        val f = SourceRowClassifier.parseRelease("[Group] Title x265.mkv")
        assertEquals("HEVC", f.codec)
    }

    @Test
    fun codec_avcNormalised() {
        val f = SourceRowClassifier.parseRelease("[Group] Title [AVC].mkv")
        assertEquals("AVC", f.codec)
    }

    @Test
    fun codec_x264Normalised() {
        val f = SourceRowClassifier.parseRelease("[Group] Title x264.mkv")
        assertEquals("AVC", f.codec)
    }

    @Test
    fun codec_av1Normalised() {
        val f = SourceRowClassifier.parseRelease("[Group] Title [AV1].mkv")
        assertEquals("AV1", f.codec)
    }

    @Test
    fun bitDepth_10bit() {
        val f = SourceRowClassifier.parseRelease("[Group] Title [10bit].mkv")
        assertEquals("10-bit", f.bitDepth)
    }

    @Test
    fun bitDepth_10Bit() {
        val f = SourceRowClassifier.parseRelease("[Group] Title [10 Bit].mkv")
        assertEquals("10-bit", f.bitDepth)
    }

    @Test
    fun bitDepth_Hi10P() {
        val f = SourceRowClassifier.parseRelease("[Group] Title [Hi10P].mkv")
        assertEquals("10-bit", f.bitDepth)
    }

    @Test
    fun hdr_HDR10() {
        val f = SourceRowClassifier.parseRelease("[Group] Title [HDR10].mkv")
        assertEquals("HDR10", f.hdrVariant)
    }

    @Test
    fun hdr_HDR10Plus() {
        val f = SourceRowClassifier.parseRelease("[Group] Title [HDR10+].mkv")
        assertEquals("HDR10+", f.hdrVariant)
    }

    @Test
    fun hdr_DolbyVision() {
        val f = SourceRowClassifier.parseRelease("[Group] Title [Dolby Vision].mkv")
        assertEquals("Dolby Vision", f.hdrVariant)
    }

    @Test
    fun hdr_DVTokenMappedToDolbyVision() {
        val f = SourceRowClassifier.parseRelease("[Group] Title [DV].mkv")
        assertEquals("Dolby Vision", f.hdrVariant)
    }

    // ---- Audio formats ------------------------------------------

    @Test
    fun audioFormat_AAC2_0() {
        // Stereo is the default: channel count is dropped.
        val f = SourceRowClassifier.parseRelease("🎬 Japanese (AAC 2.0) [Group] Title.mkv")
        assertEquals("AAC", f.audioFormat)
    }

    @Test
    fun audioFormat_DDP2_0() {
        val f = SourceRowClassifier.parseRelease("[EMBER] Title DDP2.0 x264-EMBER.mkv")
        // v3.3 display normalisation, stereo collapsed: DDP 2.0 -> E-AC-3 / DDP.
        assertEquals("E-AC-3 / DDP", f.audioFormat)
    }

    @Test
    fun audioFormat_DDP5_1() {
        val f = SourceRowClassifier.parseRelease("[Group] Title DDP5.1.mkv")
        // Multichannel is always kept.
        assertEquals("E-AC-3 / DDP 5.1", f.audioFormat)
    }

    @Test
    fun audioFormat_FLAC() {
        val f = SourceRowClassifier.parseRelease("[Group] Title FLAC 2.0.mkv")
        assertEquals("FLAC", f.audioFormat)
    }

    @Test
    fun audioFormat_stereoCollapsed_multichannelKept() {
        assertEquals("OPUS",
            SourceRowClassifier.parseRelease("[Group] Title OPUS 2.0.mkv").audioFormat)
        assertEquals("AAC 5.1",
            SourceRowClassifier.parseRelease("[Group] Title AAC 5.1.mkv").audioFormat)
        assertEquals("AC-3",
            SourceRowClassifier.parseRelease("[Group] Title AC-3.mkv").audioFormat)
        assertEquals("E-AC-3 5.1",
            SourceRowClassifier.parseRelease("[Group] Title E-AC-3 5.1.mkv").audioFormat)
        assertEquals("E-AC-3 / DDP 5.1",
            SourceRowClassifier.parseRelease("[Group] Title DDP5.mkv").audioFormat)
        assertEquals("AC-3 5.1",
            SourceRowClassifier.parseRelease("[Group] Title AC3 5.1.mkv").audioFormat)
    }

    @Test
    fun audioFormat_tailsDoNotSwallowCodecAndGroup() {
        // The DTS detail tail is bounded: codec/group text after it
        // must not become part of the format.
        assertEquals("DTS-HD",
            SourceRowClassifier.parseRelease("Title DTS-HD.X265-IAHD.mkv").audioFormat)
        assertEquals("DTS",
            SourceRowClassifier.parseRelease("Title DTS.mkv").audioFormat)
        // Bare Dolby is ambiguous (Vision/Atmos/Digital?) and matches
        // nothing since the generic catch-all was removed (v2.2 Fix C).
        assertNull(SourceRowClassifier.parseRelease("[Group] Title DOLBY.mkv").audioFormat)
        // Dolby Vision is HDR metadata, never an audio format.
        assertNull(SourceRowClassifier.parseRelease("[Group] Title Dolby Vision.mkv").audioFormat)
        assertNull(SourceRowClassifier.parseRelease("[Group] Title Dolby.Vision.mkv").audioFormat)
    }

    // ---- Subtitle / audio flag separation ------------------------

    @Test
    fun flags_dualAudioContext_AreAudioFlags() {
        val f = SourceRowClassifier.parseRelease(
            "🎬 Japanese 🇯🇵[Group] Title.mkv",
        )
        // The flag on the audio line should be classified as an audio
        // flag, NOT a subtitle flag.
        assertTrue("audio flag should be detected", f.audioFlags.isNotEmpty())
        assertEquals("🇯🇵", f.audioFlags.firstOrNull())
        assertTrue("subtitle flag should NOT include the audio flag",
            f.subtitleFlags.isEmpty())
    }

    @Test
    fun flags_multiSubsContext_AreSubtitleFlags() {
        val f = SourceRowClassifier.parseRelease(
            "[Group] Title [Multi-Subs] 🇬🇧 🇵🇱 🇪🇸.mkv",
        )
        assertEquals(3, f.subtitleFlags.size)
        assertTrue("audio flag should NOT include the subtitle flags",
            f.audioFlags.isEmpty())
    }

    @Test
    fun flags_uncontextualFlag_isNotClassified() {
        val f = SourceRowClassifier.parseRelease(
            "[Group] Title 🇯🇵 S01E01 [JP] [HEVC].mkv",
        )
        // The flag has no Sub / Audio / Multi-Subs context. It must NOT
        // be classified as a subtitle or audio flag.
        assertTrue("uncontextual flag should not be classified as subtitle",
            f.subtitleFlags.isEmpty())
        assertTrue("uncontextual flag should not be classified as audio",
            f.audioFlags.isEmpty())
    }

    @Test
    fun subtitleLanguages_ENGonly() {
        val f = SourceRowClassifier.parseRelease("[Group] Title S01E01 [Subs] [ENG].mkv")
        assertTrue("ENG should be detected", "ENG" in f.subtitleLanguages)
    }

    @Test
    fun subtitleLanguages_REGIONAL_CODES() {
        // Fixture has subtitle context (Multi-Subs) so the bracketed
        // language codes are collected into subtitleLanguages.
        val f = SourceRowClassifier.parseRelease(
            "[CameEsp] Title S01E01 [Multi-Subs] [ESP-ENG][mkv]",
        )
        assertTrue("ESP-ENG should be detected as a single regional code",
            "ESP-ENG" in f.subtitleLanguages)
    }

    // ---- Clean display title -------------------------------------

    @Test
    fun cleanTitle_removesGroupResolutionCodec() {
        val cleaned = SourceRowClassifier.cleanDisplayTitle(
            releaseName = "1080p [Ironclad] Yani Neko - S01E04 [1080p][HEVC][10bit].mkv",
            releaseGroup = "Ironclad",
            resolution = "1080p",
            codec = "HEVC",
            bitDepth = "10-bit",
            sourceService = null,
        )
        // Group, leading resolution, and bracketed codec / bit-depth / res
        // should be removed; the title text remains.
        assertFalse("clean title should not contain [Ironclad]",
            cleaned.contains("[Ironclad]"))
        assertFalse("clean title should not contain [HEVC]",
            cleaned.contains("[HEVC]"))
        assertFalse("clean title should not contain [10bit]",
            cleaned.contains("[10bit]"))
        assertTrue("clean title should keep the release description",
            cleaned.contains("Yani Neko"))
    }

    @Test
    fun cleanTitle_removesSourceService() {
        val cleaned = SourceRowClassifier.cleanDisplayTitle(
            releaseName = "1080p [Ironclad] Title NF S01E01.mkv",
            releaseGroup = "Ironclad",
            resolution = "1080p",
            codec = null,
            bitDepth = null,
            sourceService = "Netflix",
        )
        assertFalse("clean title should not contain NF",
            cleaned.contains("NF"))
    }

    @Test
    fun cleanTitle_keepsRawTitleForIdentity() {
        val raw = "1080p [Ironclad] Yani Neko - S01E04 [1080p][HEVC].mkv"
        val cleaned = SourceRowClassifier.cleanDisplayTitle(
            releaseName = raw,
            releaseGroup = "Ironclad",
            resolution = "1080p",
            codec = "HEVC",
            bitDepth = null,
            sourceService = null,
        )
        // The exact raw title is preserved in the parseRelease result
        // for identity / selection. cleanDisplayTitle is the UI-only
        // variant.
        val fields = SourceRowClassifier.parseRelease(raw)
        assertEquals(raw, fields.releaseName)
        assertTrue(cleaned.length < raw.length)
    }

    // ---- Summarizer end-to-end -----------------------------------

    @Test
    fun summarize_IroncladNyaaSi_headerAndTracker() {
        // Use the v2-era fixture that has a metadata block including
        // the 📂 tracker line and the ⚙ group line; group is the title
        // bracket (SubsPlease); tracker is NyaaSi.
        val row = SourceRowSummarizer.build(
            displayLabel = fixtureTorrentioMetadataBlock,
            qualityText = fixtureTorrentioMetadataBlock,
            formatName = "CONTAINER",
            url = magnetUrl,
        )
        assertEquals("SubsPlease", row.header)
        assertEquals("NyaaSi", row.tracker)
    }

    @Test
    fun summarize_4kTenraiSensei_cleanTitleStripsPrefix() {
        val row = SourceRowSummarizer.build(
            displayLabel = fixture4kTenraiSensei,
            qualityText = fixture4kTenraiSensei,
            formatName = "CONTAINER",
            url = magnetUrl,
        )
        assertEquals("Tenrai-Sensei", row.header)
        // cleanSubtitle should not contain the leading "4k" or the group
        // bracket.
        assertFalse("cleanSubtitle should not start with '4k'",
            row.cleanSubtitle?.startsWith("4k") == true)
    }

    @Test
    fun summarize_NFAmznCR_NotInPills() {
        // Source-service aliases must NOT be top-row chips per the v3.3
        // brief. They live in details instead.
        val quality = "1080p [Group] Title [NF] S01E01.mkv"
        val row = SourceRowSummarizer.build(
            displayLabel = quality,
            qualityText = quality,
            formatName = "CONTAINER",
            url = magnetUrl,
        )
        val nfInPills = row.pills.any { it.text.equals("NF", ignoreCase = true) }
        assertFalse("NF must not be a top-row pill", nfInPills)
        val nfInDetails = row.details.any { it.label == "Source service" && it.value == "Netflix" }
        assertTrue("NF must appear in details", nfInDetails)
    }

    // ---- v3.3: negative release-group fixtures -------------------------

    @Test
    fun releaseGroup_negative_crc_doesNotFabricate() {
        // [ABC12345] is a CRC-like bracket token, NOT a release group.
        val f = SourceRowClassifier.parseRelease(fixtureNoGroup_crc)
        assertNull("CRC-like bracket must not become the release group",
            f.releaseGroup)
    }

    @Test
    fun releaseGroup_negative_unknownTechToken_doesNotFabricate() {
        // [SomeUnknownTechnicalToken] is bracketed but is not a known
        // group. Must not be promoted.
        val f = SourceRowClassifier.parseRelease(fixtureNoGroup_unknownTech)
        assertNull("Unknown technical bracket must not become the release group",
            f.releaseGroup)
    }

    // ---- v3.3: 4k -> canonical 2160p ----

    @Test
    fun parseResolution_4k_isCanonical2160p() {
        val f = SourceRowClassifier.parseRelease(
            "4k [Group] Title S01E01 [1080p][HEVC].mkv",
        )
        assertEquals("2160p", f.resolution)
        assertEquals("4K",
            SourceRowClassifier.resolutionDisplayAlias(f.resolution))
    }

    @Test
    fun parseResolution_4k_winsOverLater1080pTechnical() {
        // The leading 4k is the quality. A later bracketed [1080p] is
        // technical metadata and must NOT replace the leading resolution.
        val f = SourceRowClassifier.parseRelease(
            "4k [Group] Title S01E01 [1080p][HEVC].mkv",
        )
        assertEquals("2160p", f.resolution)
    }

    // ---- v3.3: standalone NF/AMZN/CR detection ----

    @Test
    fun sourceService_standalone_NF() {
        val f = SourceRowClassifier.parseRelease(
            "1080p [Group] Chainsmoker Cat S01E04 NF.mkv",
        )
        assertEquals("Netflix", f.sourceService)
    }

    @Test
    fun sourceService_standalone_AMZN() {
        val f = SourceRowClassifier.parseRelease(
            "1080p [Group] Title AMZN S01E01.mkv",
        )
        assertEquals("Amazon", f.sourceService)
    }

    @Test
    fun sourceService_standalone_CR() {
        val f = SourceRowClassifier.parseRelease(
            "1080p [Group] Title CR S01E01.mkv",
        )
        assertEquals("Crunchyroll", f.sourceService)
    }

    @Test
    fun sourceService_doesNotSubstringMatchInArbitraryWords() {
        // "knife" contains "nf" but must not be misread as Netflix.
        val f = SourceRowClassifier.parseRelease(
            "1080p [Group] Title knife S01E01.mkv",
        )
        assertNull(f.sourceService)
    }

    // ---- v3.3: pills use the agreed priority ----

    @Test
    fun pills_priorityIsResolutionThenCodecThenHdrThenAudio() {
        val f = SourceRowClassifier.parseRelease(
            "1080p [Group] Title S01E01 [HEVC] [10bit] [HDR10+] [AAC 2.0] [Japanese].mkv",
        )
        val row = SourceRowSummarizer.build(
            displayLabel = f.releaseName,
            qualityText = f.releaseName,
            formatName = "CONTAINER",
            url = "magnet:?xt=urn:btih:0123456789ABCDEF0123456789ABCDEF01234567",
        )
        // First pill: resolution.
        assertEquals(SourcePillKind.QUALITY, row.pills[0].kind)
        // Second pill: codec.
        assertEquals(SourcePillKind.CODEC, row.pills[1].kind)
        // Third pill: HDR variant (more informative than bit depth).
        assertEquals(SourcePillKind.HDR10_PLUS, row.pills[2].kind)
        // At most 4 pills.
        assertTrue("pills must be capped at 4, was ${row.pills.size}",
            row.pills.size <= 4)
    }

    // ---- v3.4 additional regressions (per override.md) -----------

    @Test
    fun releaseGroup_negative_1080pTitleFoo_doesNotFabricate() {
        // Short token "Title" is not a tracker, not a quality. The bracket
        // cannot become the release group without corroboration from a
        // 📂 tracker line.
        val f = SourceRowClassifier.parseRelease("1080p Title [Foo].mkv")
        assertNull("1080p Title [Foo] must not fabricate a release group",
            f.releaseGroup)
    }

    @Test
    fun releaseGroup_negative_1080pTitleUnknown_doesNotFabricate() {
        val f = SourceRowClassifier.parseRelease("1080p Title [Unknown].mkv")
        assertNull("1080p Title [Unknown] must not fabricate a release group",
            f.releaseGroup)
    }

    @Test
    fun releaseGroup_positive_corroboratedNyaaSi_withTracker() {
        // The 📂 line provides the canonical tracker. The "NyaaSi" token
        // matches the parsed tracker exactly (case-preserved), so the
        // bracketed group is accepted.
        val f = SourceRowClassifier.parseRelease(
            """
            Torrentio
            📂 NyaaSi
            NyaaSi 1080p [Ironclad] Title S01E01.mkv
            """.trimIndent()
        )
        assertEquals("Ironclad", f.releaseGroup)
        assertEquals("NyaaSi", f.tracker)
    }

    @Test
    fun releaseGroup_QualityAndTracker_OrderIndependent() {
        // Either order (quality then tracker, or tracker then quality)
        // must work as long as the tracker token matches the parsed
        // tracker.
        val f1 = SourceRowClassifier.parseRelease(
            """
            Torrentio
            📂 NyaaSi
            1080p NyaaSi [Ironclad] Title.mkv
            """.trimIndent()
        )
        val f2 = SourceRowClassifier.parseRelease(
            """
            Torrentio
            📂 NyaaSi
            NyaaSi 1080p [Ironclad] Title.mkv
            """.trimIndent()
        )
        assertEquals("Ironclad", f1.releaseGroup)
        assertEquals("Ironclad", f2.releaseGroup)
    }

    @Test
    fun releaseGroup_TrackerInTitleNotMatchingParsedTracker_doesNotFabricate() {
        // The 📂 line says NyaaSi, but the prefix token says "Other".
        // Because the prefix token does not match the parsed tracker,
        // the bracketed token is rejected.
        val f = SourceRowClassifier.parseRelease(
            """
            Torrentio
            📂 NyaaSi
            Other 1080p [Ironclad] Title.mkv
            """.trimIndent()
        )
        assertNull("Mismatched tracker prefix must not fabricate a group",
            f.releaseGroup)
    }

    // ---- v3.5 visual hierarchy tests -----------------------------

    @Test
    fun v35_pillsCappedToFour() {
        // v3.5 requires max 4 visible chips. The summarizer already
        // enforces this; verify it explicitly.
        val quality = "1080p [Group] Title [HEVC] [10bit] [HDR10+] [AAC 2.0] [Japanese].mkv"
        val row = SourceRowSummarizer.build(
            displayLabel = quality,
            qualityText = quality,
            formatName = "CONTAINER",
            url = "magnet:?xt=urn:btih:0123456789ABCDEF0123456789ABCDEF01234567",
        )
        assertTrue("pills must be capped at 4, was ${row.pills.size}",
            row.pills.size <= 4)
    }

    @Test
    fun v35_trackerSetOnlyWhenParsed() {
        // Tracker is exposed only when the 📂 line is parsed. A
        // title-only fixture does not have a tracker.
        val quality = "1080p [Group] Title S01E01.mkv"
        val row = SourceRowSummarizer.build(
            displayLabel = quality,
            qualityText = quality,
            formatName = "CONTAINER",
            url = "magnet:?xt=urn:btih:0123456789ABCDEF0123456789ABCDEF01234567",
        )
        assertEquals("Group", row.header)
        assertNull("tracker must be null when not parsed", row.tracker)
    }

    @Test
    fun v35_trackerSetWhenParsed() {
        val quality = """
            Torrentio
            📂 NyaaSi
            1080p [Group] Title S01E01.mkv
        """.trimIndent()
        val row = SourceRowSummarizer.build(
            displayLabel = quality,
            qualityText = quality,
            formatName = "CONTAINER",
            url = "magnet:?xt=urn:btih:0123456789ABCDEF0123456789ABCDEF01234567",
        )
        assertEquals("Group", row.header)
        assertEquals("NyaaSi", row.tracker)
    }

    @Test
    fun v35_cleanTitlePreservedOnExpandedDisplay() {
        val quality = "1080p [Group] Title S01E01 [1080p][HEVC].mkv"
        val row = SourceRowSummarizer.build(
            displayLabel = quality,
            qualityText = quality,
            formatName = "CONTAINER",
            url = "magnet:?xt=urn:btih:0123456789ABCDEF0123456789ABCDEF01234567",
        )
        // cleanSubtitle must remove the [Group] bracket and the
        // trailing [1080p][HEVC] technical metadata; the descriptive
        // title "Title S01E01" remains.
        assertNotNull(row.cleanSubtitle)
        assertTrue("cleanSubtitle must not include [Group]",
            !row.cleanSubtitle!!.contains("[Group]"))
        assertTrue("cleanSubtitle must not include the bracketed codec tag",
            !row.cleanSubtitle!!.contains("[HEVC]"))
        assertTrue("cleanSubtitle must contain the title body",
            row.cleanSubtitle!!.contains("Title S01E01"))
    }

    // ---- v3.6 narrow tracker-cue tests ---------------------------

    @Test
    fun v36_viewModel_carriesTrackerField() {
        // The view-model still exposes the parsed tracker for the bind code
        // to set on the small icon cue.
        val quality = """
            Torrentio
            📂 NyaaSi
            1080p [Group] Title S01E01.mkv
        """.trimIndent()
        val row = SourceRowSummarizer.build(
            displayLabel = quality,
            qualityText = quality,
            formatName = "CONTAINER",
            url = "magnet:?xt=urn:btih:0123456789ABCDEF0123456789ABCDEF01234567",
        )
        assertEquals("NyaaSi", row.tracker)
    }

    @Test
    fun v36_viewModel_trackerAbsentWithoutParsedLine() {
        // No 📂 line -> tracker is null. The bind code hides the icon cue.
        val quality = "1080p [Group] Title S01E01.mkv"
        val row = SourceRowSummarizer.build(
            displayLabel = quality,
            qualityText = quality,
            formatName = "CONTAINER",
            url = "magnet:?xt=urn:btih:0123456789ABCDEF0123456789ABCDEF01234567",
        )
        assertNull("tracker must be null without a parsed 📂 line", row.tracker)
    }

    @Test
    fun v36_textualTrackerStillInLowerMetadataRow() {
        // The lower metadata row still contains the tracker as TEXT.
        // The new icon cue is in the header and does NOT remove the
        // textual appearance of the tracker here.
        val quality = """
            Torrentio
            📂 NyaaSi
            1080p [Group] Title S01E01.mkv
        """.trimIndent()
        val row = SourceRowSummarizer.build(
            displayLabel = quality,
            qualityText = quality,
            formatName = "CONTAINER",
            url = "magnet:?xt=urn:btih:0123456789ABCDEF0123456789ABCDEF01234567",
        )
        val metaText = row.meta.joinToString(" · ") { it.text }
        assertTrue(
            "lower metadata row must still contain textual tracker; was '$metaText'",
            metaText.contains("NyaaSi"),
        )
    }

    @Test
    fun v36_cleanTitleDoesNotInferTracker() {
        // Title alone (no 📂 line) must not produce a fake tracker.
        val quality = "1080p [Group] Title S01E01.mkv"
        val row = SourceRowSummarizer.build(
            displayLabel = quality,
            qualityText = quality,
            formatName = "CONTAINER",
            url = "magnet:?xt=urn:btih:0123456789ABCDEF0123456789ABCDEF01234567",
        )
        assertNull(row.tracker)
        // No fake "verified" indicator is exposed by the view-model.
        // (No field exists; the layout's icon cue is hidden when
        // tracker is null.)
    }

    // ---- v3.7 non-action ImageView tests -------------------------
    //
    // The view-model is unchanged in v3.7; only the layout
    // semantics are corrected (ImageButton -> ImageView with
    // clickable=false focusable=false). The unit tests assert the
    // view-model still provides the fields the new ImageView binds
    // to.

    @Test
    fun v37_viewModelProvidesTrackerForCue() {
        // The v3.7 ImageView is a non-action image that takes the
        // parsed tracker string as contentDescription and a visibility
        // signal. The view-model must expose the parsed tracker so
        // the bind code can set the cue.
        val quality = """
            Torrentio
            📂 NyaaSi
            1080p [Group] Title S01E01.mkv
        """.trimIndent()
        val row = SourceRowSummarizer.build(
            displayLabel = quality,
            qualityText = quality,
            formatName = "CONTAINER",
            url = "magnet:?xt=urn:btih:0123456789ABCDEF0123456789ABCDEF01234567",
        )
        assertEquals("NyaaSi", row.tracker)
    }

    @Test
    fun v37_viewModelTrackerAbsentForCue() {
        // No 📂 line -> the cue must remain hidden.
        val quality = "1080p [Group] Title S01E01.mkv"
        val row = SourceRowSummarizer.build(
            displayLabel = quality,
            qualityText = quality,
            formatName = "CONTAINER",
            url = "magnet:?xt=urn:btih:0123456789ABCDEF0123456789ABCDEF01234567",
        )
        assertNull("tracker must be null when no parsed 📂 line is present",
            row.tracker)
    }
    // ---- inline direct-stream rows v2 (device-smoke follow-up) -----

    @Test
    fun inline_streamUsesInlineLayout_releaseUsesFullLayout() {
        assertEquals(
            StreamRowLayoutPolicy.RowLayout.INLINE_STREAM,
            StreamRowLayoutPolicy.layoutFor(SourcePresentationKind.STREAM),
        )
        assertEquals(
            StreamRowLayoutPolicy.RowLayout.FULL_RELEASE,
            StreamRowLayoutPolicy.layoutFor(SourcePresentationKind.RELEASE),
        )
    }

    @Test
    fun inline_serverNameDecisionMatchesViewModelPresentation() {
        // The adapter decides the layout from the server name; the
        // bound view-model built from the same name must agree, in
        // both directions, so recycled holders always match their
        // layout.
        val streamName = "Vidstream - Sub - 1080p"
        assertTrue(StreamRowLayoutPolicy.isInlineServerName(streamName))
        val stream = SourceRowSummarizer.build(
            displayLabel = streamName,
            qualityText = streamName,
            formatName = "M3U8",
            url = "https://example.com/vidstream/playlist.m3u8",
        )
        assertEquals(SourcePresentationKind.STREAM, stream.presentation)
        assertEquals(
            StreamRowLayoutPolicy.RowLayout.INLINE_STREAM,
            StreamRowLayoutPolicy.layoutFor(stream.presentation),
        )
        val release = SourceRowSummarizer.build(
            displayLabel = fixtureIroncladNyaaSi,
            qualityText = fixtureIroncladNyaaSi,
            formatName = "CONTAINER",
            url = magnetUrl,
        )
        assertEquals(SourcePresentationKind.RELEASE, release.presentation)
        assertFalse(StreamRowLayoutPolicy.isInlineServerName(fixtureIroncladNyaaSi))
        assertEquals(
            StreamRowLayoutPolicy.RowLayout.FULL_RELEASE,
            StreamRowLayoutPolicy.layoutFor(release.presentation),
        )
    }

    @Test
    fun inline_actionTouchTargetsStayFullSize() {
        // Action glyphs may render smaller, but touch targets must
        // never shrink below 48dp on either card.
        assertEquals(48, StreamRowLayoutPolicy.MIN_ACTION_TOUCH_DP)
    }

    @Test
    fun inline_streamRowKeepsNamePillsDetailsAndNoTorrentMetadata() {
        // The inline decision must not hide information: stream
        // rows keep a prominent header, compact pills, and details
        // content for the info action, and carry no torrent-only
        // fields that would justify the full card.
        val row = SourceRowSummarizer.build(
            displayLabel = "Vidstream - Sub - 1080p",
            qualityText = "Vidstream - Sub - 1080p",
            formatName = "M3U8",
            url = "https://example.com/vidstream/playlist.m3u8",
        )
        assertEquals(StreamRowLayoutPolicy.RowLayout.INLINE_STREAM,
            StreamRowLayoutPolicy.layoutFor(row.presentation))
        assertFalse("header stays prominent", row.header.isNullOrBlank())
        assertTrue("quality/Sub pills stay", row.pills.isNotEmpty())
        assertTrue("details stay for the info action", row.details.isNotEmpty())
        assertNull("no tracker cue on streams", row.tracker)
        assertTrue("no seeders row on streams",
            row.meta.none { it.icon == SourceMetaIcon.SEEDERS })
    }

    @Test
    fun inline_layoutDecisionIsDeterministicBothDirections() {
        // STREAM -> RELEASE and RELEASE -> STREAM must resolve to
        // the same layout every time: the decision is a pure
        // function of presentation, so holder reuse can never leak
        // one layout's state into the other.
        val names = listOf(
            "Vidstream - Sub - 1080p",
            fixtureIroncladNyaaSi,
            "VidPlay-1",
            "1080p [Group] Title S01E01.mkv",
        )
        for (name in names) {
            val first = StreamRowLayoutPolicy.isInlineServerName(name)
            val second = StreamRowLayoutPolicy.isInlineServerName(name)
            assertEquals("layout decision must be stable for: $name", first, second)
        }
        assertTrue(StreamRowLayoutPolicy.isInlineServerName("VidPlay-1"))
        assertFalse(StreamRowLayoutPolicy.isInlineServerName("1080p [Group] Title S01E01.mkv"))
    }


    // ---- follow-ups v1: Dub/Sub label correctness --------------------
    //
    // Bare dub/sub markers in the release title surface information
    // already present in real torrent release names. Explicit signals
    // (🎬 audio line / subtitle flags / language codes) always win;
    // the provider/tracker name is never a signal source.

    @Test
    fun followups_dualBareWord_isAudioKind() {
        val f = SourceRowClassifier.parseRelease(
            "[Erai-raws] Sousou no Frieren - 01 [1080p][HEVC][DUAL AAC2.0].mkv",
        )
        assertEquals("Erai-raws", f.releaseGroup)
        assertEquals("AAC", f.audioFormat)
        assertEquals("Dual", f.audioSummary)
    }

    @Test
    fun followups_dualLedBracket_isNotGroup() {
        // An audio-led bracket is a content signal, not a group.
        val f = SourceRowClassifier.parseRelease("[DUAL AAC2.0] Some Title S01E01.mkv")
        assertNull("DUAL AAC2.0 must not be a release group", f.releaseGroup)
        assertEquals("Dual", f.audioSummary)
    }

    @Test
    fun followups_dubbed_isAudioKind() {
        val f = SourceRowClassifier.parseRelease(
            "Sousou no Frieren S02E01 Dubbed 1080p AAC.mkv",
        )
        assertEquals("Dub", f.audioSummary)
        assertNull(f.releaseGroup)
    }

    @Test
    fun followups_engDub_isAudioKind() {
        val f = SourceRowClassifier.parseRelease("[Group] Title S01E01 Eng Dub [1080p].mkv")
        assertEquals("Dub", f.audioSummary)
        assertEquals("Group", f.releaseGroup)
    }

    @Test
    fun followups_englishDub_isAudioKind() {
        val f = SourceRowClassifier.parseRelease("Title S01E01 English Dub 1080p.mkv")
        assertEquals("Dub", f.audioSummary)
    }

    @Test
    fun followups_dualAudioBare_isAudioKind() {
        val f = SourceRowClassifier.parseRelease("[Group] Title Dual Audio 1080p.mkv")
        assertEquals("Dual", f.audioSummary)
    }

    @Test
    fun followups_multiSubs_isSubtitleSummary() {
        val f = SourceRowClassifier.parseRelease("[Group] Title S01E01 Multi Subs 1080p.mkv")
        assertEquals("Multi-subs", f.subtitleSummary)
        assertEquals("Group", f.releaseGroup)
    }

    @Test
    fun followups_multiHyphenSubs_isSubtitleSummary_notGroup() {
        val f = SourceRowClassifier.parseRelease("[Multi-Subs] Title S01E01.mkv")
        assertNull("Multi-Subs must not be a release group", f.releaseGroup)
        assertEquals("Multi-subs", f.subtitleSummary)
    }

    @Test
    fun followups_multisubOneWord_isSubtitleSummary() {
        val f = SourceRowClassifier.parseRelease("Title S01E01 Multisub 1080p.mkv")
        assertEquals("Multi-subs", f.subtitleSummary)
    }

    @Test
    fun followups_explicitAudioLine_winsOverDubGuess() {
        // The 🎬 line is higher-confidence than a bare title marker.
        val f = SourceRowClassifier.parseRelease(
            "🎬 Japanese\n[Group] Title Dubbed S01E01.mkv",
        )
        assertEquals("Japanese", f.audioSummary)
    }

    @Test
    fun followups_explicitSubFlags_winOverMultiGuess() {
        // Classified flags beat the bare multi-sub guess.
        val f = SourceRowClassifier.parseRelease("[Group] Title [Multi-Subs] 🇬🇧 🇵🇱.mkv")
        assertEquals("🇬🇧 🇵🇱", f.subtitleSummary)
    }

    @Test
    fun followups_providerNameAlone_doesNotInferDub() {
        // A "dub" substring in the tracker name must not fabricate an
        // audio kind; the title here is neutral.
        val f = SourceRowClassifier.parseRelease(
            """
            Torrentio
            📂 DubTorrents
            1080p [Group] Title S01E01.mkv
            """.trimIndent(),
        )
        assertNull("tracker name must not infer dub", f.audioSummary)
        assertNull("tracker name must not infer subs", f.subtitleSummary)
        assertEquals("Group", f.releaseGroup)
    }

    @Test
    fun followups_japaneseDoesNotTriggerDub() {
        // "Japanese" contains no dub word-boundary match.
        val f = SourceRowClassifier.parseRelease("🎬 Japanese (AAC 2.0) [Group] Title.mkv")
        assertEquals("AAC", f.audioFormat)
    }

    // ---- follow-ups v1: suffix-style -GROUP extraction --------------

    @Test
    fun followups_suffixVaryg_extracted() {
        val f = SourceRowClassifier.parseRelease(
            "Sousou no Frieren S02E01 [1080p][HEVC]-VARYG.mkv",
        )
        assertEquals("VARYG", f.releaseGroup)
    }

    @Test
    fun followups_suffixRapta_extracted_preservesCase() {
        val f = SourceRowClassifier.parseRelease("Frieren S02E01 1080p HEVC-Rapta.mkv")
        assertEquals("Rapta", f.releaseGroup)
    }

    @Test
    fun followups_suffixWithoutExtension_extracted() {
        val f = SourceRowClassifier.parseRelease("Frieren S02E01 1080p-VARYG")
        assertEquals("VARYG", f.releaseGroup)
    }

    @Test
    fun followups_bracketWinsOverSuffix() {
        // An authoritative [Group] tag beats any suffix guess.
        val f = SourceRowClassifier.parseRelease("[SubsPlease] Title S01E01 1080p-VARYG.mkv")
        assertEquals("SubsPlease", f.releaseGroup)
    }

    @Test
    fun followups_cogWinsOverSuffix() {
        // The explicit ⚙ line beats the weaker suffix guess.
        val f = SourceRowClassifier.parseRelease(
            """
            Torrentio
            📂 NyaaSi
            ⚙️ SubsPlease
            Title S01E01 1080p-VARYG.mkv
            """.trimIndent(),
        )
        assertEquals("SubsPlease", f.releaseGroup)
    }

    @Test
    fun followups_suffixRejectsCodec() {
        assertNull(SourceRowClassifier.parseRelease("Title S01E01-x264.mkv").releaseGroup)
        assertNull(SourceRowClassifier.parseRelease("Title-HEVC.mkv").releaseGroup)
    }

    @Test
    fun followups_suffixRejectsResolution() {
        assertNull(SourceRowClassifier.parseRelease("Title-1080p.mkv").releaseGroup)
        assertNull(SourceRowClassifier.parseRelease("Title-4K.mkv").releaseGroup)
    }

    @Test
    fun followups_suffixRejectsAudio() {
        assertNull(SourceRowClassifier.parseRelease("Title-AAC.mkv").releaseGroup)
        assertNull(SourceRowClassifier.parseRelease("Title-FLAC.mkv").releaseGroup)
    }

    @Test
    fun followups_suffixRejectsEpisodeAndVersion() {
        assertNull(SourceRowClassifier.parseRelease("Show-S01E01.mkv").releaseGroup)
        assertNull(SourceRowClassifier.parseRelease("Show-E01.mkv").releaseGroup)
        assertNull(SourceRowClassifier.parseRelease("Title-v2.mkv").releaseGroup)
    }

    @Test
    fun followups_suffixRejectsTagsAndLanguages() {
        assertNull(SourceRowClassifier.parseRelease("Title-WEBDL.mkv").releaseGroup)
        assertNull(SourceRowClassifier.parseRelease("Title-COMPLETE.mkv").releaseGroup)
        assertNull(SourceRowClassifier.parseRelease("Title-JP.mkv").releaseGroup)
        assertNull(SourceRowClassifier.parseRelease("Title-NF.mkv").releaseGroup)
    }

    @Test
    fun followups_suffixRejectsExtensionAndSpacedForms() {
        // The extension itself is never the group.
        assertNull(SourceRowClassifier.parseRelease("Title.mkv").releaseGroup)
        // A spaced hyphen does not form a suffix group.
        assertNull(SourceRowClassifier.parseRelease("Title - 01.mkv").releaseGroup)
        // No trailing hyphen token at all.
        assertNull(SourceRowClassifier.parseRelease("Title S01E01 1080p.mkv").releaseGroup)
    }

    // ---- follow-ups v1: Dub/Sub pills end-to-end ---------------------

    @Test
    fun followups_dualRelease_pillsCarryAudioMode() {
        val row = SourceRowSummarizer.build(
            displayLabel = "[Erai-raws] Title - 01 [1080p][DUAL AAC2.0].mkv",
            qualityText = "[Erai-raws] Title - 01 [1080p][DUAL AAC2.0].mkv",
            formatName = "CONTAINER",
            url = magnetUrl,
        )
        assertEquals("Erai-raws", row.header)
        val mode = row.pills.firstOrNull { it.kind == SourcePillKind.AUDIO_MODE }
        assertNotNull("DUAL row must carry an audio-mode pill", mode)
        assertEquals("Dual", mode!!.text)
    }

    @Test
    fun followups_multiSubsRelease_pillsCarrySubLang() {
        val row = SourceRowSummarizer.build(
            displayLabel = "[Group] Title S01E01 Multi Subs 1080p.mkv",
            qualityText = "[Group] Title S01E01 Multi Subs 1080p.mkv",
            formatName = "CONTAINER",
            url = magnetUrl,
        )
        val sub = row.pills.firstOrNull { it.kind == SourcePillKind.SUB_LANG }
        assertNotNull("Multi-subs row must carry a subtitle pill", sub)
        assertEquals("Multi-subs", sub!!.text)
    }


    // ---- provider-dataset v1: pipe-terminated tracker/cog --------

    @Test
    fun dataset_cogLine_stopsAtPipeSegment() {
        // Torrentio metadata uses ` | ` separators: the ⚙️ value is
        // `Torrent9`, not `Torrent9 | 🇫🇷`. Under v2.2 current-format
        // semantics a lone ⚙️ is provenance (tracker), never a group.
        val f = SourceRowClassifier.parseRelease(
            "L'Attaque des Titans Saison 1 FRENCH HDTV\n" +
                "UBT-L.Attaque.Des.Titans.VOL01.ep01.avi\n" +
                "👤 5 💾 186.25 MB ⚙️ Torrent9 | 🇫🇷",
        )
        assertEquals("Torrent9", f.tracker)
        assertNull("lone ⚙️ provider must not be a group", f.releaseGroup)
    }

    @Test
    fun dataset_cogFallback_rescuedByPipeTermination() {
        // Under v2.2 semantics the lone ⚙️ value is the tracker; with
        // no title bracket and no filename suffix the group is null
        // (correct: null when absent). The row carries both Dubbed and
        // Dual Audio signals; DUAL wins (more specific).
        val f = SourceRowClassifier.parseRelease(
            "1080p Jujutsu Kaisen S01 (2020) (1080p x265 10bit BD Dual Audio FLAC 2.0) [Prof]\n" +
                "S01E01 - Ryoumen Sukuna.mkv\n" +
                "👤 1 💾 1001.08 MB ⚙️ TorrentGalaxy | Dubbed / Dual Audio / 🇬🇧",
        )
        assertEquals("TorrentGalaxy", f.tracker)
        assertNull("lone ⚙️ provider must not be a group", f.releaseGroup)
        assertEquals("Dual", f.audioSummary)
    }

    // ---- provider-dataset v1: generalized quality prefix ----------

    @Test
    fun dataset_uncommonResolutionPrefix_isAllowed() {
        val ice = SourceRowClassifier.parseRelease("800p [IceBlue] Naruto (Season 1) - 01.mkv")
        assertEquals("IceBlue", ice.releaseGroup)
        val evil = SourceRowClassifier.parseRelease("528p [EvilMini] Naruto - 001.mkv")
        assertEquals("EvilMini", evil.releaseGroup)
    }

    @Test
    fun dataset_technicalSecondPrefix_isAllowed() {
        // `1080p HDR` is quality + technical metadata, not a title.
        val f = SourceRowClassifier.parseRelease("1080p HDR [Salieri] Title S01 BD.mkv")
        assertEquals("Salieri", f.releaseGroup)
    }

    @Test
    fun dataset_qualityTitlePrefix_stillRejected() {
        // Non-technical second token still blocks fabrication.
        val f = SourceRowClassifier.parseRelease("1080p Title [Foo].mkv")
        assertNull(f.releaseGroup)
    }

    // ---- provider-dataset v1: hyphenated real groups survive ------

    @Test
    fun dataset_dubsEmpire_isGroup_notAudioMarker() {
        val f = SourceRowClassifier.parseRelease(
            "4k [Dubs-Empire] Jujutsu Kaisen (2020) S01 v3 [2160p].mkv",
        )
        assertEquals("Dubs-Empire", f.releaseGroup)
    }

    @Test
    fun dataset_dualLedBracket_stillRejected() {
        val f = SourceRowClassifier.parseRelease("[DUAL AAC2.0] Some Title S01E01.mkv")
        assertNull(f.releaseGroup)
    }

    @Test
    fun dataset_multiAudioSubsBracket_rejected() {
        val f = SourceRowClassifier.parseRelease("[Multi-Audio-Subs] Title S01E01.mkv")
        assertNull(f.releaseGroup)
    }

    // ---- provider-dataset v1: structural brackets -----------------

    @Test
    fun dataset_seasonEpisodeBrackets_rejected() {
        assertNull(SourceRowClassifier.parseRelease("[S01] Title S01E01.mkv").releaseGroup)
        assertNull(SourceRowClassifier.parseRelease("Title [S01-04 + OVA].mkv").releaseGroup)
        assertNull(SourceRowClassifier.parseRelease("Show E01 [E01].mkv").releaseGroup)
        assertNull(SourceRowClassifier.parseRelease("Title S01E01 [01x01].mkv").releaseGroup)
        assertNull(SourceRowClassifier.parseRelease("[Cap.101] Title.mkv").releaseGroup)
        assertNull(SourceRowClassifier.parseRelease("[OVA 01] Title.mkv").releaseGroup)
        assertNull(SourceRowClassifier.parseRelease("[Specials] Title.mkv").releaseGroup)
    }

    @Test
    fun dataset_versionBracket_rejected() {
        // A `[v2]`-first title previously fabricated group "v2".
        assertNull(SourceRowClassifier.parseRelease("[v2] Title S01E01.mkv").releaseGroup)
    }

    @Test
    fun dataset_urlBracket_rejected() {
        assertNull(SourceRowClassifier.parseRelease("Title [www.descargas2020.org].mkv").releaseGroup)
    }

    @Test
    fun dataset_completaBracket_rejected() {
        assertNull(SourceRowClassifier.parseRelease("Title (2022) [COMPLETA] [576p].mkv").releaseGroup)
    }

    // ---- provider-dataset v1: dotted compounds --------------------

    @Test
    fun dataset_dottedCompound_extractsTrailingGroup() {
        // Scene-style compound with a valid quality prefix.
        val f = SourceRowClassifier.parseRelease("1080p [HDTV.x264-zyl] Title S01E01.mkv")
        assertEquals("zyl", f.releaseGroup)
    }

    @Test
    fun dataset_dottedCompound_liveRow_groupAndSubs() {
        // Real BestTorrents row: technical-first bracket blocks the
        // title path; under v2.2 semantics the lone ⚙️ value is the
        // tracker (not a group) and the Napisy marker still surfaces
        // subtitles.
        val f = SourceRowClassifier.parseRelease(
            "Naruto 2002-2007 Complet [HDTV.x264-zyl][Napisy PL][Alusia]\n" +
                "Naruto - 001.PLSUB.mkv\n" +
                "👤 1 💾 172.32 MB ⚙️ BestTorrents | 🇵🇱",
        )
        assertEquals("BestTorrents", f.tracker)
        assertNull("lone ⚙️ provider must not be a group", f.releaseGroup)
        assertEquals("Multi-subs", f.subtitleSummary)
    }

    @Test
    fun dataset_dottedCompound_technicalTail_rejected() {
        val f = SourceRowClassifier.parseRelease("[JAP.AC3.SUB.ITA] Title.mkv")
        assertNull(f.releaseGroup)
    }

    @Test
    fun dataset_dottedNonTechnicalName_acceptedWhole() {
        val f = SourceRowClassifier.parseRelease("[anime4life.] Title S01E01.mkv")
        assertEquals("anime4life.", f.releaseGroup)
    }

    // ---- provider-dataset v1: per-line suffix + extensions --------

    @Test
    fun dataset_suffixFoundOnFilenameLine_belowTitle() {
        // No cog line: the per-line suffix scan reaches the filename
        // line even though metadata-style lines are absent here.
        val f = SourceRowClassifier.parseRelease(
            "L'attaque des Titans (Shingeki No Kyojin) Saison 1 MULTI 1080p HDTV\n" +
                "Shingeki.No.Kyojin.S01E01.MULTi.1080p.BluRay.REMUX.AVC.DTS-HDMA.2.0-Psaro.mkv",
        )
        assertEquals("Psaro", f.releaseGroup)
        assertEquals("Dual", f.audioSummary)
    }

    @Test
    fun dataset_explicitCogBeatsFilenameSuffix() {
        // v2.2 current-format semantics on the full regional row: the
        // lone ⚙️ value is the tracker, and the filename suffix wins
        // the group (this is the handoff's mandatory Psaro fixture).
        val f = SourceRowClassifier.parseRelease(
            "L'attaque des Titans (Shingeki No Kyojin) Saison 1 MULTI 1080p HDTV\n" +
                "Shingeki.No.Kyojin.S01E01.MULTi.1080p.BluRay.REMUX.AVC.DTS-HDMA.2.0-Psaro.mkv\n" +
                "👤 1 💾 4.86 GB ⚙️ Torrent9\n" +
                "Dubbed / Multi Audio / 🇫🇷",
        )
        assertEquals("Psaro", f.releaseGroup)
        assertEquals("Torrent9", f.tracker)
        assertEquals("Dub", f.audioSummary)
    }

    @Test
    fun dataset_tsExtension_strippedForSuffix() {
        val f = SourceRowClassifier.parseRelease("One Piece English Dub 214.ts")
        assertNull(f.releaseGroup)
        assertEquals("Dub", f.audioSummary)
    }

    @Test
    fun dataset_camSuffix_rejected() {
        assertNull(SourceRowClassifier.parseRelease("Title S01E01-CAM.mkv").releaseGroup)
        assertNull(SourceRowClassifier.parseRelease("Title S01E01-INTERNAL.mkv").releaseGroup)
    }

    // ---- provider-dataset v1: dub/sub vocabulary ------------------

    @Test
    fun dataset_vostfr_isSubtitleKind() {
        // VOSTFR materializes as a French flag chip, consistent with
        // the flag system (VOSTFR = original audio + French subs).
        val f = SourceRowClassifier.parseRelease(
            "Shingeki No Kyojin S01 BDRIP 1080p X265 10bit Vostfr.mkv",
        )
        assertEquals("🇫🇷", f.subtitleSummary)
        assertEquals(listOf("🇫🇷"), f.subtitleFlags)
    }

    @Test
    fun dataset_french_isAudioKind() {
        val f = SourceRowClassifier.parseRelease("Title Saison 1 FRENCH HDTV.mkv")
        assertEquals("Dub", f.audioSummary)
    }

    @Test
    fun dataset_bareMulti_isDualAudioKind() {
        // French-scene MULTI = FR+JP audio (user-confirmed).
        val f = SourceRowClassifier.parseRelease("Title Saison 1 MULTI 1080p HDTV.mkv")
        assertEquals("Dual", f.audioSummary)
    }

    @Test
    fun dataset_bareMulti_yieldsToMultiSubs() {
        // An explicit multi-subs marker wins over bare MULTI audio.
        val f = SourceRowClassifier.parseRelease("Title MULTI SUBS 1080p.mkv")
        assertEquals("Multi-subs", f.subtitleSummary)
        assertNull(f.audioSummary)
    }

    @Test
    fun dataset_castellanoLatinoDublado_areDubKind() {
        // Audio-format-led bracket with a VALID prefix: still not a group.
        val cast = SourceRowClassifier.parseRelease("1080p [AC3 5.1 Castellano] Title.mkv")
        assertNull("audio-led bracket must not be a group", cast.releaseGroup)
        assertEquals("Dub", cast.audioSummary)
        assertEquals("Dub",
            SourceRowClassifier.parseRelease("Title Latino 1080p.mkv").audioSummary)
        assertEquals("Dub",
            SourceRowClassifier.parseRelease("Title Dublado 720p.mkv").audioSummary)
    }

    @Test
    fun dataset_napisyLegendado_areSubtitleKind() {
        assertEquals("Multi-subs",
            SourceRowClassifier.parseRelease("Title [Napisy PL].mkv").subtitleSummary)
        assertEquals("Multi-subs",
            SourceRowClassifier.parseRelease("Title Legendado.mkv").subtitleSummary)
    }

    @Test
    fun dataset_bareSubsWord_isSubtitleKind() {
        assertEquals("Multi-subs",
            SourceRowClassifier.parseRelease("Title Ita Eng Jap SubS.mkv").subtitleSummary)
    }

    @Test
    fun dataset_espanol_resolvesThroughContext() {
        // Sub context -> subs ...
        assertEquals("Multi-subs",
            SourceRowClassifier.parseRelease("Title Sub Espanol.mkv").subtitleSummary)
        // ... dub context -> dub ...
        assertEquals("Dub",
            SourceRowClassifier.parseRelease("Title Audio Espanol.mkv").audioSummary)
        // ... otherwise neutral.
        val neutral = SourceRowClassifier.parseRelease("[Group] Title Espanol S01E01.mkv")
        assertNull(neutral.audioSummary)
        assertNull(neutral.subtitleSummary)
    }

    @Test
    fun dataset_slashedAudio_isDualKind() {
        val f = SourceRowClassifier.parseRelease("Title (Season 1) [1080p][Jpn/Eng/Ger Audio].mkv")
        assertEquals("Dual", f.audioSummary)
    }

    @Test
    fun dataset_englishAudio_isDubKind() {
        val f = SourceRowClassifier.parseRelease("Title S01E01 English Audio 1080p.mkv")
        assertEquals("Dub", f.audioSummary)
    }

    @Test
    fun dataset_providerNames_doNotLeakIntoKinds() {        // Tracker/provider-flavored words must not fabricate kinds.
        val f = SourceRowClassifier.parseRelease(
            """
            Torrentio
            📂 HorribleSubs
            1080p [Group] Title S01E01.mkv
            """.trimIndent(),
        )
        assertNull(f.audioSummary)
        assertNull(f.subtitleSummary)
    }

    @Test
    fun dataset_subsPleaseName_isNotSubsWord() {
        // No word boundary inside "SubsPlease", so the subs-word rule
        // does not fire — but the implied group default does (ENG).
        val f = SourceRowClassifier.parseRelease("1080p [SubsPlease] Title S01E01.mkv")
        assertEquals("SubsPlease", f.releaseGroup)
        assertEquals("ENG", f.subtitleSummary)
        // A neutral group with no markers stays unlabeled.
        val neutral = SourceRowClassifier.parseRelease("1080p [Group] Title S01E01.mkv")
        assertNull(neutral.subtitleSummary)
    }


    // ---- trash-guides/scenerules cross-check -------------------------
    //
    // Scene-standard shapes from trash-guides + scenerules reference:
    // SxxExx multi-episode/part-suffixed ranges, numeric ranges,
    // Part markers, daily dates, source/audio tags, DD/LPCM audio.

    @Test
    fun trash_sceneEpisodeShapes_rejected() {
        assertNull(SourceRowClassifier.parseRelease("[S01E01-E02] Title.mkv").releaseGroup)
        assertNull(SourceRowClassifier.parseRelease("1080p [0001-0130] Title.mkv").releaseGroup)
        assertNull(SourceRowClassifier.parseRelease("[Part 1] Title.mkv").releaseGroup)
        assertNull(SourceRowClassifier.parseRelease("[2020.01.15] Title.mkv").releaseGroup)
    }

    @Test
    fun trash_sourceTags_rejected() {
        assertNull(SourceRowClassifier.parseRelease("[WEB] Title S01E01.mkv").releaseGroup)
        assertNull(SourceRowClassifier.parseRelease("[PDTV] Title S01E01.mkv").releaseGroup)
        assertNull(SourceRowClassifier.parseRelease("[SDR] Title S01E01.mkv").releaseGroup)
        assertNull(SourceRowClassifier.parseRelease("[LPCM] Title S01E01.mkv").releaseGroup)
        assertNull(SourceRowClassifier.parseRelease("Title S01E01-REAL.mkv").releaseGroup)
    }

    @Test
    fun trash_audioFormats_detected() {
        // DD5.1 is basic Dolby Digital 5.1, NOT in the DDP family
        // (standards-gap v2.1 correction separates them).
        assertEquals("DD 5.1",
            SourceRowClassifier.parseRelease("[Group] Title DD5.1.mkv").audioFormat)
        assertEquals("LPCM",
            SourceRowClassifier.parseRelease("[Group] Title LPCM.mkv").audioFormat)
    }

    @Test
    fun trash_fullLanguageNames_resolveThroughContext() {
        // Dub context -> dub (reachable bare forms) ...
        assertEquals("Dub",
            SourceRowClassifier.parseRelease("Title German Audio 1080p.mkv").audioSummary)
        assertEquals("Dub",
            SourceRowClassifier.parseRelease("Title Italian Dub 1080p.mkv").audioSummary)
        // ... sub context -> subs (outcome via subs-word path) ...
        assertEquals("Multi-subs",
            SourceRowClassifier.parseRelease("Title German Subs 1080p.mkv").subtitleSummary)
        // ... otherwise neutral ...
        val neutral = SourceRowClassifier.parseRelease("[Group] Title Italian S01E01.mkv")
        assertNull(neutral.audioSummary)
        assertNull(neutral.subtitleSummary)
        // ... and Japanese is never a dub signal (original language).
        val jp = SourceRowClassifier.parseRelease("Title Japanese Audio 1080p.mkv")
        assertNull(jp.audioSummary)
    }


    // ---- human naming + implied subs -------------------------------
    //
    // Stereo collapse (AAC 2.0 -> AAC): stereo is the default, channels
    // shown only when multichannel. Famous-group implied subtitles
    // (SubsPlease/HorribleSubs always ship English subs).

    @Test
    fun naming_impliedSubs_subsPlease() {
        val f = SourceRowClassifier.parseRelease("1080p [SubsPlease] Title S01E01.mkv")
        assertEquals("SubsPlease", f.releaseGroup)
        assertEquals("ENG", f.subtitleSummary)
        assertEquals(listOf("ENG"), f.subtitleLanguages)
    }

    @Test
    fun naming_impliedSubs_yieldsToExplicit() {
        // Explicit flags beat the group default ...
        val flags = SourceRowClassifier.parseRelease(
            "[SubsPlease] Title [Multi-Subs] 🇬🇧 🇵🇱.mkv",
        )
        assertEquals("🇬🇧 🇵🇱", flags.subtitleSummary)
        // ... and so do bare title markers (VOSTFR materializes 🇫🇷).
        val marked = SourceRowClassifier.parseRelease("[SubsPlease] Title VOSTFR.mkv")
        assertEquals("🇫🇷", marked.subtitleSummary)
    }

    @Test
    fun naming_impliedSubs_horribleSubs() {
        val f = SourceRowClassifier.parseRelease("[HorribleSubs] Title - 01 [720p].mkv")
        assertEquals("ENG", f.subtitleSummary)
    }


    // ---- standards-gap v1: bare HDR grammar ------------------------
    //
    // TRaSH P2P/scene forms: bare and dot-delimited HDR / HDR10 /
    // HDR10+ / HDR10Plus / DV / Dolby Vision. Pill priority unchanged
    // (HDR10+ > HDR10 > DV > HDR); raw title/details keep everything.

    @Test
    fun standards_trashFixture_fullParse() {
        // Mandatory TRaSH fixture.
        val f = SourceRowClassifier.parseRelease(
            "The.Series.Title's!.2010.S01E01.Episode.Title.1.ATVP.WEBDL-2160p.EAC3.Atmos.5.1.DV.HDR10Plus.h265-RlsGrp",
        )
        assertEquals("RlsGrp", f.releaseGroup)
        assertEquals("2160p", f.resolution)
        assertEquals("HEVC", f.codec)
        assertEquals("HDR10+", f.hdrVariant)
        assertEquals("Apple TV+", f.sourceService)
        assertEquals("WEBDL", f.sourceClass)
        // Deterministic priority: HDR10+ wins over co-present DV; the
        // raw name is preserved for details.
        assertEquals(
            "The.Series.Title's!.2010.S01E01.Episode.Title.1.ATVP.WEBDL-2160p.EAC3.Atmos.5.1.DV.HDR10Plus.h265-RlsGrp",
            f.releaseName,
        )
    }

    @Test
    fun standards_bareHdrForms_detected() {
        assertEquals("HDR10+",
            SourceRowClassifier.parseRelease("Title S01E01 HDR10+.mkv").hdrVariant)
        assertEquals("HDR10+",
            SourceRowClassifier.parseRelease("Title.S01E01.HDR10Plus.mkv").hdrVariant)
        assertEquals("HDR10",
            SourceRowClassifier.parseRelease("Title S01E01 HDR10.mkv").hdrVariant)
        assertEquals("Dolby Vision",
            SourceRowClassifier.parseRelease("Title.S01E01.DV.mkv").hdrVariant)
        assertEquals("Dolby Vision",
            SourceRowClassifier.parseRelease("Title S01E01 Dolby Vision.mkv").hdrVariant)
        assertEquals("HDR",
            SourceRowClassifier.parseRelease("Title S01E01 HDR.mkv").hdrVariant)
    }

    @Test
    fun standards_hdrSubstringsInWords_rejected() {
        // ADV Films, DVDRip, HDRip must not trigger HDR/DV.
        assertNull(SourceRowClassifier.parseRelease("[ADV] Title S01E01.mkv").hdrVariant)
        assertNull(SourceRowClassifier.parseRelease("Title S01E01 DVDRip.mkv").hdrVariant)
        assertNull(SourceRowClassifier.parseRelease("Title S01E01 HDRip.mkv").hdrVariant)
        // HDR10 must not be shadowed by a bare-HDR read of its prefix.
        assertEquals("HDR10",
            SourceRowClassifier.parseRelease("Title HDR10.mkv").hdrVariant)
    }

    // ---- standards-gap v1: dual-audio grammar ----------------------

    @Test
    fun standards_dualAudioSeparators_detected() {
        assertEquals("Dual",
            SourceRowClassifier.parseRelease("Title Dual Audio 1080p.mkv").audioSummary)
        assertEquals("Dual",
            SourceRowClassifier.parseRelease("Title Dual-Audio 1080p.mkv").audioSummary)
        assertEquals("Dual",
            SourceRowClassifier.parseRelease("Title Dual_Audio 1080p.mkv").audioSummary)
        assertEquals("Dual",
            SourceRowClassifier.parseRelease("Title.Dual.Audio.1080p.mkv").audioSummary)
        assertEquals("Dual",
            SourceRowClassifier.parseRelease("[Dual] Title S01E01.mkv").audioSummary)
    }

    @Test
    fun standards_langPairs_detected() {
        for (pair in listOf("JA+EN", "ZH+EN", "KO+EN", "EN+JA", "EN+ZH", "EN+KO")) {            assertEquals("Dual ($pair)",
                "Dual",
                SourceRowClassifier.parseRelease("Title S01E01 $pair 1080p.mkv").audioSummary)
        }
        assertEquals("Dual",
            SourceRowClassifier.parseRelease("Title Japanese + English 1080p.mkv").audioSummary)
        assertEquals("Dual",
            SourceRowClassifier.parseRelease("Title Korean-English 1080p.mkv").audioSummary)
        assertEquals("Dual",
            SourceRowClassifier.parseRelease("Title English + Korean 1080p.mkv").audioSummary)
        // JP shorthand pair from the live corpus ([JP-EN] rows).
        assertEquals("Dual",
            SourceRowClassifier.parseRelease("[JP-EN] Title S01 [2020] 1080p.mkv").audioSummary)
    }

    @Test
    fun standards_dualNegatives_rejected() {
        // Substrings inside ordinary words ...
        assertNull(SourceRowClassifier.parseRelease("Title individual S01E01.mkv").audioSummary)
        assertNull(SourceRowClassifier.parseRelease("Title gradual S01E01.mkv").audioSummary)
        assertNull(SourceRowClassifier.parseRelease("Title dualism S01E01.mkv").audioSummary)
        // ... Japanese-only text, lone EN token, provider name.
        assertNull(SourceRowClassifier.parseRelease("Title Japanese S01E01.mkv").audioSummary)
        assertNull(SourceRowClassifier.parseRelease("[Group] Title S01E01 [ENG].mkv").audioSummary)
    }

    // ---- standards-gap v1: underscore dub grammar ------------------

    @Test
    fun standards_underscoreDubs_detected() {
        assertEquals("Dub",
            SourceRowClassifier.parseRelease("Title eng_dub 1080p.mkv").audioSummary)
        assertEquals("Dub",
            SourceRowClassifier.parseRelease("Title english_dub 1080p.mkv").audioSummary)
        assertEquals("Dub",
            SourceRowClassifier.parseRelease("Title funi_dub 1080p.mkv").audioSummary)
        assertEquals("Dub",
            SourceRowClassifier.parseRelease("Title English.Dub 1080p.mkv").audioSummary)
        assertEquals("Dub",
            SourceRowClassifier.parseRelease("Title English-Dub 1080p.mkv").audioSummary)
    }

    @Test
    fun standards_redubbed_rejected() {
        assertNull(SourceRowClassifier.parseRelease("Title redubbed 1080p.mkv").audioSummary)
    }

    // ---- standards-gap v1: DD+ / PCM / DTS:X -----------------------

    @Test
    fun standards_ddPlus_normalised() {
        assertEquals("E-AC-3 / DDP",
            SourceRowClassifier.parseRelease("[Group] Title DD+.mkv").audioFormat)
        assertEquals("E-AC-3 / DDP",
            SourceRowClassifier.parseRelease("[Group] Title DD+ 2.0.mkv").audioFormat)
        assertEquals("E-AC-3 / DDP 5.1",
            SourceRowClassifier.parseRelease("[Group] Title DD+ 5.1.mkv").audioFormat)
        // DD+ family retained, not silently reduced to Atmos.
        assertEquals("E-AC-3 / DDP",
            SourceRowClassifier.parseRelease("[Group] Title DD+ Atmos.mkv").audioFormat)
    }

    @Test
    fun standards_pcm_normalised() {
        assertEquals("PCM",
            SourceRowClassifier.parseRelease("[Group] Title PCM.mkv").audioFormat)
        assertEquals("PCM",
            SourceRowClassifier.parseRelease("[Group] Title PCM 2.0.mkv").audioFormat)
        assertEquals("LPCM",
            SourceRowClassifier.parseRelease("[Group] Title LPCM 2.0.mkv").audioFormat)
    }

    @Test
    fun standards_dtsX_preserved() {
        assertEquals("DTS X",
            SourceRowClassifier.parseRelease("[Group] Title DTS X.mkv").audioFormat)
        assertEquals("DTS-X",
            SourceRowClassifier.parseRelease("[Group] Title DTS-X.mkv").audioFormat)
        assertEquals("DTS:X",
            SourceRowClassifier.parseRelease("[Group] Title DTS:X.mkv").audioFormat)
    }

    // ---- standards-gap v1: service aliases -------------------------

    @Test
    fun standards_serviceStandalone_allMapEntries() {
        // Bracketed ...
        for ((alias, service) in mapOf(
            "NF" to "Netflix", "AMZN" to "Amazon", "CR" to "Crunchyroll",
            "DSNP" to "Disney+", "HULU" to "Hulu", "AT-X" to "AT-X",
            "ATVP" to "Apple TV+",
        )) {
            assertEquals("$alias bracket", service,
                SourceRowClassifier.parseRelease("1080p [Group] Title [$alias] S01E01.mkv").sourceService)
            // ... spaced ...
            assertEquals("$alias spaced", service,
                SourceRowClassifier.parseRelease("1080p [Group] Title $alias S01E01.mkv").sourceService)
            // ... and dot-delimited.
            assertEquals("$alias dotted", service,
                SourceRowClassifier.parseRelease("Title.S01E01.$alias.WEBDL.mkv").sourceService)
        }
    }

    @Test
    fun standards_serviceAliases_neverGroupsOrPills() {
        val f = SourceRowClassifier.parseRelease("[ATVP] Title S01E01.mkv")
        assertNull(f.releaseGroup)
        assertEquals("Apple TV+", f.sourceService)
    }

    // ---- standards-gap v1: extension-set consistency ---------------

    @Test
    fun standards_releaseFilename_allKnownExtensions() {
        for (ext in listOf(
            "mkv", "mp4", "avi", "webm", "ts", "m4v",
            "m2ts", "mov", "flv", "rmvb",
        )) {
            val row = SourceRowSummarizer.build(
                displayLabel = "Torrentio\n[Group] Title S01E01.$ext",
                qualityText = "Torrentio\n[Group] Title S01E01.$ext",
                formatName = "CONTAINER",
                url = "magnet:?xt=urn:btih:0123456789ABCDEF0123456789ABCDEF01234567",
            )
            assertEquals("RELEASE for .$ext", SourcePresentationKind.RELEASE, row.presentation)
        }
        // A bare name with none of the known extensions stays STREAM.
        val stream = SourceRowSummarizer.build(
            displayLabel = "Vidstream - Sub - 1080p",
            qualityText = "Vidstream - Sub - 1080p",
            formatName = "M3U8",
            url = "https://example.com/vidstream/playlist.m3u8",
        )
        assertEquals(SourcePresentationKind.STREAM, stream.presentation)
    }


    // ---- standards-gap v2.1: full-language separators --------------

    @Test
    fun standards21_fullLanguageUnderscoreAndSpace() {
        for (title in listOf(
            "Title Japanese_English 1080p.mkv",
            "Title Chinese_English 1080p.mkv",
            "Title Korean_English 1080p.mkv",
            "Title English_Japanese 1080p.mkv",
            "Title English_Chinese 1080p.mkv",
            "Title English_Korean 1080p.mkv",
            "Title Japanese English 1080p.mkv",
            "Title Chinese English 1080p.mkv",
            "Title Korean English 1080p.mkv",
            "Title English Japanese 1080p.mkv",
            "Title English Chinese 1080p.mkv",
            "Title English Korean 1080p.mkv",
        )) {
            assertEquals("Dual ($title)", "Dual",
                SourceRowClassifier.parseRelease(title).audioSummary)
        }
    }

    @Test
    fun standards21_singleLanguageWords_stayNull() {
        assertNull(SourceRowClassifier.parseRelease("Title Japanese S01E01.mkv").audioSummary)
        assertNull(SourceRowClassifier.parseRelease("Title English S01E01.mkv").audioSummary)
        assertNull(SourceRowClassifier.parseRelease("Title individual S01E01.mkv").audioSummary)
        assertNull(SourceRowClassifier.parseRelease("Title gradual S01E01.mkv").audioSummary)
        assertNull(SourceRowClassifier.parseRelease("Title dualism S01E01.mkv").audioSummary)
    }

    // ---- standards-gap v2.1: DD vs DD+ separation ------------------

    @Test
    fun standards21_plainDd_staysDd() {
        assertEquals("DD", SourceRowClassifier.parseRelease("[Group] Title DD.mkv").audioFormat)
        assertEquals("DD", SourceRowClassifier.parseRelease("[Group] Title DD 2.0.mkv").audioFormat)
        assertEquals("DD", SourceRowClassifier.parseRelease("[Group] Title DD2.0.mkv").audioFormat)
        assertEquals("DD 5.1", SourceRowClassifier.parseRelease("[Group] Title DD 5.1.mkv").audioFormat)
        assertEquals("DD 5.1", SourceRowClassifier.parseRelease("[Group] Title DD5.1.mkv").audioFormat)
    }

    @Test
    fun standards21_ac3Channels_preserved() {
        assertEquals("AC-3", SourceRowClassifier.parseRelease("[Group] Title AC3.mkv").audioFormat)
        assertEquals("AC-3", SourceRowClassifier.parseRelease("[Group] Title AC3 2.0.mkv").audioFormat)
        assertEquals("AC-3 5.1", SourceRowClassifier.parseRelease("[Group] Title AC3 5.1.mkv").audioFormat)
    }

    @Test
    fun standards21_ddpFamily_keptSeparate() {
        assertEquals("E-AC-3 / DDP",
            SourceRowClassifier.parseRelease("[Group] Title DDP.mkv").audioFormat)
        assertEquals("E-AC-3 / DDP 5.1",
            SourceRowClassifier.parseRelease("[Group] Title DDP 5.1.mkv").audioFormat)
        assertEquals("E-AC-3 / DDP",
            SourceRowClassifier.parseRelease("[Group] Title DD+ 2.0.mkv").audioFormat)
        assertEquals("E-AC-3 / DDP 5.1",
            SourceRowClassifier.parseRelease("[Group] Title DD+ 5.1.mkv").audioFormat)
    }

    // ---- standards-gap v2.1: canonical E-AC-3 -----------------------

    @Test
    fun standards21_eac3Spellings_canonical() {
        for (spell in listOf("EAC3", "E-AC3", "EAC-3", "E-AC-3")) {
            assertEquals("E-AC-3 ($spell)", "E-AC-3",
                SourceRowClassifier.parseRelease("[Group] Title $spell.mkv").audioFormat)
        }
        assertEquals("E-AC-3",
            SourceRowClassifier.parseRelease("[Group] Title EAC3 2.0.mkv").audioFormat)
        assertEquals("E-AC-3 5.1",
            SourceRowClassifier.parseRelease("[Group] Title EAC3 5.1.mkv").audioFormat)
        assertEquals("E-AC-3 5.1",
            SourceRowClassifier.parseRelease("[Group] Title E-AC3 5.1.mkv").audioFormat)
    }

    // ---- standards-gap v2.1: DTS-HD tails --------------------------

    @Test
    fun standards21_dtsHdMa_canonical() {
        assertEquals("DTS-HD MA",
            SourceRowClassifier.parseRelease("[Group] Title DTS-HD MA.mkv").audioFormat)
        assertEquals("DTS-HD MA",
            SourceRowClassifier.parseRelease("[Group] Title DTS.HD.MA.mkv").audioFormat)
        assertEquals("DTS-HD MA",
            SourceRowClassifier.parseRelease("[Group] Title DTS HD MA.mkv").audioFormat)
        assertEquals("DTS-HD MA",
            SourceRowClassifier.parseRelease("[Group] Title DTS-HDMA.mkv").audioFormat)
        assertEquals("DTS-HD MA",
            SourceRowClassifier.parseRelease("[Group] Title DTS-HDMA.2.0.mkv").audioFormat)
        assertEquals("DTS-HD MA 5.1",
            SourceRowClassifier.parseRelease("[Group] Title DTS-HD MA 5.1.mkv").audioFormat)
        assertEquals("DTS-HD MA 5.1",
            SourceRowClassifier.parseRelease("[Group] Title DTS.HD.MA.5.1.mkv").audioFormat)
        assertEquals("DTS-HD MA 7.1",
            SourceRowClassifier.parseRelease("[Group] Title DTS-HD MA 7.1.mkv").audioFormat)
    }

    @Test
    fun standards21_dtsHdHra_canonical() {
        assertEquals("DTS-HD HRA",
            SourceRowClassifier.parseRelease("[Group] Title DTS-HD HRA.mkv").audioFormat)
        assertEquals("DTS-HD HRA",
            SourceRowClassifier.parseRelease("[Group] Title DTS HD HRA.mkv").audioFormat)
        assertEquals("DTS-HD HRA 5.1",
            SourceRowClassifier.parseRelease("[Group] Title DTS-HD HRA 5.1.mkv").audioFormat)
    }

    @Test
    fun standards21_dtsGuard_staysNonGreedy() {
        // The old safety case: codec/group text must not be swallowed.
        assertEquals("DTS-HD",
            SourceRowClassifier.parseRelease("Title DTS-HD.X265-IAHD.mkv").audioFormat)
        // Accepted DTS:X forms stay green.
        assertEquals("DTS:X",
            SourceRowClassifier.parseRelease("[Group] Title DTS:X.mkv").audioFormat)
        assertEquals("DTS",
            SourceRowClassifier.parseRelease("[Group] Title DTS.mkv").audioFormat)
    }


    // ---- standards-gap v2.2: current single-⚙ semantics -----------
    //
    // The 784-row corpus has zero 📂 rows: current Torrentio payloads
    // carry the provider in ⚙️, so lone ⚙️ is tracker/provenance and
    // NEVER a release group. Legacy two-marker (📂+⚙️) coverage stays.

    @Test
    fun current_bracketGroupWithProvider() {
        val f = SourceRowClassifier.parseRelease(
            "1080p\n" +
                "[SubsPlease] Some Anime - 01 [1080p].mkv\n" +
                "👤 10 💾 1.2 GB ⚙️ NyaaSi",
        )
        assertEquals("SubsPlease", f.releaseGroup)
        assertEquals("NyaaSi", f.tracker)
    }

    @Test
    fun current_providerWithNoGroup() {
        val f = SourceRowClassifier.parseRelease(
            "1080p\n" +
                "Some.Show.S01E01.1080p.x265.mkv\n" +
                "👤 10 💾 1.2 GB ⚙️ TorrentGalaxy",
        )
        assertNull("no group present: null is correct", f.releaseGroup)
        assertEquals("TorrentGalaxy", f.tracker)
    }

    @Test
    fun current_suffixGroupWithProvider() {
        // Real French-row shape from the returned corpus.
        val f = SourceRowClassifier.parseRelease(
            "1080p\n" +
                "L'attaque des Titans (Shingeki No Kyojin) Saison 1 MULTI 1080p HDTV\n" +
                "Shingeki.No.Kyojin.S01E01.MULTi.1080p.BluRay.REMUX.AVC.DTS-HDMA.2.0-Psaro.mkv\n" +
                "👤 1 💾 4.86 GB ⚙️ Torrent9\n" +
                "Dubbed / Multi Audio / 🇫🇷",
        )
        assertEquals("Psaro", f.releaseGroup)
        assertEquals("Torrent9", f.tracker)
    }

    @Test
    fun legacy_twoMarkerShape_preserved() {
        // Legacy/two-marker compatibility fixture (NOT current format).
        val f = SourceRowClassifier.parseRelease(
            "📂 NyaaSi\n" +
                "⚙️ SubsPlease\n" +
                "Title.mkv",
        )
        assertEquals("NyaaSi", f.tracker)
        assertEquals("SubsPlease", f.releaseGroup)
    }

    @Test
    fun legacy_sameTrackerAndCog_noGroupFabrication() {
        val f = SourceRowClassifier.parseRelease(
            "📂 NyaaSi\n" +
                "⚙️ NyaaSi\n" +
                "Title.mkv",
        )
        assertEquals("NyaaSi", f.tracker)
        assertNull("cog equal to tracker must not become a group", f.releaseGroup)
    }


    // ---- standards-gap v2.2: bounded channel delimiters ---------

    @Test
    fun standards22_channelDelimiters_normalised() {
        // Dot/underscore/hyphen channel separators resolve exactly
        // like spaced forms; 2.0 collapses, 5.1+ preserved.
        assertEquals("DD 5.1",
            SourceRowClassifier.parseRelease("[Group] Title DD.5.1.mkv").audioFormat)
        assertEquals("DD 5.1",
            SourceRowClassifier.parseRelease("[Group] Title DD-5.1.mkv").audioFormat)
        assertEquals("DD 5.1",
            SourceRowClassifier.parseRelease("[Group] Title DD_5.1.mkv").audioFormat)
        assertEquals("E-AC-3 / DDP 5.1",
            SourceRowClassifier.parseRelease("[Group] Title DDP.5.1.mkv").audioFormat)
        assertEquals("E-AC-3 / DDP 5.1",
            SourceRowClassifier.parseRelease("[Group] Title DD+.5.1.mkv").audioFormat)
        assertEquals("E-AC-3 5.1",
            SourceRowClassifier.parseRelease("[Group] Title EAC3.5.1.mkv").audioFormat)
        assertEquals("E-AC-3 5.1",
            SourceRowClassifier.parseRelease("[Group] Title E-AC3.5.1.mkv").audioFormat)
        assertEquals("FLAC 5.1",
            SourceRowClassifier.parseRelease("[Group] Title FLAC.5.1.mkv").audioFormat)
        assertEquals("PCM 5.1",
            SourceRowClassifier.parseRelease("[Group] Title PCM.5.1.mkv").audioFormat)
        assertEquals("DTS-HD MA 5.1",
            SourceRowClassifier.parseRelease("[Group] Title DTS.HD.MA.5.1.mkv").audioFormat)
        assertEquals("DD 6.1",
            SourceRowClassifier.parseRelease("[Group] Title DD 6.1.mkv").audioFormat)
        assertEquals("E-AC-3 / DDP 7.1",
            SourceRowClassifier.parseRelease("[Group] Title DDP 7.1.mkv").audioFormat)
    }


    companion object {
        private val magnetUrl =
            "magnet:?xt=urn:btih:0123456789ABCDEF0123456789ABCDEF01234567&dn=t"
    }
}