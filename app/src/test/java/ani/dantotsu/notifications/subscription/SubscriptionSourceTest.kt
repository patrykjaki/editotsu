package ani.dantotsu.notifications.subscription

import org.junit.Assert.assertEquals
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
}
