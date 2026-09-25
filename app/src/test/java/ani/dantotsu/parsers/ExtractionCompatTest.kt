package ani.dantotsu.parsers

import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SAnimeImpl
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.SEpisodeImpl
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.extension.anime.model.AnimeExtension
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Lib16 tranche (upstream 61e8248b, items A–E): extraction compatibility
 * matrix. Fake sources (no Android, no network) driven through the real
 * `DynamicAnimeParser.getVideoList` and the compat getters.
 *
 * Covered: old-API-only source, modern Lib16 hoster source, UOE fallback,
 * old fallback preserved. Boundaries (documented, need extension runtime):
 * resolveVideo modern-implementation ordering (requires AnimeHttpSource),
 * search fallback paths, dub-availability prefs.
 */
class ExtractionCompatTest {

    private open class FakeSource : AnimeSource {
        override val id: Long = 1L
        override val name: String = "Fake"
    }

    /** Old extension: direct videos only; hoster API absent (default throws). */
    private class OldSource(val videos: List<Video>) : FakeSource() {
        override suspend fun getVideoList(episode: SEpisode): List<Video> = videos
    }

    /** Lib16 extension: hosters only; direct list throws UOE. */
    private class Lib16Source(val hosters: List<Hoster>) : FakeSource() {
        override suspend fun getVideoList(episode: SEpisode): List<Video> =
            throw UnsupportedOperationException("Not used")

        override suspend fun getHosterList(episode: SEpisode): List<Hoster> = hosters
    }

    /** Lib16 extension whose update entrypoint throws UOE (old fallback must win). */
    private class UoeUpdateSource(val episodes: List<SEpisode>) : FakeSource() {
        override suspend fun getAnimeEpisodeUpdate(
            anime: SAnime,
            episodes: List<SEpisode>,
            fetchDetails: Boolean,
            fetchEpisodes: Boolean
        ): eu.kanade.tachiyomi.animesource.model.SAnimeEpisodeUpdate =
            throw UnsupportedOperationException("Not used")

        override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> = episodes
    }

    private fun parserFor(source: AnimeSource) = DynamicAnimeParser(
        AnimeExtension.Installed(
            name = "Fake", pkgName = "fake.pkg", versionName = "1.0",
            versionCode = 1L, libVersion = 14.0, lang = "en",
            isNsfw = false, hasReadme = false, hasChangelog = false,
            pkgFactory = null, sources = listOf(source), icon = null
        )
    )

    private fun episode() = SEpisodeImpl().apply {
        url = "https://fake.test/e1"
        name = "E1"
    }

    private fun video(url: String) = Video(videoUrl = url, videoTitle = "v", initialized = true)

    @Test
    fun `old api only source resolves direct videos`() = runBlocking {
        val source = OldSource(listOf(video("https://cdn.test/a.mp4")))
        val out = parserFor(source).getVideoList(source, episode())
        assertEquals(listOf("https://cdn.test/a.mp4"), out.map { it.videoUrl })
    }

    @Test
    fun `lib16 hoster source skips deprecated direct list`() = runBlocking {
        var directCalled = false
        val source = object : FakeSource() {
            override suspend fun getVideoList(episode: SEpisode): List<Video> {
                directCalled = true
                throw UnsupportedOperationException("Not used")
            }

            override suspend fun getHosterList(episode: SEpisode) = listOf(
                Hoster(
                    hosterUrl = "https://h.test/v",
                    hosterName = "H",
                    videoList = listOf(video("https://cdn.test/b.mp4"))
                )
            )
        }
        val out = parserFor(source).getVideoList(source, episode())
        assertEquals(false, directCalled)
        assertEquals(listOf("https://cdn.test/b.mp4"), out.map { it.videoUrl })
    }

    @Test
    fun `lib16 videos keep hoster-qualified titles`() = runBlocking {
        val source = Lib16Source(
            listOf(
                Hoster(
                    hosterUrl = "https://h.test/v",
                    hosterName = "H",
                    videoList = listOf(video("https://cdn.test/c.mp4"))
                )
            )
        )
        val out = parserFor(source).getVideoList(source, episode())
        assertEquals(listOf("H - v"), out.map { it.videoTitle })
    }

    @Test
    fun `episode compat falls back on UOE`() = runBlocking {
        val eps = listOf(episode())
        val source = UoeUpdateSource(eps)
        assertEquals(eps, source.getEpisodeListCompat(SAnimeImpl()))
    }

    @Test
    fun `episode compat uses modern update when present`() = runBlocking {
        val eps = listOf(episode())
        val source = object : FakeSource() {
            override suspend fun getAnimeEpisodeUpdate(
                anime: SAnime,
                episodes: List<SEpisode>,
                fetchDetails: Boolean,
                fetchEpisodes: Boolean
            ) = eu.kanade.tachiyomi.animesource.model.SAnimeEpisodeUpdate(anime, eps)
        }
        assertEquals(eps, source.getEpisodeListCompat(SAnimeImpl()))
    }

    @Test
    fun `season compat resolves directly`() = runBlocking {
        val seasons = listOf(SAnimeImpl().apply { url = "https://fake.test/s" })
        val source = object : FakeSource() {
            override suspend fun getSeasonList(anime: SAnime) = seasons
        }
        assertEquals(seasons, source.getSeasonListCompat(SAnimeImpl()))
    }
}
