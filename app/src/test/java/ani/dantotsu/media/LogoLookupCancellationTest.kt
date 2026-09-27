package ani.dantotsu.media

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

/**
 * M1 FINAL CLOSE (V4 Blockers 1+2): cancellation-safe logo lookup chain.
 *
 * `resolve()` delegates sequencing to [firstResult] and writes the
 * negative cache only after it returns, so a propagating
 * [CancellationException] structurally means: no negative-cache write,
 * no further fallback. These tests prove the chain contract against the
 * real helpers plus the TMDB identity gate.
 */
class LogoLookupCancellationTest {

    @Test
    fun `cancelled lookup throws and later fallbacks never run`() = runBlocking {
        var secondRan = false
        try {
            firstResult(
                listOf(
                    { throw CancellationException("gone") },
                    { secondRan = true; "logo" },
                )
            )
            fail("expected CancellationException")
        } catch (e: CancellationException) {
            assertEquals("gone", e.message)
        }
        assertFalse(secondRan)
    }

    @Test
    fun `ordinary failure falls through to the next lookup`() = runBlocking {
        // Production lookups suppress their own network errors; the chain
        // only sequences them and lets cancellation through.
        assertEquals(
            "b",
            firstResult(
                listOf(
                    { suppressNonCancellation<String> { throw IOException("net down") } },
                    { "b" },
                )
            )
        )
    }

    @Test
    fun `all failing yields null so the caller caches negative once`() = runBlocking {
        assertNull(
            firstResult(
                listOf(
                    { suppressNonCancellation<String> { throw IOException("a") } },
                    { null },
                )
            )
        )
    }

    @Test
    fun `suppressNonCancellation rethrows cancellation but nulls errors`() = runBlocking {
        try {
            suppressNonCancellation<String> { throw CancellationException("gone") }
            fail("expected CancellationException")
        } catch (e: CancellationException) {
            assertEquals("gone", e.message)
        }
        assertNull(suppressNonCancellation<String> { throw IOException("net") })
        assertEquals("ok", suppressNonCancellation { "ok" })
    }

    @Test
    fun `mal mode never invokes the anilist mapper even with a numeric id`() = runBlocking {
        var mapperRan = false
        // Known idTMDB is used directly in MAL mode.
        assertEquals(
            123,
            resolveTmdbId(
                idTMDB = "123",
                allowAnilistMapper = false,
                anilistId = 300,
                lookupAnilistMapper = { mapperRan = true; 999 },
            )
        )
        assertFalse(mapperRan)
        // Without idTMDB the MAL fallback is skipped, not mis-mapped.
        assertNull(
            resolveTmdbId(
                idTMDB = null,
                allowAnilistMapper = false,
                anilistId = 300,
                lookupAnilistMapper = { mapperRan = true; 999 },
            )
        )
        assertFalse(mapperRan)
    }

    @Test
    fun `anilist mode prefers known idTMDB and maps otherwise`() = runBlocking {
        var mappedArg: Int? = null
        assertEquals(
            123,
            resolveTmdbId("123", true, 100) { mappedArg = it; 999 },
        )
        assertNull(mappedArg)
        assertEquals(
            999,
            resolveTmdbId(null, true, 100) { mappedArg = it; 999 },
        )
        assertEquals(100, mappedArg)
    }

    @Test
    fun `cancelled mapper lookup propagates out of the tmdb gate`() = runBlocking {
        try {
            resolveTmdbId(null, true, 100) { throw CancellationException("gone") }
            fail("expected CancellationException")
        } catch (e: CancellationException) {
            assertEquals("gone", e.message)
        }
    }

    @Test
    fun `non-positive anilist id never reaches the mapper`() = runBlocking {
        var mapperRan = false
        assertNull(
            resolveTmdbId(null, true, 0) { mapperRan = true; 999 }
        )
        assertFalse(mapperRan)
    }

    @Test
    fun `first result wins and order is preserved`() = runBlocking {
        var secondRan = false
        assertEquals(
            "a",
            firstResult(listOf({ "a" }, { secondRan = true; "b" }))
        )
        assertFalse(secondRan)
    }
}
