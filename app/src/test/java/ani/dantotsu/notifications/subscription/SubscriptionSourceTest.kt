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
}
