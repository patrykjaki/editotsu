package ani.dantotsu.parsers

import ani.dantotsu.media.anime.player.ExtensionMpvArgs
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import kotlinx.coroutines.runBlocking
import okhttp3.Headers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * EXT-COMPAT-1/3/6 + adapter seam proofs for the beta03 playback-compat fix.
 *
 * Synthetic AniZone-like extension Video goes through the real
 * [VideoServerPassthrough.extract] seam; tests fail until subtitle headers,
 * relative-URL resolution and mpv option plumbing are preserved.
 */
class ExtensionTrackCompatTest {

    private fun anizoneLikeVideo(): Video {
        val headers = Headers.headersOf(
            "Referer", "https://site.example/",
            "Cookie", "session=abc123",
            "User-Agent", "AniZoneAgent/1.0"
        )
        return Video(
            videoUrl = "https://cdn.example/master.m3u8",
            videoTitle = "AniZone - 1080p",
            headers = headers,
            subtitleTracks = listOf(Track("/subs/en.vtt", "English")),
            audioTracks = listOf(Track("https://cdn.example/audio/en.m3u8", "English")),
            mpvArgs = listOf("demuxer-lavf-o" to "force_mpegts=1")
        )
    }

    private fun passthrough(video: Video): VideoServerPassthrough {
        val server = VideoServer(
            name = "AniZone",
            embedUrl = "https://site.example/embed/1",
            extraData = null,
            video = video
        )
        return VideoServerPassthrough(server)
    }

    // EXT-COMPAT-1: subtitle headers survive the adapter with resolved URL.
    @Test
    fun `extension subtitle keeps source headers and resolved absolute url`() = runBlocking {
        val container = passthrough(anizoneLikeVideo()).extract()

        assertEquals(1, container.subtitles.size)
        val sub = container.subtitles[0]
        assertEquals("https://cdn.example/subs/en.vtt", sub.file.url)
        // Note: okhttp `toMultimap` lowercases header names; header matching
        // throughout the player is case-insensitive (RFC 9110), so look up
        // accordingly here.
        fun header(name: String) = sub.file.headers.entries
            .firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
        assertEquals("https://site.example/", header("Referer"))
        assertEquals("session=abc123", header("Cookie"))
        assertEquals("AniZoneAgent/1.0", header("User-Agent"))
    }

    // EXT-COMPAT-2 (adapter half): separate audio track URL survives.
    @Test
    fun `separate extension audio track url survives adapter`() = runBlocking {
        val container = passthrough(anizoneLikeVideo()).extract()

        assertEquals(1, container.audioTracks.size)
        assertEquals("https://cdn.example/audio/en.m3u8", container.audioTracks[0].url)
        assertEquals("English", container.audioTracks[0].lang)
    }

    // EXT-COMPAT-6: relative / protocol-relative / absolute resolution.
    @Test
    fun `subtitle relative url resolution`() {
        val videoUrl = "https://cdn.example/hls/master.m3u8"
        assertEquals(
            "https://cdn.example/subs/en.vtt",
            ExtensionTrackCompat.resolveTrackUrl("/subs/en.vtt", videoUrl)
        )
        assertEquals(
            "https://cdn.example/hls/en.vtt",
            ExtensionTrackCompat.resolveTrackUrl("en.vtt", videoUrl)
        )
        assertEquals(
            "https://other.example/en.vtt",
            ExtensionTrackCompat.resolveTrackUrl("//other.example/en.vtt", videoUrl)
        )
        assertEquals(
            "https://abs.example/en.vtt",
            ExtensionTrackCompat.resolveTrackUrl("https://abs.example/en.vtt", videoUrl)
        )
        assertEquals("", ExtensionTrackCompat.resolveTrackUrl("   ", videoUrl))
    }

    // EXT-COMPAT-4: extension mpv HLS option is preserved in validated form.
    @Test
    fun `extension mpv demuxer option preserved`() = runBlocking {
        val container = passthrough(anizoneLikeVideo()).extract()

        assertTrue(container.mpvFileOptions.contains("demuxer-lavf-o=\"force_mpegts=1\""))
    }

    // EXT-COMPAT-5: unsupported/malicious mpv options are dropped.
    @Test
    fun `malicious mpv args rejected`() {
        val sanitized = ExtensionMpvArgs.sanitize(
            listOf(
                "demuxer-lavf-o" to "force_mpegts=1",
                "vo" to "gpu-next",
                "input-ipc-server" to "/tmp/x",
                "demuxer-lavf-o" to "evil\r\ninjected",
                "sub-file" to "http://evil.example/x",
                "" to "empty",
                "demuxer-lavf-o" to "first-kept-only-ignored"
            )
        )
        assertEquals(mapOf("demuxer-lavf-o" to "force_mpegts=1"), sanitized)
        assertFalse(ExtensionMpvArgs.toFileOptions(sanitized).contains("vo="))
    }

    // EXT-COMPAT-3: ordinary videos without auxiliary tracks are unaffected.
    @Test
    fun `video without aux tracks yields empty compat surface`() = runBlocking {
        val plain = Video(videoUrl = "https://cdn.example/ep1.mp4", videoTitle = "Ep1")
        val container = passthrough(plain).extract()

        assertTrue(container.subtitles.isEmpty())
        assertTrue(container.audioTracks.isEmpty())
        assertEquals("", container.mpvFileOptions)
    }

    // Log redaction: header VALUES never enter diagnostic strings.
    @Test
    fun `redacted log line never contains header values`() {
        val line = ExtensionTrackCompat.redactedTrackLine(
            kind = "sub",
            url = "https://cdn.example/subs/en.vtt",
            headers = mapOf("Referer" to "https://site.example/", "Cookie" to "session=abc123")
        )
        assertTrue(line.contains("https://cdn.example/subs/en.vtt"))
        assertTrue(line.contains("Referer"))
        assertFalse(line.contains("abc123"))
        assertFalse(line.contains("site.example/") && line.contains("Cookie:"))
    }
}
