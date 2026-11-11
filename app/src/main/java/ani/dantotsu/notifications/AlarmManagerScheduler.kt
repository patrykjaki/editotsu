package ani.dantotsu.notifications

import ani.dantotsu.connections.comments.CommentsOptIn
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import ani.dantotsu.notifications.TaskScheduler.TaskType
import ani.dantotsu.notifications.anilist.AnilistNotificationReceiver
import ani.dantotsu.notifications.comment.CommentNotificationReceiver
import ani.dantotsu.notifications.subscription.SubscriptionNotificationReceiver
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

class AlarmManagerScheduler(private val context: Context) : TaskScheduler {
    override fun scheduleRepeatingTask(taskType: TaskType, interval: Long) {
        if (TimeUnit.MINUTES.toMillis(interval) < TimeUnit.MINUTES.toMillis(15)) {
            cancelTask(taskType)
            return
        }
        if (taskType == TaskType.COMMENT_NOTIFICATION && !CommentsOptIn.isEnabled()) {
            cancelTask(taskType)
            return
        }
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pendingIntent = pendingIntent(taskType)

        val triggerAtMillis = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(interval)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtMillis,
                    pendingIntent
                )
            } else {
                alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
            }
        } catch (e: SecurityException) {
            PrefManager.setVal(PrefName.UseAlarmManager, false)
            TaskScheduler.create(context, true).cancelAllTasks()
            TaskScheduler.create(context, false).scheduleAllTasks(context)
        }
    }

    /** Also cancels a task whose notifications were turned off after its alarm was set. */
    override fun cancelTask(taskType: TaskType) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.cancel(pendingIntent(taskType))
    }

    private fun pendingIntent(taskType: TaskType): PendingIntent {
        val receiver = when (taskType) {
            TaskType.COMMENT_NOTIFICATION -> CommentNotificationReceiver::class.java
            TaskType.ANILIST_NOTIFICATION -> AnilistNotificationReceiver::class.java
            TaskType.SUBSCRIPTION_NOTIFICATION -> SubscriptionNotificationReceiver::class.java
        }
        return PendingIntent.getBroadcast(
            context,
            taskType.ordinal,
            Intent(context, receiver),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    companion object {
        /**
         * Runs when a task's alarm fires. The next alarm is set first, so the chain survives
         * the process being killed; the check itself runs as a one-time job, since it can take
         * minutes and a broadcast receiver gets about ten seconds. KEEP: an alarm that fires
         * while the previous check still runs does not start a second one.
         */
        fun onAlarm(context: Context, taskType: TaskType, worker: Class<out ListenableWorker>, workName: String) {
            PrefManager.init(context)
            AlarmManagerScheduler(context).scheduleRepeatingTask(taskType, TaskScheduler.interval(taskType))
            val work = OneTimeWorkRequest.Builder(worker)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(workName + "_alarm", ExistingWorkPolicy.KEEP, work)
        }
    }
}
