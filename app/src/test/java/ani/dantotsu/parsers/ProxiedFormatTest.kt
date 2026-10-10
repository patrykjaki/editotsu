package ani.dantotsu.parsers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * MAX MERGE FINAL-PORT C3 (upstream f1731534): proxied/nested HLS
 * resolution matrix. The stub classifier stands in for the private
 * extension-based `getVideoType`; ordering guarantees are what matter.
 */
class ProxiedFormatTest {

    private val stubClassify: (String) -> VideoType? = { name ->
        when {
            name.endsWith(".mp4", ignoreCase = true) -> VideoType.CONTAINER
            name.endsWith(".m3u8", ignoreCase = true) -> VideoType.M3U8
            name.endsWith(".mpd", ignoreCase = true) -> VideoType.DASH
            name.equals("m3u8", ignoreCase = true) -> VideoType.M3U8
            else -> null
        }
    }

    @Test
    fun `explicit type param wins over url param`() {
        assertEquals(
            VideoType.M3U8,
            resolveProxiedFormat(
                "https://cdn/x?url=https://cdn/v.mp4&type=stream.m3u8",
                "url=https://cdn/v.mp4&type=stream.m3u8",
                stubClassify
            )
        )
    }

    @Test
    fun `type param match is case-insensitive on key`() {
        assertEquals(
            VideoType.DASH,
            resolveProxiedFormat(
                "https://cdn/x?TYPE=s.mpd",
                "TYPE=s.mpd",
                stubClassify
            )
        )
    }

    @Test
    fun `unclassified type falls through to url fallback`() {
        assertEquals(
            VideoType.CONTAINER,
            resolveProxiedFormat(
                "https://cdn/x?type=weird&url=https://cdn/v.mp4",
                "type=weird&url=https://cdn/v.mp4",
                stubClassify
            )
        )
    }

    @Test
    fun `file param is equivalent fallback`() {
        assertEquals(
            VideoType.CONTAINER,
            resolveProxiedFormat(
                "https://cdn/x?file=https://cdn/v.mp4",
                "file=https://cdn/v.mp4",
                stubClassify
            )
        )
    }

    @Test
    fun `m3u8 marker in url is last resort`() {
        assertEquals(
            VideoType.M3U8,
            resolveProxiedFormat(
                "https://proxy/?id=42&src=.m3u8",
                "id=42&src=.m3u8",
                stubClassify
            )
        )
    }

    @Test
    fun `m3u8 marker in query value recognized`() {
        assertEquals(
            VideoType.M3U8,
            resolveProxiedFormat(
                "https://proxy/play",
                "token=abc.M3U8&e=1",
                stubClassify
            )
        )
    }

    @Test
    fun `unrelated media without marker stays null`() {
        assertNull(
            resolveProxiedFormat(
                "https://cdn/play?token=abc123&e=1",
                "token=abc123&e=1",
                stubClassify
            )
        )
    }

    @Test
    fun `empty query stays null`() {
        assertNull(resolveProxiedFormat("https://cdn/play", "", stubClassify))
    }

    @Test
    fun `marker never overrides explicit classification`() {
        // type=mp4 wins even though a stray m3u8 token is present.
        assertEquals(
            VideoType.CONTAINER,
            resolveProxiedFormat(
                "https://cdn/x.m3u8?type=v.mp4",
                "type=v.mp4",
                stubClassify
            )
        )
    }
}
