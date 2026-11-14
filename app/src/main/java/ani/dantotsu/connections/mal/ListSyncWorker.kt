package ani.dantotsu.connections.mal

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import ani.dantotsu.connections.anilist.Anilist
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.util.Logger
import java.util.concurrent.TimeUnit

/**
 * Daily list sync in the background: the same comparison and writes as the sync sheet, in the
 * direction chosen there. One run writes at most [MAX_WRITES] entries, so a first run over a
 * large difference is spread over days instead of holding the device awake.
 */
class ListSyncWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        PrefManager.init(applicationContext)
        if (!PrefManager.getVal<Boolean>(PrefName.ListSyncAuto)) return Result.success()
        val main = if (PrefManager.getVal<Int>(PrefName.ListSyncMain) == 1) MalListSync.Side.MAL else MalListSync.Side.ANILIST
        if (!Anilist.getSavedToken() || !MAL.getSavedToken()) return Result.success()
        var written = 0
        for (isAnime in listOf(true, false)) {
            val changes = MalListSync.load(isAnime, main) ?: return Result.retry()
            for (change in changes) {
                if (written >= MAX_WRITES) return Result.success()
                MalListSync.write(change, main)
                written++
            }
        }
        Logger.log("ListSyncWorker: wrote $written entries")
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "ani.dantotsu.connections.mal.ListSyncWorker"
        private const val MAX_WRITES = 60

        /** schedules or cancels the daily run to match the setting */
        fun apply(context: Context) {
            val manager = WorkManager.getInstance(context)
            if (!PrefManager.getVal<Boolean>(PrefName.ListSyncAuto)) {
                manager.cancelUniqueWork(WORK_NAME)
                return
            }
            val request = PeriodicWorkRequestBuilder<ListSyncWorker>(24, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .setRequiresBatteryNotLow(true)
                        .build(),
                )
                .build()
            manager.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
