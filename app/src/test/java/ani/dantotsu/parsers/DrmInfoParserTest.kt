package ani.dantotsu.parsers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MAX MERGE M4B DRM (HOLD2): extension DRM metadata mapping. Malformed or
 * absent metadata must always resolve to null so playback stays on the
 * clear-content (MPV) path.
 */
class DrmInfoParserTest {

    @Test
    fun `full shape maps`() {
        val info = drmInfoFromMap(
            mapOf(
                "drmScheme" to "widevine",
                "licenseUrl" to "https://lic.example/wv",
                "licenseHeaders" to mapOf("Authorization" to "Bearer x"),
                "offline" to mapOf(
                    "manifestUrl" to "https://dl.example/manifest.mpd",
                    "licenseUrl" to "https://dl.example/license",
                    "licenseHeaders" to mapOf("Authorization" to "Bearer y"),
                    "headers" to mapOf("User-Agent" to "u")
                )
            )
        )!!
        assertEquals("widevine", info.scheme)
        assertEquals("https://lic.example/wv", info.licenseUrl)
        assertEquals(mapOf("Authorization" to "Bearer x"), info.licenseHeaders)
        assertEquals("https://dl.example/manifest.mpd", info.offline!!.manifestUrl)
        assertEquals("https://dl.example/license", info.offline!!.licenseUrl)
    }

    @Test
    fun `minimal shape defaults scheme to widevine`() {
        val info = drmInfoFromMap(mapOf("licenseUrl" to "https://lic.example/wv"))!!
        assertEquals("widevine", info.scheme)
        assertTrue(info.licenseHeaders.isEmpty())
        assertNull(info.offline)
    }

    @Test
    fun `absent or blank license stays clear path`() {
        assertNull(drmInfoFromMap(null))
        assertNull(drmInfoFromMap(emptyMap()))
        assertNull(drmInfoFromMap(mapOf("drmScheme" to "widevine")))
        assertNull(drmInfoFromMap(mapOf("licenseUrl" to "  ")))
        assertNull(drmInfoFromMap(mapOf("unrelated" to "extension-data")))
    }

    @Test
    fun `incomplete offline block is dropped, online kept`() {
        val info = drmInfoFromMap(
            mapOf(
                "licenseUrl" to "https://lic.example/wv",
                "offline" to mapOf("manifestUrl" to "https://dl.example/m.mpd")
            )
        )!!
        assertNull(info.offline)
    }

    @Test
    fun `non-string header values coerce safely`() {
        val info = drmInfoFromMap(
            mapOf(
                "licenseUrl" to "https://lic.example/wv",
                "licenseHeaders" to mapOf("Authorization" to 42, 7 to "x")
            )
        )!!
        assertEquals(mapOf("Authorization" to ""), info.licenseHeaders)
    }
}
