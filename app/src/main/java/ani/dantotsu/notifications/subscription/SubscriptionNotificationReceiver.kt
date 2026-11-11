package ani.dantotsu.notifications.subscription

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import ani.dantotsu.notifications.AlarmManagerScheduler
import ani.dantotsu.notifications.TaskScheduler
import ani.dantotsu.util.Logger

class SubscriptionNotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        Logger.log("SubscriptionNotificationReceiver: onReceive")
        AlarmManagerScheduler.onAlarm(
            context,
            TaskScheduler.TaskType.SUBSCRIPTION_NOTIFICATION,
            SubscriptionNotificationWorker::class.java,
            SubscriptionNotificationWorker.WORK_NAME,
        )
    }
}
