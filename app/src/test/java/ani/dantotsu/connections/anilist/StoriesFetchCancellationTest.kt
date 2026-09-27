package ani.dantotsu.connections.anilist

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * MAX MERGE M6 HOLD (Blocker 2): cancellation in the AniList/Stories path
 * propagates and never becomes empty/error state; ordinary failures keep
 * their lenient behavior; only host/connect failures signal AniList-down.
 */
class StoriesFetchCancellationTest {

    @Test
    fun `cancelled stories fetch propagates and posts nothing`() = runBlocking {
        try {
            fetchUserStatusLenient { throw CancellationException("gone") }
            fail("expected CancellationException")
        } catch (e: CancellationException) {
            assertEquals("gone", e.message)
        }
    }

    @Test
    fun `ordinary stories failure resolves to empty`() = runBlocking {
        assertTrue(fetchUserStatusLenient { throw IOException("net") }.isEmpty())
    }

    @Test
    fun `null stories result resolves to empty`() = runBlocking {
        assertTrue(fetchUserStatusLenient { null }.isEmpty())
    }

    @Test
    fun `host failure signals down, timeout does not`() {
        assertTrue(shouldSignalAnilistDown(UnknownHostException("dns")))
        assertTrue(shouldSignalAnilistDown(IOException("wrap", java.net.ConnectException("refused"))))
        assertFalse(shouldSignalAnilistDown(SocketTimeoutException("slow")))
        assertFalse(shouldSignalAnilistDown(IOException("plain")))
        assertFalse(shouldSignalAnilistDown(CancellationException("gone")))
    }
}
