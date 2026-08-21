package ani.dantotsu.media.anime.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MpvEventMapperTest {

    @Test
    fun testHttpErrorClassificationPrecedence() {
        // 403 Forbidden must be SOURCE and NOT retryable (fixing previous bug)
        val err403 = MpvEventMapper.classifyError("HTTP 403 Forbidden connection failed")
        assertEquals(ErrorCategory.SOURCE, err403.category)
        assertFalse(err403.retryable)

        val err401 = MpvEventMapper.classifyError("HTTP 401 Unauthorized access denied")
        assertEquals(ErrorCategory.SOURCE, err401.category)
        assertFalse(err401.retryable)

        val err404 = MpvEventMapper.classifyError("HTTP 404 Not Found")
        assertEquals(ErrorCategory.SOURCE, err404.category)
        assertFalse(err404.retryable)

        val err410 = MpvEventMapper.classifyError("HTTP 410 Gone")
        assertEquals(ErrorCategory.SOURCE, err410.category)
        assertFalse(err410.retryable)

        // 500 & 503 must be NETWORK and retryable
        val err500 = MpvEventMapper.classifyError("HTTP 500 Internal Server Error")
        assertEquals(ErrorCategory.NETWORK, err500.category)
        assertTrue(err500.retryable)

        val err503 = MpvEventMapper.classifyError("HTTP 503 Service Unavailable")
        assertEquals(ErrorCategory.NETWORK, err503.category)
        assertTrue(err503.retryable)

        val errTimeout = MpvEventMapper.classifyError("Connection timed out after 10000ms")
        assertEquals(ErrorCategory.NETWORK, errTimeout.category)
        assertTrue(errTimeout.retryable)

        val errDns = MpvEventMapper.classifyError("Failed to resolve host name")
        assertEquals(ErrorCategory.NETWORK, errDns.category)
        assertTrue(errDns.retryable)

        val errCodec = MpvEventMapper.classifyError("Decoder init failed for codec hevc")
        assertEquals(ErrorCategory.DECODER, errCodec.category)
        assertFalse(errCodec.retryable)
    }

    @Test
    fun testParseEndFileStates() {
        val activeId = 100L

        // Stale event from retired entry
        val stale = MpvEventMapper.parseEndFile("eof", null, 99L, activeId, 1000L, 2000L)
        assertTrue(stale.isStale)

        // Valid EOF
        val eof = MpvEventMapper.parseEndFile("eof", null, 100L, activeId, 1995L, 2000L)
        assertFalse(eof.isStale)
        assertTrue(eof.state is PlaybackState.Ended)
        assertEquals(1995L, (eof.state as PlaybackState.Ended).positionMs)

        // Stop / Quit
        val stop = MpvEventMapper.parseEndFile("stop", null, 100L, activeId, 500L, 2000L)
        assertFalse(stop.isStale)
        assertEquals(PlaybackState.Idle, stop.state)

        // Redirect
        val redirect = MpvEventMapper.parseEndFile("redirect", null, 100L, activeId, 0L, 0L)
        assertFalse(redirect.isStale)
        assertEquals(PlaybackState.Buffering, redirect.state)

        // Error
        val error = MpvEventMapper.parseEndFile("error", "HTTP 403 Forbidden", 100L, activeId, 0L, 0L)
        assertFalse(error.isStale)
        assertTrue(error.state is PlaybackState.Error)
        assertEquals(ErrorCategory.SOURCE, (error.state as PlaybackState.Error).error.category)
    }
}
