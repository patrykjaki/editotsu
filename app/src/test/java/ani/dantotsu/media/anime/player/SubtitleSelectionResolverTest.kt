package ani.dantotsu.media.anime.player

import org.junit.Assert.assertEquals
import org.junit.Test

class SubtitleSelectionResolverTest {

    @Test
    fun testResolveSubtitleUri() {
        val embedUrl = "https://server.anime.net/embed/ep123"
        val mediaUrl = "https://cdn.anime.net/hls/ep123/master.m3u8"

        // Absolute URLs
        assertEquals("https://cdn.anime.net/subs/en.vtt", SubtitleSelectionResolver.resolveSubtitleUri("https://cdn.anime.net/subs/en.vtt", embedUrl, mediaUrl))
        assertEquals("http://cdn.anime.net/subs/en.vtt", SubtitleSelectionResolver.resolveSubtitleUri("http://cdn.anime.net/subs/en.vtt", embedUrl, mediaUrl))
        assertEquals("file:///sdcard/subs/en.vtt", SubtitleSelectionResolver.resolveSubtitleUri("file:///sdcard/subs/en.vtt", embedUrl, mediaUrl))

        // Protocol-relative
        assertEquals("https://cdn.anime.net/subs/en.vtt", SubtitleSelectionResolver.resolveSubtitleUri("//cdn.anime.net/subs/en.vtt", embedUrl, mediaUrl))

        // Root-relative
        assertEquals("https://server.anime.net/subs/en.vtt", SubtitleSelectionResolver.resolveSubtitleUri("/subs/en.vtt", embedUrl, mediaUrl))

        // Relative paths
        assertEquals("https://server.anime.net/embed/sub.ass", SubtitleSelectionResolver.resolveSubtitleUri("sub.ass", embedUrl, mediaUrl))
        assertEquals("https://server.anime.net/sub.ass", SubtitleSelectionResolver.resolveSubtitleUri("../sub.ass", embedUrl, mediaUrl))
    }
}
