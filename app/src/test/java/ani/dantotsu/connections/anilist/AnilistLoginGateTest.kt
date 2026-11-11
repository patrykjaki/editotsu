package ani.dantotsu.connections.anilist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnilistLoginGateTest {

    @Test
    fun parsesTokenFromBothSchemes() {
        assertEquals("abc.def.ghi", AnilistLoginGate.parseToken("editotsu://anilist#access_token=abc.def.ghi&token_type=Bearer&expires_in=31536000"))
        assertEquals("abc.def.ghi", AnilistLoginGate.parseToken("dantotsu://anilist#access_token=abc.def.ghi&token_type=Bearer"))
    }

    @Test
    fun noTokenWithoutTheExpectedShape() {
        assertNull(AnilistLoginGate.parseToken(null))
        assertNull(AnilistLoginGate.parseToken("editotsu://anilist"))
        assertNull(AnilistLoginGate.parseToken("editotsu://anilist#access_token=&token_type=Bearer"))
        assertNull(AnilistLoginGate.parseToken("editotsu://anilist#access_token=abc"))
    }

    @Test
    fun pendingOnlyWithinTheTimeout() {
        val start = 1_000_000L
        assertTrue(AnilistLoginGate.isPending(start, start))
        assertTrue(AnilistLoginGate.isPending(start, start + AnilistLoginGate.TIMEOUT_MS))
        assertFalse(AnilistLoginGate.isPending(start, start + AnilistLoginGate.TIMEOUT_MS + 1))
    }

    @Test
    fun notPendingWhenNeverStartedOrClockWentBack() {
        assertFalse(AnilistLoginGate.isPending(0L, 5_000L))
        assertFalse(AnilistLoginGate.isPending(10_000L, 9_000L))
    }
}
