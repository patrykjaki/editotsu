package ani.dantotsu.others

import ani.dantotsu.Mapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The id mapper (Isagi2025-idmapper) returns `themoviedb_id` as an object
 * (`{"tv": 1429}`); older payloads and ani.zip use a number or a string.
 * A shape mismatch must not fail the whole AnimeId decode, because
 * tvdb_id (TheIntroDB v4), mal_id and simkl_id come from the same payload.
 */
class AnimeIdDecodeTest {
    private fun decode(json: String): AnimeId = Mapper.json.decodeFromString(AnimeId.serializer(), json)

    // Live response for anilist_id=16498 (Attack on Titan), 2026-11-01.
    private val live = """{"anilist_id": 16498, "mal_id": [16498, 19285, 19391], "simkl_id": 39687,
        "themoviedb_id": {"tv": 1429}, "imdb_id": ["tt2560140", "tt6811662"], "kitsu_id": 7442,
        "anidb_id": 9541, "tvdb_id": 267440}"""

    @Test
    fun `live object-shaped tmdb id decodes and keeps the other ids`() {
        val id = decode(live)
        assertEquals(1429, id.tmdbId)
        assertEquals(267440, id.tvdbId)
        assertEquals(16498, id.malId)
        assertEquals(39687, id.simklId)
        assertEquals("tt2560140", id.imdbId)
    }

    @Test
    fun `number and numeric string shapes still work`() {
        assertEquals(1429, decode("""{"anilist_id": 1, "themoviedb_id": 1429}""").tmdbId)
        assertEquals(1429, decode("""{"anilist_id": 1, "themoviedb_id": "1429"}""").tmdbId)
    }

    @Test
    fun `movie-only or unusable tmdb id is null but does not break decoding`() {
        val movie = decode("""{"anilist_id": 1, "themoviedb_id": {"movie": 99}, "tvdb_id": 5}""")
        assertNull(movie.tmdbId)
        assertEquals(5, movie.tvdbId)
        assertNull(decode("""{"anilist_id": 1, "themoviedb_id": "tv/abc"}""").tmdbId)
        assertNull(decode("""{"anilist_id": 1, "themoviedb_id": null}""").tmdbId)
        assertNull(decode("""{"anilist_id": 1}""").tmdbId)
    }
}
