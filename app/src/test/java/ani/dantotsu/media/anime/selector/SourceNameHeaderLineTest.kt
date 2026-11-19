package ani.dantotsu.media.anime.selector

import org.junit.Assert.assertEquals
import org.junit.Test

/** A source's own name printed as the first line of a release row is not part of the release name. */
class SourceNameHeaderLineTest {

    @Test
    fun `one-word first line of a release row is dropped`() {
        val text = "Relayaddon\n📂 IdxOne\n[GroupA] Show - 05 (1080p) [ABCD1234].mkv\n👤 545 💾 1.48 GB"
        assertEquals("[GroupA] Show - 05 (1080p) [ABCD1234].mkv", SourceRowClassifier.extractReleaseName(text))
        assertEquals("GroupA", SourceRowClassifier.parseRelease(text).releaseGroup)
        assertEquals("IdxOne", SourceRowClassifier.parseRelease(text).tracker)
    }

    @Test
    fun `header before a release file name is dropped`() {
        assertEquals(
            "[Group] Title S01E01.mkv",
            SourceRowClassifier.extractReleaseName("Anyaddon\n[Group] Title S01E01.mkv"),
        )
    }

    @Test
    fun `a single title line is kept`() {
        assertEquals("Frieren", SourceRowClassifier.extractReleaseName("Frieren\n👤 3 💾 1 GB"))
    }

    @Test
    fun `stream labels are not touched`() {
        assertEquals("Hoster 1080p", SourceRowClassifier.extractReleaseName("Hoster\n1080p"))
    }

    // Stored-identity extractor: same rule, separate copy.

    @Test
    fun `extractor drops the header line of a release row`() {
        val text = "Relayaddon\n📂 IdxOne\nShow.S01E01.1080p.HEVC-GRPb.mkv\n👤 5 💾 1 GB"
        assertEquals(listOf("Show.S01E01.1080p.HEVC-GRPb.mkv"), StableReleaseMetadataExtractor.releaseLines(text))
        assertEquals("GRPb", StableReleaseMetadataExtractor.extract(text).group)
    }

    @Test
    fun `extractor drops the header before a file name without markers`() {
        assertEquals(
            listOf("Show.S01E01.1080p-GRPb.mkv"),
            StableReleaseMetadataExtractor.releaseLines("Anyaddon\nShow.S01E01.1080p-GRPb.mkv"),
        )
    }

    @Test
    fun `extractor keeps a single line and plain stream labels`() {
        assertEquals(listOf("Frieren"), StableReleaseMetadataExtractor.releaseLines("Frieren\n👤 3 💾 1 GB"))
        assertEquals(listOf("Hoster", "1080p"), StableReleaseMetadataExtractor.releaseLines("Hoster\n1080p"))
    }
}
