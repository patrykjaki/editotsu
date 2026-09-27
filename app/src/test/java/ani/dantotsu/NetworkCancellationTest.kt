package ani.dantotsu

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

/**
 * MAX MERGE M6: cancellation discipline for shared coroutine helpers.
 * Cancellation must propagate (never become null/log/snackbar); ordinary
 * failures keep old behavior.
 */
class NetworkCancellationTest {

    @Test
    fun `tryWithSuspend rethrows cancellation before touching android logging`() = runBlocking {
        try {
            tryWithSuspend { throw CancellationException("gone") }
            fail("expected CancellationException")
        } catch (e: CancellationException) {
            assertEquals("gone", e.message)
        }
    }

    @Test
    fun `asyncMap rethrows cancellation instead of nulling it`() = runBlocking {
        try {
            listOf(1, 2).asyncMap { throw CancellationException("gone") }
            fail("expected CancellationException")
        } catch (e: CancellationException) {
            assertEquals("gone", e.message)
        }
    }

    @Test
    fun `asyncMapNotNull rethrows cancellation instead of nulling it`() = runBlocking {
        try {
            listOf(1, 2).asyncMapNotNull<Int, Int> { throw CancellationException("gone") }
            fail("expected CancellationException")
        } catch (e: CancellationException) {
            assertEquals("gone", e.message)
        }
    }

    @Test
    fun `asyncMap still nulls ordinary failures`() = runBlocking {
        assertTrue(listOf(1).asyncMap<Int, Int> { throw IOException("net") }.isEmpty())
    }
}
