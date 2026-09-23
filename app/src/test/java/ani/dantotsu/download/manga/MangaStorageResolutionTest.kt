package ani.dantotsu.download.manga

import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Handoff-B regressions: one shared exception-safe storage resolution boundary
 * ([MangaStorageResolution]) for the authoritative manga destructive operations
 * and reconciliation availability.
 *
 * - root resolves -> Available (proceed);
 * - root null/unresolvable -> Unavailable (no metadata mutation);
 * - root resolution throws SecurityException / representative provider exception ->
 *   Unavailable (no metadata mutation, no exception escape);
 * - coroutine CancellationException is never swallowed into Unavailable.
 *
 * The resolver is injected, so no Android storage stack is needed: production
 * wires `getBaseDirectory`, and these tests prove the boundary maps every
 * resolver outcome exactly once.
 */
class MangaStorageResolutionTest {

    @Test
    fun resolverAvailable_proceeds() {
        val token = Object()
        val root = MangaStorageResolution.resolve { token }
        assertTrue(root is MangaStorageResolution.Root.Available)
        assertEquals(token, (root as MangaStorageResolution.Root.Available).directory)
    }

    @Test
    fun resolverNull_isUnavailable() {
        val root = MangaStorageResolution.resolve { null }
        assertTrue(root is MangaStorageResolution.Root.Unavailable)
    }

    @Test
    fun resolverThrowsSecurityException_isUnavailable() {
        val root = MangaStorageResolution.resolve<Any> {
            throw SecurityException("persisted SAF permission revoked")
        }
        assertTrue(root is MangaStorageResolution.Root.Unavailable)
        assertTrue(
            (root as MangaStorageResolution.Root.Unavailable).reason.contains("SecurityException")
        )
    }

    @Test
    fun resolverThrowsProviderException_isUnavailable() {
        val root = MangaStorageResolution.resolve<Any> {
            throw IllegalStateException("representative provider failure")
        }
        assertTrue(root is MangaStorageResolution.Root.Unavailable)
        assertTrue(
            (root as MangaStorageResolution.Root.Unavailable).reason.contains("IllegalStateException")
        )
    }

    @Test
    fun resolverCancellationException_propagates() {
        try {
            MangaStorageResolution.resolve<Any> {
                throw CancellationException("must not be swallowed into Unavailable")
            }
            fail("CancellationException must propagate, not map to Unavailable")
        } catch (_: CancellationException) {
            // Expected: coroutine cancellation is never masked as storage state.
        }
    }
}
