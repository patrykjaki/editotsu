package ani.dantotsu.settings

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.os.Bundle
import android.os.Environment
import android.os.StatFs
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityEvent
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import ani.dantotsu.R
import ani.dantotsu.databinding.ActivityDownloadQueueBinding
import ani.dantotsu.databinding.ItemDownloadQueueBinding
import ani.dantotsu.databinding.ItemDownloadsHeaderBinding
import ani.dantotsu.databinding.SheetDownloadOptionsBinding
import ani.dantotsu.download.DownloadCompat.Companion.loadMediaCompat
import ani.dantotsu.download.DownloadedType
import ani.dantotsu.download.DownloadsManager
import ani.dantotsu.download.DownloadsManager.Companion.compareName
import ani.dantotsu.download.anime.AnimeDownloaderService
import ani.dantotsu.download.anime.AnimeServiceDataSingleton
import ani.dantotsu.download.manga.MangaDownloaderService
import ani.dantotsu.download.manga.MangaQueueCancel
import ani.dantotsu.download.manga.MangaServiceDataSingleton
import ani.dantotsu.download.novel.NovelDownloaderService
import ani.dantotsu.download.novel.NovelServiceDataSingleton
import ani.dantotsu.initActivity
import ani.dantotsu.loadImage
import ani.dantotsu.media.Media
import ani.dantotsu.media.MediaDetailsActivity
import ani.dantotsu.media.MediaType
import ani.dantotsu.navBarHeight
import ani.dantotsu.settings.data.SettingsDataActivity
import ani.dantotsu.settings.rows.SettingsCards
import ani.dantotsu.settings.rows.markSegment
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.snackString
import ani.dantotsu.statusBarHeight
import ani.dantotsu.themes.ThemeManager
import ani.dantotsu.themes.followThemeStatusBarIcons
import ani.dantotsu.util.Logger
import ani.dantotsu.util.SizeFormatter
import ani.dantotsu.util.customAlertDialog
import com.anggrayudi.storage.file.openInputStream
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import com.google.gson.GsonBuilder
import com.google.gson.InstanceCreator
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SAnimeImpl
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.SEpisodeImpl
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SChapterImpl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class DownloadQueueActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDownloadQueueBinding
    private lateinit var adapter: DownloadsAdapter
    private var updateJob: Job? = null
    private var measureJob: Job? = null
    private var selectedType: MediaType = MediaType.ANIME
    private val folderSizeCache = mutableMapOf<String, Long>()
    private val coverPathCache = mutableMapOf<String, String?>()

    private val mangaScopeDeleteReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != MangaDownloaderService.ACTION_MANGA_SCOPE_DELETED) return
            if (intent.getBooleanExtra(MangaDownloaderService.EXTRA_MANGA_DELETED, false)) {
                val scopeTitle = intent.getStringExtra(MangaDownloaderService.EXTRA_SCOPE_TITLE)
                if (scopeTitle != null) {
                    folderSizeCache.remove(DownloadsSummary.mediaCacheKey(MediaType.MANGA, scopeTitle))
                } else {
                    folderSizeCache.keys.removeAll { it.startsWith("${MediaType.MANGA.name}:") }
                }
                measureAllDownloadedMedia()
                refreshUI()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager(this).applyTheme()
        binding = ActivityDownloadQueueBinding.inflate(layoutInflater)
        setContentView(binding.root)
        followThemeStatusBarIcons()
        initActivity(this)

        selectedType = when (savedInstanceState?.getInt(STATE_SELECTED_TYPE, 0) ?: 0) {
            1 -> MediaType.MANGA
            2 -> MediaType.NOVEL
            else -> MediaType.ANIME
        }

        val listBottomBase = binding.downloadQueueRecyclerView.paddingBottom
        binding.downloadQueueContainer.updatePadding(top = statusBarHeight)
        binding.downloadQueueRecyclerView.updatePadding(bottom = listBottomBase + navBarHeight)
        ViewCompat.setOnApplyWindowInsetsListener(binding.downloadQueueContainer) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            if (v.paddingTop < bars.top) v.updatePadding(top = bars.top)
            val listBottom = listBottomBase + bars.bottom
            if (binding.downloadQueueRecyclerView.paddingBottom < listBottom) {
                binding.downloadQueueRecyclerView.updatePadding(bottom = listBottom)
            }
            insets
        }

        binding.downloadQueueBack.setOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }

        binding.downloadQueueClear.setOnClickListener {
            confirmClearQueue()
        }

        binding.downloadQueueOptions.setOnClickListener {
            showOptionsSheet()
        }

        adapter = DownloadsAdapter(
            onCancelQueueClick = { item -> cancelTask(item) },
            onDeleteMediaClick = { title, type -> showDeleteMediaDialog(title, type) },
            onOpenMediaClick = { title, type -> openDownloadedMedia(title, type) },
        )

        binding.downloadQueueRecyclerView.layoutManager = LinearLayoutManager(this)
        (binding.downloadQueueRecyclerView.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false
        binding.downloadQueueRecyclerView.adapter = adapter

        setupTypeToggle()

        ContextCompat.registerReceiver(
            this,
            mangaScopeDeleteReceiver,
            IntentFilter(MangaDownloaderService.ACTION_MANGA_SCOPE_DELETED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(
            STATE_SELECTED_TYPE,
            when (selectedType) {
                MediaType.ANIME -> 0
                MediaType.MANGA -> 1
                MediaType.NOVEL -> 2
            },
        )
    }

    override fun onResume() {
        super.onResume()
        folderSizeCache.clear()
        coverPathCache.clear()
        updateDeviceStorage()
        measureAllDownloadedMedia()
        startUpdating()
    }

    override fun onPause() {
        super.onPause()
        stopUpdating()
    }

    override fun onDestroy() {
        measureJob?.cancel()
        runCatching { unregisterReceiver(mangaScopeDeleteReceiver) }
        super.onDestroy()
    }

    private fun setupTypeToggle() {
        val checkedId = when (selectedType) {
            MediaType.ANIME -> R.id.downloadsTypeAnime
            MediaType.MANGA -> R.id.downloadsTypeManga
            MediaType.NOVEL -> R.id.downloadsTypeNovel
        }
        binding.downloadsTypeToggle.check(checkedId)
        listOf(
            binding.downloadsTypeAnime,
            binding.downloadsTypeManga,
            binding.downloadsTypeNovel,
        ).forEach { markSegment(it, null, it.isChecked) }

        binding.downloadsTypeToggle.addOnButtonCheckedListener { group, id, isChecked ->
            group.findViewById<MaterialButton>(id)?.let { markSegment(it, null, isChecked) }
            if (!isChecked) return@addOnButtonCheckedListener
            val nextType = when (id) {
                R.id.downloadsTypeManga -> MediaType.MANGA
                R.id.downloadsTypeNovel -> MediaType.NOVEL
                else -> MediaType.ANIME
            }
            if (nextType != selectedType) {
                selectedType = nextType
                refreshUI()
            }
        }
    }

    private fun updateDeviceStorage() {
        lifecycleScope.launch(Dispatchers.IO) {
            val stats = runCatching {
                val stat = StatFs(Environment.getDataDirectory().path)
                val total = stat.blockCountLong * stat.blockSizeLong
                val available = stat.availableBlocksLong * stat.blockSizeLong
                total to available
            }.getOrNull() ?: return@launch

            withContext(Dispatchers.Main) {
                val (totalBytes, availableBytes) = stats
                binding.downloadsStorageBar.progress =
                    DownloadsSummary.storageUsedPercent(totalBytes, availableBytes)
                binding.downloadsFreeSpace.text = getString(
                    R.string.downloads_free_space,
                    Formatter.formatShortFileSize(this@DownloadQueueActivity, availableBytes),
                )
            }
        }
    }

    private fun startUpdating() {
        stopUpdating()
        updateJob = lifecycleScope.launch {
            while (isActive) {
                refreshUI()
                delay(1000)
            }
        }
    }

    private fun stopUpdating() {
        updateJob?.cancel()
        updateJob = null
    }

    private fun refreshUI() {
        val animeQueue = getActiveQueueTasks(MediaType.ANIME)
        val mangaQueue = getActiveQueueTasks(MediaType.MANGA)
        val novelQueue = getActiveQueueTasks(MediaType.NOVEL)

        updateSegmentLabel(binding.downloadsTypeAnime, getString(R.string.anime), animeQueue.size)
        updateSegmentLabel(binding.downloadsTypeManga, getString(R.string.manga), mangaQueue.size)
        updateSegmentLabel(binding.downloadsTypeNovel, getString(R.string.extensions_type_novel), novelQueue.size)

        binding.downloadQueueClear.isVisible = DownloadsSummary.hasQueuedWork(
            animeQueue.size,
            mangaQueue.size,
            novelQueue.size,
        )

        val totalActive = animeQueue.count { it.isActive } +
            mangaQueue.count { it.isActive } +
            novelQueue.count { it.isActive }
        val totalQueued = (animeQueue.size + mangaQueue.size + novelQueue.size) - totalActive

        updateSummaryCard(totalActive, totalQueued)

        val currentQueue = when (selectedType) {
            MediaType.ANIME -> animeQueue
            MediaType.MANGA -> mangaQueue
            MediaType.NOVEL -> novelQueue
        }
        val newItems = buildListItems(selectedType, currentQueue)
        adapter.submitList(newItems)

        val empty = newItems.isEmpty()
        binding.downloadQueueEmptyState.isVisible = empty
        binding.downloadQueueRecyclerView.isVisible = !empty
        if (empty) {
            binding.downloadQueueEmptyBody.setText(
                when (selectedType) {
                    MediaType.ANIME -> R.string.downloads_empty_anime_body
                    MediaType.MANGA -> R.string.downloads_empty_manga_body
                    MediaType.NOVEL -> R.string.downloads_empty_novel_body
                },
            )
        }
    }

    private fun updateSegmentLabel(button: MaterialButton, baseLabel: String, count: Int) {
        val next = DownloadsSummary.segmentLabel(baseLabel, count)
        if (button.text.toString() != next) {
            button.text = next
            if (button.isChecked) markSegment(button, null, true)
        }
    }

    private fun updateSummaryCard(totalActive: Int, totalQueued: Int) {
        val dm = Injekt.get<DownloadsManager>()
        val overview = DownloadsSummary.computeOverview(
            animeTitleSet = dm.animeDownloadedTypes.map { it.titleName }.toSet(),
            mangaTitleSet = dm.mangaDownloadedTypes.map { it.titleName }.toSet(),
            novelTitleSet = dm.novelDownloadedTypes.map { it.titleName }.toSet(),
            sizeByKey = folderSizeCache,
            activeCount = totalActive,
            queuedCount = totalQueued,
        )

        binding.downloadsTotalSize.text = when {
            overview.totalTitles == 0 -> SizeFormatter.formatBytes(0L)
            !overview.sizesComplete && overview.totalDownloadedBytes == 0L -> getString(R.string.cache_calculating)
            else -> SizeFormatter.formatBytes(overview.totalDownloadedBytes)
        }

        val breakdownParts = buildList {
            if (overview.animeTitles > 0) {
                add(resources.getQuantityString(R.plurals.downloads_summary_anime_count, overview.animeTitles, overview.animeTitles))
            }
            if (overview.mangaTitles > 0) {
                add(resources.getQuantityString(R.plurals.downloads_summary_manga_count, overview.mangaTitles, overview.mangaTitles))
            }
            if (overview.novelTitles > 0) {
                add(resources.getQuantityString(R.plurals.downloads_summary_novel_count, overview.novelTitles, overview.novelTitles))
            }
        }
        binding.downloadsBreakdown.text = if (breakdownParts.isEmpty()) {
            getString(R.string.downloads_no_titles)
        } else {
            breakdownParts.joinToString(" · ")
        }

        val activeText = formatInProgress(overview.activeCount, overview.queuedCount)
        binding.downloadsActiveBadge.isVisible = activeText != null
        if (activeText != null) binding.downloadsActiveBadge.text = activeText
    }

    private fun formatInProgress(active: Int, queued: Int): String? = when {
        active > 0 && queued > 0 -> getString(R.string.downloads_active_and_queued, active, queued)
        active > 0 -> getString(R.string.downloads_active_count, active)
        queued > 0 -> getString(R.string.downloads_queued_count, queued)
        else -> null
    }

    private fun getActiveQueueTasks(type: MediaType): List<QueueItem> {
        val activeList = when (type) {
            MediaType.ANIME -> synchronized(AnimeServiceDataSingleton.currentTasks) {
                AnimeServiceDataSingleton.currentTasks.toList()
            }.map { QueueItem.Anime(it, true) }
            MediaType.MANGA -> synchronized(MangaServiceDataSingleton.currentTasks) {
                MangaServiceDataSingleton.currentTasks.toList()
            }.map { QueueItem.Manga(it, true) }
            MediaType.NOVEL -> synchronized(NovelServiceDataSingleton.currentTasks) {
                NovelServiceDataSingleton.currentTasks.toList()
            }.map { QueueItem.Novel(it, true) }
        }
        val queuedList = when (type) {
            MediaType.ANIME -> AnimeServiceDataSingleton.downloadQueue.toList().map { QueueItem.Anime(it, false) }
            MediaType.MANGA -> MangaServiceDataSingleton.downloadQueue.toList().map { QueueItem.Manga(it, false) }
            MediaType.NOVEL -> NovelServiceDataSingleton.downloadQueue.toList().map { QueueItem.Novel(it, false) }
        }

        val queueItems = mutableListOf<QueueItem>()
        queueItems.addAll(activeList)
        for (item in queuedList) {
            if (!queueItems.any { it.uniqueId == item.uniqueId }) {
                queueItems.add(item)
            }
        }
        return queueItems
    }

    private fun buildListItems(type: MediaType, queueItems: List<QueueItem>): List<DownloadsListItem> {
        val listItems = mutableListOf<DownloadsListItem>()

        // 1. In progress
        if (queueItems.isNotEmpty()) {
            val active = queueItems.count { it.isActive }
            val queued = queueItems.size - active
            listItems.add(
                DownloadsListItem.Header(
                    id = "queue_${type.name}",
                    title = getString(R.string.downloads_section_in_progress),
                    meta = formatInProgress(active, queued).orEmpty(),
                ),
            )
            listItems.addAll(
                queueItems.map { item ->
                    val progress = when (item) {
                        is QueueItem.Anime -> AnimeServiceDataSingleton.progress[item.uniqueId]
                        is QueueItem.Manga -> MangaServiceDataSingleton.progress[item.uniqueId]
                        is QueueItem.Novel -> NovelServiceDataSingleton.progress[item.uniqueId]
                    }
                    val key = DownloadsSummary.mediaCacheKey(item.mediaType, item.title)
                    DownloadsListItem.QueueItemWrapper(item, progress, coverPathCache[key])
                },
            )
        }

        // 2. Downloaded titles
        val downloadsManager = Injekt.get<DownloadsManager>()
        val downloadedTypes = when (type) {
            MediaType.ANIME -> downloadsManager.animeDownloadedTypes
            MediaType.MANGA -> downloadsManager.mangaDownloadedTypes
            MediaType.NOVEL -> downloadsManager.novelDownloadedTypes
        }
        val grouped = downloadedTypes.groupBy { it.titleName }
        val mediaItems = grouped.map { (title, list) ->
            val key = DownloadsSummary.mediaCacheKey(type, title)
            DownloadsListItem.DownloadedMediaItem(
                title = title,
                type = type,
                count = list.size,
                sizeBytes = folderSizeCache[key],
                coverPath = coverPathCache[key],
            )
        }.sortedBy { it.title.lowercase() }

        if (mediaItems.isNotEmpty()) {
            val measuredSizes = mediaItems.mapNotNull { it.sizeBytes }
            val titleCountStr = resources.getQuantityString(
                R.plurals.downloads_summary_titles,
                mediaItems.size,
                mediaItems.size,
            )
            val meta = if (measuredSizes.isNotEmpty()) {
                "${SizeFormatter.formatBytes(measuredSizes.sum())} · $titleCountStr"
            } else {
                titleCountStr
            }
            listItems.add(
                DownloadsListItem.Header(
                    id = "downloaded_${type.name}",
                    title = getString(R.string.downloads_section_downloaded),
                    meta = meta,
                ),
            )
            listItems.addAll(mediaItems)
        }

        return listItems
    }

    private fun measureAllDownloadedMedia() {
        measureJob?.cancel()
        measureJob = lifecycleScope.launch(Dispatchers.IO) {
            val downloadsManager = Injekt.get<DownloadsManager>()
            // Measure the currently visible type first so its sizes and covers land immediately.
            val orderedTypes = listOf(selectedType) +
                listOf(MediaType.ANIME, MediaType.MANGA, MediaType.NOVEL).filter { it != selectedType }

            for (type in orderedTypes) {
                val downloadedTypes = when (type) {
                    MediaType.ANIME -> downloadsManager.animeDownloadedTypes
                    MediaType.MANGA -> downloadsManager.mangaDownloadedTypes
                    MediaType.NOVEL -> downloadsManager.novelDownloadedTypes
                }
                val batchSizes = mutableMapOf<String, Long>()
                val batchCovers = mutableMapOf<String, String?>()

                val titles = downloadedTypes.map { it.titleName }.distinct()
                for (title in titles) {
                    val key = DownloadsSummary.mediaCacheKey(type, title)
                    val folder = DownloadsManager.findSubDirectory(this@DownloadQueueActivity, type, title, null)
                    batchSizes[key] = getFolderSize(folder)
                    batchCovers[key] = resolveCoverPath(folder, title)
                }

                for (item in getActiveQueueTasks(type)) {
                    val key = DownloadsSummary.mediaCacheKey(item.mediaType, item.title)
                    if (!batchCovers.containsKey(key)) {
                        val folder = DownloadsManager.findSubDirectory(
                            this@DownloadQueueActivity,
                            item.mediaType,
                            item.title,
                            null,
                        )
                        batchCovers[key] = resolveCoverPath(folder, item.title)
                    }
                }

                withContext(Dispatchers.Main) {
                    folderSizeCache.putAll(batchSizes)
                    coverPathCache.putAll(batchCovers)
                    refreshUI()
                }
            }
        }
    }

    private fun resolveCoverPath(folder: DocumentFile?, title: String): String? {
        val coverFile = folder?.findFile("cover.jpg")
        if (coverFile != null && coverFile.exists()) return coverFile.uri.toString()
        val cachedCover = PrefManager.getCustomVal<String>("local_cover_$title", "")
        return cachedCover.takeIf { it.isNotEmpty() && it != "null" }
    }

    private fun getFolderSize(directory: DocumentFile?): Long {
        if (directory == null || !directory.exists()) return 0L
        var size = 0L
        for (file in directory.listFiles()) {
            size += if (file.isDirectory) getFolderSize(file) else file.length()
        }
        return size
    }

    private fun openDownloadedMedia(title: String, type: MediaType) {
        val downloadsManager = Injekt.get<DownloadsManager>()
        val list = when (type) {
            MediaType.ANIME -> downloadsManager.animeDownloadedTypes
            MediaType.MANGA -> downloadsManager.mangaDownloadedTypes
            MediaType.NOVEL -> downloadsManager.novelDownloadedTypes
        }
        val entry = list.firstOrNull { it.titleName == title || it.titleName.compareName(title) }
        if (entry == null) {
            snackString(getString(R.string.error_loading_data, title))
            return
        }

        lifecycleScope.launch {
            val mediaModel = withContext(Dispatchers.IO) { loadDownloadedMedia(entry) }
            if (mediaModel == null) {
                snackString(getString(R.string.error_loading_data, title))
                return@launch
            }
            MediaDetailsActivity.mediaSingleton = mediaModel
            ContextCompat.startActivity(
                this@DownloadQueueActivity,
                Intent(this@DownloadQueueActivity, MediaDetailsActivity::class.java)
                    .putExtra("download", true),
                null,
            )
        }
    }

    private fun loadDownloadedMedia(downloadedType: DownloadedType): Media? {
        return try {
            val directory = DownloadsManager.findSubDirectory(
                this,
                downloadedType.type,
                downloadedType.titleName,
            )
            val gson = GsonBuilder()
                .registerTypeAdapter(SChapter::class.java, InstanceCreator<SChapter> { SChapterImpl() })
                .registerTypeAdapter(SAnime::class.java, InstanceCreator<SAnime> { SAnimeImpl() })
                .registerTypeAdapter(SEpisode::class.java, InstanceCreator<SEpisode> { SEpisodeImpl() })
                .create()
            val mediaFile = directory?.findFile("media.json")
                ?: return loadMediaCompat(downloadedType)
            val mediaJson = mediaFile.openInputStream(this)?.bufferedReader().use { it?.readText() }
                ?: return null
            gson.fromJson(mediaJson, Media::class.java)
        } catch (e: Exception) {
            Logger.log("DownloadQueueActivity: error loading media.json: ${e.message}")
            null
        }
    }

    private fun showOptionsSheet() {
        val dialog = BottomSheetDialog(this)
        val b = SheetDownloadOptionsBinding.inflate(layoutInflater)

        val animeQueue = getActiveQueueTasks(MediaType.ANIME)
        val mangaQueue = getActiveQueueTasks(MediaType.MANGA)
        val novelQueue = getActiveQueueTasks(MediaType.NOVEL)
        val active = animeQueue.count { it.isActive } +
            mangaQueue.count { it.isActive } +
            novelQueue.count { it.isActive }
        val queued = (animeQueue.size + mangaQueue.size + novelQueue.size) - active
        val hasQueue = active + queued > 0

        b.optionAutoDeleteSwitch.isChecked = PrefManager.getCustomVal("auto_delete_downloads", false)
        b.optionAutoDeleteRow.setOnClickListener {
            val now = !b.optionAutoDeleteSwitch.isChecked
            b.optionAutoDeleteSwitch.isChecked = now
            PrefManager.setCustomVal("auto_delete_downloads", now)
            b.optionAutoDeleteRow.sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_CLICKED)
        }
        ViewCompat.setAccessibilityDelegate(b.optionAutoDeleteRow, object : AccessibilityDelegateCompat() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.className = android.widget.Switch::class.java.name
                info.isCheckable = true
                info.isChecked = b.optionAutoDeleteSwitch.isChecked
            }
        })

        b.optionStorageSettingsRow.setOnClickListener {
            dialog.dismiss()
            startActivity(Intent(this, SettingsDataActivity::class.java))
        }

        b.optionClearQueueRow.isVisible = hasQueue
        if (hasQueue) {
            b.optionClearQueueSummary.text = formatInProgress(active, queued).orEmpty()
            b.optionClearQueueRow.setOnClickListener {
                dialog.dismiss()
                confirmClearQueue()
            }
        }

        SettingsCards.apply(b.optionAutoDeleteRow, first = true, last = false)
        SettingsCards.apply(b.optionStorageSettingsRow, first = false, last = !hasQueue)
        if (hasQueue) {
            SettingsCards.apply(b.optionClearQueueRow, first = false, last = true)
        }

        dialog.setContentView(b.root)
        val baseBottom = b.root.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(b.root) { v, insets ->
            val nav = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            v.updatePadding(bottom = baseBottom + nav)
            insets
        }
        dialog.show()
        dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.backgroundTintList =
            ColorStateList.valueOf(
                MaterialColors.getColor(b.root, com.google.android.material.R.attr.colorSurface),
            )
        ViewCompat.requestApplyInsets(b.root)
    }

    private fun confirmClearQueue() {
        customAlertDialog().apply {
            setTitle(R.string.clear_queue)
            setMessage(R.string.clear_queue_confirm)
            setPosButton(R.string.yes) {
                clearAllQueues()
            }
            setNegButton(R.string.no)
            show()
        }
    }

    private fun cancelTask(item: QueueItem) {
        when (item) {
            is QueueItem.Anime -> {
                val intent = Intent(AnimeDownloaderService.ACTION_CANCEL_DOWNLOAD).apply {
                    putExtra(AnimeDownloaderService.EXTRA_TASK_NAME, item.uniqueId)
                }
                sendBroadcast(intent.setPackage(packageName))
            }
            is QueueItem.Manga -> {
                // Exact physical-key contract: the service receiver requires BOTH
                // chapter and title (chapter-only selects nothing by design, so a
                // title-less cancel would silently no-op).
                val command = MangaQueueCancel.commandFor(item.task)
                val intent = Intent(MangaDownloaderService.ACTION_CANCEL_DOWNLOAD).apply {
                    putExtra(MangaDownloaderService.EXTRA_CHAPTER, command.chapter)
                    putExtra(MangaDownloaderService.EXTRA_TITLE, command.title)
                }
                sendBroadcast(intent.setPackage(packageName))
            }
            is QueueItem.Novel -> {
                val intent = Intent(NovelDownloaderService.ACTION_CANCEL_DOWNLOAD).apply {
                    putExtra(NovelDownloaderService.EXTRA_TASK_KEY, item.uniqueId)
                }
                sendBroadcast(intent.setPackage(packageName))
            }
        }
        startUpdating()
    }

    private fun showDeleteMediaDialog(title: String, type: MediaType) {
        customAlertDialog().apply {
            setTitle(R.string.downloads_delete_title)
            setMessage(getString(R.string.clear_media_confirm, title))
            setPosButton(R.string.yes) {
                if (type == MediaType.MANGA) {
                    // Authoritative title delete via the service scope barrier
                    // (live owners cancelled+joined before physical deletion).
                    val intent = Intent(this@DownloadQueueActivity, MangaDownloaderService::class.java).apply {
                        action = MangaDownloaderService.ACTION_DELETE_TITLE
                        putExtra(MangaDownloaderService.EXTRA_TITLE, title)
                    }
                    ContextCompat.startForegroundService(this@DownloadQueueActivity, intent)
                } else {
                    val downloadsManager = Injekt.get<DownloadsManager>()
                    downloadsManager.removeMedia(title, type)
                }
                folderSizeCache.remove(DownloadsSummary.mediaCacheKey(type, title))
                updateDeviceStorage()
                measureAllDownloadedMedia()
                startUpdating()
            }
            setNegButton(R.string.no)
            show()
        }
    }

    private fun clearAllQueues() {
        // Clear Queues. Anime/Novel keep their existing full-clear behavior.
        // Manga clear-all is ONE linearizable cutoff transaction owned by the
        // service (ACTION_CLEAR_MANGA).
        AnimeServiceDataSingleton.downloadQueue.clear()
        NovelServiceDataSingleton.downloadQueue.clear()
        ContextCompat.startForegroundService(
            this,
            Intent(this, MangaDownloaderService::class.java).apply {
                action = MangaDownloaderService.ACTION_CLEAR_MANGA
            },
        )
        MangaServiceDataSingleton.isServiceRunning = true

        val animeActive = synchronized(AnimeServiceDataSingleton.currentTasks) {
            AnimeServiceDataSingleton.currentTasks.toList()
        }
        for (task in animeActive) {
            val intent = Intent(AnimeDownloaderService.ACTION_CANCEL_DOWNLOAD).apply {
                putExtra(AnimeDownloaderService.EXTRA_TASK_NAME, task.getTaskName())
            }
            sendBroadcast(intent.setPackage(packageName))
        }

        val novelActive = synchronized(NovelServiceDataSingleton.currentTasks) {
            NovelServiceDataSingleton.currentTasks.toList()
        }
        for (task in novelActive) {
            val intent = Intent(NovelDownloaderService.ACTION_CANCEL_DOWNLOAD).apply {
                putExtra(
                    NovelDownloaderService.EXTRA_TASK_KEY,
                    ani.dantotsu.download.novel.novelTaskKey(task.title, task.chapter),
                )
            }
            sendBroadcast(intent.setPackage(packageName))
        }

        startUpdating()
    }

    private companion object {
        const val STATE_SELECTED_TYPE = "downloads_selected_type"
    }
}

sealed class QueueItem {
    abstract val title: String
    abstract val subTitle: String
    abstract val type: String
    abstract val uniqueId: String
    abstract val isActive: Boolean
    abstract val mediaType: MediaType

    data class Anime(val task: AnimeDownloaderService.AnimeDownloadTask, override val isActive: Boolean) : QueueItem() {
        override val title: String get() = task.title
        override val subTitle: String get() = task.episode
        override val type: String get() = "Anime"
        override val uniqueId: String get() = task.getTaskName()
        override val mediaType: MediaType get() = MediaType.ANIME
    }

    data class Manga(val task: MangaDownloaderService.DownloadTask, override val isActive: Boolean) : QueueItem() {
        override val title: String get() = task.title
        override val subTitle: String get() = task.chapter
        override val type: String get() = "Manga"
        override val uniqueId: String get() = DownloadsSummary.mangaProgressKey(task.title, task.chapter)
        override val mediaType: MediaType get() = MediaType.MANGA
    }

    data class Novel(val task: NovelDownloaderService.DownloadTask, override val isActive: Boolean) : QueueItem() {
        override val title: String get() = task.title
        override val subTitle: String get() = task.chapter
        override val type: String get() = "Novel"
        override val uniqueId: String get() = ani.dantotsu.download.novel.novelTaskKey(task.title, task.chapter)
        override val mediaType: MediaType get() = MediaType.NOVEL
    }
}

sealed class DownloadsListItem {
    data class Header(val id: String, val title: String, val meta: String) : DownloadsListItem()
    data class QueueItemWrapper(
        val queueItem: QueueItem,
        val progress: Int?,
        val coverPath: String?,
    ) : DownloadsListItem()
    data class DownloadedMediaItem(
        val title: String,
        val type: MediaType,
        val count: Int,
        val sizeBytes: Long?,
        val coverPath: String?,
    ) : DownloadsListItem()
}

class DownloadsDiffCallback : DiffUtil.ItemCallback<DownloadsListItem>() {
    override fun areItemsTheSame(oldItem: DownloadsListItem, newItem: DownloadsListItem): Boolean {
        return when {
            oldItem is DownloadsListItem.Header && newItem is DownloadsListItem.Header ->
                oldItem.id == newItem.id
            oldItem is DownloadsListItem.QueueItemWrapper && newItem is DownloadsListItem.QueueItemWrapper ->
                oldItem.queueItem.mediaType == newItem.queueItem.mediaType &&
                    oldItem.queueItem.uniqueId == newItem.queueItem.uniqueId
            oldItem is DownloadsListItem.DownloadedMediaItem && newItem is DownloadsListItem.DownloadedMediaItem ->
                oldItem.title == newItem.title && oldItem.type == newItem.type
            else -> false
        }
    }

    override fun areContentsTheSame(oldItem: DownloadsListItem, newItem: DownloadsListItem): Boolean {
        return oldItem == newItem
    }
}

class DownloadsAdapter(
    private val onCancelQueueClick: (QueueItem) -> Unit,
    private val onDeleteMediaClick: (String, MediaType) -> Unit,
    private val onOpenMediaClick: (String, MediaType) -> Unit,
) : ListAdapter<DownloadsListItem, RecyclerView.ViewHolder>(DownloadsDiffCallback()) {

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_QUEUE = 1
        private const val TYPE_MEDIA = 2
    }

    override fun getItemViewType(position: Int): Int {
        return when (getItem(position)) {
            is DownloadsListItem.Header -> TYPE_HEADER
            is DownloadsListItem.QueueItemWrapper -> TYPE_QUEUE
            is DownloadsListItem.DownloadedMediaItem -> TYPE_MEDIA
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_HEADER -> HeaderViewHolder(ItemDownloadsHeaderBinding.inflate(inflater, parent, false))
            TYPE_QUEUE -> QueueViewHolder(ItemDownloadQueueBinding.inflate(inflater, parent, false))
            else -> MediaViewHolder(ItemDownloadQueueBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = getItem(position)
        when (holder) {
            is HeaderViewHolder -> {
                val header = item as DownloadsListItem.Header
                holder.binding.headerTitle.text = header.title
                holder.binding.headerMeta.text = header.meta
                holder.binding.headerMeta.isVisible = header.meta.isNotEmpty()
            }
            is QueueViewHolder -> {
                val wrapper = item as DownloadsListItem.QueueItemWrapper
                val queueItem = wrapper.queueItem
                val ctx = holder.binding.root.context
                holder.binding.itemDownloadCard.isClickable = false
                holder.binding.itemDownloadCard.setOnClickListener(null)
                holder.binding.itemDownloadTitle.text = queueItem.title
                holder.binding.itemDownloadSubtitle.text = queueItem.subTitle

                bindCover(holder.binding, queueItem.mediaType, wrapper.coverPath)

                holder.binding.itemDownloadCancel.setIconResource(R.drawable.ic_round_close_24)
                holder.binding.itemDownloadCancel.contentDescription =
                    ctx.getString(R.string.downloads_cancel_cd, queueItem.title)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    holder.binding.itemDownloadCancel.tooltipText = ctx.getString(R.string.cancel)
                }

                if (queueItem.isActive) {
                    holder.binding.itemDownloadProgressLayout.isVisible = true
                    holder.binding.itemDownloadQueuedLabel.isVisible = false
                    val progress = wrapper.progress
                    val bar = holder.binding.itemDownloadProgressBar
                    if (progress != null) {
                        bar.setProgressCompat(progress, true)
                        holder.binding.itemDownloadProgressText.text = "$progress%"
                    } else {
                        if (!bar.isIndeterminate) {
                            bar.visibility = View.INVISIBLE
                            bar.isIndeterminate = true
                            bar.visibility = View.VISIBLE
                        }
                        holder.binding.itemDownloadProgressText.setText(R.string.downloads_starting)
                    }
                } else {
                    holder.binding.itemDownloadProgressLayout.isVisible = false
                    holder.binding.itemDownloadQueuedLabel.isVisible = true
                }

                holder.binding.itemDownloadCancel.setOnClickListener {
                    onCancelQueueClick(queueItem)
                }
            }
            is MediaViewHolder -> {
                val media = item as DownloadsListItem.DownloadedMediaItem
                val ctx = holder.binding.root.context
                holder.binding.itemDownloadCard.isClickable = true
                holder.binding.itemDownloadCard.setOnClickListener {
                    onOpenMediaClick(media.title, media.type)
                }
                holder.binding.itemDownloadTitle.text = media.title

                val countText = when (media.type) {
                    MediaType.ANIME -> ctx.resources.getQuantityString(
                        R.plurals.downloads_episode_count,
                        media.count,
                        media.count,
                    )
                    else -> ctx.resources.getQuantityString(
                        R.plurals.downloads_chapter_count,
                        media.count,
                        media.count,
                    )
                }
                holder.binding.itemDownloadSubtitle.text = if (media.sizeBytes != null) {
                    "$countText · ${SizeFormatter.formatBytes(media.sizeBytes)}"
                } else {
                    countText
                }

                bindCover(holder.binding, media.type, media.coverPath)

                holder.binding.itemDownloadCancel.setIconResource(R.drawable.ic_round_delete_24)
                holder.binding.itemDownloadCancel.contentDescription =
                    ctx.getString(R.string.downloads_delete_cd, media.title)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    holder.binding.itemDownloadCancel.tooltipText = ctx.getString(R.string.delete)
                }
                holder.binding.itemDownloadCancel.setOnClickListener {
                    onDeleteMediaClick(media.title, media.type)
                }

                holder.binding.itemDownloadProgressLayout.isVisible = false
                holder.binding.itemDownloadQueuedLabel.isVisible = false
            }
        }
    }

    private fun bindCover(b: ItemDownloadQueueBinding, type: MediaType, coverPath: String?) {
        val placeholderIcon = when (type) {
            MediaType.ANIME -> R.drawable.ic_round_movie_filter_24
            MediaType.MANGA -> R.drawable.ic_round_import_contacts_24
            MediaType.NOVEL -> R.drawable.ic_round_book_24
        }
        b.itemDownloadPlaceholder.setImageResource(placeholderIcon)
        if (coverPath != null) {
            b.itemDownloadImage.isVisible = true
            b.itemDownloadImage.loadImage(coverPath)
        } else {
            b.itemDownloadImage.setImageDrawable(null)
            b.itemDownloadImage.isVisible = false
        }
    }

    class HeaderViewHolder(val binding: ItemDownloadsHeaderBinding) : RecyclerView.ViewHolder(binding.root)
    class QueueViewHolder(val binding: ItemDownloadQueueBinding) : RecyclerView.ViewHolder(binding.root)
    class MediaViewHolder(val binding: ItemDownloadQueueBinding) : RecyclerView.ViewHolder(binding.root)
}
