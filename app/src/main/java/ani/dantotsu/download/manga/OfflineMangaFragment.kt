package ani.dantotsu.download.manga

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.SpannableString
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AlphaAnimation
import android.view.animation.LayoutAnimationController
import android.widget.AbsListView
import android.widget.GridView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.marginBottom
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import ani.dantotsu.R
import ani.dantotsu.bottomBarOrNull
import ani.dantotsu.connections.crashlytics.CrashlyticsInterface
import ani.dantotsu.currContext
import ani.dantotsu.databinding.FragmentOfflinePageBinding
import ani.dantotsu.download.DownloadCompat
import ani.dantotsu.download.DownloadCompat.Companion.loadOfflineMangaModelCompat
import ani.dantotsu.download.DownloadedType
import ani.dantotsu.download.DownloadsManager
import ani.dantotsu.download.DownloadsManager.Companion.compareName
import ani.dantotsu.download.DownloadsManager.Companion.getSubDirectory
import ani.dantotsu.download.findValidName
import ani.dantotsu.initActivity
import ani.dantotsu.media.Media
import ani.dantotsu.media.MediaDetailsActivity
import ani.dantotsu.media.MediaType
import ani.dantotsu.navBarHeight
import ani.dantotsu.setSafeOnClickListener
import ani.dantotsu.settings.DownloadQueueActivity
import ani.dantotsu.settings.SettingsDialogFragment
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.snackString
import ani.dantotsu.statusBarHeight
import ani.dantotsu.util.Logger
import ani.dantotsu.util.customAlertDialog
import com.anggrayudi.storage.file.openInputStream
import com.google.android.material.color.MaterialColors
import com.google.gson.GsonBuilder
import com.google.gson.InstanceCreator
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SChapterImpl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class OfflineMangaFragment : Fragment(), OfflineMangaSearchListener {

    private val downloadManager = Injekt.get<DownloadsManager>()
    private var _binding: FragmentOfflinePageBinding? = null
    private val binding get() = _binding!!
    private var downloads: List<OfflineMangaModel> = listOf()
    private lateinit var gridView: GridView
    private lateinit var adapter: OfflineMangaAdapter
    private var downloadsJob: Job = Job()
    private var currentQuery: String = ""

    /**
     * Ordered scope-delete completion (whole-title / purge-all). Refreshes the grid
     * only on success; on failure the entries stay (files + metadata intact) and the
     * failure surfaces instead.
     */
    private val scopeDeleteReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != MangaDownloaderService.ACTION_MANGA_SCOPE_DELETED) return
            if (intent.getBooleanExtra(MangaDownloaderService.EXTRA_MANGA_DELETE_FAILED, false)) {
                snackString("Failed to delete")
                return
            }
            if (intent.getBooleanExtra(MangaDownloaderService.EXTRA_MANGA_DELETED, false)) {
                getDownloads()
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentOfflinePageBinding.inflate(inflater, container, false)

        val baseGridBottom = binding.gridView.paddingBottom
        binding.offlineRoot.updatePadding(top = statusBarHeight)
        binding.gridView.updatePadding(bottom = baseGridBottom + navBarHeight)
        binding.gridView1.updatePadding(bottom = baseGridBottom + navBarHeight)
        ViewCompat.setOnApplyWindowInsetsListener(binding.offlineRoot) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            if (v.paddingTop < bars.top) v.updatePadding(top = bars.top)
            val bottomPad = baseGridBottom + bars.bottom
            if (binding.gridView.paddingBottom < bottomPad) {
                binding.gridView.updatePadding(bottom = bottomPad)
                binding.gridView1.updatePadding(bottom = bottomPad)
            }
            insets
        }

        binding.animeSearchBarText.hint = getString(R.string.offline_search_manga)
        binding.offlineSearchClear.setOnClickListener {
            binding.animeSearchBarText.setText("")
        }
        binding.animeSearchBarText.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {}
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                currentQuery = s?.toString().orEmpty()
                binding.offlineSearchClear.isVisible = currentQuery.isNotEmpty()
                onSearchQuery(currentQuery)
            }
        })

        binding.offlineDownloadsQueue.setSafeOnClickListener {
            startActivity(Intent(requireContext(), DownloadQueueActivity::class.java))
        }

        binding.offlineActionButton.setSafeOnClickListener {
            val dialogFragment =
                SettingsDialogFragment.newInstance(SettingsDialogFragment.Companion.PageType.OfflineMANGA)
            dialogFragment.show((it.context as AppCompatActivity).supportFragmentManager, "dialog")
        }

        var style: Int = PrefManager.getVal(PrefName.OfflineView)
        binding.offlineViewToggle.check(if (style == 1) R.id.downloadedGrid else R.id.downloadedList)
        gridView = if (style == 1) binding.gridView1 else binding.gridView

        binding.offlineViewToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val nextStyle = if (checkedId == R.id.downloadedGrid) 1 else 0
            if (nextStyle == style) return@addOnButtonCheckedListener
            style = nextStyle
            PrefManager.setVal(PrefName.OfflineView, style)
            gridView.isVisible = false
            gridView = if (style == 1) binding.gridView1 else binding.gridView
            adapter.notifyNewGrid()
            grid()
            bindScrollListener()
        }

        binding.localRefresh.setOnChildScrollUpCallback { _, _ ->
            gridView.canScrollVertically(-1)
        }
        binding.localRefresh.setOnRefreshListener {
            getDownloads()
            binding.localRefresh.isRefreshing = false
        }

        grid()
        return binding.root
    }

    private fun grid() {
        gridView.isVisible = true
        val fadeIn = AlphaAnimation(0f, 1f)
        fadeIn.duration = 250
        gridView.layoutAnimation = LayoutAnimationController(fadeIn)
        adapter = OfflineMangaAdapter(requireContext(), downloads, this)
        if (currentQuery.isNotEmpty()) adapter.onSearchQuery(currentQuery)
        gridView.adapter = adapter
        gridView.scheduleLayoutAnimation()
        updateHeaderAndEmptyState()

        gridView.setOnItemClickListener { _, _, position, _ ->
            val item = adapter.getItem(position) as OfflineMangaModel
            val media =
                downloadManager.mangaDownloadedTypes.firstOrNull { it.titleName.compareName(item.title) }
                    ?: downloadManager.novelDownloadedTypes.firstOrNull {
                        it.titleName.compareName(item.title)
                    }
            media?.let {
                lifecycleScope.launch {
                    val mediaModel = withContext(Dispatchers.IO) { getMedia(it) }
                    if (mediaModel == null) {
                        snackString("Error loading media.json")
                        return@launch
                    }
                    MediaDetailsActivity.mediaSingleton = mediaModel
                    ContextCompat.startActivity(
                        requireActivity(),
                        Intent(requireContext(), MediaDetailsActivity::class.java)
                            .putExtra("download", true),
                        null
                    )
                }
            } ?: run {
                snackString("no media found")
            }
        }

        gridView.setOnItemLongClickListener { _, _, position, _ ->
            val item = adapter.getItem(position) as OfflineMangaModel
            val type: MediaType =
                MediaType.fromText(item.type)
                    ?: if (downloadManager.mangaDownloadedTypes.any {
                            it.titleName.findValidName() == item.title.findValidName()
                        }
                    ) {
                        MediaType.MANGA
                    } else {
                        MediaType.NOVEL
                    }
            // Alert dialog to confirm deletion
            requireContext().customAlertDialog().apply {
                setTitle("Delete ${item.title}?")
                setMessage("Are you sure you want to delete ${item.title}?")
                setPosButton(R.string.yes) {
                    if (type == MediaType.MANGA) {
                        // Authoritative title delete via the non-exported service.
                        val intent = Intent(
                            requireContext(),
                            MangaDownloaderService::class.java
                        ).apply {
                            action = MangaDownloaderService.ACTION_DELETE_TITLE
                            putExtra(MangaDownloaderService.EXTRA_TITLE, item.title)
                        }
                        ContextCompat.startForegroundService(requireContext(), intent)
                    } else {
                        downloadManager.removeMedia(item.title, type)
                        getDownloads()
                    }
                }
                setNegButton(R.string.no)
            }.show()
            true
        }
        getDownloads()
    }

    override fun onSearchQuery(query: String) {
        if (::adapter.isInitialized) {
            adapter.onSearchQuery(query)
            updateHeaderAndEmptyState()
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initActivity(requireActivity())
        ContextCompat.registerReceiver(
            requireContext(),
            scopeDeleteReceiver,
            IntentFilter(MangaDownloaderService.ACTION_MANGA_SCOPE_DELETED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        binding.mangaPageScrollTop.setOnClickListener {
            gridView.smoothScrollToPositionFromTop(0, 0)
        }
        binding.mangaPageScrollTop.isVisible = false
        bindScrollListener()
    }

    private fun bindScrollListener() {
        gridView.setOnScrollListener(object : AbsListView.OnScrollListener {
            override fun onScrollStateChanged(view: AbsListView, scrollState: Int) {}
            override fun onScroll(
                view: AbsListView,
                firstVisibleItem: Int,
                visibleItemCount: Int,
                totalItemCount: Int
            ) {
                val b = _binding ?: return
                val first = view.getChildAt(0)
                val visibility = first != null && first.top < 0
                b.mangaPageScrollTop.isVisible = visibility
                b.mangaPageScrollTop.translationY =
                    -(navBarHeight + (bottomBarOrNull?.height ?: 0) + (bottomBarOrNull?.marginBottom ?: 0)).toFloat()
            }
        })
    }

    private fun updateHeaderAndEmptyState() {
        val b = _binding ?: return
        val count = if (::adapter.isInitialized) adapter.count else 0
        val title = getString(R.string.offline_manga_novels)
        b.total.text = if (count > 0) {
            SpannableString("$title  $count").apply {
                setSpan(
                    ForegroundColorSpan(
                        MaterialColors.getColor(b.total, com.google.android.material.R.attr.colorOnSurfaceVariant)
                    ),
                    title.length,
                    length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }
        } else {
            title
        }

        val isEmpty = count == 0
        b.offlineEmptyState.isVisible = isEmpty
        gridView.isVisible = !isEmpty
        if (isEmpty) {
            b.offlineEmptyIcon.setImageResource(
                if (currentQuery.isNotBlank()) R.drawable.ic_round_search_24 else R.drawable.ic_download_24
            )
            b.offlineEmptyAction.isVisible = false
            if (currentQuery.isNotBlank()) {
                b.offlineEmptyTitle.setText(R.string.offline_empty_search_title)
                b.offlineEmptyBody.text = getString(R.string.offline_empty_search_body, currentQuery)
            } else {
                b.offlineEmptyTitle.setText(R.string.offline_empty_manga_title)
                b.offlineEmptyBody.setText(R.string.offline_empty_manga_body)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        getDownloads()
    }

    override fun onPause() {
        super.onPause()
        downloads = listOf()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        downloadsJob.cancel()
        runCatching { requireContext().unregisterReceiver(scopeDeleteReceiver) }
        _binding = null
    }

    override fun onDestroy() {
        super.onDestroy()
        downloads = listOf()
    }

    override fun onStop() {
        super.onStop()
        downloads = listOf()
    }

    private fun getDownloads() {
        downloads = listOf()
        if (downloadsJob.isActive) {
            downloadsJob.cancel()
        }
        downloadsJob = Job()
        CoroutineScope(Dispatchers.IO + downloadsJob).launch {
            // Reconcile persisted state: drop manga entries whose page set is
            // incomplete so a stale/partial COMPLETE marker is not trusted.
            downloadManager.reconcileIncompleteDownloads()
            val mangaTitles =
                downloadManager.mangaDownloadedTypes.map { it.titleName.findValidName() }.distinct()
            val newMangaDownloads = mutableListOf<OfflineMangaModel>()
            for (title in mangaTitles) {
                val tDownloads =
                    downloadManager.mangaDownloadedTypes.filter { it.titleName.findValidName() == title }
                val download = tDownloads.firstOrNull() ?: continue
                val offlineMangaModel = loadOfflineMangaModel(download)
                newMangaDownloads += offlineMangaModel
            }
            downloads = newMangaDownloads
            val novelTitles = downloadManager.novelDownloadedTypes.map { it.titleName }.distinct()
            val newNovelDownloads = mutableListOf<OfflineMangaModel>()
            for (title in novelTitles) {
                val tDownloads =
                    downloadManager.novelDownloadedTypes.filter { it.titleName.findValidName() == title }
                val download = tDownloads.firstOrNull() ?: continue
                val offlineMangaModel = loadOfflineMangaModel(download)
                newNovelDownloads += offlineMangaModel
            }
            downloads += newNovelDownloads
            withContext(Dispatchers.Main) {
                if (!isAdded || _binding == null) return@withContext
                adapter.setItems(downloads)
                if (currentQuery.isNotEmpty()) adapter.onSearchQuery(currentQuery)
                updateHeaderAndEmptyState()
            }
        }
    }

    private suspend fun getMedia(downloadedType: DownloadedType): Media? {
        return try {
            val directory = getSubDirectory(
                context ?: currContext()!!, downloadedType.type,
                false, downloadedType.titleName
            )
            val gson = GsonBuilder()
                .registerTypeAdapter(SChapter::class.java, InstanceCreator<SChapter> { SChapterImpl() })
                .create()
            val media = directory?.findFile("media.json")
            if (media == null) {
                Logger.log("No media.json found at ${directory?.uri?.path}")
                return DownloadCompat.loadMediaCompat(downloadedType)
            }
            val mediaJson =
                media.openInputStream(context ?: currContext()!!)?.bufferedReader().use {
                    it?.readText()
                }
            gson.fromJson(mediaJson, Media::class.java)
        } catch (e: Exception) {
            Logger.log("Error loading media.json: ${e.message}")
            Logger.log(e)
            Injekt.get<CrashlyticsInterface>().logException(e)
            null
        }
    }

    private suspend fun loadOfflineMangaModel(downloadedType: DownloadedType): OfflineMangaModel {
        val type = downloadedType.type.asText()
        try {
            val directory = getSubDirectory(
                context ?: currContext()!!, downloadedType.type,
                false, downloadedType.titleName
            )
            val mediaModel = getMedia(downloadedType)!!
            val cover = directory?.findFile("cover.jpg")
            val coverUri: Uri? = if (cover?.exists() == true) cover.uri else null
            val banner = directory?.findFile("banner.jpg")
            val bannerUri: Uri? = if (banner?.exists() == true) banner.uri else null
            if (coverUri == null && bannerUri == null) throw Exception("No cover or banner found, probably compat")
            val title = mediaModel.mainName()
            val score = ani.dantotsu.connections.anilist.cardScoreText(mediaModel.userScore, mediaModel.meanScore)
            val isOngoing =
                mediaModel.status == (context ?: currContext())?.getString(R.string.status_releasing)
            val isUserScored = mediaModel.userScore != 0
            val readChapter = (mediaModel.userProgress ?: "~").toString()
            val totalChapter = "${mediaModel.manga?.totalChapters ?: "??"}"
            val chapters = " Chapters"
            return OfflineMangaModel(
                title,
                score,
                totalChapter,
                readChapter,
                type,
                chapters,
                isOngoing,
                isUserScored,
                coverUri,
                bannerUri
            )
        } catch (e: Exception) {
            Logger.log(e)
            return try {
                loadOfflineMangaModelCompat(downloadedType)
            } catch (e: Exception) {
                Logger.log("Error loading media.json: ${e.message}")
                Logger.log(e)
                Injekt.get<CrashlyticsInterface>().logException(e)
                return OfflineMangaModel(
                    downloadedType.titleName,
                    "0",
                    "??",
                    "??",
                    "movie",
                    "hmm",
                    isOngoing = false,
                    isUserScored = false,
                    null,
                    null
                )
            }
        }
    }
}

interface OfflineMangaSearchListener {
    fun onSearchQuery(query: String)
}
