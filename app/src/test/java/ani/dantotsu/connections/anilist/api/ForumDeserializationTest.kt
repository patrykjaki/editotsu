package ani.dantotsu.connections.anilist.api

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MAX MERGE M2: upstream 95db71ee added safe deserialization defaults so
 * partial GraphQL payloads never throw MissingFieldException. These tests
 * pin that contract for the forum models and the Media-v3 additions.
 */
class ForumDeserializationTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `thread list parses minimal payload`() {
        val parsed = json.decodeFromString<ForumThreadsResponse>(
            """{"data":{"Page":{"pageInfo":{"total":1,"perPage":25,"currentPage":1,"lastPage":1,"hasNextPage":true},"threads":[{"id":1,"title":"Hello"}]}}}"""
        )
        val threads = parsed.data?.page?.threads
        assertEquals(1, threads?.size)
        assertEquals("Hello", threads?.first()?.title)
        assertNull(threads?.first()?.user)
        assertEquals(false, threads?.first()?.isSubscribed ?: false)
    }

    @Test
    fun `thread details parse with user and categories`() {
        val parsed = json.decodeFromString<ForumThreadsResponse>(
            """{"data":{"Thread":{"id":7,"title":"T","user":{"id":3,"name":"amy"},"categories":[{"id":2,"name":"Anime"}]}}}"""
        )
        val thread = parsed.data?.thread
        assertEquals(7, thread?.id)
        assertEquals("amy", thread?.user?.name)
        assertEquals("Anime", thread?.categories?.first()?.name)
    }

    @Test
    fun `thread comments tolerate missing optionals and keep child payload`() {
        val parsed = json.decodeFromString<ThreadCommentsResponse>(
            """{"data":{"Page":{"threadComments":[{"id":9,"threadId":7,"comment":"hi","childComments":[{"id":10}]}]}}}"""
        )
        val comment = parsed.data?.page?.threadComments?.first()
        assertEquals(9, comment?.id)
        assertNull(comment?.user)
        assertTrue(comment?.childComments.toString().contains("10"))
    }

    @Test
    fun `media stats and rankings parse`() {
        val parsed = json.decodeFromString<MediaStats>(
            """{"scoreDistribution":[{"score":100,"amount":5}],"statusDistribution":[{"status":"COMPLETED","amount":9}]}"""
        )
        assertEquals(5, parsed.scoreDistribution?.first()?.amount)
        assertEquals(9, parsed.statusDistribution?.first()?.amount)
        val rank = json.decodeFromString<MediaRank>(
            """{"id":1,"rank":3,"type":"RATED","format":"TV","year":2024,"season":"SPRING","allTime":false,"context":"Top Airing"}"""
        )
        assertEquals(3, rank.rank)
        assertEquals("Top Airing", rank.context)
    }

    @Test
    fun `media source and relation supersets cover v3 values`() {
        assertEquals(MediaSource.WEB_NOVEL, MediaSource.valueOf("WEB_NOVEL"))
        assertEquals(MediaSource.MULTIMEDIA_PROJECT, MediaSource.valueOf("MULTIMEDIA_PROJECT"))
        assertEquals(MediaRelation.SAME_UNIVERSE, MediaRelation.valueOf("SAME_UNIVERSE"))
    }

    @Test
    fun `user tolerates missing fields with defaults`() {
        val parsed = json.decodeFromString<User>(
            """{"id":5,"name":"bob"}"""
        )
        assertEquals(5, parsed.id)
        assertEquals("bob", parsed.name)
        assertNull(parsed.avatar)
        assertNull(parsed.statistics)
    }

    @Test
    fun `recommendation user rating reads as string`() {
        val parsed = json.decodeFromString<Recommendation>(
            """{"id":1,"rating":85,"userRating":"VERY_GOOD","media":null,"mediaRecommendation":null,"user":null}"""
        )
        assertEquals(85, parsed.rating)
        assertEquals("VERY_GOOD", parsed.userRating)
    }
}
