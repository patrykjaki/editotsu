package ani.dantotsu.notifications.subscription

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

class SubscriptionSourceTest {
    private val names = listOf("AllAnime", "Gogo", "HiAnime")

    @Test
    fun findsTheSavedSourceWhereverItMovedTo() {
        assertEquals(2, SubscriptionHelper.sourceIndexFor("HiAnime", names))
        assertEquals(0, SubscriptionHelper.sourceIndexFor("HiAnime", listOf("HiAnime", "Gogo")))
    }

    @Test
    fun noNameOrUninstalledSourceKeepsTheSavedIndex() {
        assertNull(SubscriptionHelper.sourceIndexFor(null, names))
        assertNull(SubscriptionHelper.sourceIndexFor("Removed", names))
    }

    @Test
    fun bellIsOnOnlyForTheSubscribedSource() {
        assertTrue(SubscriptionHelper.bellOn(true, "Editotsu Torrents", "Editotsu Torrents"))
        assertFalse(SubscriptionHelper.bellOn(true, "Editotsu Torrents", "AnimeGG"))
        assertFalse(SubscriptionHelper.bellOn(false, "Editotsu Torrents", "Editotsu Torrents"))
        // Subscriptions saved before 0.7 have no source name: on everywhere, as before.
        assertTrue(SubscriptionHelper.bellOn(true, null, "AnimeGG"))
    }

    @Test
    fun notificationTitleDropsTheRepeatedEpisodeNumber() {
        assertEquals("The White-Snake Immortal", SubscriptionHelper.episodeTitle("5", "Episode 5: The White-Snake Immortal"))
        assertEquals("Title", SubscriptionHelper.episodeTitle("5", "Ep. 05 - Title"))
        assertNull(SubscriptionHelper.episodeTitle("4", "Episode 4"))
        assertNull(SubscriptionHelper.episodeTitle("4", "  "))
        // Another number is part of the title, not a repeat.
        assertEquals("Episode 50 recap", SubscriptionHelper.episodeTitle("5", "Episode 50 recap"))
        assertEquals("The Beginning", SubscriptionHelper.episodeTitle("1", "The Beginning"))
    }

    @Test
    fun firstCheckWithoutBaselineOnlyRecords() {
        assertFalse(SubscriptionHelper.announce(null))
        assertTrue(SubscriptionHelper.announce(4f))
    }

    @Test
    fun episodesPastAniListAiredCountWait() {
        // Kusuriya S3: episode 6 not aired yet, AniList next airing is 6 -> 5 aired.
        val limit = SubscriptionHelper.airedLimit("RELEASING", 5, 24)
        assertEquals(5, limit)
        assertTrue(SubscriptionHelper.withinAired(5f, limit))
        assertFalse(SubscriptionHelper.withinAired(6f, limit))
        assertEquals(12, SubscriptionHelper.airedLimit("FINISHED", null, 12))
        assertEquals(0, SubscriptionHelper.airedLimit("NOT_YET_RELEASED", null, null))
        // Unknown status or failed lookup: no limit.
        assertNull(SubscriptionHelper.airedLimit("RELEASING", null, null))
        assertTrue(SubscriptionHelper.withinAired(99f, null))
    }
}
