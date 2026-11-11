package ani.dantotsu.media.anime.selector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResolutionParserTest {

    @Test
    fun heights() {
        assertEquals(1080, ResolutionParser.height("[SubsPlease] Show - 05 (1080p) [F2A1B3C4].mkv"))
        assertEquals(720, ResolutionParser.height("MTBB 720 p"))
        assertEquals(1080, ResolutionParser.height("Show 1080i"))
        assertEquals(2160, ResolutionParser.height("[Group] Show - 01 [4K HDR].mkv"))
        assertEquals(2160, ResolutionParser.height("[Group] Show - 01 (4K).mkv"))
        assertEquals(2160, ResolutionParser.height("[Group] Show - 01 [4K].mkv"))
        assertEquals(2160, ResolutionParser.height("Torrentio\n4k DV | HDR"))
        assertEquals(2160, ResolutionParser.height("Show UHD BluRay"))
        assertEquals(1080, ResolutionParser.height("[Group] Show - 01 [1920x1080]"))
        assertEquals(1080, ResolutionParser.height("Kaijuu 8-gou S2 - 05 (CR 1920 x 1080 x264 AAC)"))
        assertEquals(1440, ResolutionParser.height("Show [2K]"))
    }

    @Test
    fun not_resolutions() {
        assertNull(ResolutionParser.height("Show - 01 [x264][10bit][H.265]"))
        assertNull(ResolutionParser.height("Show 2025 S01E08"))
        assertNull(ResolutionParser.height("UHDMovies mirror"))
        assertNull(ResolutionParser.height(null))
        assertNull(ResolutionParser.height(""))
    }

    @Test
    fun five_readers_agree_on_the_report_inputs() {
        val bracket4k = "[Group] Show - 01 [4K HDR].mkv"
        assertEquals("2160p", SourceRowClassifier.parseResolution(bracket4k))
        assertEquals(2160, ResolutionLabel.resolve(null, bracket4k))
        assertEquals(
            "2160p",
            StableSoftCanonicalizer.canonicalResolution(StableReleaseMetadataExtractor.extract(bracket4k).resolution),
        )

        val wxh = "[Group] Show - 01 [1920x1080]"
        assertEquals("1080p", SourceRowClassifier.parseResolution(wxh))
        assertEquals(1080, ResolutionLabel.resolve(null, wxh))
        assertEquals(
            "1080p",
            StableSoftCanonicalizer.canonicalResolution(StableReleaseMetadataExtractor.extract(wxh).resolution),
        )

        assertEquals("2160p", SourceRowClassifier.parseResolution("[Group] Show - 01 (4K).mkv"))
    }

    @Test
    fun quality_key_and_stream_names_drop_every_spelling() {
        val a = ReleaseQualityKey.of("[SubsPlease] Show - 05 (1080p) [F2A1B3C4].mkv")
        val b = ReleaseQualityKey.of("[SubsPlease] Show - 05 (4K) [0B9D7E21].mkv")
        val c = ReleaseQualityKey.of("[SubsPlease] Show - 05 (1920x1080) [0B9D7E22].mkv")
        assertEquals(a, b)
        assertEquals(a, c)
        assertEquals("MTBB", StreamNameCleaner.groupName("MTBB [4K]"))
        assertEquals("MTBB", StreamNameCleaner.groupName("MTBB 1920x1080"))
        assertEquals("MTBB", StreamNameCleaner.groupName("MTBB 720 p"))
    }

    @Test
    fun old_stored_family_values_still_match() {
        // values earlier builds stored in the family payload
        assertEquals("1080p", StableSoftCanonicalizer.canonicalResolution("1920x1080"))
        assertEquals("2160p", StableSoftCanonicalizer.canonicalResolution("4k"))
        assertEquals("2160p", StableSoftCanonicalizer.canonicalResolution("4K"))
        assertEquals("2160p", StableSoftCanonicalizer.canonicalResolution("2160p"))
        assertEquals("1440p", StableSoftCanonicalizer.canonicalResolution("1440p"))
        assertEquals("1080p", StableSoftCanonicalizer.canonicalResolution("1080p"))

        val pkg = "pkg"
        val stored = FamilyPayload(pkg, "group", mapOf("resolution" to "1920x1080"))
        val candidates = listOf(
            FamilyCandidate("a", pkg, "group", mapOf("resolution" to "720p")),
            FamilyCandidate("b", pkg, "group", mapOf("resolution" to "1080p")),
        )
        assertEquals(FamilyAutoResult.Chosen("b"), FamilyAutoSelector.select(stored, candidates))
    }

    @Test
    fun exact_key_resolution_spelling_is_unchanged() {
        val wxh = StableReleaseCanonicalizer.canonicalize(
            group = "Group", resolution = "1920x1080", audioFormat = null, audioMode = null,
            sourceService = null, rawTitle = "[Group] Show - 01",
        )
        assertTrue(wxh!!.contains("res=1920x1080"))
        assertEquals("1920x1080", StableReleaseMetadataExtractor.extract("[Group] Show - 01 [1920x1080]").resolution)
        assertEquals("4k", StableReleaseMetadataExtractor.extract("[Group] Show - 01 [4K HDR].mkv").resolution)
    }
}
