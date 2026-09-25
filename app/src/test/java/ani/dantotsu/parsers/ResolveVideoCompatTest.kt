package ani.dantotsu.parsers

import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SAnimeImpl
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.SEpisodeImpl
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.extension.anime.model.AnimeExtension
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okhttp3.Headers.Companion.headersOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Review HOLD corrections: resolveVideo validity, cancellation
 * propagation, season dual-interface ordering, hoster fallback.
 */
class ResolveVideoCompatTest {

    private open class HttpFake : AnimeHttpSource() {
        override val id: Long = 2L
        override val name: String = "HttpFake"
        override val baseUrl: String = "https://fake.test"
        override val lang: String = "en"
        override val supportsLatest: Boolean = false
        var resolveResult: Video? = null
        var resolveThrows: Throwable? = null
        var legacyUrl: String? = null
        var legacyThrows: Throwable? = null

        override suspend fun resolveVideo(video: Video): Video? {
            resolveThrows?.let { throw it }
            return resolveResult
        }

        override suspend fun getVideoUrl(video: Video): String {
            legacyThrows?.let { throw it }
            return legacyUrl ?: throw UnsupportedOperationException("no legacy")
        }
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

    private fun runList(source: HttpFake, video: Video): List<Video> {
        return runBlocking {
            parserFor(source).getVideoList(
                object : AnimeHttpSource() {
                    override val id: Long = 4L
                    override val name: String = "Combo"
                    override val baseUrl: String = "https://fake.test"
                    override val lang: String = "en"
                    override val supportsLatest: Boolean = false
                    override suspend fun getVideoList(episode: SEpisode) =
                        emptyList<Video>()

                    override suspend fun getHosterList(episode: SEpisode) =
                        listOf(
                            Hoster(
                                hosterUrl = "https://h.test/v",
                                hosterName = "H",
                                videoList = listOf(video)
                            )
                        )

                    override suspend fun resolveVideo(v: Video): Video? {
                        source.resolveThrows?.let { throw it }
                        return source.resolveResult
                    }

                    override suspend fun getVideoUrl(v: Video): String {
                        source.legacyThrows?.let { throw it }
                        return source.legacyUrl
                            ?: throw UnsupportedOperationException("no legacy")
                    }
                },
                episode()
            )
        }
    }

    private fun blankVideo() = Video(videoUrl = "", videoTitle = "t", initialized = false)

    @Test
    fun `modern useful url wins with headers preserved`() {
        val source = HttpFake().apply {
            resolveResult = Video(
                videoUrl = "https://cdn.test/modern.mp4",
                videoTitle = "t",
                headers = headersOf("Referer", "https://fake.test"),
                initialized = true
            )
            legacyUrl = "https://cdn.test/legacy.mp4"
        }
        val out = runList(source, blankVideo())
        assertEquals(1, out.size)
        assertEquals("https://cdn.test/modern.mp4", out.single().videoUrl)
        assertEquals("https://fake.test", out.single().headers?.get("Referer"))
    }

    @Test
    fun `modern null falls to legacy`() {
        val source = HttpFake().apply {
            resolveResult = null
            legacyUrl = "https://cdn.test/legacy.mp4"
        }
        val out = runList(source, blankVideo())
        assertEquals(listOf("https://cdn.test/legacy.mp4"), out.map { it.videoUrl })
        assertTrue(out.single().initialized)
    }

    @Test
    fun `modern blank falls to legacy`() {
        val source = HttpFake().apply {
            resolveResult = Video(videoUrl = "", videoTitle = "t")
            legacyUrl = "https://cdn.test/legacy.mp4"
        }
        val out = runList(source, blankVideo())
        assertEquals(listOf("https://cdn.test/legacy.mp4"), out.map { it.videoUrl })
    }

    @Test
    fun `modern null-string falls to legacy`() {
        val source = HttpFake().apply {
            resolveResult = Video(videoUrl = "null", videoTitle = "t")
            legacyUrl = "https://cdn.test/legacy.mp4"
        }
        val out = runList(source, blankVideo())
        assertEquals(listOf("https://cdn.test/legacy.mp4"), out.map { it.videoUrl })
    }

    @Test
    fun `modern throws falls to legacy`() {
        val source = HttpFake().apply {
            resolveThrows = UnsupportedOperationException("x")
            legacyUrl = "https://cdn.test/legacy.mp4"
        }
        val out = runList(source, blankVideo())
        assertEquals(listOf("https://cdn.test/legacy.mp4"), out.map { it.videoUrl })
    }

    @Test
    fun `both fail yields no unplayable entries`() {
        val source = HttpFake().apply {
            resolveResult = null
            legacyThrows = IllegalStateException("down")
        }
        val out = runList(source, blankVideo())
        assertTrue(out.isEmpty())
    }
}
