package ani.dantotsu.notifications.subscription

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import ani.dantotsu.FileUrl
import ani.dantotsu.Lazier
import ani.dantotsu.connections.anilist.Anilist
import ani.dantotsu.media.Media
import ani.dantotsu.media.anime.Anime
import ani.dantotsu.notifications.subscription.SubscriptionHelper.Companion.SubscribeMedia
import ani.dantotsu.parsers.AnimeParser
import ani.dantotsu.parsers.AnimeSources
import ani.dantotsu.parsers.BaseParser
import ani.dantotsu.parsers.Episode
import ani.dantotsu.parsers.MangaSources
import ani.dantotsu.parsers.ShowResponse
import ani.dantotsu.parsers.VideoServer
import ani.dantotsu.screenshots.ScreenshotTestApp
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.data.notification.Notifications
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Subscription checks with a fake source instead of an extension: what the phone test list
 * checks by waiting for real episodes (no repeat of old episodes, no unaired episodes,
 * newest episode, notification text). No network: AniList is skipped via its rate-limit gate.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], application = ScreenshotTestApp::class)
class SubscriptionCheckTest {

    /** A source whose episode list the test sets. */
    private class FakeSource(var episodes: List<Episode>, var fail: Boolean = false) : AnimeParser() {
        override val name = "Fake Torrents"
        override val saveName = "fake_torrents"
        var latestCalls = 0
        override suspend fun loadSavedShowResponse(mediaId: Int) =
            ShowResponse("Andon Yakkyoku Nisshi", "show/$mediaId", FileUrl("")).apply { sAnime = SAnime.create() }
        override suspend fun loadEpisodes(animeLink: String, extra: Map<String, String>?, sAnime: SAnime): List<Episode> {
            latestCalls++
            if (fail) throw IllegalStateException("source down")
            return episodes
        }
        override suspend fun loadVideoServers(episodeLink: String, extra: Map<String, String>?, sEpisode: SEpisode) =
            emptyList<VideoServer>()
        override suspend fun search(query: String) = emptyList<ShowResponse>()
    }

    private val id = 900001
    private val sub = SubscribeMedia(isAnime = true, isAdult = false, id = id, name = "Andon Yakkyoku Nisshi", image = null)
    private fun eps(n: Int) = (1..n).map { Episode("$it", "ep/$it", title = "Episode $it: Title $it") }
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before fun setUp() {
        // AniList is not asked: airedLimits() sees the rate-limit gate and returns no limits.
        Anilist.rateLimitReset = System.currentTimeMillis() / 1000 + 3600
    }

    @After fun tearDown() {
        Anilist.rateLimitReset = 0
        AnimeSources.list = emptyList()
        AnimeSources.isInitialized = false
        MangaSources.isInitialized = false
    }

    // ---- SubscriptionHelper.getEpisode ----------------------------------------------------

    @Test fun first_check_only_records_the_newest_episode() = runBlocking {
        val source = FakeSource(eps(5))
        assertNull(SubscriptionHelper.getEpisode(source, sub))
        assertEquals(5f, SubscriptionHelper.loadLatest(id))
    }

    @Test fun new_episode_after_baseline_is_announced_once() = runBlocking {
        SubscriptionHelper.saveLatest(id, 5f)
        val source = FakeSource(eps(6))
        assertEquals("6", SubscriptionHelper.getEpisode(source, sub)?.number)
        assertEquals(6f, SubscriptionHelper.loadLatest(id))
        assertNull("second check repeats episode 6", SubscriptionHelper.getEpisode(source, sub))
    }

    @Test fun announces_the_newest_episode_when_several_came_out() = runBlocking {
        SubscriptionHelper.saveLatest(id, 3f)
        assertEquals("5", SubscriptionHelper.getEpisode(FakeSource(eps(5).shuffled(java.util.Random(1))), sub)?.number)
    }

    @Test fun episode_anilist_has_not_aired_waits_until_it_airs() = runBlocking {
        SubscriptionHelper.saveLatest(id, 5f)
        val source = FakeSource(eps(7)) // 7 uploaded early / mislabelled; AniList says 6 aired
        assertNull(SubscriptionHelper.getEpisode(source, sub, airedLimit = 6))
        assertEquals("baseline must not move", 5f, SubscriptionHelper.loadLatest(id))
        assertEquals("7", SubscriptionHelper.getEpisode(source, sub, airedLimit = 7)?.number)
    }

    @Test fun failing_source_announces_nothing_and_keeps_the_baseline() = runBlocking {
        SubscriptionHelper.saveLatest(id, 5f)
        assertNull(SubscriptionHelper.getEpisode(FakeSource(eps(6), fail = true), sub))
        assertEquals(5f, SubscriptionHelper.loadLatest(id))
    }

    @Test fun parser_follows_the_subscribed_source_by_name() {
        val other = FakeSource(eps(1))
        val torrents = FakeSource(eps(1))
        AnimeSources.list = listOf(
            Lazier<BaseParser>({ other }, "AnimeGG"),
            Lazier<BaseParser>({ torrents }, "Fake Torrents"),
        )
        SubscriptionHelper.saveSource(id, "Fake Torrents")
        assertTrue(SubscriptionHelper.getAnimeParser(id) === torrents)
        assertTrue(SubscriptionHelper.isSubscribedOn(id, "Fake Torrents") || !SubscriptionHelper.getSubscriptions().containsKey(id))
        assertFalse(SubscriptionHelper.bellOn(true, "Fake Torrents", "AnimeGG"))
    }

    // ---- SubscriptionNotificationTask --------------------------------------------------------

    private fun runTask(source: FakeSource): Boolean {
        AnimeSources.list = listOf(Lazier<BaseParser>({ source }, source.name))
        AnimeSources.isInitialized = true
        MangaSources.isInitialized = true
        SubscriptionHelper.saveSubscription(
            Media(anime = Anime(), id = id, name = null, nameRomaji = sub.name, userPreferredName = sub.name, isAdult = false),
            true,
        )
        SubscriptionHelper.saveSource(id, source.name)
        return runBlocking { SubscriptionNotificationTask().execute(context) }
    }

    private fun postedTexts(): List<String> {
        val nm = context.getSystemService(NotificationManager::class.java)
        return shadowOf(nm).allNotifications
            .filter { it.channelId == Notifications.CHANNEL_SUBSCRIPTION_CHECK }
            .map { it.extras.getCharSequence(android.app.Notification.EXTRA_TEXT).toString() }
    }

    @Test fun task_notifies_a_new_episode_with_its_title_once() {
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        Notifications.createChannels(context)
        SubscriptionHelper.saveLatest(id, 5f)
        assertTrue(runTask(FakeSource(eps(6))))
        assertEquals(listOf("Episode 6: Title 6 - Newly released!"), postedTexts())
        assertEquals(1, PrefManager.getVal<Int>(PrefName.UnreadSubscriptionNotifications))
        val store = PrefManager.getNullableVal<List<SubscriptionStore>>(PrefName.SubscriptionNotificationStore, null).orEmpty()
        assertEquals(listOf("Episode 6: Title 6 - Newly released!"), store.map { it.content })
    }

    @Test fun task_first_run_seeds_without_notifying() {
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        Notifications.createChannels(context)
        assertTrue(runTask(FakeSource(eps(5))))
        assertEquals(emptyList<String>(), postedTexts())
        assertEquals(5f, SubscriptionHelper.loadLatest(id))
    }
}
