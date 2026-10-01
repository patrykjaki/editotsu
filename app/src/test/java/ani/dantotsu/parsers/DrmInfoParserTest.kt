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

    @Test
    fun `unrelated internalData stays clear`() {
        assertTrue(parseDrmMetadata(null) is DrmParseResult.Clear)
        assertTrue(parseDrmMetadata(emptyMap()) is DrmParseResult.Clear)
        assertTrue(
            parseDrmMetadata(mapOf("unrelated" to "extension-data")) is DrmParseResult.Clear
        )
    }

    @Test
    fun `valid license with default scheme is valid widevine`() {
        val result = parseDrmMetadata(mapOf("licenseUrl" to "https://lic.example/wv"))
        assertTrue(result is DrmParseResult.Valid)
        assertEquals("widevine", (result as DrmParseResult.Valid).info.scheme)
    }

    @Test
    fun `explicit widevine plus blank license is invalid, never clear`() {
        val result = parseDrmMetadata(
            mapOf("drmScheme" to "widevine", "licenseUrl" to "")
        )
        assertTrue(result is DrmParseResult.Invalid)
    }

    @Test
    fun `unknown explicit scheme is invalid, never clear`() {
        val result = parseDrmMetadata(
            mapOf("drmScheme" to "rot13", "licenseUrl" to "https://lic.example/x")
        )
        assertTrue(result is DrmParseResult.Invalid)
    }

    @Test
    fun `supported metadata parses valid end to end`() {
        val result = parseDrmMetadata(
            mapOf(
                "drmScheme" to "playready",
                "licenseUrl" to "https://lic.example/pr",
                "licenseHeaders" to mapOf("Authorization" to "Bearer x")
            )
        )
        assertTrue(result is DrmParseResult.Valid)
        val info = (result as DrmParseResult.Valid).info
        assertEquals("playready", info.scheme)
        assertEquals("https://lic.example/pr", info.licenseUrl)
    }
}
