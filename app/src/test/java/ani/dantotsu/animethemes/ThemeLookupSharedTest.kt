package ani.dantotsu.animethemes

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/** A lookup the screen stopped waiting for still finishes and is cached. */
class ThemeLookupSharedTest {

    private lateinit var dir: File

    /** Answers only when [release] completes. */
    private class SlowApi : AnimeThemesApiClient {
        val release = CompletableDeferred<Unit>()
        var calls = 0
        override suspend fun queryAnimeByExternalSite(site: String, externalId: Int): AnimeThemesApiResult {
            calls++
            release.await()
            return AnimeThemesApiResult.Success(AnimeThemesGraphQLResponse(data = AnimeThemesDataResponse(emptyList())))
        }
    }

    @Before
    fun setUp() {
        dir = java.nio.file.Files.createTempDirectory("themes_shared_").toFile()
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun repo(api: AnimeThemesApiClient) = AnimeThemesRepository(
        api = api,
        cacheDirProvider = { dir },
        atomicFileWriterFactory = { StandaloneAtomicFileWriter(it) },
    )

    @Test
    fun cancelledWaitDoesNotCancelTheLookup() = runBlocking {
        val api = SlowApi()
        val repository = repo(api)
        val waiter = async { repository.lookupShared(7, null).await() }
        while (api.calls == 0) yield()
        waiter.cancel()

        // A second visit joins the running lookup instead of starting another.
        val again = repository.lookupShared(7, null)
        api.release.complete(Unit)
        assertEquals(ThemesLookup.NoMatch, withTimeout(5_000) { again.await() })
        assertEquals(1, api.calls)
        assertTrue(File(dir, AnimeThemesRepository.CACHE_FILE_NAME).exists())
    }

    @Test
    fun sameShowSharesOneLookup() {
        val api = SlowApi()
        val repository = repo(api)
        val first = repository.lookupShared(8, null)
        assertSame(first, repository.lookupShared(8, null))
        api.release.complete(Unit)
    }
}
