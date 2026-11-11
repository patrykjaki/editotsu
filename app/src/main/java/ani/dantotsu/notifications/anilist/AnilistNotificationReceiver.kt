package ani.dantotsu.notifications.anilist

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import ani.dantotsu.notifications.AlarmManagerScheduler
import ani.dantotsu.notifications.TaskScheduler
import ani.dantotsu.util.Logger

class AnilistNotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        Logger.log("AnilistNotificationReceiver: onReceive")
        AlarmManagerScheduler.onAlarm(
            context,
            TaskScheduler.TaskType.ANILIST_NOTIFICATION,
            AnilistNotificationWorker::class.java,
            AnilistNotificationWorker.WORK_NAME,
        )
    }
}
