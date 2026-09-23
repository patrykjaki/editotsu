package ani.dantotsu.media.anime.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CP4-B (REPO_REVIEW §3.6): sentinel-secret tests. Secrets planted in URIs, mpv command
 * arguments, and option/property values must NEVER survive the logging boundary —
 * whatever string these functions return is what reaches logcat and Logger.shareLog.
 */
class MpvLogRedactorTest {

    private val sentinelToken = "SUPERSECRET_TOKEN_9f8e7d6c"
    private val signedUrl = "https://cdn.example.com/video/master.m3u8?token=$sentinelToken&expires=1799999999"
    private val authHeader = "Authorization: Bearer $sentinelToken"
    private val cookieHeader = "Cookie: session=$sentinelToken; theme=dark"

    // ---------- redactUri ----------

    @Test
    fun testUriQueryStringValuesAreMasked() {
        val out = MpvLogRedactor.redactUri(signedUrl)
        assertFalse(sentinelToken in out)
        assertTrue("token=" in out)
        assertTrue("expires=" in out)          // keys retained for debugging
        assertTrue("master.m3u8" in out)       // path retained
    }

    @Test
    fun testContentUriWithoutQueryPassesThrough() {
        val uri = "content://media/external/video/12345"
        assertEquals(uri, MpvLogRedactor.redactUri(uri))
    }

    @Test
    fun testUriWithoutQueryUnchanged() {
        val uri = "https://cdn.example.com/video.mp4"
        assertEquals(uri, MpvLogRedactor.redactUri(uri))
    }

    // ---------- redactCommandArgs ----------

    @Test
    fun testHeaderFieldsSeparateValueTokenIsMasked() {
        val out = MpvLogRedactor.redactCommandArgs(
            listOf("set", "http-header-fields", "$authHeader,$cookieHeader")
        )
        assertFalse(sentinelToken in out.joinToString(" "))
        assertEquals("set", out[0])
        assertEquals("http-header-fields", out[1])
        assertEquals(MpvLogRedactor.REDACTED, out[2])
    }

    @Test
    fun testHeaderFieldsInlineFormIsMasked() {
        val out = MpvLogRedactor.redactCommandArgs(
            listOf("set", "http-header-fields=$authHeader")
        )
        assertFalse(sentinelToken in out.joinToString(" "))
        assertTrue(out.any { it.startsWith("http-header-fields=") })
    }

    @Test
    fun testCredentialPatternInsideArbitraryTokenIsMasked() {
        val out = MpvLogRedactor.redactCommandArgs(
            listOf("loadfile", "https://h/x?auth=Bearer+$sentinelToken")
        )
        assertFalse(sentinelToken in out.joinToString(" "))
    }

    @Test
    fun testLoadfileUrlQueryIsMaskedButPathKept() {
        val out = MpvLogRedactor.redactCommandArgs(listOf("loadfile", signedUrl))
        assertFalse(sentinelToken in out.joinToString(" "))
        assertTrue(out[1].contains("master.m3u8"))
    }

    @Test
    fun testBenignCommandArgsPassThrough() {
        val args = listOf("set", "hwdec", "auto-safe")
        assertEquals(args, MpvLogRedactor.redactCommandArgs(args))
    }

    // ---------- redactOptionValue ----------

    @Test
    fun testSafeOptionValuesPassThroughAndOthersRedacted() {
        assertEquals("auto-safe", MpvLogRedactor.redactOptionValue("hwdec", "auto-safe"))
        assertEquals("[pathKind: sub-fonts]", MpvLogRedactor.redactOptionValue("sub-fonts-dir", "/data/secret/path"))
        assertEquals(MpvLogRedactor.REDACTED, MpvLogRedactor.redactOptionValue("file-open-options", "--http-header-fields=$authHeader"))
    }

    // ---------- redactPropertyValue ----------

    @Test
    fun testPropertyValuesRedactedByNameClass() {
        assertEquals("true", MpvLogRedactor.redactPropertyValue("pause", "true"))
        assertEquals("[pathKind: stream-open-filename]",
            MpvLogRedactor.redactPropertyValue("stream-open-filename", signedUrl))
        assertEquals(
            MpvLogRedactor.REDACTED,
            MpvLogRedactor.redactPropertyValue("unknown-property", signedUrl)
        )
    }
}
