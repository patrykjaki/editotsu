package ani.dantotsu.download.video

import ani.dantotsu.FileUrl
import ani.dantotsu.parsers.Video
import ani.dantotsu.parsers.VideoType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * MAX MERGE M3 (upstream e954d9f1): preferred-resolution ordering and
 * fallback contract.
 */
class DownloadResolutionTest {

    private fun video(quality: Int?, url: String = "https://cdn.test/v.mp4", note: String? = null) =
        Video(quality, VideoType.CONTAINER, FileUrl(url), null, note)

    @Test
    fun `empty list yields null`() {
        assertNull(findBestVideoForDownload(emptyList(), listOf("1080p")))
    }

    @Test
    fun `empty preferences fall back to max quality`() {
        val videos = listOf(video(480), video(1080), video(720))
        assertEquals(1080, findBestVideoForDownload(videos, emptyList())?.quality)
    }

    @Test
    fun `exact quality match wins in preference order`() {
        val videos = listOf(video(480), video(1080), video(720))
        assertEquals(720, findBestVideoForDownload(videos, listOf("720p", "1080p"))?.quality)
        assertEquals(1080, findBestVideoForDownload(videos, listOf("1080p", "720p"))?.quality)
    }

    @Test
    fun `missing preference falls to next then max quality`() {
        val videos = listOf(video(480), video(720))
        assertEquals(480, findBestVideoForDownload(videos, listOf("1080p", "480p"))?.quality)
        assertEquals(720, findBestVideoForDownload(videos, listOf("2160p"))?.quality)
    }

    @Test
    fun `note and url fallback match without quality ints`() {
        val videos = listOf(
            video(null, "https://cdn.test/low.mp4", "Backup SD"),
            video(null, "https://cdn.test/hd_720p.mp4", "Main")
        )
        assertEquals(
            "https://cdn.test/hd_720p.mp4",
            findBestVideoForDownload(videos, listOf("720p"))?.file?.url
        )
    }

    @Test
    fun `null qualities do not crash max fallback`() {
        val videos = listOf(video(null), video(null))
        assertEquals(videos[0], findBestVideoForDownload(videos, listOf("1080p")))
    }

    @Test
    fun `duplicate-looking qualities keep first match`() {
        val a = video(720, "https://a.test/v.mp4")
        val b = video(720, "https://b.test/v.mp4")
        assertEquals(a, findBestVideoForDownload(listOf(a, b), listOf("720p")))
    }

    // FINAL-PORT C4 (upstream 6f77ea20): extension-preferred stream wins
    // auto-resolution ahead of resolution preferences; untagged catalogs
    // behave exactly as before (all rows above use preferred=false).

    private fun flaggedVideo(quality: Int?, url: String = "https://cdn.test/v.mp4") =
        Video(quality, VideoType.CONTAINER, FileUrl(url), preferred = true)

    @Test
    fun `preferred stream wins over resolution preferences`() {
        val videos = listOf(video(1080), flaggedVideo(480))
        assertEquals(480, findBestVideoForDownload(videos, listOf("1080p"))?.quality)
    }

    @Test
    fun `preferred stream wins empty-preference max fallback`() {
        val videos = listOf(video(1080), flaggedVideo(720), video(2160))
        assertEquals(720, findBestVideoForDownload(videos, emptyList())?.quality)
    }

    @Test
    fun `first preferred wins among several`() {
        val a = flaggedVideo(480, "https://a.test/v.mp4")
        val b = flaggedVideo(720, "https://b.test/v.mp4")
        assertEquals(a, findBestVideoForDownload(listOf(video(1080), a, b), listOf("1080p")))
    }

    @Test
    fun `preferred flag survives changing bitrate labels across episodes`() {
        // Same-flagged stream selected regardless of quality ordering.
        val low = flaggedVideo(480, "https://cdn.test/ep2.mp4")
        val high = video(1080, "https://cdn.test/ep2-hi.mp4")
        assertEquals(low, findBestVideoForDownload(listOf(high, low), listOf("1080p")))
        assertEquals(low, findBestVideoForDownload(listOf(low, high), listOf("720p")))
    }
}
