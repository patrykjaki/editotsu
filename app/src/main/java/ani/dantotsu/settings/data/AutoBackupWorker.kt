package ani.dantotsu.settings.data

import android.content.Context
import android.os.Environment
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import ani.dantotsu.settings.saving.internal.Location
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.util.Logger
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * MAX MERGE M3 (upstream e954d9f1): scheduled settings backup.
 *
 * SECRET RULE: the auto payload EXCLUDES [Location.Protected] (auth
 * tokens, lock PIN/biometric secrets, proxy credentials). Scheduled
 * plaintext exports must never carry secrets; manual encrypted export
 * remains the path for full backups.
 *
 * Writes are atomic (temp file + rename) so a killed worker never leaves
 * a torn backup. Explicit [TimeUnit] on every duration (M6 convention).
 */
class AutoBackupWorker(appContext: Context, workerParams: WorkerParameters) :
    CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        Logger.log("AutoBackupWorker: Starting auto-backup")
        PrefManager.init(applicationContext)

        val interval = PrefManager.getVal<Int>(PrefName.AutoBackupInterval)
        if (interval <= 0) {
            Logger.log("AutoBackupWorker: Auto-backup is disabled")
            return Result.success()
        }

        return try {
            // SECRET RULE: Protected locations never enter the scheduled
            // plaintext payload (see autoBackupLocations).
            val serialized = PrefManager.exportAllPrefs(autoBackupLocations())

            val backupDir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "Editotsu/Backups"
            ).apply { if (!exists()) mkdirs() }

            val timestampStr = SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.getDefault()).format(Date())
            val backupFile = File(backupDir, "editotsu_autobackup_${timestampStr}.ani")
            val tmpFile = File(backupDir, "${backupFile.name}.tmp")
            tmpFile.writeText(serialized)
            if (!tmpFile.renameTo(backupFile)) {
                tmpFile.delete()
                throw Exception("Atomic backup rename failed")
            }
            android.media.MediaScannerConnection.scanFile(applicationContext, arrayOf(backupFile.absolutePath), null, null)

            PrefManager.setVal(PrefName.LastAutoBackupTimestamp, System.currentTimeMillis())
            Logger.log("AutoBackupWorker: Backup saved to ${backupFile.absolutePath}")

            // Retention: prune oldest beyond max copies.
            val maxCopies = PrefManager.getVal<Int>(PrefName.AutoBackupMaxCopies).coerceAtLeast(1)
            val autoBackups = backupDir.listFiles { file ->
                file.isFile && file.name.startsWith("editotsu_autobackup_") && file.name.endsWith(".ani")
            }?.map { BackupCandidate(it.name, it.lastModified()) } ?: emptyList()

            selectBackupsToPrune(autoBackups, maxCopies).forEach { stale ->
                Logger.log("AutoBackupWorker: Pruning old backup ${stale.name}")
                File(backupDir, stale.name).delete()
            }

            Result.success()
        } catch (e: Exception) {
            Logger.log("AutoBackupWorker failed: ${e.message}")
            Result.retry()
        }
    }

    companion object {
        const val WORK_NAME = "ani.dantotsu.settings.data.AutoBackupWorker"

        fun getIntervalHours(index: Int): Long = autoBackupIntervalHours(index)

        fun schedule(context: Context, intervalIndex: Int) {
            val workManager = WorkManager.getInstance(context)
            val hours = getIntervalHours(intervalIndex)
            if (hours <= 0) {
                workManager.cancelUniqueWork(WORK_NAME)
                Logger.log("AutoBackupWorker cancelled")
            } else {
                val request = PeriodicWorkRequestBuilder<AutoBackupWorker>(hours, TimeUnit.HOURS)
                    .setConstraints(
                        Constraints.Builder()
                            .setRequiresBatteryNotLow(true)
                            .build()
                    )
                    .build()
                workManager.enqueueUniquePeriodicWork(
                    WORK_NAME,
                    ExistingPeriodicWorkPolicy.UPDATE,
                    request
                )
                Logger.log("AutoBackupWorker scheduled every $hours hours")
            }
        }
    }
}
