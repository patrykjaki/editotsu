package ani.dantotsu.settings.data

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import ani.dantotsu.BuildConfig
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.settings.saving.internal.wrapBackupEnvelope
import ani.dantotsu.util.Logger
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * MAX MERGE M3 (upstream e954d9f1, HOLD Blockers 2+3): scheduled settings
 * backup.
 *
 * SECRET RULE: the auto payload is [plaintextBackupAllowlist] only —
 * Protected secrets and unclassified extension prefs never enter it.
 *
 * STORAGE RULE (HOLD Blocker 3): API 29+ writes through
 * `MediaStore.Downloads` (`Download/Editotsu/Backups`, pending-state
 * publish, ContentResolver retention); API 26–28 uses the legacy public
 * path with atomic tmp+rename. No `MANAGE_EXTERNAL_STORAGE`.
 * Explicit [TimeUnit] on every duration (M6 convention).
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
            val payload = PrefManager.exportAllPrefs(plaintextBackupAllowlist())
            val enveloped = wrapBackupEnvelope(
                payload,
                BuildConfig.VERSION_NAME,
                System.currentTimeMillis()
            )
            val timestampStr =
                SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.getDefault()).format(Date())
            val fileName = "editotsu_autobackup_${timestampStr}.ani"

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                writeViaMediaStore(fileName, enveloped)
                pruneViaMediaStore()
            } else {
                writeViaLegacyFile(fileName, enveloped)
                pruneViaLegacyFile()
            }

            PrefManager.setVal(PrefName.LastAutoBackupTimestamp, System.currentTimeMillis())
            Logger.log("AutoBackupWorker: Backup saved ($fileName)")
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.log("AutoBackupWorker failed: ${e.message}")
            Result.retry()
        }
    }

    private fun writeViaMediaStore(fileName: String, content: String) {
        val resolver = applicationContext.contentResolver
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val pending = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, "application/json")
            put(MediaStore.Downloads.RELATIVE_PATH, "Download/Editotsu/Backups")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, pending)
            ?: throw Exception("MediaStore insert failed")
        try {
            resolver.openOutputStream(uri)?.use { out ->
                out.write(content.toByteArray(Charsets.UTF_8))
            } ?: throw Exception("MediaStore write failed")
            ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }.let { published ->
                resolver.update(uri, published, null, null)
            }
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
    }

    private fun pruneViaMediaStore() {
        val resolver = applicationContext.contentResolver
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        data class Entry(val uri: android.net.Uri, val name: String, val dateAdded: Long)
        val found = mutableListOf<Entry>()
        resolver.query(
            collection,
            arrayOf(
                MediaStore.Downloads._ID,
                MediaStore.Downloads.DISPLAY_NAME,
                MediaStore.Downloads.DATE_ADDED,
                MediaStore.Downloads.RELATIVE_PATH
            ),
            "${MediaStore.Downloads.RELATIVE_PATH} LIKE ? AND ${MediaStore.Downloads.DISPLAY_NAME} LIKE ?",
            arrayOf("%Editotsu/Backups%", "editotsu_autobackup_%.ani"),
            null
        )?.use { cursor ->
            val idIdx = cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID)
            val nameIdx = cursor.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME)
            val dateIdx = cursor.getColumnIndexOrThrow(MediaStore.Downloads.DATE_ADDED)
            while (cursor.moveToNext()) {
                found.add(
                    Entry(
                        android.net.Uri.withAppendedPath(collection, cursor.getLong(idIdx).toString()),
                        cursor.getString(nameIdx),
                        cursor.getLong(dateIdx)
                    )
                )
            }
        }
        val maxCopies = PrefManager.getVal<Int>(PrefName.AutoBackupMaxCopies).coerceAtLeast(1)
        val stale = selectBackupsToPrune(
            found.map { BackupCandidate(it.name, it.dateAdded) },
            maxCopies
        ).map { it.name }.toSet()
        found.filter { it.name in stale }.forEach { entry ->
            Logger.log("AutoBackupWorker: Pruning old backup ${entry.name}")
            runCatching { resolver.delete(entry.uri, null, null) }
        }
    }

    private fun legacyBackupDir(): File {
        return File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "Editotsu/Backups"
        ).apply { if (!exists()) mkdirs() }
    }

    private fun writeViaLegacyFile(fileName: String, content: String) {
        val backupFile = File(legacyBackupDir(), fileName)
        val tmpFile = File(legacyBackupDir(), "${backupFile.name}.tmp")
        tmpFile.writeText(content)
        if (!tmpFile.renameTo(backupFile)) {
            tmpFile.delete()
            throw Exception("Atomic backup rename failed")
        }
        android.media.MediaScannerConnection.scanFile(
            applicationContext,
            arrayOf(backupFile.absolutePath),
            null,
            null
        )
    }

    private fun pruneViaLegacyFile() {
        val backupDir = legacyBackupDir()
        val maxCopies = PrefManager.getVal<Int>(PrefName.AutoBackupMaxCopies).coerceAtLeast(1)
        val autoBackups = backupDir.listFiles { file ->
            file.isFile && file.name.startsWith("editotsu_autobackup_") && file.name.endsWith(".ani")
        }?.map { BackupCandidate(it.name, it.lastModified()) } ?: emptyList()
        selectBackupsToPrune(autoBackups, maxCopies).forEach { stale ->
            Logger.log("AutoBackupWorker: Pruning old backup ${stale.name}")
            File(backupDir, stale.name).delete()
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
