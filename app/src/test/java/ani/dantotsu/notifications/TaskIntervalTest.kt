package ani.dantotsu.notifications

import ani.dantotsu.notifications.subscription.SubscriptionNotificationTask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class TaskIntervalTest {

    private val intervals = arrayOf(0L, 480, 720, 1440)

    @Test
    fun indexInRangePicksThatInterval() {
        assertEquals(720L, TaskScheduler.pickInterval(intervals, 2, 1))
        assertEquals(0L, TaskScheduler.pickInterval(intervals, 0, 1))
    }

    @Test
    fun outOfRangeIndexFallsBackToTheDefault() {
        // An index from another version's list, e.g. the AniList list has 8 entries.
        assertEquals(480L, TaskScheduler.pickInterval(intervals, 7, 1))
        assertEquals(480L, TaskScheduler.pickInterval(intervals, -1, 1))
    }

    @Test
    fun brokenDefaultMeansOff() {
        assertEquals(0L, TaskScheduler.pickInterval(intervals, 9, 9))
    }

    @Test
    fun notificationIdIsStablePerMediaAndEpisode() {
        assertEquals(
            SubscriptionNotificationTask.notificationId(21, "5"),
            SubscriptionNotificationTask.notificationId(21, "5"),
        )
        assertNotEquals(
            SubscriptionNotificationTask.notificationId(21, "5"),
            SubscriptionNotificationTask.notificationId(21, "6"),
        )
        assertNotEquals(
            SubscriptionNotificationTask.notificationId(21, "5"),
            SubscriptionNotificationTask.notificationId(22, "5"),
        )
    }
}
