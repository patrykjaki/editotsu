package ani.dantotsu.settings.data

import android.annotation.SuppressLint
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.StatFs
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.text.format.Formatter
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.updateLayoutParams
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import com.anggrayudi.storage.file.getAbsolutePath
import androidx.recyclerview.widget.LinearLayoutManager
import ani.dantotsu.R
import ani.dantotsu.databinding.ActivitySettingsDataBinding
import ani.dantotsu.download.DownloadsManager
import ani.dantotsu.download.manga.MangaDownloaderService
import ani.dantotsu.initActivity
import ani.dantotsu.media.MediaType
import ani.dantotsu.navBarHeight
import ani.dantotsu.util.LauncherWrapper
import ani.dantotsu.util.StoragePermissions
import ani.dantotsu.restartApp
import ani.dantotsu.savePrefsToDownloads
import ani.dantotsu.settings.Settings
import ani.dantotsu.settings.SettingsAdapter
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.settings.saving.internal.Location
import ani.dantotsu.settings.saving.internal.PreferencePackager
import ani.dantotsu.statusBarHeight
import ani.dantotsu.themes.ThemeManager
import ani.dantotsu.toast
import ani.dantotsu.torrent.TorrentCacheManager
import ani.dantotsu.util.customAlertDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * MAX MERGE M3 (upstream e954d9f1): Data and Storage screen — storage
 * usage, cache clearing, manual/auto backup, restore, download-dir move,
 * download purge. Adapted: Editotsu branding; no rebuild-index row (no
 * local index API — not invented); manga purge travels the service scope
 * barrier (direct purge refuses manga); restore handles `.ani` here
 * (encrypted `.sani` restore lives in Settings > Common).
 */
class SettingsDataActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySettingsDataBinding
    private lateinit var launcher: LauncherWrapper
    private var cacheSize: Long = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager(this).applyTheme()
        initActivity(this)
        binding = ActivitySettingsDataBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val context = this
        binding.settingsDataLayout.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            topMargin = statusBarHeight
            bottomMargin = navBarHeight
        }
        binding.dataSettingsBack.setOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }

        val contract = ActivityResultContracts.OpenDocumentTree()
        launcher = LauncherWrapper(this, contract)

        val openDocumentLauncher =
            registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                if (uri != null) {
                    try {
                        val jsonString =
                            contentResolver.openInputStream(uri)?.readBytes()
                                ?: throw Exception("Error reading file")
                        val name = DocumentFile.fromSingleUri(this, uri)?.name ?: "settings"
                        if (name.endsWith(".ani")) {
                            val decryptedJson = jsonString.toString(Charsets.UTF_8)
                            if (PreferencePackager.unpack(decryptedJson)) restartApp()
                        } else {
                            toast(getString(R.string.unknown_file_type))
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                        toast(getString(R.string.error_importing_settings))
                    }
                }
            }

        updateStorageInfo()
        setupRecyclerView(openDocumentLauncher)
        calculateCacheSize()
    }

    private fun updateStorageInfo() {
        try {
            val path = Environment.getDataDirectory().path
            val stat = StatFs(path)
            val blockSize = stat.blockSizeLong
            val totalBlocks = stat.blockCountLong
            val availableBlocks = stat.availableBlocksLong

            val totalBytes = totalBlocks * blockSize
            val availableBytes = availableBlocks * blockSize
            val usedBytes = totalBytes - availableBytes

            val progress = if (totalBytes > 0) ((usedBytes * 100) / totalBytes).toInt() else 0
            binding.storageProgressBar.progress = progress

            val freeFormatted = Formatter.formatFileSize(this, availableBytes)
            val totalFormatted = Formatter.formatFileSize(this, totalBytes)
            binding.storageAvailableText.text =
                getString(R.string.storage_available_info, freeFormatted, totalFormatted)

            val downloadsDirUri = PrefManager.getVal<String>(PrefName.DownloadsDir)
            val displayPath = getReadableDownloadPath(downloadsDirUri)
            binding.storageDirectoryText.text = "Downloads: $displayPath"
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

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
                                val sub = rel.trimEnd('/')
                                return "/storage/emulated/0/$sub"
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
            cacheSize = size
            withContext(Dispatchers.Main) {
                binding.dataSettingsRecyclerView.adapter?.notifyDataSetChanged()
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

    private fun formatSize(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt().coerceIn(0, units.size - 1)
        return String.format(java.util.Locale.US, "%.2f %s", bytes / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
    }

    private fun torrentQuotaOptions(): Pair<Array<String>, LongArray> {
        val options = arrayOf("1 GB", "2 GB", "3 GB (Default)", "5 GB", "10 GB", "No Auto-Eviction (Unlimited)")
        val values = longArrayOf(
            1L * 1024L * 1024L * 1024L,
            2L * 1024L * 1024L * 1024L,
            3L * 1024L * 1024L * 1024L,
            5L * 1024L * 1024L * 1024L,
            10L * 1024L * 1024L * 1024L,
            0L
        )
        return options to values
    }

    private fun torrentQuotaIndex(): Int {
        val (_, values) = torrentQuotaOptions()
        return values.indexOf(PrefManager.getVal<Long>(PrefName.TorrentRetainedCacheQuota)).let { if (it >= 0) it else 2 }
    }

    private fun torrentCacheUsageText(): String {
        val breakdown = TorrentCacheManager.getInstance(this).getStorageBreakdown()
        return "Torrent cache: ${formatSize(breakdown.torrentCacheBytes)} (Tap to purge)"
    }

    private fun clearAppCache() {
        lifecycleScope.launch(Dispatchers.IO) {
            val sizeToClear = cacheSize
            try {
                cacheDir?.deleteRecursively()
                externalCacheDir?.deleteRecursively()
            } catch (e: Exception) {
                e.printStackTrace()
            }
            cacheSize = 0L
            withContext(Dispatchers.Main) {
                val formatted = Formatter.formatFileSize(this@SettingsDataActivity, sizeToClear)
                toast(getString(R.string.cache_cleared, formatted))
                binding.dataSettingsRecyclerView.adapter?.notifyDataSetChanged()
                calculateCacheSize()
                updateStorageInfo()
            }
        }
    }

    @SuppressLint("SimpleDateFormat")
    private fun setupRecyclerView(openDocumentLauncher: androidx.activity.result.ActivityResultLauncher<Array<String>>) {
        val context = this
        val intervals = arrayOf(
            getString(R.string.auto_backup_interval_off),
            getString(R.string.auto_backup_interval_6h),
            getString(R.string.auto_backup_interval_12h),
            getString(R.string.auto_backup_interval_24h),
            getString(R.string.auto_backup_interval_48h),
            getString(R.string.auto_backup_interval_weekly)
        )
        val copyCounts = arrayOf("1", "2", "3", "5")

        val settingsList = arrayListOf(
            Settings(
                type = 1,
                name = getString(R.string.clear_cache),
                desc = getString(
                    R.string.clear_cache_desc,
                    if (cacheSize > 0) Formatter.formatFileSize(context, cacheSize) else getString(R.string.cache_calculating)
                ),
                icon = R.drawable.ic_round_delete_24,
                onClick = {
                    context.customAlertDialog().apply {
                        setTitle(R.string.clear_cache)
                        setMessage(R.string.clear_cache)
                        setPosButton(R.string.ok) {
                            clearAppCache()
                        }
                        setNegButton(R.string.cancel)
                        show()
                    }
                }
            ),

            Settings(
                type = 1,
                name = getString(R.string.auto_backup_interval),
                desc = intervals.getOrElse(PrefManager.getVal(PrefName.AutoBackupInterval)) { intervals[0] },
                icon = R.drawable.ic_round_auto_awesome_24,
                onClick = {
                    val current = PrefManager.getVal<Int>(PrefName.AutoBackupInterval)
                    context.customAlertDialog().apply {
                        setTitle(R.string.auto_backup_interval)
                        singleChoiceItems(intervals, current) { which ->
                            PrefManager.setVal(PrefName.AutoBackupInterval, which)
                            AutoBackupWorker.schedule(context, which)
                            binding.dataSettingsRecyclerView.adapter?.notifyDataSetChanged()
                        }
                        setPosButton(R.string.ok)
                        show()
                    }
                }
            ),

            Settings(
                type = 1,
                name = getString(R.string.auto_backup_max_copies),
                desc = "${PrefManager.getVal<Int>(PrefName.AutoBackupMaxCopies)} backups",
                icon = R.drawable.backup_restore,
                onClick = {
                    val current = PrefManager.getVal<Int>(PrefName.AutoBackupMaxCopies)
                    val currentIndex = copyCounts.indexOf(current.toString()).coerceAtLeast(0)
                    context.customAlertDialog().apply {
                        setTitle(R.string.auto_backup_max_copies)
                        singleChoiceItems(copyCounts, currentIndex) { which ->
                            val copies = copyCounts[which].toIntOrNull() ?: 3
                            PrefManager.setVal(PrefName.AutoBackupMaxCopies, copies)
                            binding.dataSettingsRecyclerView.adapter?.notifyDataSetChanged()
                        }
                        setPosButton(R.string.ok)
                        show()
                    }
                }
            ),

            Settings(
                type = 1,
                name = getString(R.string.auto_backup),
                desc = run {
                    val timestamp = PrefManager.getVal<Long>(PrefName.LastAutoBackupTimestamp)
                    if (timestamp > 0) {
                        val formattedDate = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(timestamp))
                        getString(R.string.last_auto_backup, formattedDate)
                    } else {
                        getString(R.string.last_auto_backup_never)
                    }
                },
                icon = R.drawable.backup_restore,
                onClick = {}
            ),

            Settings(
                type = 1,
                name = getString(R.string.backup_now),
                desc = getString(R.string.backup_now_desc),
                icon = R.drawable.backup_restore,
                onClick = {
                    StoragePermissions.downloadsPermission(context)
                    val exportableLocations = Location.entries.filter { it.exportable }
                    val timestampStr = SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.getDefault()).format(Date())
                    savePrefsToDownloads(
                        "Editotsu_${timestampStr}",
                        PrefManager.exportAllPrefs(exportableLocations),
                        context
                    )
                }
            ),

            Settings(
                type = 1,
                name = getString(R.string.restore_backup),
                desc = getString(R.string.restore_backup_desc),
                icon = R.drawable.backup_restore,
                onClick = {
                    StoragePermissions.downloadsPermission(context)
                    openDocumentLauncher.launch(arrayOf("*/*"))
                }
            ),

            // MAX MERGE M3: torrent cache quota + managed purge live here
            // (canonical storage home; moved out of Torrent Settings — no
            // duplicated editable UI).
            Settings(
                type = 1,
                name = getString(R.string.torrent_cache_quota),
                desc = torrentQuotaOptions().let { opts ->
                    opts.first[torrentQuotaIndex()]
                },
                icon = R.drawable.ic_round_folder_24,
                onClick = { view ->
                    val (options, values) = torrentQuotaOptions()
                    val selected = torrentQuotaIndex()
                    context.customAlertDialog().apply {
                        setTitle(getString(R.string.torrent_cache_quota))
                        singleChoiceItems(options, selected) { index ->
                            val chosenQuota = values[index]
                            PrefManager.setVal(PrefName.TorrentRetainedCacheQuota, chosenQuota)
                            view.settingsDesc.text = options[index]
                            val cacheMgr = TorrentCacheManager.getInstance(context)
                            lifecycleScope.launch(Dispatchers.IO) {
                                cacheMgr.evictIfNeeded(chosenQuota)
                            }
                        }
                        show()
                    }
                }
            ),

            Settings(
                type = 1,
                name = getString(R.string.clear_torrent_cache),
                desc = torrentCacheUsageText(),
                icon = R.drawable.ic_round_delete_24,
                onClick = { view ->
                    context.customAlertDialog().apply {
                        setTitle(getString(R.string.clear_torrent_cache))
                        setMessage("Purge all dormant/retained torrent downloads? Active playback streams will be preserved.")
                        setPosButton(R.string.yes) {
                            lifecycleScope.launch(Dispatchers.IO) {
                                val cacheManager = TorrentCacheManager.getInstance(context)
                                val result = cacheManager.clearTorrentCache()
                                val updated = torrentCacheUsageText()
                                withContext(Dispatchers.Main) {
                                    view.settingsDesc.text = updated
                                    val skippedMsg = if (result.activeBytesSkipped > 0) " (${formatSize(result.activeBytesSkipped)} active stream skipped)" else ""
                                    toast("Freed ${formatSize(result.bytesFreed)}$skippedMsg")
                                }
                            }
                        }
                        setNegButton(R.string.no)
                        show()
                    }
                }
            ),

            Settings(
                type = 1,
                name = getString(R.string.change_download_directory),
                desc = getString(R.string.change_download_directory_desc),
                icon = R.drawable.ic_round_folder_24,
                onClick = {
                    val oldUri = PrefManager.getVal<String>(PrefName.DownloadsDir)
                    context.customAlertDialog().apply {
                        setTitle(R.string.change_download_directory)
                        setMessage(R.string.change_download_directory_confirm)
                        setPosButton(R.string.ok) {
                            launcher.registerForCallback { success ->
                                if (success) {
                                    toast(getString(R.string.please_wait))
                                    val newUri = PrefManager.getVal<String>(PrefName.DownloadsDir)
                                    lifecycleScope.launch(Dispatchers.IO) {
                                        Injekt.get<DownloadsManager>().moveDownloadsDir(
                                            context,
                                            Uri.parse(oldUri),
                                            Uri.parse(newUri),
                                        ) { finished, message ->
                                            if (finished) {
                                                toast(getString(R.string.success))
                                                updateStorageInfo()
                                            } else {
                                                toast(message)
                                            }
                                        }
                                    }
                                } else {
                                    toast(getString(R.string.error))
                                }
                            }
                            launcher.launch()
                        }
                        setNegButton(R.string.cancel)
                        show()
                    }
                }
            ),

            Settings(
                type = 1,
                name = getString(R.string.purge_downloads),
                desc = getString(R.string.purge_downloads_desc),
                icon = R.drawable.ic_round_delete_24,
                onClick = {
                    context.customAlertDialog().apply {
                        setTitle(R.string.purge_downloads)
                        setMessage(R.string.purge_confirm, "${getString(R.string.anime)} & ${getString(R.string.manga)}")
                        setPosButton(R.string.yes) {
                            val downloadsManager = Injekt.get<DownloadsManager>()
                            downloadsManager.purgeDownloads(MediaType.ANIME)
                            // Manga purge refuses direct calls (live-owner race);
                            // travel the service scope barrier instead.
                            val purgeIntent = android.content.Intent(
                                context,
                                MangaDownloaderService::class.java
                            ).apply {
                                action = MangaDownloaderService.ACTION_PURGE_MANGA
                            }
                            ContextCompat.startForegroundService(context, purgeIntent)
                            updateStorageInfo()
                        }
                        setNegButton(R.string.no)
                        show()
                    }
                }
            )
        )

        binding.dataSettingsRecyclerView.apply {
            layoutManager = LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false)
            adapter = SettingsAdapter(settingsList)
            setHasFixedSize(true)
        }
    }
}
