package ani.dantotsu.connections.anilist

import ani.dantotsu.connections.anilist.api.Activity
import ani.dantotsu.connections.anilist.api.Media
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Batch 2B — own-activity visibility correction (upstream 5ffa682b).
 * Proves the merge contract without a live AniList call. forceRefresh
 * cache-bypass itself is inspection-verified (3 one-liners: param,
 * cache guard, call site) — PrefManager/Network are Android-bound.
 */
class UserStatusMergeTest {

    private val savedUserid = Anilist.userid

    @After
    fun tearDown() {
        Anilist.userid = savedUserid
    }

    private fun media(isAdult: Boolean) = Media(
        id = 1, idMal = null, title = null, type = null, format = null,
        status = null, description = null, startDate = null, endDate = null,
        season = null, seasonYear = null, seasonInt = null, episodes = null,
        duration = null, chapters = null, volumes = null, countryOfOrigin = null,
        isLicensed = null, source = null, hashtag = null, trailer = null,
        updatedAt = null, coverImage = null, bannerImage = null, genres = null,
        synonyms = null, averageScore = null, meanScore = null, popularity = null,
        isLocked = null, trending = null, favourites = null, tags = null,
        relations = null, characters = null, staff = null, studios = null,
        producers = null, isFavourite = null, isFavouriteBlocked = null,
        isAdult = isAdult, nextAiringEpisode = null, externalLinks = null,
        streamingEpisodes = null, mediaListEntry = null, reviews = null,
        recommendations = null, siteUrl = null, autoCreateForumThread = null,
        isRecommendationBlocked = null, isReviewBlocked = null, modNotes = null
    )

    private fun activity(
        id: Int,
        createdAt: Int,
        typename: String = "TextActivity",
        media: Media? = null
    ) = Activity(
        typename = typename, id = id, recipientId = null, messengerId = null,
        userId = 7, type = "TEXT", replyCount = 0, status = null, progress = null,
        text = null, message = null, siteUrl = null, isLocked = null,
        isSubscribed = null, likeCount = null, isLiked = null, isPinned = null,
        isPrivate = null, createdAt = createdAt, user = null, recipient = null,
        messenger = null, media = media, replies = null, likes = null
    )

    private val queries = AnilistQueries()
    // createdAt seconds used below are 1000..3000 (-> ms); cutoff 0 keeps all.
    private val fresh = 0L

    @Test
    fun `myStatus present only with user id`() {
        Anilist.userid = 7
        val q = queries.myStatus()
        assertTrue(q.contains("MyActivities"))
        assertTrue(q.contains("userId:7"))

        Anilist.userid = null
        assertEquals("", queries.myStatus())
    }

    @Test
    fun `own page merges with followed pages newest-first`() {
        val followed = listOf(activity(1, 1000), activity(2, 3000))
        val own = listOf(activity(3, 2000))
        val out = queries.mergeStatusActivities(listOf(followed, own), adult = false, fresh)
        assertEquals(listOf(2, 3, 1), out.map { it.id })
    }

    @Test
    fun `duplicate ids collapse`() {
        val a = listOf(activity(1, 1000))
        val b = listOf(activity(1, 2000))
        val out = queries.mergeStatusActivities(listOf(a, b), adult = false, fresh)
        assertEquals(listOf(1), out.map { it.id })
    }

    @Test
    fun `MessageActivity filtered`() {
        val pages = listOf(listOf(activity(1, 1000, "MessageActivity"), activity(2, 1000)))
        val out = queries.mergeStatusActivities(pages, adult = false, fresh)
        assertEquals(listOf(2), out.map { it.id })
    }

    @Test
    fun `adult media excluded unless adult`() {
        val pages = listOf(
            listOf(
                activity(1, 1000, media = media(isAdult = true)),
                activity(2, 1000, media = media(isAdult = false)),
                activity(3, 1000, media = null)
            )
        )
        assertEquals(
            listOf(2, 3),
            queries.mergeStatusActivities(pages, adult = false, fresh).map { it.id }
        )
        assertEquals(
            listOf(1, 2, 3),
            queries.mergeStatusActivities(pages, adult = true, fresh).map { it.id }
        )
    }

    @Test
    fun `stale items excluded`() {
        val pages = listOf(listOf(activity(1, 10), activity(2, 2_000_000_000)))
        val out = queries.mergeStatusActivities(pages, adult = false, threeDaysAgoMillis = 1_000_000_000_000L)
        assertEquals(listOf(2), out.map { it.id })
    }

    @Test
    fun `empty and missing pages harmless`() {
        assertTrue(queries.mergeStatusActivities(emptyList(), adult = false, fresh).isEmpty())
        assertTrue(
            queries.mergeStatusActivities(listOf(emptyList(), emptyList()), adult = false, fresh).isEmpty()
        )
    }
}
