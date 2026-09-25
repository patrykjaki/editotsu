package ani.dantotsu.parsers

import eu.kanade.tachiyomi.animesource.AnimeCatalogueSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SAnimeImpl
import eu.kanade.tachiyomi.extension.anime.model.AnimeExtension
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import rx.Observable

/**
 * Review HOLD round 3, Blocker 2: the ACTUAL DynamicAnimeParser.search()
 * must propagate coroutine cancellation end-to-end (the inner rethrow
 * alone is swallowed by the outer generic handler without the fix).
 */
private fun searchAnime(title: String) = SAnimeImpl().apply {
    url = "https://fake.test/$title"
    this.title = title
}

class ParserSearchCancellationTest {

    private open class SearchFake(
        var searchThrows: Throwable? = null,
        var legacyThrows: Throwable? = null,
        var legacyPages: AnimesPage? = null,
    ) : AnimeCatalogueSource {
        override val id: Long = 9L
        override val name: String = "SearchFake"
        override val lang: String = "en"
        override val supportsLatest: Boolean = false

        override suspend fun getSearchAnime(
            page: Int,
            query: String,
            filters: AnimeFilterList
        ): AnimesPage {
            searchThrows?.let { throw it }
            return AnimesPage(listOf(searchAnime("Modern")), false)
        }

        override fun getFilterList(): AnimeFilterList = AnimeFilterList(emptyList())

        override fun fetchSearchAnime(
            page: Int,
            query: String,
            filters: AnimeFilterList
        ): Observable<AnimesPage> {
            legacyThrows?.let { throw it }
            return Observable.just(legacyPages ?: AnimesPage(listOf(searchAnime("Legacy")), false))
        }
    }

    private fun parserFor(source: AnimeCatalogueSource) = DynamicAnimeParser(
        AnimeExtension.Installed(
            name = "Fake", pkgName = "fake.pkg", versionName = "1.0",
            versionCode = 1L, libVersion = 14.0, lang = "en",
            isNsfw = false, hasReadme = false, hasChangelog = false,
            pkgFactory = null, sources = listOf(source), icon = null
        )
    )

    private fun searchNames(source: SearchFake): List<String> =
        runBlocking { parserFor(source).search("q").map { it.name } }

    @Test
    fun `modern cancellation reaches the caller`() {
        val source = SearchFake(searchThrows = CancellationException("gone"))
        try {
            searchNames(source)
            fail("expected CancellationException")
        } catch (e: CancellationException) {
            assertEquals("gone", e.message)
        }
    }

    @Test
    fun `modern unsupported falls back to legacy`() {
        val source = SearchFake(searchThrows = UnsupportedOperationException("old"))
        assertEquals(listOf("Legacy"), searchNames(source))
    }

    @Test
    fun `legacy cancellation reaches the caller`() {
        val source = SearchFake(
            searchThrows = UnsupportedOperationException("old"),
            legacyThrows = CancellationException("gone")
        )
        try {
            searchNames(source)
            fail("expected CancellationException")
        } catch (e: CancellationException) {
            assertEquals("gone", e.message)
        }
    }

    @Test
    fun `both paths failing keeps safe empty result`() {
        val source = SearchFake(
            searchThrows = IllegalStateException("broken"),
            legacyThrows = IllegalStateException("also broken")
        )
        assertTrue(searchNames(source).isEmpty())
    }
}
