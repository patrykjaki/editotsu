package ani.dantotsu.notifications.comment

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import ani.dantotsu.notifications.AlarmManagerScheduler
import ani.dantotsu.notifications.TaskScheduler
import ani.dantotsu.util.Logger

class CommentNotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        Logger.log("CommentNotificationReceiver: onReceive")
        AlarmManagerScheduler.onAlarm(
            context,
            TaskScheduler.TaskType.COMMENT_NOTIFICATION,
            CommentNotificationWorker::class.java,
            CommentNotificationWorker.WORK_NAME,
        )
    }
}
