package ani.dantotsu.parsers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URL

/**
 * Upstream Oct sync D (upstream 7eaf0f69 / d3dbb860): AniyomiAdapter
 * format detection. Main guarantee: Editotsu's torrent relay URL is never
 * classified as localhost HLS.
 */
class StreamTypeDetectorTest {
    private val hash = "08ada5a7a6183aae1e09d831df6748d566095a10"
    private val relay = URL("http://127.0.0.1:8065/stream?hash=$hash&index=3")

    @Test
    fun `editotsu relay url is a torrent stream`() {
        assertTrue(StreamTypeDetector.isTorrentStream(relay))
        assertTrue(StreamTypeDetector.isTorrentStream(URL("http://127.0.0.1:8065/stream?index=0&hash=$hash")))
        assertTrue(StreamTypeDetector.isTorrentStream(URL("http://localhost:9000/stream")))
    }

    @Test
    fun `relay url is never classified as HLS or DASH`() {
        assertNull(StreamTypeDetector.fallbackFormat(relay, "Episode 1", "1080p"))
        // A title hint must not override a torrent relay either.
        assertNull(StreamTypeDetector.fallbackFormat(relay, "HLS 1080p", "m3u8"))
        assertNull(StreamTypeDetector.videoTypeFromPath(relay.path))
    }

    @Test
    fun `upstream torrent patterns still match`() {
        assertTrue(StreamTypeDetector.isTorrentStream(URL("http://127.0.0.1:11470/abc/0")))
        assertTrue(StreamTypeDetector.isTorrentStream(URL("http://127.0.0.1:1234/$hash/0")))
        assertTrue(StreamTypeDetector.isTorrentStream(URL("http://127.0.0.1:1234/play?infohash=x")))
        assertTrue(StreamTypeDetector.isTorrentStream(URL("http://127.0.0.1:1234/play?tr=udp")))
    }

    @Test
    fun `remote hosts are never torrent or localhost`() {
        val remote = URL("https://cdn.example/stream?hash=$hash")
        assertFalse(StreamTypeDetector.isLocalhost(remote))
        assertFalse(StreamTypeDetector.isTorrentStream(remote))
        assertNull(StreamTypeDetector.fallbackFormat(remote, "Episode 1", "720p"))
    }

    @Test
    fun `localhost proxy classification`() {
        assertEquals(VideoType.CONTAINER, StreamTypeDetector.fallbackFormat(URL("http://127.0.0.1:5000/v.mp4"), null, null))
        assertEquals(
            VideoType.CONTAINER,
            StreamTypeDetector.fallbackFormat(URL("http://127.0.0.1:5000/proxy?url=https%3A%2F%2Fcdn%2Fv.mp4%3Fa%3D1"), null, null)
        )
        assertEquals(VideoType.DASH, StreamTypeDetector.fallbackFormat(URL("http://127.0.0.1:5000/dash/x"), null, null))
        assertEquals(
            VideoType.DASH,
            StreamTypeDetector.fallbackFormat(URL("http://localhost:5000/proxy?file=https%3A%2F%2Fcdn%2Fm.mpd"), null, null)
        )
        // Unknown localhost proxy content defaults to HLS (upstream behaviour).
        assertEquals(VideoType.M3U8, StreamTypeDetector.fallbackFormat(URL("http://127.0.0.1:5000/proxy?id=7"), null, null))
        assertEquals(
            VideoType.M3U8,
            StreamTypeDetector.fallbackFormat(URL("http://127.0.0.1:5000/proxy?url=https%3A%2F%2Fcdn%2Fmaster.m3u8"), null, null)
        )
    }

    @Test
    fun `title and quality hints for remote urls`() {
        val u = URL("https://cdn.example/watch/abc")
        assertEquals(VideoType.M3U8, StreamTypeDetector.fallbackFormat(u, "Server HLS", "1080p"))
        assertEquals(VideoType.M3U8, StreamTypeDetector.fallbackFormat(u, null, "m3u8 720p"))
        assertEquals(VideoType.DASH, StreamTypeDetector.fallbackFormat(u, "DASH", null))
        assertNull(StreamTypeDetector.fallbackFormat(u, "Server 1", "1080p"))
    }

    @Test
    fun `path patterns from upstream`() {
        assertEquals(VideoType.CONTAINER, StreamTypeDetector.videoTypeFromPath("/v/file.mkv"))
        assertEquals(VideoType.CONTAINER, StreamTypeDetector.videoTypeFromPath("/get/mp4/123"))
        assertEquals(VideoType.M3U8, StreamTypeDetector.videoTypeFromPath("/stream/abc/uwu.m3u8"))
        assertEquals(VideoType.M3U8, StreamTypeDetector.videoTypeFromPath("/pl/abc"))
        assertEquals(VideoType.M3U8, StreamTypeDetector.videoTypeFromPath("/hls/abc/index"))
        assertEquals(VideoType.M3U8, StreamTypeDetector.videoTypeFromPath("/playlist/42"))
        assertEquals(VideoType.M3U8, StreamTypeDetector.videoTypeFromPath("m3u8"))
        assertEquals(VideoType.DASH, StreamTypeDetector.videoTypeFromPath("/dash/abc"))
        assertEquals(VideoType.DASH, StreamTypeDetector.videoTypeFromPath("/v/manifest.mpd"))
        assertNull(StreamTypeDetector.videoTypeFromPath("/watch/abc"))
    }

    @Test
    fun `ipv6 loopback is localhost`() {
        assertTrue(StreamTypeDetector.isLocalhost(URL("http://[::1]:5000/x")))
    }
}
