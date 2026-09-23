package ani.dantotsu.media.anime.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CP4v1-04 regressions: the credential-bearing failure paths of [RealMpvClient] format their
 * diagnostics ONLY through the shared builders below (the catch blocks delegate to them), so
 * these tests pin EXACTLY what reaches logcat/shareLog when mpv/JNI throws with a sentinel
 * secret inside its exception message. (`MPV` itself is `final`, so direct injection is not
 * possible without a production refactor — the builder path is the production formatting path.)
 */
class MpvFailurePathRedactionTest {

    private val sentinel = "SUPERSECRET_TOKEN_9f8e7d6c"
    private val signedUrl = "https://cdn.example.com/v/master.m3u8?token=$sentinel"
    private val authHeader = "Authorization: Bearer $sentinel"

    private fun failing(op: String) =
        IllegalStateException("$op failed for $signedUrl [$authHeader]")

    /** 1) mpv command failure */
    @Test
    fun testCommandFailureDiagnosticNeverLeaksSentinel() {
        val diag = commandFailureDiagnostic(failing("command"))
        assertFalse(sentinel in diag)
        assertFalse("Bearer" in diag)
        assertTrue("mpv.command failed" in diag)
        assertTrue("<redacted>" in diag) // query/header scrubbed visibly
    }

    /** 2) option-setting failure (safeValue already redacted by the option allowlist) */
    @Test
    fun testSetOptionFailureDiagnosticNeverLeaksSentinel() {
        val safeValue = MpvLogRedactor.redactOptionValue(
            "file-open-options", "--http-header-fields=$authHeader"
        )
        val diag = setOptionFailureDiagnostic("file-open-options", safeValue, failing("setOptionString"))
        assertFalse(sentinel in diag)
        assertFalse(authHeader in diag)
        assertTrue("file-open-options=<redacted>" in diag)
    }

    /** 3) property-setting failure (value redacted by name class, cause message scrubbed) */
    @Test
    fun testSetPropertyFailureDiagnosticNeverLeaksSentinel() {
        val diag = setPropertyFailureDiagnostic("stream-open-filename", signedUrl, failing("setPropertyString"))
        assertFalse(sentinel in diag)
        assertFalse(signedUrl in diag)
        assertTrue("[pathKind: stream-open-filename]" in diag)
    }

    /**
     * 4b) OUTER load-media failure: the PlaybackError message and the adjacent log line share
     * one scrubbed builder (CP4v2-02) — sentinel URL query + credential header must not survive.
     */
    @Test
    fun testOuterLoadMediaFailureMessageNeverLeaksSentinel() {
        val cause = IllegalStateException(
            "open failed for $signedUrl [$authHeader] (http-header-fields)"
        )
        val msg = MpvPlaybackEngine.loadMediaFailureMessage(cause.message)
        assertFalse(sentinel in msg)
        assertFalse(authHeader in msg)
        assertFalse(signedUrl in msg)
        assertTrue("Failed to load media:" in msg)
        assertTrue("open failed for" in msg) // useful non-sensitive context retained

        // The log line and the PlaybackError share the SAME builder output.
        val logLine = "MPVSTEP ERROR in loadMedia: ${MpvLogRedactor.redactDiagnosticText(cause.message)}"
        assertFalse(sentinel in logLine)
    }

    /**
     * 4) content-URI open-failure PlaybackError messages go through ONE scrubbing builder
     * used by the engine — both cause message and URI must be sanitized.
     */
    @Test
    fun testContentUriOpenFailureMessageIsFullyScrubbed() {
        val msg = MpvPlaybackEngine.contentOpenFailureMessage(
            operation = "Failed to open content URI",
            uri = "content://media/external/file/42?token=$sentinel",
            causeMessage = "Permission denied while fetching $signedUrl [$authHeader]"
        )
        assertFalse(sentinel in msg)
        assertFalse(authHeader in msg)
        assertTrue("content://media/external/file/42" in msg)
        assertTrue("Permission denied" in msg) // useful diagnostics retained
    }
}
