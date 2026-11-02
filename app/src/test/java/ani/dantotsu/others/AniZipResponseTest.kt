package ani.dantotsu.others

import ani.dantotsu.Mapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AniZipResponseTest {

    @Test
    fun readsImdbIdFromFlatMappings() {
        val json = """{"episodes":{},"mappings":{"animeplanet_id":"dragon-ball-super","kitsu_id":50497,
            |"mal_id":63367,"anilist_id":206814,"anidb_id":null,"imdb_id":"tt4644488"}}""".trimMargin()
        assertEquals("tt4644488", Mapper.json.decodeFromString(AniZipResponse.serializer(), json).mappings.imdbId)
    }

    @Test
    fun missingImdbIdIsNull() {
        val json = """{"mappings":{"animeplanet_id":"dragon-ball-super-beerus","anidb_id":null}}"""
        assertNull(Mapper.json.decodeFromString(AniZipResponse.serializer(), json).mappings.imdbId)
    }
}
