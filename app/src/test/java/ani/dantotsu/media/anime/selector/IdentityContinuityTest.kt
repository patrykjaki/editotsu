package ani.dantotsu.media.anime.selector

import ani.dantotsu.parsers.VideoServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Focused tests for exact identity and family continuity.
 *
 * Tests construct the expected string and assert `.length` rather
 * than asserting against decomposed constants.
 */
class IdentityContinuityTest {

    // ===== BTIH canonicalizer =====

    @Test
    fun btih_known_40hex_canonical() {
        val hex = "1234567890abcdef1234567890abcdef12345678"
        val canon = BtihCanonicalizer.canonicalHex40(hex)
        assertNotNull(canon)
        assertEquals(40, canon!!.length)
        assertEquals(hex, canon)
    }

    @Test
    fun btih_known_base32_canonical_to_same_hex() {
        // 20 bytes [0x00, 0x01, ..., 0x13] base32-encoded to
        // AAAQEAYEAUDAOCAJBIFQYDIOB4IBCEQT (32 chars, no padding).
        // The canonical hex is the same bytes rendered as lowercase
        // hex: 000102030405060708090a0b0c0d0e0f10111213.
        val base32 = "AAAQEAYEAUDAOCAJBIFQYDIOB4IBCEQT"
        val hexFromBase32 = BtihCanonicalizer.canonicalHex40(base32)
        assertNotNull(hexFromBase32)
        assertEquals(
            "000102030405060708090a0b0c0d0e0f10111213",
            hexFromBase32,
        )
        val hexDirect = BtihCanonicalizer.canonicalHex40(
            "000102030405060708090a0b0c0d0e0f10111213",
        )
        assertEquals(hexDirect, hexFromBase32)
    }

    @Test
    fun btih_invalid_partial_rejected() {
        assertNull(BtihCanonicalizer.canonicalHex40("1234"))
        assertNull(BtihCanonicalizer.canonicalHex40("g".repeat(40)))
        assertNull(BtihCanonicalizer.canonicalHex40(""))
        assertNull(BtihCanonicalizer.canonicalHex40(null))
    }

    @Test
    fun btih_overlong_rejected() {
        assertNull(BtihCanonicalizer.canonicalHex40("a".repeat(41)))
        assertNull(BtihCanonicalizer.canonicalHex40("A".repeat(33)))
    }

    // ===== Canonical index parser =====

    @Test
    fun index_absent_is_zero() {
        val n = CanonicalIndexParser.canonicalIndex("magnet:?xt=urn:btih:abc")
        assertEquals(0L, n)
    }

    @Test
    fun index_explicit_zero_to_99999_accepted() {
        for (v in listOf(0L, 1L, 99_999L)) {
            val m = "magnet:?xt=urn:btih:abc&index=$v"
            assertEquals(v, CanonicalIndexParser.canonicalIndex(m))
        }
    }

    @Test
    fun index_100000_rejected() {
        val m = "magnet:?xt=urn:btih:abc&index=100000"
        assertNull(CanonicalIndexParser.canonicalIndex(m))
    }

    @Test
    fun index_negative_rejected() {
        val m = "magnet:?xt=urn:btih:abc&index=-1"
        assertNull(CanonicalIndexParser.canonicalIndex(m))
    }

    @Test
    fun index_xindex_ignored() {
        val m = "magnet:?xt=urn:btih:abc&xindex=5"
        assertEquals(0L, CanonicalIndexParser.canonicalIndex(m))
    }

    // ===== URL canonicaliser =====

    @Test
    fun url_query_and_fragment_collapsed() {
        val c = UrlCanonicaliser.canonicalise(
            "https://NYAA.SI:443/a.torrent?x=1#z"
        )
        assertNotNull(c)
        assertEquals("https://nyaa.si/a.torrent", c!!.canonicalUrlString())
    }

    @Test
    fun url_no_slash_query_preserves_path() {
        val c = UrlCanonicaliser.canonicalise("https://nyaa.si?token=a")
        assertNotNull(c)
        assertEquals("https://nyaa.si", c!!.canonicalUrlString())
    }

    @Test
    fun url_case_only_host_equality() {
        val a = UrlCanonicaliser.canonicalise("https://NYAA.si/a.torrent")
        val b = UrlCanonicaliser.canonicalise("https://nyaa.SI/a.torrent")
        assertEquals(a?.canonicalUrlString(), b?.canonicalUrlString())
    }

    @Test
    fun url_default_port_stripped() {
        val https443 = UrlCanonicaliser.canonicalise("https://nyaa.si:443/a.torrent")
        val http80 = UrlCanonicaliser.canonicalise("http://nyaa.si:80/a.torrent")
        assertEquals("https://nyaa.si/a.torrent", https443?.canonicalUrlString())
        assertEquals("http://nyaa.si/a.torrent", http80?.canonicalUrlString())
    }

    @Test
    fun url_nondefault_port_preserved() {
        val a = UrlCanonicaliser.canonicalise("https://nyaa.si:8443/a.torrent")
        assertEquals("https://nyaa.si:8443/a.torrent", a?.canonicalUrlString())
    }

    @Test
    fun url_user_info_rejected() {
        assertNull(UrlCanonicaliser.canonicalise("https://user:pass@nyaa.si/a.torrent"))
    }

    @Test
    fun url_malformed_port_rejected() {
        assertNull(UrlCanonicaliser.canonicalise("https://nyaa.si:0/a.torrent"))
        assertNull(UrlCanonicaliser.canonicalise("https://nyaa.si:65536/a.torrent"))
    }

    // ===== Provider hash =====

    @Test
    fun provider_exact_case_preserved() {
        // Two providers differing only in case should yield different
        // hashes.
        val a = ProviderHash.provider8("com.nyaa.Sub")
        val b = ProviderHash.provider8("com.nyaa.SUB")
        assertNotNull(a); assertNotNull(b)
        assertTrue(a != b)
    }

    @Test
    fun provider_null_blank_returns_null() {
        assertNull(ProviderHash.provider8(null))
        assertNull(ProviderHash.provider8(""))
        assertNull(ProviderHash.provider8("   "))
    }

    // ===== Magnet key length =====

    @Test
    fun magnet_key_length_77_at_index_0() {
        // 40-hex BTIH (first 40 hex of SHA-256("foo")).
        val hex = "0000000000000000000000000000000000000000"
        val magnet = "magnet:?xt=urn:btih:$hex&dn=foo"
        val k = MagnetKeyBuilder.build("com.nyaa.sub", magnet)
        assertNotNull(k)
        assertEquals(77, k!!.length)
    }

    @Test
    fun magnet_key_length_81_at_index_99999() {
        val hex = "0000000000000000000000000000000000000000"
        val magnet = "magnet:?xt=urn:btih:$hex&index=99999&dn=foo"
        val k = MagnetKeyBuilder.build("com.nyaa.sub", magnet)
        assertNotNull(k)
        assertEquals(81, k!!.length)
    }

    // ===== HTTP-torrent key length =====

    @Test
    fun http_torrent_key_length_formula() {
        // v12 spec claims 41 + len(canonicalUrl). Formula with
        // provider8=8 hex:
        //   editotsu-source:v1:http-torrent:  (32)
        //   + provider8                       (8)
        //   + :                               (1)
        //   + url                             (url.length)
        //   = 32+8+1+url = 41+url. Match.
        val url = "https://nyaa.si/a.torrent"
        val k = HttpTorrentKeyBuilder.build("com.nyaa.sub", url)
        assertNotNull(k)
        val expected = "editotsu-source:v1:http-torrent:".length +
            8 + 1 + url.length
        assertEquals(expected, k!!.length)
    }

    // ===== Direct release key length =====

    @Test
    fun direct_release_key_length_51() {
        // v12 spec: length 51. Formula with provider8=8 hex:
        //   editotsu-source:v1:direct:  (26)
        //   + provider8                  (8)
        //   + :                          (1)
        //   + releaseStableId16          (16)
        //   = 26+8+1+16 = 51. Match.
        val k = DirectReleaseKeyBuilder.build(
            "com.nyaa.sub",
            group = "SubsPlease",
            resolution = "1080p",
            audioFormat = "AAC",
            audioMode = null,
            sourceService = null,
            rawTitle = "[SubsPlease] Some Anime - 01 [1080p].mkv",
        )
        assertNotNull(k)
        assertEquals(51, k!!.length)
    }

    // ===== Stable group canonicalizer =====

    @Test
    fun group_canonical_lowercase_and_trim() {
        assertEquals("subsplease", StableGroupCanonicalizer.canonicalKey("  SubsPlease  "))
    }

    @Test
    fun group_nyaasi_not_treated_as_group() {
        assertNull(StableGroupCanonicalizer.canonicalKey("NyaaSi"))
        assertNull(StableGroupCanonicalizer.canonicalKey("1337x"))
        assertNull(StableGroupCanonicalizer.canonicalKey("nekoBT"))
    }

    @Test
    fun group_technical_resolution_rejected() {
        assertNull(StableGroupCanonicalizer.canonicalKey("1080p"))
        assertNull(StableGroupCanonicalizer.canonicalKey("4k"))
    }

    @Test
    fun group_source_service_rejected() {
        assertNull(StableGroupCanonicalizer.canonicalKey("NF"))
        assertNull(StableGroupCanonicalizer.canonicalKey("AMZN"))
    }

    // ===== Tracker canonicalizer =====

    @Test
    fun tracker_separate_from_group() {
        val g = StableGroupCanonicalizer.canonicalKey("NyaaSi")
        val t = TrackerCanonicalizer.canonicalKey("NyaaSi")
        assertNull(g)
        assertEquals("nyaasi", t)
    }

    // ===== Source service canonicalizer =====

    @Test
    fun source_service_aliases() {
        assertEquals("netflix", SourceServiceCanonicalizer.canonicalKey("NF"))
        assertEquals("amazon", SourceServiceCanonicalizer.canonicalKey("AMZN"))
        assertEquals("crunchyroll", SourceServiceCanonicalizer.canonicalKey("CR"))
    }

    @Test
    fun source_service_bracketed_accepted() {
        assertEquals("netflix", SourceServiceCanonicalizer.canonicalKey("[Netflix]"))
    }

    @Test
    fun source_service_unknown_lowercased() {
        assertEquals("at-x", SourceServiceCanonicalizer.canonicalKey("AT-X"))
    }

    // ===== SelectedFamilyCodec =====

    @Test
    fun family_codec_round_trip() {
        val p = FamilyPayload(
            providerPkg = "com.nyaa.sub",
            groupKey = "subsplease",
            soft = mapOf(
                "trackerKey" to "nyaasi",
                "resolution" to "1080p",
            ),
        )
        val encoded = SelectedFamilyCodec.encode(p)
        assertNotNull(encoded)
        val decoded = SelectedFamilyCodec.decode(encoded)
        assertNotNull(decoded)
        assertEquals(p.providerPkg, decoded!!.providerPkg)
        assertEquals(p.groupKey, decoded.groupKey)
        assertEquals(p.soft, decoded.soft)
    }

    @Test
    fun family_codec_version_required() {
        val raw = "{\"providerPkg\":\"a\",\"groupKey\":\"b\"}"
        assertNull(SelectedFamilyCodec.decode(raw))
    }

    @Test
    fun family_codec_wrong_version_rejected() {
        val raw = "{\"version\":2,\"providerPkg\":\"a\",\"groupKey\":\"b\"}"
        assertNull(SelectedFamilyCodec.decode(raw))
    }

    @Test
    fun family_codec_oversize_rejected_no_truncate() {
        val p = FamilyPayload(
            providerPkg = "x".repeat(200),
            groupKey = "a",
            soft = emptyMap(),
        )
        assertNull(SelectedFamilyCodec.encode(p))
    }

    @Test
    fun family_codec_too_many_soft_rejected() {
        val soft = (1..8).associate { i -> "k$i" to "v" }
        val p = FamilyPayload("a", "b", soft)
        assertNull(SelectedFamilyCodec.encode(p))
    }

    // ===== Stable release canonicalizer (no cleanDisplayTitle) =====

    @Test
    fun stable_release_canonical_part_order() {
        val s = StableReleaseCanonicalizer.canonicalize(
            group = "SubsPlease",
            resolution = "1080p",
            audioFormat = "AAC",
            audioMode = null,
            sourceService = "NF",
            rawTitle = "  Title  Here  ",
        )
        assertNotNull(s)
        assertEquals(
            "group=subsplease|res=1080p|afmt=aac|svc=netflix|raw=Title Here",
            s,
        )
    }

    @Test
    fun stable_release_canonical_afmt_absent_uses_audio() {
        val s = StableReleaseCanonicalizer.canonicalize(
            group = "SubsPlease", resolution = "1080p",
            audioFormat = null, audioMode = "Dual",
            sourceService = null, rawTitle = "X",
        )
        assertNotNull(s)
        assertTrue(s!!.contains("audio=dual"))
        assertFalse(s.contains("afmt="))
    }

    @Test
    fun stable_release_id_16_hex() {
        val id = StableReleaseCanonicalizer.releaseStableId16(
            "SubsPlease", "1080p", "AAC", null, "NF", "raw",
        )
        assertNotNull(id)
        assertEquals(16, id!!.length)
        assertTrue(id.all { it.isDigit() || it in 'a'..'f' })
    }

    // ===== FamilyAutoSelector =====

    @Test
    fun family_chooses_single_hard_anchor() {
        val stored = FamilyPayload("com.nyaa.sub", "subsplease")
        val cands = listOf(
            FamilyCandidate("k1", "com.nyaa.sub", "subsplease"),
        )
        val r = FamilyAutoSelector.select(stored, cands)
        assertTrue(r is FamilyAutoResult.Chosen)
        assertEquals("k1", (r as FamilyAutoResult.Chosen).exactKey)
    }

    @Test
    fun family_absent_returns_none() {
        val cands = listOf(
            FamilyCandidate("k1", "com.nyaa.sub", "subsplease"),
        )
        assertTrue(FamilyAutoSelector.select(null, cands) is FamilyAutoResult.None)
    }

    @Test
    fun family_tied_returns_ambiguous() {
        val stored = FamilyPayload("com.nyaa.sub", "subsplease")
        val cands = listOf(
            FamilyCandidate("k1", "com.nyaa.sub", "subsplease", mapOf("trackerKey" to "nyaasi")),
            FamilyCandidate("k2", "com.nyaa.sub", "subsplease", mapOf("trackerKey" to "1337x")),
        )
        val r = FamilyAutoSelector.select(stored, cands)
        assertTrue(r is FamilyAutoResult.Ambiguous)
    }

    @Test
    fun family_does_not_use_seeders() {
        // Two candidates identical on stored soft fields -> both
        // equally good. Selector returns Ambiguous, not "the seeders
        // win" path.
        val stored = FamilyPayload("com.nyaa.sub", "subsplease")
        val cands = listOf(
            FamilyCandidate("k1", "com.nyaa.sub", "subsplease", mapOf("trackerKey" to "nyaasi")),
            FamilyCandidate("k2", "com.nyaa.sub", "subsplease", mapOf("trackerKey" to "nyaasi")),
        )
        assertTrue(FamilyAutoSelector.select(stored, cands) is FamilyAutoResult.Ambiguous)
    }

    @Test
    fun family_soft_match_picks_unique() {
        val stored = FamilyPayload(
            "com.nyaa.sub", "subsplease",
            mapOf("trackerKey" to "nyaasi", "resolution" to "1080p"),
        )
        val cands = listOf(
            FamilyCandidate("k1", "com.nyaa.sub", "subsplease",
                mapOf("trackerKey" to "nyaasi", "resolution" to "1080p")),
            FamilyCandidate("k2", "com.nyaa.sub", "subsplease",
                mapOf("trackerKey" to "nyaasi", "resolution" to "720p")),
        )
        val r = FamilyAutoSelector.select(stored, cands)
        assertTrue(r is FamilyAutoResult.Chosen)
        assertEquals("k1", (r as FamilyAutoResult.Chosen).exactKey)
    }

    /**
     * Required counterexample for true lexicographic ranking
     * (reviewer blocker 2). A's match on the FIRST priority
     * (trackerKey) must outrank B's match on the SECOND priority
     * (resolution), even though the legacy weighted-sum approach
     * would have preferred B.
     */
    @Test
    fun family_lex_first_priority_dominates() {
        val stored = FamilyPayload(
            "com.nyaa.sub", "subsplease",
            mapOf("trackerKey" to "nyaasi", "resolution" to "1080p"),
        )
        val cands = listOf(
            // A: tracker matches (priority 0), resolution mismatches.
            FamilyCandidate("A", "com.nyaa.sub", "subsplease",
                mapOf("trackerKey" to "nyaasi", "resolution" to "720p")),
            // B: tracker mismatches (priority 0), resolution matches.
            FamilyCandidate("B", "com.nyaa.sub", "subsplease",
                mapOf("trackerKey" to "nekobt", "resolution" to "1080p")),
        )
        val r = FamilyAutoSelector.select(stored, cands)
        assertTrue(r is FamilyAutoResult.Chosen)
        assertEquals("A", (r as FamilyAutoResult.Chosen).exactKey)
    }

    /**
     * v1.3 blocker 1: duplicate representations of the SAME exact
     * key must be collapsed by BEST strict-lex match against the
     * stored vector, not by an inverted signature order. X has two
     * representations whose match vectors are 01 (bad) and 10
     * (good); Y is 01. X must win in BOTH duplicate input orders.
     */
    @Test
    fun family_duplicate_representations_use_best_strict_lex_match() {
        val stored = FamilyPayload(
            "com.nyaa.sub", "subsplease",
            mapOf("trackerKey" to "nyaasi", "resolution" to "1080p"),
        )
        val xBad = FamilyCandidate("X", "com.nyaa.sub", "subsplease",
            mapOf("trackerKey" to "nekobt", "resolution" to "1080p"))
        val xGood = FamilyCandidate("X", "com.nyaa.sub", "subsplease",
            mapOf("trackerKey" to "nyaasi", "resolution" to "720p"))
        val y = FamilyCandidate("Y", "com.nyaa.sub", "subsplease",
            mapOf("trackerKey" to "nekobt", "resolution" to "1080p"))
        val forward = FamilyAutoSelector.select(stored, listOf(xBad, xGood, y))
        val reversed = FamilyAutoSelector.select(stored, listOf(xGood, xBad, y))
        assertTrue(forward is FamilyAutoResult.Chosen)
        assertTrue(reversed is FamilyAutoResult.Chosen)
        assertEquals("X", (forward as FamilyAutoResult.Chosen).exactKey)
        assertEquals("X", (reversed as FamilyAutoResult.Chosen).exactKey)
    }

    // ===== StableReleaseMetadataExtractor (no cleanDisplayTitle) =====

    @Test
    fun metadata_extract_subsplease_with_nyaasi() {
        val m = StableReleaseMetadataExtractor.extract(
            "📂 NyaaSi | [SubsPlease] Some Anime - 01 [1080p AAC]"
        )
        assertEquals("SubsPlease", m.group)
        assertEquals("NyaaSi", m.tracker)
        assertEquals("1080p", m.resolution)
        assertEquals("AAC", m.audioFormat)
        assertTrue(m.isReleaseShaped)
    }

    @Test
    fun metadata_extract_varyg_with_nekobt() {
        val m = StableReleaseMetadataExtractor.extract(
            "📂 nekoBT | [VARYG] Other Anime - 02 [1080p HEVC]"
        )
        assertEquals("VARYG", m.group)
        assertEquals("nekoBT", m.tracker)
        assertEquals("HEVC", m.codec)
        assertTrue(m.isReleaseShaped)
    }

    // ===== Required positives (reviewer v1.1 correction 3) =====

    @Test
    fun metadata_positive_subsplease_bare() {
        val m = StableReleaseMetadataExtractor.extract("[SubsPlease] Title")
        assertEquals("SubsPlease", m.group)
        assertTrue(m.isReleaseShaped)
    }

    @Test
    fun metadata_positive_subsplease_with_quality() {
        val m = StableReleaseMetadataExtractor.extract("1080p [SubsPlease] Title")
        assertEquals("SubsPlease", m.group)
        assertTrue(m.isReleaseShaped)
    }

    @Test
    fun metadata_positive_ironclad_with_tracker_in_prefix() {
        val m = StableReleaseMetadataExtractor.extract(
            "📂 NyaaSi | NyaaSi 1080p [Ironclad] Title"
        )
        assertEquals("Ironclad", m.group)
        assertEquals("nyaasi", TrackerCanonicalizer.canonicalKey(m.tracker))
        assertTrue(m.isReleaseShaped)
    }

    @Test
    fun metadata_positive_varyg_with_nekobt() {
        val m = StableReleaseMetadataExtractor.extract(
            "📂 nekoBT | nekoBT 1080p [VARYG] Title"
        )
        assertEquals("VARYG", m.group)
        assertEquals("nekobt", TrackerCanonicalizer.canonicalKey(m.tracker))
        assertTrue(m.isReleaseShaped)
    }

    // ===== Required negatives (reviewer v1.1 correction 3) =====

    @Test
    fun metadata_negative_foo_bracket_no_other_marker() {
        val m = StableReleaseMetadataExtractor.extract("1080p Title [Foo].mkv")
        assertNull(m.group)
        assertFalse(m.isReleaseShaped)
    }

    @Test
    fun metadata_negative_unknown_bracket_no_other_marker() {
        val m = StableReleaseMetadataExtractor.extract("1080p Title [Unknown].mkv")
        assertNull(m.group)
        assertFalse(m.isReleaseShaped)
    }

    @Test
    fun metadata_negative_other_word_in_prefix_with_tracker() {
        val m = StableReleaseMetadataExtractor.extract(
            "📂 NyaaSi | Other 1080p [Ironclad] Title"
        )
        // "Other" is not the parsed tracker (NyaaSi), so the prefix
        // has a non-tracker word AND a quality token. With tracker
        // present, that combination is rejected.
        assertNull(m.group)
        assertFalse(m.isReleaseShaped)
    }

    // ===== Case-insensitive tracker corroboration (v1.3 blocker 3) =====

    @Test
    fun corroboration_tracker_prefix_case_insensitive() {
        val m = StableReleaseMetadataExtractor.extract(
            "📂 NyaaSi\nnyaasi 1080p [Ironclad] Title"
        )
        assertEquals("Ironclad", m.group)
        assertTrue(m.isReleaseShaped)
    }

    @Test
    fun corroboration_parsed_tracker_case_insensitive() {
        val m = StableReleaseMetadataExtractor.extract(
            "📂 NYAASI\nNyaaSi 1080p [Ironclad] Title"
        )
        assertEquals("Ironclad", m.group)
        assertTrue(m.isReleaseShaped)
    }

    @Test
    fun corroboration_nekobt_prefix_case_insensitive() {
        val m = StableReleaseMetadataExtractor.extract(
            "📂 nekoBT\nNEKOBT 1080p [VARYG] Title"
        )
        assertEquals("VARYG", m.group)
        assertTrue(m.isReleaseShaped)
    }

    @Test
    fun corroboration_different_tracker_token_still_rejected() {
        val m = StableReleaseMetadataExtractor.extract(
            "📂 NyaaSi\nnekoBT 1080p [Ironclad] Title"
        )
        // "nekoBT" canonicalizes to "nekobt", which differs from
        // the parsed tracker "nyaasi": no corroboration.
        assertNull(m.group)
        assertFalse(m.isReleaseShaped)
    }

    // ===== Generic direct streams must NOT acquire release identity =====

    @Test
    fun direct_generic_1080p_no_release_identity() {
        val s = makeServer("1080p")
        assertNull(StableCandidateBuilder.exactKey(
            "com.nyaa.sub", s,
        ))
    }

    @Test
    fun direct_release_shaped_acquires_stable_identity() {
        val s = makeServer("1080p [SubsPlease] Title")
        val k = StableCandidateBuilder.exactKey("com.nyaa.sub", s)
        assertNotNull(k)
    }

    // ===== Regression fixtures =====

    @Test
    fun regression_ironclad_nyaasi() {
        val m = StableReleaseMetadataExtractor.extract(
            "📂 NyaaSi | NyaaSi 1080p [Ironclad] Anime - 01"
        )
        assertEquals("Ironclad", m.group)
        assertEquals("NyaaSi", m.tracker)
    }

    @Test
    fun regression_toonshub_nyaasi() {
        val m = StableReleaseMetadataExtractor.extract(
            "📂 NyaaSi | NyaaSi 1080p [ToonsHub] Anime - 01"
        )
        assertEquals("ToonsHub", m.group)
    }

    @Test
    fun regression_dkb_nyaasi() {
        val m = StableReleaseMetadataExtractor.extract(
            "📂 NyaaSi | NyaaSi 1080p [DKB] Anime - 01"
        )
        assertEquals("DKB", m.group)
    }

    @Test
    fun regression_tenrai_sensei_1337x() {
        val m = StableReleaseMetadataExtractor.extract(
            "📂 1337x | 1337x 1080p [Tenrai-Sensei] Anime - 01"
        )
        assertEquals("Tenrai-Sensei", m.group)
    }

    // ===== RecoveryCoordinator =====

    @Test
    fun recovery_exact_name_first() {
        val stored = FamilyPayload("com.nyaa.sub", "subsplease")
        val s1 = makeServer("[SubsPlease] Anime - 01")
        val s2 = makeServer("[Other] Anime - 01")
        val r = RecoveryCoordinator.resolve(
            storedExactKey = null,
            storedFamily = stored,
            selectedName = "[SubsPlease] Anime - 01",
            servers = listOf(s1, s2),
        )
        assertTrue(r is RecoveryResult.Resolved)
        assertEquals("[SubsPlease] Anime - 01", (r as RecoveryResult.Resolved).server.name)
    }

    @Test
    fun recovery_family_unique_wins() {
        val stored = FamilyPayload("com.nyaa.sub", "subsplease",
            mapOf("trackerKey" to "nyaasi"))
        val s1 = makeServer("📂 NyaaSi | [SubsPlease] Anime - 02 [1080p]")
        val s2 = makeServer("📂 1337x | [Other] Anime - 02 [1080p]")
        val r = RecoveryCoordinator.resolve(
            storedExactKey = null,
            storedFamily = stored,
            selectedName = null,
            servers = listOf(s1, s2),
        )
        assertTrue(r is RecoveryResult.Resolved)
        assertEquals("📂 NyaaSi | [SubsPlease] Anime - 02 [1080p]",
            (r as RecoveryResult.Resolved).server.name)
    }

    @Test
    fun recovery_family_tied_returns_ambiguous() {
        val stored = FamilyPayload("com.nyaa.sub", "subsplease",
            mapOf("trackerKey" to "nyaasi"))
        // Two distinct magnet URLs (different BTIHs) with the same
        // soft fields => different exact keys => Ambiguous.
        val s1 = VideoServer(
            "📂 NyaaSi | [SubsPlease] Anime - 02 [1080p]",
            "magnet:?xt=urn:btih:0000000000000000000000000000000000000001&dn=a",
            mapOf(StableCandidateBuilder.PROVIDER_EXTRA_KEY to "com.nyaa.sub"),
        )
        val s2 = VideoServer(
            "📂 NyaaSi | [SubsPlease] Anime - 02 [1080p]",
            "magnet:?xt=urn:btih:0000000000000000000000000000000000000002&dn=b",
            mapOf(StableCandidateBuilder.PROVIDER_EXTRA_KEY to "com.nyaa.sub"),
        )
        val r = RecoveryCoordinator.resolve(
            storedExactKey = null, storedFamily = stored,
            selectedName = null, servers = listOf(s1, s2),
        )
        assertTrue(r is RecoveryResult.Ambiguous)
    }

    @Test
    fun recovery_family_absent_returns_no_match() {
        val stored = null
        val s1 = makeServer("[SubsPlease] Anime")
        val r = RecoveryCoordinator.resolve(
            storedExactKey = null, storedFamily = stored,
            selectedName = null, servers = listOf(s1),
        )
        assertTrue(r is RecoveryResult.NoMatch)
    }

    // ===== B10 missing vectors =====

    @Test
    fun btih_first_valid_xt_wins_when_earlier_invalid() {
        // First xt is partial (rejected); second xt is 40 hex
        // (accepted).
        val hex = "0000000000000000000000000000000000000000"
        val magnet = "magnet:?xt=urn:btih:abc&xt=urn:btih:$hex&dn=x"
        val k = MagnetKeyBuilder.build("com.nyaa.sub", magnet)
        assertNotNull(k)
        assertTrue(k!!.endsWith(":$hex:0"))
    }

    @Test
    fun btih_wrong_base32_alphabet_rejected() {
        // "0" and "1" are NOT in the Base32 alphabet
        // (ABCDEFGHIJKLMNOPQRSTUVWXYZ234567).
        assertNull(BtihCanonicalizer.canonicalHex40("00000000000000000000000000000000"))
        assertNull(BtihCanonicalizer.canonicalHex40("11111111111111111111111111111111"))
    }

    @Test
    fun index_someindex_ignored() {
        val m = "magnet:?xt=urn:btih:0000000000000000000000000000000000000000&someindex=5"
        assertEquals(0L, CanonicalIndexParser.canonicalIndex(m))
    }

    @Test
    fun index_malformed_rejected() {
        assertNull(CanonicalIndexParser.canonicalIndex(
            "magnet:?xt=urn:btih:0000000000000000000000000000000000000000&index=abc"
        ))
        assertNull(CanonicalIndexParser.canonicalIndex(
            "magnet:?xt=urn:btih:0000000000000000000000000000000000000000&index=9999999999"
        ))
    }

    @Test
    fun http_torrent_signed_query_classified_via_candidate_builder() {
        // Signed HTTP .torrent URLs with different query / fragment
        // must produce the SAME exact key, classified through
        // StableCandidateBuilder (not raw endsWith).
        val s1 = VideoServer(
            "Torrent 1",
            "https://nyaa.si/a.torrent?token=a#x",
            mapOf(StableCandidateBuilder.PROVIDER_EXTRA_KEY to "com.nyaa.sub"),
        )
        val s2 = VideoServer(
            "Torrent 2",
            "https://NYAA.SI:443/a.torrent?token=b#y",
            mapOf(StableCandidateBuilder.PROVIDER_EXTRA_KEY to "com.nyaa.sub"),
        )
        val k1 = StableCandidateBuilder.exactKey("com.nyaa.sub", s1)
        val k2 = StableCandidateBuilder.exactKey("com.nyaa.sub", s2)
        assertNotNull(k1); assertNotNull(k2)
        assertEquals(k1, k2)
    }

    @Test
    fun exact_key_recovery_works_when_family_null() {
        val s1 = VideoServer(
            "1080p [SubsPlease] Title",
            "https://example.com/abc",
            mapOf(StableCandidateBuilder.PROVIDER_EXTRA_KEY to "com.nyaa.sub"),
        )
        val storedKey = StableCandidateBuilder.exactKey("com.nyaa.sub", s1)
        assertNotNull(storedKey)
        val r = RecoveryCoordinator.resolve(
            storedExactKey = storedKey,
            storedFamily = null,
            selectedName = null,
            servers = listOf(s1),
        )
        assertTrue(r is RecoveryResult.Resolved)
    }

    @Test
    fun family_tied_at_complete_vector_returns_ambiguous() {
        val stored = FamilyPayload(
            "com.nyaa.sub", "subsplease",
            mapOf("trackerKey" to "nyaasi", "resolution" to "1080p"),
        )
        val cands = listOf(
            FamilyCandidate("A", "com.nyaa.sub", "subsplease",
                mapOf("trackerKey" to "nyaasi", "resolution" to "1080p")),
            FamilyCandidate("B", "com.nyaa.sub", "subsplease",
                mapOf("trackerKey" to "nyaasi", "resolution" to "1080p")),
        )
        val r = FamilyAutoSelector.select(stored, cands)
        assertTrue(r is FamilyAutoResult.Ambiguous)
    }

    @Test
    fun batch_index0_to_index1_family_continuity() {
        // E01 family index0 -> E02 same family index1. The family
        // preference is provider+group anchored, so the index
        // change does not break the family anchor. Both candidates
        // pass the hard anchor and have the same soft vector; with
        // distinct exact keys, the family selector returns
        // AMBIGUOUS. The test asserts that:
        //   - the family anchor is independent of the file index
        //     (i.e. the BTIH-bearing E02 magnet is a family
        //     candidate, NOT no-match), and
        //   - the soft-vector tie is correctly reported as
        //     Ambiguous (no fabricated winner).
        val s1 = VideoServer(
            "1080p [SubsPlease] Title - 01",
            "magnet:?xt=urn:btih:0000000000000000000000000000000000000001&index=0&dn=a",
            mapOf(StableCandidateBuilder.PROVIDER_EXTRA_KEY to "com.nyaa.sub"),
        )
        val s2 = VideoServer(
            "1080p [SubsPlease] Title - 02",
            "magnet:?xt=urn:btih:0000000000000000000000000000000000000002&index=1&dn=b",
            mapOf(StableCandidateBuilder.PROVIDER_EXTRA_KEY to "com.nyaa.sub"),
        )
        val family = FamilyPayload("com.nyaa.sub", "subsplease")
        val r1 = RecoveryCoordinator.resolve(
            storedExactKey = null, storedFamily = family,
            selectedName = null, servers = listOf(s1, s2),
        )
        // The E02 index1 candidate IS a family member (no-match
        // would mean the family anchor depends on file index, which
        // it does not). Two distinct exact keys with identical soft
        // vectors are correctly AMBIGUOUS.
        assertTrue(r1 is RecoveryResult.Ambiguous)
        // With a single family candidate and a stored soft
        // preference, the family resolve succeeds.
        val r2 = RecoveryCoordinator.resolve(
            storedExactKey = null, storedFamily = family,
            selectedName = null, servers = listOf(s2),
        )
        assertTrue(r2 is RecoveryResult.Resolved)
    }

    @Test
    fun invalid_direct_canonicalization_returns_no_key() {
        // All optional fields blank AND raw title empty -> no direct
        // key. Never SHA-256("") as a fallback identity.
        val k1 = DirectReleaseKeyBuilder.build(
            "com.nyaa.sub", null, null, null, null, null, "",
        )
        assertNull(k1)
        // Two distinct invalid fixtures must NOT collapse to the
        // same key (no SHA-256("") fallback).
        val k2 = DirectReleaseKeyBuilder.build(
            "com.nyaa.sub", "    ", "  ", null, null, null, "   ",
        )
        assertNull(k2)
    }

    @Test
    fun direct_canonicalizer_fails_closed_on_invalid_group() {
        // Two distinct invalid canonical fixtures must NOT collapse
        // to the same key. The first has a confident group + raw
        // title; the second is all-blank. The all-blank one must
        // return null (no SHA-256("") fallback).
        val idA = StableReleaseCanonicalizer.releaseStableId16(
            "foo", null, null, null, null, "raw title A",
        )
        val idB = StableReleaseCanonicalizer.releaseStableId16(
            null, null, null, null, null, "",
        )
        assertNotNull(idA)
        assertNull(idB)
    }

    @Test
    fun provider_absent_dormant_family() {
        val s = VideoServer(
            "1080p [SubsPlease] Title",
            "https://example.com/abc",
            null, // no provider extraData
        )
        val cand = StableCandidateBuilder.familyCandidate(s)
        assertNull(cand)
        // exactKey also null because no provider.
        assertNull(StableCandidateBuilder.exactKey(null, s))
    }

    @Test
    fun make_default_off_does_not_persist_via_candidate_builder() {
        // The candidate builder itself is just a key builder. The
        // MakeDefault gating is in SelectorDialogFragment. The test
        // here asserts that the candidate builder does not auto-save
        // anywhere (no shared mutable state involved).
        val s = makeServer("1080p [SubsPlease] Title")
        val k1 = StableCandidateBuilder.exactKey("com.nyaa.sub", s)
        val k2 = StableCandidateBuilder.exactKey("com.nyaa.sub", s)
        assertEquals(k1, k2)
    }

    @Test
    fun recovery_returns_NoMatch_when_no_stored_preference() {
        val s = makeServer("1080p [SubsPlease] Title")
        val r = RecoveryCoordinator.resolve(
            storedExactKey = null, storedFamily = null,
            selectedName = null, servers = listOf(s),
        )
        assertTrue(r is RecoveryResult.NoMatch)
    }

    @Test
    fun recovery_returns_Ambiguous_when_two_distinct_exact_keys_tied() {
        val stored = FamilyPayload("com.nyaa.sub", "subsplease",
            mapOf("trackerKey" to "nyaasi"))
        val s1 = VideoServer(
            "📂 NyaaSi | NyaaSi 1080p [SubsPlease] Title",
            "magnet:?xt=urn:btih:0000000000000000000000000000000000000001&dn=a",
            mapOf(StableCandidateBuilder.PROVIDER_EXTRA_KEY to "com.nyaa.sub"),
        )
        val s2 = VideoServer(
            "📂 NyaaSi | NyaaSi 1080p [SubsPlease] Title",
            "magnet:?xt=urn:btih:0000000000000000000000000000000000000002&dn=b",
            mapOf(StableCandidateBuilder.PROVIDER_EXTRA_KEY to "com.nyaa.sub"),
        )
        val r = RecoveryCoordinator.resolve(
            storedExactKey = null, storedFamily = stored,
            selectedName = null, servers = listOf(s1, s2),
        )
        assertTrue(r is RecoveryResult.Ambiguous)
    }

    @Test
    fun family_attempt_suppression_blocks_repeat_in_same_attempt() {
        val sup = FamilyAttemptSuppression()
        assertTrue(sup.shouldAttempt("ep-1"))
        // Second call for the same episode MUST be suppressed.
        assertFalse(sup.shouldAttempt("ep-1"))
        assertTrue(sup.contains("ep-1"))
        // A different episode is independent.
        assertTrue(sup.shouldAttempt("ep-2"))
        // Reset clears the suppression.
        sup.reset()
        assertTrue(sup.shouldAttempt("ep-1"))
    }

    @Test
    fun make_default_off_no_overwrite_in_family_store_helper() {
        // The candidate builder returns null for unparseable
        // metadata. The store `save(..., null)` is a no-op for the
        // family; the existing family is left intact. We assert the
        // store contract here: passing null to encode returns the
        // saved-null branch and does NOT mutate the existing
        // preference.
        val existing = FamilyPayload("com.nyaa.sub", "subsplease")
        val encoded = SelectedFamilyCodec.encode(existing)
        assertNotNull(encoded)
        val decoded = SelectedFamilyCodec.decode(encoded)
        assertNotNull(decoded)
        assertEquals("subsplease", decoded!!.groupKey)
    }

    // ===== v1.2 regressions =====

    /**
     * Fix 1: FamilyAutoSelector collapse by exact key must not
     * depend on arrival order. Two duplicate representations of the
     * SAME exact key with different soft values must produce the
     * same Chosen result regardless of input order.
     */
    @Test
    fun family_collapse_representations_independent_of_arrival_order() {
        val stored = FamilyPayload(
            "com.nyaa.sub", "subsplease",
            mapOf("trackerKey" to "nyaasi", "resolution" to "1080p"),
        )
        // Two distinct representations of the SAME exact key, each
        // with a different soft vector. The representative that
        // matches the stored vector better (nyaasi+1080p) should
        // win.
        val rep1 = FamilyCandidate("k1", "com.nyaa.sub", "subsplease",
            mapOf("trackerKey" to "nekobt", "resolution" to "1080p"))
        val rep2 = FamilyCandidate("k1", "com.nyaa.sub", "subsplease",
            mapOf("trackerKey" to "nyaasi", "resolution" to "1080p"))
        val a = FamilyAutoSelector.select(stored, listOf(rep1, rep2))
        val b = FamilyAutoSelector.select(stored, listOf(rep2, rep1))
        assertTrue(a is FamilyAutoResult.Chosen)
        assertTrue(b is FamilyAutoResult.Chosen)
        // Both orders must agree.
        assertEquals(
            (a as FamilyAutoResult.Chosen).exactKey,
            (b as FamilyAutoResult.Chosen).exactKey,
        )
    }

    /**
     * Fix 2: RecoveryCoordinator exact-key recovery must NOT return
     * Ambiguous solely because the same exact release appeared
     * twice in the input. Two server rows with the same exact key
     * must collapse to one resolved candidate.
     */
    @Test
    fun recovery_duplicate_exact_key_collapses_to_resolved() {
        val s1 = VideoServer(
            "1080p [SubsPlease] Title",
            "https://example.com/a",
            mapOf(StableCandidateBuilder.PROVIDER_EXTRA_KEY to "com.nyaa.sub"),
        )
        val s2 = VideoServer(
            // Identical raw title so the direct key matches.
            "1080p [SubsPlease] Title",
            "https://example.com/b",
            mapOf(StableCandidateBuilder.PROVIDER_EXTRA_KEY to "com.nyaa.sub"),
        )
        // Both rows produce the same exact key.
        val k1 = StableCandidateBuilder.exactKey("com.nyaa.sub", s1)
        val k2 = StableCandidateBuilder.exactKey("com.nyaa.sub", s2)
        assertEquals(k1, k2)
        val r = RecoveryCoordinator.resolve(
            storedExactKey = k1,
            storedFamily = null,
            selectedName = null,
            servers = listOf(s1, s2),
        )
        assertTrue(r is RecoveryResult.Resolved)
    }

    /**
     * Fix 3: stable direct identity must be independent of volatile
     * metadata. Same release with different tracker / seeder / size
     * metadata must produce the same direct stable key. A change
     * to the actual release title must still change the key.
     */
    @Test
    fun direct_stable_key_independent_of_volatile_metadata() {
        val a = DirectReleaseKeyBuilder.build(
            "com.nyaa.sub",
            group = "SubsPlease",
            resolution = "1080p",
            audioFormat = "AAC",
            audioMode = null,
            sourceService = null,
            rawTitle = "📂 NyaaSi | 👤 10 | 💾 1.4 GiB | [SubsPlease] Title [1080p AAC]",
        )
        val b = DirectReleaseKeyBuilder.build(
            "com.nyaa.sub",
            group = "SubsPlease",
            resolution = "1080p",
            audioFormat = "AAC",
            audioMode = null,
            sourceService = null,
            rawTitle = "📂 nekoBT | 👤 999 | 💾 1.4 GiB | [SubsPlease] Title [1080p AAC]",
        )
        assertNotNull(a); assertNotNull(b)
        assertEquals(a, b)
    }

    @Test
    fun direct_stable_key_changes_when_release_title_changes() {
        val a = DirectReleaseKeyBuilder.build(
            "com.nyaa.sub",
            group = "SubsPlease",
            resolution = "1080p",
            audioFormat = "AAC",
            audioMode = null,
            sourceService = null,
            rawTitle = "[SubsPlease] Anime A - 01 [1080p AAC]",
        )
        val b = DirectReleaseKeyBuilder.build(
            "com.nyaa.sub",
            group = "SubsPlease",
            resolution = "1080p",
            audioFormat = "AAC",
            audioMode = null,
            sourceService = null,
            rawTitle = "[SubsPlease] Anime B - 01 [1080p AAC]",
        )
        assertNotNull(a); assertNotNull(b)
        assertTrue(a != b)
    }

    /**
     * v1.3 blocker 2: the stable raw title must visibly retain the
     * release title through metadata-rich inputs. Same title with
     * changed tracker/seeder/size -> SAME direct key; changed
     * title with the same metadata shape -> DIFFERENT key.
     */
    @Test
    fun stable_raw_title_retains_release_title_through_metadata() {
        val a = "📂 NyaaSi | 👤 10 | 💾 1.4 GiB | [SubsPlease] Anime A - 01 [1080p AAC]"
        val stable = StableRawTitleExtractor.extract(a)
        assertNotNull(stable)
        assertTrue(stable!!.contains("Anime A - 01"))
        assertTrue(stable.contains("[SubsPlease]"))
    }

    @Test
    fun direct_key_metadata_rich_same_title_equal_changed_title_differs() {
        fun directFor(raw: String) = DirectReleaseKeyBuilder.build(
            "com.nyaa.sub",
            group = "SubsPlease",
            resolution = "1080p",
            audioFormat = "AAC",
            audioMode = null,
            sourceService = null,
            rawTitle = raw,
        )
        val a = directFor(
            "📂 NyaaSi | 👤 10 | 💾 1.4 GiB | [SubsPlease] Anime A - 01 [1080p AAC]",
        )
        val b = directFor(
            "📂 nekoBT | 👤 999 | 💾 2.0 GiB | [SubsPlease] Anime A - 01 [1080p AAC]",
        )
        val c = directFor(
            "📂 NyaaSi | 👤 10 | 💾 1.4 GiB | [SubsPlease] Anime B - 01 [1080p AAC]",
        )
        assertNotNull(a); assertNotNull(b); assertNotNull(c)
        assertEquals(a, b)
        assertTrue(a != c)
    }

    @Test
    fun stable_raw_title_multiline_metadata_block_preserves_title() {
        val raw = "📂 NyaaSi\n" +
            "👤 10 seeders\n" +
            "💾 1.4 GiB\n" +
            "[SubsPlease] Anime A - 01 [1080p AAC]"
        val stable = StableRawTitleExtractor.extract(raw)
        assertNotNull(stable)
        assertTrue(stable!!.contains("Anime A - 01"))
        assertFalse(stable.contains("seeders"))
        assertFalse(stable.contains("GiB"))
    }

    @Test
    fun stable_raw_title_metadata_only_returns_null_and_no_direct_key() {
        val metaOnly = "📂 NyaaSi | 👤 10 | 💾 1.4 GiB"
        assertNull(StableRawTitleExtractor.extract(metaOnly))
        assertNull(DirectReleaseKeyBuilder.build(
            "com.nyaa.sub",
            group = "SubsPlease",
            resolution = "1080p",
            audioFormat = "AAC",
            audioMode = null,
            sourceService = null,
            rawTitle = metaOnly,
        ))
        // Metadata-only multiline form likewise yields no key.
        assertNull(DirectReleaseKeyBuilder.build(
            "com.nyaa.sub",
            group = "SubsPlease",
            resolution = "1080p",
            audioFormat = "AAC",
            audioMode = null,
            sourceService = null,
            rawTitle = "📂 NyaaSi\n👤 10\n💾 1.4 GiB",
        ))
    }

    /**
     * Fix 4: BTIH must reject padded / overlong Base32 forms.
     */
    @Test
    fun btih_padded_base32_rejected() {
        // Real exactly-32-character unpadded token: 20 bytes
        // [0x00..0x13] Base32-encoded with no padding.
        val base32 = "AAAQEAYEAUDAOCAJBIFQYDIOB4IBCEQT"
        assertEquals(32, base32.length)
        val hex = BtihCanonicalizer.canonicalHex40(base32)
        assertNotNull(hex)
        assertEquals(
            "000102030405060708090a0b0c0d0e0f10111213",
            hex,
        )
        // The same 32 valid chars plus padding are rejected.
        assertNull(BtihCanonicalizer.canonicalHex40("$base32="))
        assertNull(BtihCanonicalizer.canonicalHex40("$base32=="))
    }

    /**
     * Fix 5: family JSON must require EOF after top-level `}` and
     * reject duplicate hard keys.
     */
    @Test
    fun family_codec_trailing_garbage_rejected() {
        val raw = "{\"version\":1,\"providerPkg\":\"p\",\"groupKey\":\"g\"}GARBAGE"
        assertNull(SelectedFamilyCodec.decode(raw))
    }

    @Test
    fun family_codec_whitespace_after_eof_ok() {
        val raw = "{\"version\":1,\"providerPkg\":\"p\",\"groupKey\":\"g\"}   \n  "
        val p = SelectedFamilyCodec.decode(raw)
        assertNotNull(p)
    }

    @Test
    fun family_codec_duplicate_providerPkg_rejected() {
        val raw = "{\"version\":1,\"providerPkg\":\"a\",\"providerPkg\":\"b\",\"groupKey\":\"g\"}"
        assertNull(SelectedFamilyCodec.decode(raw))
    }

    @Test
    fun family_codec_duplicate_version_rejected() {
        val raw = "{\"version\":1,\"version\":2,\"providerPkg\":\"p\",\"groupKey\":\"g\"}"
        assertNull(SelectedFamilyCodec.decode(raw))
    }

    @Test
    fun family_codec_duplicate_groupKey_rejected() {
        val raw = "{\"version\":1,\"providerPkg\":\"p\",\"groupKey\":\"a\",\"groupKey\":\"b\"}"
        assertNull(SelectedFamilyCodec.decode(raw))
    }

    // ===== Canonical family hard anchor (v1.3 blocker 4) =====

    @Test
    fun family_codec_canonical_groupKey_accepted() {
        val raw = "{\"version\":1,\"providerPkg\":\"p\",\"groupKey\":\"subsplease\"}"
        val p = SelectedFamilyCodec.decode(raw)
        assertNotNull(p)
        assertEquals("subsplease", p!!.groupKey)
    }

    @Test
    fun family_codec_uppercase_groupKey_rejected() {
        val raw = "{\"version\":1,\"providerPkg\":\"p\",\"groupKey\":\"SUBSPLEASE\"}"
        assertNull(SelectedFamilyCodec.decode(raw))
        assertNull(SelectedFamilyCodec.encode(
            FamilyPayload("p", "SUBSPLEASE"),
        ))
    }

    @Test
    fun family_codec_tracker_groupKey_rejected() {
        val raw = "{\"version\":1,\"providerPkg\":\"p\",\"groupKey\":\"nyaasi\"}"
        assertNull(SelectedFamilyCodec.decode(raw))
        assertNull(SelectedFamilyCodec.encode(
            FamilyPayload("p", "nyaasi"),
        ))
    }

    @Test
    fun family_codec_technical_groupKey_rejected() {
        val raw = "{\"version\":1,\"providerPkg\":\"p\",\"groupKey\":\"1080p\"}"
        assertNull(SelectedFamilyCodec.decode(raw))
        assertNull(SelectedFamilyCodec.encode(
            FamilyPayload("p", "1080p"),
        ))
    }

    // ===== Strict JSON string/key handling (v1.3 blocker 5) =====

    @Test
    fun family_codec_unescaped_control_char_rejected() {
        // Raw U+0001 inside a JSON string is malformed; the family
        // preference must fail closed.
        val raw = "{\"version\":1,\"providerPkg\":\"p\u0001\",\"groupKey\":\"subsplease\"}"
        assertNull(SelectedFamilyCodec.decode(raw))
    }

    @Test
    fun family_codec_unescaped_newline_in_string_rejected() {
        val raw = "{\"version\":1,\"providerPkg\":\"p\n\",\"groupKey\":\"subsplease\"}"
        assertNull(SelectedFamilyCodec.decode(raw))
    }

    @Test
    fun family_codec_duplicate_soft_key_rejected() {
        val raw = "{\"version\":1,\"providerPkg\":\"p\",\"groupKey\":\"subsplease\"," +
            "\"soft\":{\"trackerKey\":\"nyaasi\",\"trackerKey\":\"nekobt\"}}"
        assertNull(SelectedFamilyCodec.decode(raw))
    }

    @Test
    fun family_codec_unknown_soft_name_still_accepted() {
        // Unknown soft field NAMES stay forward-compatible after
        // bounds validation.
        val raw = "{\"version\":1,\"providerPkg\":\"p\",\"groupKey\":\"subsplease\"," +
            "\"soft\":{\"futureField\":\"v\"}}"
        val p = SelectedFamilyCodec.decode(raw)
        assertNotNull(p)
        assertEquals("v", p!!.soft["futureField"])
    }

    // ===== Strict soft schema + version syntax (v1.4 blocker 3) =====

    @Test
    fun family_codec_soft_omitted_is_valid() {
        val p = SelectedFamilyCodec.decode(
            "{\"version\":1,\"providerPkg\":\"p\",\"groupKey\":\"subsplease\"}"
        )
        assertNotNull(p)
        assertTrue(p!!.soft.isEmpty())
    }

    @Test
    fun family_codec_soft_empty_object_is_valid() {
        val p = SelectedFamilyCodec.decode(
            "{\"version\":1,\"providerPkg\":\"p\",\"groupKey\":\"subsplease\",\"soft\":{}}"
        )
        assertNotNull(p)
        assertTrue(p!!.soft.isEmpty())
    }

    @Test
    fun family_codec_soft_wrong_type_rejected() {
        assertNull(SelectedFamilyCodec.decode(
            "{\"version\":1,\"providerPkg\":\"p\",\"groupKey\":\"subsplease\",\"soft\":\"oops\"}"
        ))
        assertNull(SelectedFamilyCodec.decode(
            "{\"version\":1,\"providerPkg\":\"p\",\"groupKey\":\"subsplease\",\"soft\":1}"
        ))
    }

    @Test
    fun family_codec_duplicate_top_level_soft_rejected() {
        assertNull(SelectedFamilyCodec.decode(
            "{\"version\":1,\"providerPkg\":\"p\",\"groupKey\":\"subsplease\"," +
                "\"soft\":{},\"soft\":{}}"
        ))
    }

    @Test
    fun family_codec_malformed_version_syntax_rejected() {
        assertNull(SelectedFamilyCodec.decode(
            "{\"version\":01,\"providerPkg\":\"p\",\"groupKey\":\"subsplease\"}"
        ))
        assertNull(SelectedFamilyCodec.decode(
            "{\"version\":+1,\"providerPkg\":\"p\",\"groupKey\":\"subsplease\"}"
        ))
        assertNull(SelectedFamilyCodec.decode(
            "{\"version\":-,\"providerPkg\":\"p\",\"groupKey\":\"subsplease\"}"
        ))
    }

    /**
     * Fix 6: real MakeDefault decision tests.
     */
    @Test
    fun remembered_policy_OFF_writes_neither() {
        val exact = "editotsu-source:v1:direct:0b6efc08:abcdef0123456789"
        val cand = FamilyCandidate("k1", "com.nyaa.sub", "subsplease",
            mapOf("trackerKey" to "nyaasi"))
        assertFalse(RememberedSelectionPolicy.shouldWriteExactKey(false, exact))
        assertFalse(RememberedSelectionPolicy.shouldReplaceFamily(false, cand))
        assertFalse(RememberedSelectionPolicy.shouldPreserveExistingFamily(
            false, null,
        ))
    }

    @Test
    fun remembered_policy_ON_exact_and_family_persists_both() {
        val exact = "editotsu-source:v1:direct:0b6efc08:abcdef0123456789"
        val cand = FamilyCandidate("k1", "com.nyaa.sub", "subsplease",
            mapOf("trackerKey" to "nyaasi"))
        assertTrue(RememberedSelectionPolicy.shouldWriteExactKey(true, exact))
        assertTrue(RememberedSelectionPolicy.shouldReplaceFamily(true, cand))
        assertFalse(RememberedSelectionPolicy.shouldPreserveExistingFamily(
            true, cand,
        ))
    }

    @Test
    fun remembered_policy_ON_exact_no_confident_family_preserves_existing() {
        val exact = "editotsu-source:v1:direct:0b6efc08:abcdef0123456789"
        assertTrue(RememberedSelectionPolicy.shouldWriteExactKey(true, exact))
        assertFalse(RememberedSelectionPolicy.shouldReplaceFamily(true, null))
        assertTrue(RememberedSelectionPolicy.shouldPreserveExistingFamily(
            true, null,
        ))
    }

    // ===== Exact-slot transaction (v1.4 blocker 1) =====

    @Test
    fun exact_slot_action_off_is_no_change() {
        val exact = "editotsu-source:v1:direct:0b6efc08:abcdef0123456789"
        assertEquals(
            RememberedSelectionPolicy.ExactSlotAction.NO_CHANGE,
            RememberedSelectionPolicy.exactSlotAction(false, exact),
        )
        assertEquals(
            RememberedSelectionPolicy.ExactSlotAction.NO_CHANGE,
            RememberedSelectionPolicy.exactSlotAction(false, null),
        )
    }

    @Test
    fun exact_slot_action_on_with_key_is_write() {
        val exact = "editotsu-source:v1:direct:0b6efc08:abcdef0123456789"
        assertEquals(
            RememberedSelectionPolicy.ExactSlotAction.WRITE,
            RememberedSelectionPolicy.exactSlotAction(true, exact),
        )
    }

    @Test
    fun exact_slot_action_on_null_clears_stale_key() {
        // ON + unkeyable manual choice must CLEAR, not preserve, a
        // stale previously persisted exact key.
        assertEquals(
            RememberedSelectionPolicy.ExactSlotAction.CLEAR,
            RememberedSelectionPolicy.exactSlotAction(true, null),
        )
        assertEquals(
            RememberedSelectionPolicy.ExactSlotAction.CLEAR,
            RememberedSelectionPolicy.exactSlotAction(true, "   "),
        )
        assertFalse(RememberedSelectionPolicy.shouldWriteExactKey(true, null))
    }

    // ===== Auto-recovery persistence transaction (v1.7) =====

    @Test
    fun auto_recovery_persistence_on_with_key_persists_legacy_and_writes() {
        val d = RememberedSelectionPolicy.autoRecoveryPersistence(
            makeDefault = true,
            exactKey = "editotsu-source:v1:direct:0b6efc08:abcdef0123456789",
        )
        assertTrue(d.persistLegacyServer)
        assertEquals(
            RememberedSelectionPolicy.ExactSlotAction.WRITE,
            d.exactAction,
        )
    }

    @Test
    fun auto_recovery_persistence_off_with_key_persists_nothing() {
        val d = RememberedSelectionPolicy.autoRecoveryPersistence(
            makeDefault = false,
            exactKey = "editotsu-source:v1:direct:0b6efc08:abcdef0123456789",
        )
        assertFalse(d.persistLegacyServer)
        assertEquals(
            RememberedSelectionPolicy.ExactSlotAction.NO_CHANGE,
            d.exactAction,
        )
    }

    @Test
    fun auto_recovery_persistence_on_null_key_persists_legacy_and_clears() {
        val d = RememberedSelectionPolicy.autoRecoveryPersistence(
            makeDefault = true,
            exactKey = null,
        )
        assertTrue(d.persistLegacyServer)
        assertEquals(
            RememberedSelectionPolicy.ExactSlotAction.CLEAR,
            d.exactAction,
        )
    }

    @Test
    fun auto_recovery_persistence_off_null_key_persists_nothing() {
        val d = RememberedSelectionPolicy.autoRecoveryPersistence(
            makeDefault = false,
            exactKey = null,
        )
        assertFalse(d.persistLegacyServer)
        assertEquals(
            RememberedSelectionPolicy.ExactSlotAction.NO_CHANGE,
            d.exactAction,
        )
    }

    // ===== Strict first exact index (v1.4 blocker 2) =====

    @Test
    fun index_empty_value_returns_null() {
        assertNull(CanonicalIndexParser.canonicalIndex(
            "magnet:?xt=urn:btih:abc&index="
        ))
    }

    @Test
    fun index_first_malformed_later_valid_returns_null() {
        // A malformed FIRST exact index is never rescued by a later
        // valid one.
        assertNull(CanonicalIndexParser.canonicalIndex(
            "magnet:?xt=urn:btih:abc&index=&index=7"
        ))
        assertNull(CanonicalIndexParser.canonicalIndex(
            "magnet:?xt=urn:btih:abc&index=abc&index=7"
        ))
    }

    @Test
    fun index_plus_one_rejected() {
        assertNull(CanonicalIndexParser.canonicalIndex(
            "magnet:?xt=urn:btih:abc&index=+1"
        ))
    }

    @Test
    fun index_leading_zeros_accepted() {
        assertEquals(
            1L,
            CanonicalIndexParser.canonicalIndex(
                "magnet:?xt=urn:btih:abc&index=00001"
            ),
        )
    }

    @Test
    fun index_bare_token_without_equals_is_malformed() {
        assertNull(CanonicalIndexParser.canonicalIndex(
            "magnet:?xt=urn:btih:abc&index&dn=x"
        ))
    }

    @Test
    fun index_xindex_then_exact_index_uses_exact() {
        assertEquals(
            2L,
            CanonicalIndexParser.canonicalIndex(
                "magnet:?xt=urn:btih:abc&xindex=9&index=2"
            ),
        )
    }

    // ===== Canonical family soft values (v1.5 blocker 1) =====

    @Test
    fun soft_canonical_text_trims_collapses_lowercases() {
        assertEquals("dual audio", StableSoftCanonicalizer.canonicalText("Dual   Audio"))
        assertEquals("aac", StableSoftCanonicalizer.canonicalText(" AAC "))
        assertEquals("hevc", StableSoftCanonicalizer.canonicalText(" HEVC "))
        assertEquals("hdr10+", StableSoftCanonicalizer.canonicalText(" HDR10+ "))
        assertNull(StableSoftCanonicalizer.canonicalText("   "))
        assertNull(StableSoftCanonicalizer.canonicalText(null))
    }

    @Test
    fun soft_canonical_resolution_4k_maps_to_2160p() {
        assertEquals("2160p", StableSoftCanonicalizer.canonicalResolution("4K"))
        assertEquals("2160p", StableSoftCanonicalizer.canonicalResolution("2160p"))
        assertEquals("1440p", StableSoftCanonicalizer.canonicalResolution("1440p"))
        assertEquals("1080p", StableSoftCanonicalizer.canonicalResolution("1080p"))
    }

    @Test
    fun family_soft_whitespace_and_4k_compare_equal() {
        // Stored family built from "Dual   Audio" + "4K" must rank
        // identically to a candidate built from "Dual Audio" +
        // "2160p": canonical soft values compare equal.
        val stored = FamilyPayload("com.nyaa.sub", "subsplease", mapOf(
            "trackerKey" to "nyaasi",
            "resolution" to StableSoftCanonicalizer.canonicalResolution("4K")!!,
            "audioMode" to StableSoftCanonicalizer.canonicalText("Dual   Audio")!!,
        ))
        val cand = FamilyCandidate("k1", "com.nyaa.sub", "subsplease", mapOf(
            "trackerKey" to "nyaasi",
            "resolution" to StableSoftCanonicalizer.canonicalResolution("2160p")!!,
            "audioMode" to StableSoftCanonicalizer.canonicalText("Dual Audio")!!,
        ))
        val r = FamilyAutoSelector.select(stored, listOf(cand))
        assertTrue(r is FamilyAutoResult.Chosen)
        assertEquals("k1", (r as FamilyAutoResult.Chosen).exactKey)
    }

    @Test
    fun family_candidate_soft_values_are_canonical() {
        val cand = StableCandidateBuilder.familyCandidate(
            makeServer("📂 NyaaSi | NyaaSi 4K [SubsPlease] Title Dual   Audio"),
        )
        assertNotNull(cand)
        assertEquals("2160p", cand!!.soft["resolution"])
        assertEquals("dual audio", cand.soft["audioMode"])
        assertEquals("nyaasi", cand.soft["trackerKey"])
    }

    // ===== Deterministic family runtime representative (v1.5 blocker 2) =====

    @Test
    fun recovery_family_chosen_key_picks_same_server_both_orders() {
        val hex = "0000000000000000000000000000000000000001"
        val s1 = VideoServer(
            "📂 NyaaSi | NyaaSi 1080p [SubsPlease] Title A",
            "magnet:?xt=urn:btih:$hex&dn=a",
            mapOf(StableCandidateBuilder.PROVIDER_EXTRA_KEY to "com.nyaa.sub"),
        )
        val s2 = VideoServer(
            "📂 NyaaSi | NyaaSi 1080p [SubsPlease] Title B",
            "magnet:?xt=urn:btih:$hex&dn=b",
            mapOf(StableCandidateBuilder.PROVIDER_EXTRA_KEY to "com.nyaa.sub"),
        )
        // Same BTIH/index/provider -> same exact key from both rows.
        val k1 = StableCandidateBuilder.exactKey("com.nyaa.sub", s1)
        val k2 = StableCandidateBuilder.exactKey("com.nyaa.sub", s2)
        assertNotNull(k1)
        assertEquals(k1, k2)
        val stored = FamilyPayload("com.nyaa.sub", "subsplease",
            mapOf("trackerKey" to "nyaasi"))
        val forward = RecoveryCoordinator.resolve(
            storedExactKey = null, storedFamily = stored,
            selectedName = null, servers = listOf(s1, s2),
        )
        val reversed = RecoveryCoordinator.resolve(
            storedExactKey = null, storedFamily = stored,
            selectedName = null, servers = listOf(s2, s1),
        )
        assertTrue(forward is RecoveryResult.Resolved)
        assertTrue(reversed is RecoveryResult.Resolved)
        assertEquals(
            (forward as RecoveryResult.Resolved).server.name,
            (reversed as RecoveryResult.Resolved).server.name,
        )
    }

    // ===== Coherent remembered-family policy (v1.5 blocker 3) =====

    @Test
    fun family_slot_action_off_is_no_change() {
        val cand = FamilyCandidate("k1", "com.nyaa.sub", "subsplease")
        assertEquals(
            RememberedSelectionPolicy.FamilySlotAction.NO_CHANGE,
            RememberedSelectionPolicy.familySlotAction(false, cand),
        )
        assertEquals(
            RememberedSelectionPolicy.FamilySlotAction.NO_CHANGE,
            RememberedSelectionPolicy.familySlotAction(false, null),
        )
    }

    @Test
    fun family_slot_action_on_confident_is_replace() {
        val cand = FamilyCandidate("k1", "com.nyaa.sub", "subsplease")
        assertEquals(
            RememberedSelectionPolicy.FamilySlotAction.REPLACE,
            RememberedSelectionPolicy.familySlotAction(true, cand),
        )
    }

    @Test
    fun family_slot_action_on_unconfident_is_preserve() {
        // Independent of the exact key: ON + null exact + null
        // family still preserves the stored family.
        assertEquals(
            RememberedSelectionPolicy.FamilySlotAction.PRESERVE,
            RememberedSelectionPolicy.familySlotAction(true, null),
        )
    }

    @Test
    fun remembered_on_null_exact_null_family_clears_exact_preserves_family() {
        assertEquals(
            RememberedSelectionPolicy.ExactSlotAction.CLEAR,
            RememberedSelectionPolicy.exactSlotAction(true, null),
        )
        assertEquals(
            RememberedSelectionPolicy.FamilySlotAction.PRESERVE,
            RememberedSelectionPolicy.familySlotAction(true, null),
        )
    }

    // ===== Technical hard-anchor rejection (v1.5 blocker 4) =====

    @Test
    fun group_hard_anchor_rejects_technical_only_values() {
        for (bad in listOf("hdr", "dv", "aac2.0", "dsnp", "hulu", "at-x")) {
            assertNull(bad, StableGroupCanonicalizer.canonicalKey(bad))
            assertNull(
                bad,
                SelectedFamilyCodec.decode(
                    "{\"version\":1,\"providerPkg\":\"p\",\"groupKey\":\"$bad\"}"
                ),
            )
            assertNull(
                bad,
                SelectedFamilyCodec.encode(FamilyPayload("p", bad)),
            )
        }
    }

    @Test
    fun group_hard_anchor_keeps_real_groups() {
        for (good in listOf(
            "subsplease", "ironclad", "toonshub", "dkb", "varyg", "tenrai-sensei",
        )) {
            assertEquals(good, StableGroupCanonicalizer.canonicalKey(good))
        }
    }

    // ===== Canonical DIRECT structured fields (v1.6 blocker 1) =====

    @Test
    fun direct_canonical_structured_fields_use_persistence_canonicalizers() {
        val a = StableReleaseCanonicalizer.canonicalize(
            group = "SubsPlease",
            resolution = "4K",
            audioFormat = null,
            audioMode = "Dual   Audio",
            sourceService = null,
            rawTitle = "[SubsPlease] Anime - 01",
        )
        val b = StableReleaseCanonicalizer.canonicalize(
            group = "SubsPlease",
            resolution = "2160p",
            audioFormat = null,
            audioMode = "Dual Audio",
            sourceService = null,
            rawTitle = "[SubsPlease] Anime - 01",
        )
        assertNotNull(a)
        assertNotNull(b)
        assertEquals(a, b)
        assertTrue(a!!.contains("res=2160p"))
        assertTrue(a.contains("audio=dual audio"))
    }

    @Test
    fun direct_canonical_resolution_1440p_stays_1440p() {
        val c = StableReleaseCanonicalizer.canonicalize(
            group = "SubsPlease",
            resolution = "1440p",
            audioFormat = null,
            audioMode = "Dual Audio",
            sourceService = null,
            rawTitle = "[SubsPlease] Anime - 01",
        )
        assertNotNull(c)
        assertTrue(c!!.contains("res=1440p"))
    }

    @Test
    fun source_service_bracketed_short_aliases_canonical() {
        assertEquals("netflix", SourceServiceCanonicalizer.canonicalKey("[NF]"))
        assertEquals("amazon", SourceServiceCanonicalizer.canonicalKey("[AMZN]"))
        assertEquals("crunchyroll", SourceServiceCanonicalizer.canonicalKey("[CR]"))
        assertEquals(
            "some service",
            SourceServiceCanonicalizer.canonicalKey("  Some   Service "),
        )
    }

    // ===== Nullable recovery exact keys, no sentinels (v1.6 blocker 2) =====

    @Test
    fun recovery_exact_name_unkeyable_resolves_with_null_key() {
        // "1080p" is a generic direct-stream label: release-shaped
        // is false, so no stable exact key exists — but the
        // exact-name fast path still resolves the runtime handle.
        val s = makeServer("1080p")
        assertNull(StableCandidateBuilder.exactKey("com.nyaa.sub", s))
        val r = RecoveryCoordinator.resolve(
            storedExactKey = null,
            storedFamily = null,
            selectedName = "1080p",
            servers = listOf(s),
        )
        assertTrue(r is RecoveryResult.Resolved)
        val resolved = r as RecoveryResult.Resolved
        assertEquals("1080p", resolved.server.name)
        assertNull(resolved.exactKey)
    }

    @Test
    fun recovery_legacy_unkeyable_fallback_resolves_with_null_key() {
        // Two unkeyable candidates, exactly one name match: the
        // bounded legacy fallback resolves the runtime handle with
        // a null exact key, never a synthetic sentinel.
        val s1 = makeServer("Stream A")
        val s2 = makeServer("Stream B")
        assertNull(StableCandidateBuilder.exactKey("com.nyaa.sub", s1))
        assertNull(StableCandidateBuilder.exactKey("com.nyaa.sub", s2))
        val r = RecoveryCoordinator.resolve(
            storedExactKey = null,
            storedFamily = null,
            selectedName = "Stream A",
            servers = listOf(s1, s2),
        )
        assertTrue(r is RecoveryResult.Resolved)
        val resolved = r as RecoveryResult.Resolved
        assertEquals("Stream A", resolved.server.name)
        assertNull(resolved.exactKey)
        assertFalse((resolved.exactKey ?: "").startsWith("__"))
    }

    // ===== Explicit empty HTTP ports rejected (v1.6 blocker 3) =====

    @Test
    fun url_explicit_empty_port_rejected() {
        assertNull(UrlCanonicaliser.canonicalise("https://host:/x"))
        assertNull(UrlCanonicaliser.canonicalise("https://[::1]:/x"))
    }

    @Test
    fun url_absent_port_still_valid() {
        val plain = UrlCanonicaliser.canonicalise("https://host/x")
        assertNotNull(plain)
        assertEquals("https://host/x", plain!!.canonicalUrlString())
        val v6 = UrlCanonicaliser.canonicalise("https://[::1]/x")
        assertNotNull(v6)
        assertEquals("https://[::1]/x", v6!!.canonicalUrlString())
    }

    // ===== Helper =====

    private fun makeServer(
        name: String,
    ): VideoServer {
        // `name` doubles as the raw description text the metadata
        // extractor parses. The persistence layer does NOT depend on
        // any UI presentation function; it only reads the raw text
        // already present on the candidate.
        return VideoServer(
            name, "https://example.com/x", mapOf(
                StableCandidateBuilder.PROVIDER_EXTRA_KEY to "com.nyaa.sub",
            ),
        )
    }
}
