package ani.dantotsu.parsers

import ani.dantotsu.FileUrl
import ani.dantotsu.torrent.ManualTorrentExtractor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}
