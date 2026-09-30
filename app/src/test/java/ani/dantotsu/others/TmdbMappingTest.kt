package ani.dantotsu.others

import ani.dantotsu.media.EpisodeMapper
import ani.dantotsu.media.anime.Episode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

/**
 * MAX MERGE M5 correction 2: bulk TMDB fallback must key metadata by
 * LOCAL episode identity, fetching each required external season once.
 * Exercises the real resolveTmdbEpisodes seam (fake map/fetch lambdas),
 * not URL builders.
 */
class TmdbMappingTest {

    private fun ep(title: String) = Episode(number = "?", title = title)

    private fun season(rows: Map<String, String>): Map<String, Episode> =
        rows.mapValues { (k, v) -> Episode(number = k, title = v) }

    @Test
    fun `local E1 to S1E1 lands on local key 1`() = runBlocking {
        val out = TmdbService.resolveTmdbEpisodes(
            listOf("1"),
            mapEpisode = { EpisodeMapper.SeasonEpisode(1, 1) },
            fetchSeason = { mapOf("1" to ep("Pilot")) }
        )
        assertEquals("Pilot", out["1"]?.title)
    }

    @Test
    fun `local E1 to S2E1 lands on local key 1`() = runBlocking {
        val out = TmdbService.resolveTmdbEpisodes(
            listOf("1"),
            mapEpisode = { EpisodeMapper.SeasonEpisode(2, 1) },
            fetchSeason = { season ->
                assertEquals(2, season)
                mapOf("1" to ep("Sequel Premiere"))
            }
        )
        assertEquals("Sequel Premiere", out["1"]?.title)
    }

    @Test
    fun `local E13 to S2E1 lands on local key 13 with local number`() = runBlocking {
        val out = TmdbService.resolveTmdbEpisodes(
            listOf("13"),
            mapEpisode = { EpisodeMapper.SeasonEpisode(2, 1) },
            fetchSeason = { season ->
                assertEquals(2, season)
                mapOf("1" to ep("S2E1 Title"), "2" to ep("S2E2 Title"))
            }
        )
        assertEquals(listOf("13"), out.keys.toList())
        assertEquals("S2E1 Title", out["13"]?.title)
        assertEquals("13", out["13"]?.number)
        assertEquals("2", out["13"]?.extra?.get("season"))
        assertEquals("1", out["13"]?.extra?.get("episode"))
    }

    @Test
    fun `multiple locals in one season fetch once`() = runBlocking {
        var fetches = 0
        val out = TmdbService.resolveTmdbEpisodes(
            listOf("1", "2", "3"),
            mapEpisode = { EpisodeMapper.SeasonEpisode(2, it.toInt()) },
            fetchSeason = {
                fetches++
                season(mapOf("1" to "A", "2" to "B", "3" to "C"))
            }
        )
        assertEquals(1, fetches)
        assertEquals(listOf("A", "B", "C"), listOf("1", "2", "3").map { out[it]?.title })
    }

    @Test
    fun `locals spanning seasons fetch each once with local keys`() = runBlocking {
        val fetched = mutableListOf<Int>()
        val out = TmdbService.resolveTmdbEpisodes(
            listOf("1", "2", "13"),
            mapEpisode = { key ->
                if (key == "13") EpisodeMapper.SeasonEpisode(2, 1)
                else EpisodeMapper.SeasonEpisode(1, key.toInt())
            },
            fetchSeason = { season ->
                fetched.add(season)
                if (season == 1) season(mapOf("1" to "S1E1", "2" to "S1E2"))
                else season(mapOf("1" to "S2E1"))
            }
        )
        assertEquals(listOf(1, 2), fetched.sorted())
        assertEquals("S1E1", out["1"]?.title)
        assertEquals("S1E2", out["2"]?.title)
        assertEquals("S2E1", out["13"]?.title)
    }

    @Test
    fun `missing external row leaves that local key empty`() = runBlocking {
        val out = TmdbService.resolveTmdbEpisodes(
            listOf("1", "2"),
            mapEpisode = { EpisodeMapper.SeasonEpisode(1, it.toInt()) },
            fetchSeason = { season(mapOf("1" to "Only E1")) }
        )
        assertEquals(listOf("1"), out.keys.toList())
    }

    @Test
    fun `empty local domain fetches nothing`() = runBlocking {
        var fetched = false
        val out = TmdbService.resolveTmdbEpisodes(
            emptyList(),
            mapEpisode = { EpisodeMapper.SeasonEpisode(1, 1) },
            fetchSeason = { fetched = true; emptyMap() }
        )
        assertTrue(out.isEmpty())
        assertEquals(false, fetched)
    }

    @Test
    fun `mapper cancellation escapes without fetching`() = runBlocking {
        var fetched = false
        try {
            TmdbService.resolveTmdbEpisodes(
                listOf("1"),
                mapEpisode = { throw CancellationException("gone") },
                fetchSeason = { fetched = true; emptyMap() }
            )
            fail("cancellation must escape")
        } catch (_: CancellationException) {
        }
        assertEquals(false, fetched)
    }

    @Test
    fun `ordinary mapping failure omits that key without touching others`() = runBlocking {
        var fetchedSeason = -1
        val out = TmdbService.resolveTmdbEpisodes(
            listOf("4", "5"),
            mapEpisode = { key ->
                if (key == "4") throw IOException("map down")
                EpisodeMapper.SeasonEpisode(2, 1)
            },
            fetchSeason = { season ->
                fetchedSeason = season
                season(mapOf("1" to "S2E1"))
            }
        )
        // Failed key receives no unverified S1 row; the mapped key still
        // resolves correctly.
        assertEquals(2, fetchedSeason)
        assertEquals(listOf("5"), out.keys.toList())
        assertEquals("S2E1", out["5"]?.title)
        assertEquals("5", out["5"]?.number)
    }
}
