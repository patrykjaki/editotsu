package ani.dantotsu.notifications

import android.content.Context
import ani.dantotsu.notifications.anilist.AnilistNotificationWorker
import ani.dantotsu.notifications.comment.CommentNotificationWorker
import ani.dantotsu.notifications.subscription.SubscriptionNotificationWorker
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName

interface TaskScheduler {
    fun scheduleRepeatingTask(taskType: TaskType, interval: Long)
    fun cancelTask(taskType: TaskType)

    fun cancelAllTasks() {
        for (taskType in TaskType.entries) {
            cancelTask(taskType)
        }
    }

    fun scheduleAllTasks(context: Context) {
        for (taskType in TaskType.entries) {
            scheduleRepeatingTask(taskType, interval(taskType))
        }
    }

    companion object {
        fun create(context: Context, useAlarmManager: Boolean): TaskScheduler {
            return if (useAlarmManager) {
                AlarmManagerScheduler(context)
            } else {
                WorkManagerScheduler(context)
            }
        }

        /** Check interval in minutes for [taskType]; 0 means off. */
        fun interval(taskType: TaskType): Long = when (taskType) {
            TaskType.COMMENT_NOTIFICATION ->
                pickInterval(CommentNotificationWorker.checkIntervals, PrefName.CommentNotificationInterval)
            TaskType.ANILIST_NOTIFICATION ->
                pickInterval(AnilistNotificationWorker.checkIntervals, PrefName.AnilistNotificationInterval)
            TaskType.SUBSCRIPTION_NOTIFICATION ->
                pickInterval(SubscriptionNotificationWorker.checkIntervals, PrefName.SubscriptionNotificationInterval)
        }

        private fun pickInterval(intervals: Array<Long>, key: PrefName): Long =
            pickInterval(intervals, PrefManager.getVal(key), key.data.default as Int)

        /** An index from an old or edited backup can be out of range; it falls back to the default. */
        internal fun pickInterval(intervals: Array<Long>, index: Int, defaultIndex: Int): Long =
            intervals.getOrNull(index) ?: intervals.getOrElse(defaultIndex) { 0L }

        /** Cancels everything the other scheduler set up and schedules all tasks with this one. */
        fun switchTo(context: Context, useAlarmManager: Boolean) {
            create(context, !useAlarmManager).cancelAllTasks()
            create(context, useAlarmManager).scheduleAllTasks(context)
        }

        fun scheduleSingleWork(context: Context) {
            val workManager = androidx.work.WorkManager.getInstance(context)
            workManager.enqueueUniqueWork(
                CommentNotificationWorker.WORK_NAME + "_single",
                androidx.work.ExistingWorkPolicy.REPLACE,
                androidx.work.OneTimeWorkRequest.Builder(CommentNotificationWorker::class.java)
                    .build()
            )
            workManager.enqueueUniqueWork(
                AnilistNotificationWorker.WORK_NAME + "_single",
                androidx.work.ExistingWorkPolicy.REPLACE,
                androidx.work.OneTimeWorkRequest.Builder(AnilistNotificationWorker::class.java)
                    .build()
            )
            workManager.enqueueUniqueWork(
                SubscriptionNotificationWorker.WORK_NAME + "_single",
                androidx.work.ExistingWorkPolicy.REPLACE,
                androidx.work.OneTimeWorkRequest.Builder(SubscriptionNotificationWorker::class.java)
                    .build()
            )
        }
    }

    enum class TaskType {
        COMMENT_NOTIFICATION,
        ANILIST_NOTIFICATION,
        SUBSCRIPTION_NOTIFICATION
    }
}

interface Task {
    suspend fun execute(context: Context): Boolean
}
