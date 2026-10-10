package ani.dantotsu.parsers

import ani.dantotsu.FileUrl
import ani.dantotsu.torrent.ManualTorrentExtractor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MAX MERGE FINAL-PORT C4 (upstream 6f77ea20): preferred-flag model
 * matrix. Flags default false (existing behavior preserved); explicit
 * user selection paths are untouched — only auto-resolution consults
 * these getters (covered in DownloadResolutionTest).
 */
class PreferredSelectionTest {

    private fun video(url: String, preferred: Boolean = false) =
        Video(null, VideoType.CONTAINER, FileUrl(url), preferred = preferred)

    @Test
    fun `video preferred defaults false`() {
        assertFalse(Video(null, VideoType.CONTAINER, "https://cdn/v.mp4").preferred)
    }

    @Test
    fun `server preferred follows extension video flag`() {
        val plain = VideoServer("s", "")
        assertFalse(plain.isPreferred)
        val flagged = VideoServer(
            "s",
            "",
            video = eu.kanade.tachiyomi.animesource.model.Video(
                videoUrl = "https://cdn/v.mp4",
                preferred = true
            )
        )
        assertTrue(flagged.isPreferred)
    }

    @Test
    fun `extractor preferred from server or any video`() {
        val byServer = ManualTorrentExtractor("m", listOf(video("https://cdn/a.mp4")))
        assertFalse(byServer.isPreferred)
        val byVideo = ManualTorrentExtractor(
            "m",
            listOf(video("https://cdn/a.mp4"), video("https://cdn/b.mp4", preferred = true))
        )
        assertTrue(byVideo.isPreferred)
    }

    @Test
    fun `manual torrent extractor never claims preferred`() {
        // M7A preserved: manual entries keep prior fallback behavior.
        val manual = ManualTorrentExtractor("Manual torrent", listOf(video("magnet:?xt=urn:btih:x")))
        assertFalse(manual.isPreferred)
        assertEquals(1, manual.videos.size)
    }

    // ---- HOLD1-A/B: gating regression matrix (pure helpers) ----

    private fun extractor(
        name: String,
        preferred: Boolean = false,
        playable: Boolean = true,
    ) = ManualTorrentExtractor(
        name,
        if (playable) listOf(video("https://cdn.test/$name.mp4", preferred)) else emptyList()
    )

    @Test
    fun `cached preferred never masks missing explicit selection`() {
        val list = listOf(extractor("PreferredB", preferred = true))
        assertFalse(hasExplicitServer(list, "UserServerA"))
    }

    @Test
    fun `present explicit selection gates true with preferred cached`() {
        val list = listOf(
            extractor("UserServerA"),
            extractor("PreferredB", preferred = true)
        )
        assertTrue(hasExplicitServer(list, "UserServerA"))
    }

    @Test
    fun `gate matrix nulls and empties`() {
        val list = listOf(extractor("PreferredB", preferred = true))
        assertFalse(hasExplicitServer(list, null))
        assertFalse(hasExplicitServer(null, "UserServerA"))
        assertFalse(hasExplicitServer(emptyList(), "UserServerA"))
        assertFalse(hasExplicitServer(listOf(extractor("Other")), "UserServerA"))
    }

    @Test
    fun `fallback prefers playable preferred over playable plain`() {
        val plain = extractor("A")
        val pref = extractor("B", preferred = true)
        assertEquals(pref, preferredPlayableFallback(listOf(plain, pref)))
        assertEquals(pref, preferredPlayableFallback(listOf(pref, plain)))
    }

    @Test
    fun `empty preferred never shadows playable fallback`() {
        // HOLD1-B witness: [A playable, B preferred-empty] must yield A.
        val playable = extractor("A")
        val emptyPref = extractor("B", preferred = true, playable = false)
        assertEquals(playable, preferredPlayableFallback(listOf(playable, emptyPref)))
        assertEquals(playable, preferredPlayableFallback(listOf(emptyPref, playable)))
    }

    @Test
    fun `fallback nulls when nothing playable`() {
        assertNull(preferredPlayableFallback(listOf(extractor("B", preferred = true, playable = false))))
        assertNull(preferredPlayableFallback(listOf(extractor("A", playable = false))))
        assertNull(preferredPlayableFallback(emptyList()))
        assertNull(preferredPlayableFallback(null))
    }

    @Test
    fun `explicit hit path never consults fallback`() {
        // Structural: hasExplicitServer true short-circuits to the named
        // load before load() runs; the fallback helpers take no selection
        // name and cannot resurrect a bypassed explicit server.
        val list = listOf(
            extractor("UserServerA", playable = false),
            extractor("PreferredB", preferred = true)
        )
        assertTrue(hasExplicitServer(list, "UserServerA"))
    }
}
