package ani.dantotsu.settings.data

import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.StatFs
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.text.format.Formatter
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import ani.dantotsu.R
import ani.dantotsu.databinding.ItemSettingStorageBinding
import ani.dantotsu.download.anime.AnimeDownloaderService
import ani.dantotsu.download.novel.NovelDownloaderService
import ani.dantotsu.settings.rows.SettingsScreenActivity
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.toast
import ani.dantotsu.torrent.TorrentCacheManager
import ani.dantotsu.util.LauncherWrapper
import com.anggrayudi.storage.file.getAbsolutePath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Settings > Data & storage ([DataScreen]). The rows live in [DataScreen]; this activity
 * holds what they need from an activity: file pickers, the purge-done receiver and the
 * cache size, which is measured in the background.
 */
class SettingsDataActivity : SettingsScreenActivity() {
    override val screen get() = DataScreen

    internal lateinit var folderPicker: LauncherWrapper
    internal lateinit var openBackup: ActivityResultLauncher<Array<String>>
    private var createDocumentLauncher: ActivityResultLauncher<String>? = null
    private var pendingBackupPayload: ByteArray? = null
    private var pendingBackupName: String? = null
    private var purgeDoneReceiver: android.content.BroadcastReceiver? = null

    /** Bytes in the app cache dirs; null until measured. */
    internal var cacheSize: Long? = null
        private set

    override fun onScreenCreate(savedInstanceState: Bundle?) {
        folderPicker = LauncherWrapper(this, ActivityResultContracts.OpenDocumentTree())
        openBackup = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> restoreFromUri(uri) }
        createDocumentLauncher =
            registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
                // User-chosen destination; one stream write, a failure deletes nothing else.
                if (uri != null) {
                    try {
                        val payload = pendingBackupPayload ?: throw Exception("Nothing to back up")
                        contentResolver.openOutputStream(uri)?.use { out -> out.write(payload) }
                            ?: throw Exception("Could not open destination")
                        toast(getString(R.string.backup_saved,
                            DocumentFile.fromSingleUri(this, uri)?.name ?: pendingBackupName.orEmpty()))
                    } catch (e: Exception) {
                        e.printStackTrace()
                        toast(getString(R.string.error_saving_settings))
                    } finally {
                        pendingBackupPayload = null
                        pendingBackupName = null
                    }
                }
            }
        calculateCacheSize()
        registerPurgeDoneReceivers()
    }

    /** Ask where to save [payload] as [name]. */
    internal fun saveBackup(payload: ByteArray, name: String) {
        pendingBackupPayload = payload
        pendingBackupName = name
        createDocumentLauncher?.launch(name)
    }

    /**
     * Purge completion arrives from the services (cancel, join, purge, report). Refresh
     * storage on either outcome; say so when it timed out instead of pretending success.
     */
    private fun registerPurgeDoneReceivers() {
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
                val success = intent?.getBooleanExtra(AnimeDownloaderService.EXTRA_PURGE_SUCCESS, true) != false &&
                    intent?.getBooleanExtra(NovelDownloaderService.EXTRA_PURGE_SUCCESS, true) != false
                refresh()
                if (!success) toast(getString(R.string.purge_incomplete))
            }
        }
        purgeDoneReceiver = receiver
        ContextCompat.registerReceiver(
            this,
            receiver,
            android.content.IntentFilter().apply {
                addAction(AnimeDownloaderService.ACTION_PURGE_ALL_ANIME_DONE)
                addAction(NovelDownloaderService.ACTION_PURGE_ALL_NOVEL_DONE)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onDestroy() {
        purgeDoneReceiver?.let {
            try {
                unregisterReceiver(it)
            } catch (_: Exception) {
            }
            purgeDoneReceiver = null
        }
        super.onDestroy()
    }

    /** Fills the storage card. */
    internal fun bindStorage(b: ItemSettingStorageBinding) {
        try {
            val stat = StatFs(Environment.getDataDirectory().path)
            val totalBytes = stat.blockCountLong * stat.blockSizeLong
            val availableBytes = stat.availableBlocksLong * stat.blockSizeLong
            val usedBytes = totalBytes - availableBytes
            b.storageProgressBar.progress = if (totalBytes > 0) ((usedBytes * 100) / totalBytes).toInt() else 0
            b.storageAvailableText.text = getString(
                R.string.storage_available_info,
                Formatter.formatFileSize(this, availableBytes),
                Formatter.formatFileSize(this, totalBytes),
            )
            b.storageDirectoryText.text = getString(R.string.storage_downloads_path, downloadsPath())
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /** Restore from a picked file; [BackupRestore] validates before writing anything. */
    private fun restoreFromUri(uri: Uri?) {
        if (uri == null) return
        try {
            val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: throw Exception("Error reading file")
            val name = DocumentFile.fromSingleUri(this, uri)?.name ?: "settings"
            BackupRestore.restore(this, bytes, name)
        } catch (e: Exception) {
            e.printStackTrace()
            toast(getString(R.string.error_importing_settings))
        }
    }

    internal fun downloadsPath(): String = getReadableDownloadPath(PrefManager.getVal(PrefName.DownloadsDir))

    private fun getReadableDownloadPath(uriString: String): String {
        val defaultPath =
            "${Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).path}/Editotsu"
        if (uriString.isBlank()) return defaultPath

        return try {
            val uri = Uri.parse(uriString)

            val docFile = DocumentFile.fromTreeUri(this, uri)
            val resolvedPath = runCatching { docFile?.getAbsolutePath(this) }.getOrNull()
            if (!resolvedPath.isNullOrBlank() && !resolvedPath.contains("/tree/") && !resolvedPath.contains("msd:")) {
                return resolvedPath
            }

            val docId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull()
                ?: uri.lastPathSegment ?: ""
            if (docId.startsWith("msd:") || uriString.contains("msd:")) {
                val mediaId = docId.substringAfterLast(":")
                if (mediaId.isNotEmpty() && mediaId.all { it.isDigit() }) {
                    val contentUri = MediaStore.Files.getContentUri("external")
                    val projection = arrayOf(
                        MediaStore.MediaColumns.DATA,
                        MediaStore.MediaColumns.RELATIVE_PATH,
                        MediaStore.MediaColumns.DISPLAY_NAME
                    )
                    contentResolver.query(
                        contentUri,
                        projection,
                        "${MediaStore.MediaColumns._ID} = ?",
                        arrayOf(mediaId),
                        null
                    )?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val dataIdx = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
                            if (dataIdx != -1) {
                                val path = cursor.getString(dataIdx)
                                if (!path.isNullOrBlank()) return path
                            }
                            val relIdx = cursor.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
                            val nameIdx = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                            val rel = if (relIdx != -1) cursor.getString(relIdx) else null
                            val name = if (nameIdx != -1) cursor.getString(nameIdx) else null
                            if (!rel.isNullOrBlank()) {
                                return "/storage/emulated/0/${rel.trimEnd('/')}"
                            } else if (!name.isNullOrBlank()) {
                                return "/storage/emulated/0/Download/$name"
                            }
                        }
                    }
                }
            }

            if (docId.contains("primary:") || uriString.contains("primary:") || uriString.contains("primary%3A")) {
                val decoded = Uri.decode(docId)
                val relative = decoded.substringAfter("primary:").substringAfter("primary%3A").trimStart('/')
                return "/storage/emulated/0/$relative"
            }

            if (docId.contains(":")) {
                val volume = docId.substringBefore(":")
                val relative = docId.substringAfter(":").trimStart('/')
                if (volume.matches(Regex("[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}"))) {
                    return "/storage/$volume/$relative"
                }
            }

            val docName = docFile?.name
            if (!docName.isNullOrBlank() && !docName.contains("msd:")) {
                return "/storage/emulated/0/Download/$docName"
            }

            defaultPath
        } catch (e: Exception) {
            defaultPath
        }
    }

    private fun calculateCacheSize() {
        lifecycleScope.launch(Dispatchers.IO) {
            var size = 0L
            cacheDir?.let { size += getFolderSize(it) }
            externalCacheDir?.let { size += getFolderSize(it) }
            withContext(Dispatchers.Main) {
                cacheSize = size
                refresh()
            }
        }
    }

    private fun getFolderSize(dir: File): Long {
        var size = 0L
        val files = dir.listFiles() ?: return 0L
        for (file in files) {
            size += if (file.isDirectory) getFolderSize(file) else file.length()
        }
        return size
    }

    internal fun torrentCacheUsageText(): String {
        val breakdown = TorrentCacheManager.getInstance(this).getStorageBreakdown()
        return getString(R.string.torrent_cache_used, formatStorageSize(breakdown.torrentCacheBytes))
    }

    internal fun clearAppCache() {
        lifecycleScope.launch(Dispatchers.IO) {
            val sizeToClear = cacheSize ?: 0L
            try {
                cacheDir?.deleteRecursively()
                externalCacheDir?.deleteRecursively()
            } catch (e: Exception) {
                e.printStackTrace()
            }
            withContext(Dispatchers.Main) {
                cacheSize = 0L
                toast(getString(R.string.cache_cleared, formatStorageSize(sizeToClear)))
                refresh()
                calculateCacheSize()
            }
        }
    }
}
