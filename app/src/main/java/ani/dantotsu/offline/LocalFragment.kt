package ani.dantotsu.offline

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Build
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
import androidx.activity.result.contract.ActivityResultContracts
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
import ani.dantotsu.databinding.FragmentOfflinePageBinding
import ani.dantotsu.download.anime.OfflineAnimeAdapter
import ani.dantotsu.download.anime.OfflineAnimeModel
import ani.dantotsu.download.anime.OfflineAnimeSearchListener
import ani.dantotsu.initActivity
import ani.dantotsu.media.Media
import ani.dantotsu.media.MediaDetailsActivity
import ani.dantotsu.media.Selected
import ani.dantotsu.navBarHeight
import ani.dantotsu.parsers.AnimeSources
import ani.dantotsu.parsers.MangaSources
import ani.dantotsu.setSafeOnClickListener
import ani.dantotsu.settings.DownloadQueueActivity
import ani.dantotsu.settings.rows.markSegment
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.snackString
import ani.dantotsu.statusBarHeight
import ani.dantotsu.util.Logger
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tachiyomi.source.local.entries.anime.LocalAnimeSource
import tachiyomi.source.local.entries.manga.LocalMangaSource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class LocalFragment : Fragment(), OfflineAnimeSearchListener {

    private val localAnimeSource by lazy { LocalAnimeSource(Injekt.get<Application>()) }
    private val localMangaSource by lazy { LocalMangaSource(Injekt.get<Application>()) }
    private var _binding: FragmentOfflinePageBinding? = null
    private val binding get() = _binding!!
    private var downloads: List<OfflineAnimeModel> = listOf()

    private lateinit var gridView: GridView
    private lateinit var adapter: OfflineAnimeAdapter
    private var scanJob: Job = Job()
    private var currentQuery: String = ""
    private var scanning: Boolean = false

    enum class LocalMediaType { ANIME, MANGA, NOVEL }
    private var currentMode = LocalMediaType.ANIME
    private val localNovelSource by lazy { tachiyomi.source.local.entries.novel.LocalNovelSource(Injekt.get<Application>()) }

    // SAF folder picker
    private val folderPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            requireContext().contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            val docFile = androidx.documentfile.provider.DocumentFile.fromTreeUri(requireContext(), uri)
            var localDir = docFile?.findFile("local")
            if (localDir == null) {
                localDir = docFile?.createDirectory("local")
            }
            localDir?.findFile("anime") ?: localDir?.createDirectory("anime")
            localDir?.findFile("manga") ?: localDir?.createDirectory("manga")
            localDir?.findFile("novel") ?: localDir?.createDirectory("novel")
            localDir?.findFile(".nomedia") ?: localDir?.createFile("", ".nomedia")

            PrefManager.setVal(PrefName.LocalDir, uri.toString())
            animeDownloadsCache = null
            mangaDownloadsCache = null
            novelDownloadsCache = null
            snackString("Master 'local' folder selected/created! Place anime, manga, and novels in their respective subfolders and refresh.")
            scanLocal()
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

        updateSearchHint()
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

        // Folder picker replaces the settings icon on Local tab
        binding.offlineActionButton.setIconResource(R.drawable.ic_round_folder_24)
        binding.offlineActionButton.contentDescription = getString(R.string.offline_choose_folder_cd)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            binding.offlineActionButton.tooltipText = getString(R.string.offline_choose_folder_cd)
        }
        binding.offlineActionButton.setSafeOnClickListener {
            folderPickerLauncher.launch(null)
        }

        binding.offlineEmptyAction.setSafeOnClickListener {
            folderPickerLauncher.launch(null)
        }

        // M3 Anime / Manga / Novel segmented toggle
        binding.offlineTypeToggle.isVisible = true
        binding.offlineTypeToggle.check(
            when (currentMode) {
                LocalMediaType.ANIME -> R.id.offlineTypeAnime
                LocalMediaType.MANGA -> R.id.offlineTypeManga
                LocalMediaType.NOVEL -> R.id.offlineTypeNovel
            }
        )
        listOf(binding.offlineTypeAnime, binding.offlineTypeManga, binding.offlineTypeNovel)
            .forEach { markSegment(it, null, it.isChecked) }
        binding.offlineTypeToggle.addOnButtonCheckedListener { group, checkedId, isChecked ->
            group.findViewById<MaterialButton>(checkedId)?.let { markSegment(it, null, isChecked) }
            if (!isChecked) return@addOnButtonCheckedListener
            val nextMode = when (checkedId) {
                R.id.offlineTypeManga -> LocalMediaType.MANGA
                R.id.offlineTypeNovel -> LocalMediaType.NOVEL
                else -> LocalMediaType.ANIME
            }
            if (nextMode != currentMode) {
                currentMode = nextMode
                updateSearchHint()
                scanLocal()
            }
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
            animeDownloadsCache = null
            mangaDownloadsCache = null
            novelDownloadsCache = null
            scanLocal()
            binding.localRefresh.isRefreshing = false
        }

        grid()
        return binding.root
    }

    private fun updateSearchHint() {
        val b = _binding ?: return
        b.animeSearchBarText.hint = getString(
            when (currentMode) {
                LocalMediaType.ANIME -> R.string.offline_search_local_anime
                LocalMediaType.MANGA -> R.string.offline_search_local_manga
                LocalMediaType.NOVEL -> R.string.offline_search_local_novel
            }
        )
    }

    private fun grid() {
        gridView.isVisible = true
        val fadeIn = AlphaAnimation(0f, 1f)
        fadeIn.duration = 250
        gridView.layoutAnimation = LayoutAnimationController(fadeIn)
        adapter = OfflineAnimeAdapter(requireContext(), downloads, this)
        if (currentQuery.isNotEmpty()) adapter.onSearchQuery(currentQuery)
        gridView.adapter = adapter
        gridView.scheduleLayoutAnimation()
        gridView.setOnItemClickListener { _, _, position, _ ->
            val item = adapter.getItem(position) as OfflineAnimeModel
            lifecycleScope.launch {
                val media = when (currentMode) {
                    LocalMediaType.ANIME -> createMediaFromLocalAnime(item)
                    LocalMediaType.MANGA -> createMediaFromLocalManga(item)
                    LocalMediaType.NOVEL -> createMediaFromLocalNovel(item)
                }
                MediaDetailsActivity.mediaSingleton = media
                ContextCompat.startActivity(
                    requireActivity(),
                    Intent(requireContext(), MediaDetailsActivity::class.java)
                        .putExtra("download", true),
                    null
                )
            }
        }
        scanLocal()
    }

    override fun onSearchQuery(query: String) {
        if (::adapter.isInitialized) {
            adapter.onSearchQuery(query)
            updateEmptyState()
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.mangaPageScrollTop.setOnClickListener {
            gridView.smoothScrollToPositionFromTop(0, 0)
        }
        binding.mangaPageScrollTop.isVisible = false
        bindScrollListener()
        initActivity(requireActivity())
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
                b.mangaPageScrollTop.translationY =
                    -(navBarHeight + (bottomBarOrNull?.height ?: 0) + (bottomBarOrNull?.marginBottom ?: 0)).toFloat()
                b.mangaPageScrollTop.isVisible = visibility
            }
        })
    }

    override fun onResume() {
        super.onResume()
        scanLocal()
    }

    override fun onPause() {
        super.onPause()
        downloads = listOf()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        scanJob.cancel()
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

    private fun formatHeading(label: String, suffix: String?): CharSequence {
        val b = _binding ?: return label
        if (suffix.isNullOrEmpty()) return label
        return SpannableString("$label  $suffix").apply {
            setSpan(
                ForegroundColorSpan(
                    MaterialColors.getColor(b.total, com.google.android.material.R.attr.colorOnSurfaceVariant)
                ),
                label.length,
                length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
    }

    private fun updateEmptyState() {
        val b = _binding ?: return
        val heading = getString(
            when (currentMode) {
                LocalMediaType.ANIME -> R.string.offline_local_anime
                LocalMediaType.MANGA -> R.string.offline_local_manga
                LocalMediaType.NOVEL -> R.string.offline_local_novel
            }
        )
        if (scanning) {
            b.total.text = formatHeading(heading, getString(R.string.offline_scanning))
            b.offlineEmptyState.isVisible = false
            gridView.isVisible = true
            return
        }

        val count = if (::adapter.isInitialized) adapter.count else 0
        b.total.text = formatHeading(heading, if (count > 0) count.toString() else null)

        val dirSet = PrefManager.getVal<String>(PrefName.LocalDir).isNotBlank()
        val isEmpty = count == 0
        b.offlineEmptyState.isVisible = isEmpty
        gridView.isVisible = !isEmpty

        if (isEmpty) {
            when {
                currentQuery.isNotBlank() && downloads.isNotEmpty() -> {
                    b.offlineEmptyIcon.setImageResource(R.drawable.ic_round_search_24)
                    b.offlineEmptyTitle.setText(R.string.offline_empty_search_title)
                    b.offlineEmptyBody.text = getString(R.string.offline_empty_search_body, currentQuery)
                    b.offlineEmptyAction.isVisible = false
                }
                !dirSet -> {
                    b.offlineEmptyIcon.setImageResource(R.drawable.ic_round_folder_24)
                    b.offlineEmptyTitle.setText(R.string.offline_local_no_folder_title)
                    b.offlineEmptyBody.setText(R.string.offline_local_no_folder_body)
                    b.offlineEmptyAction.isVisible = true
                }
                else -> {
                    val kind = when (currentMode) {
                        LocalMediaType.ANIME -> getString(R.string.anime).lowercase()
                        LocalMediaType.MANGA -> getString(R.string.manga).lowercase()
                        LocalMediaType.NOVEL -> getString(R.string.novels).lowercase()
                    }
                    b.offlineEmptyIcon.setImageResource(R.drawable.ic_round_folder_24)
                    b.offlineEmptyTitle.setText(R.string.offline_local_empty_title)
                    b.offlineEmptyBody.text = getString(R.string.offline_local_empty_body, kind)
                    b.offlineEmptyAction.isVisible = false
                }
            }
        }
    }

    private fun scanLocal() {
        when (currentMode) {
            LocalMediaType.ANIME -> scanLocalAnime()
            LocalMediaType.MANGA -> scanLocalManga()
            LocalMediaType.NOVEL -> scanLocalNovel()
        }
    }

    private fun scanLocalAnime() {
        if (animeDownloadsCache != null) {
            scanning = false
            downloads = animeDownloadsCache!!
            adapter.setItems(downloads)
            if (currentQuery.isNotEmpty()) adapter.onSearchQuery(currentQuery)
            updateEmptyState()
            return
        }

        downloads = listOf()
        adapter.setItems(downloads)
        scanning = true
        updateEmptyState()
        val appContext = context?.applicationContext ?: return
        if (scanJob.isActive) scanJob.cancel()
        scanJob = Job()
        CoroutineScope(Dispatchers.IO + scanJob).launch {
            try {
                val animesPage = try { localAnimeSource.getPopularAnime(1) } catch (e: Exception) { null }
                val localItems = animesPage?.animes?.map { sAnime ->
                    val details = localAnimeSource.getAnimeDetails(sAnime)
                    val episodes = localAnimeSource.getEpisodeList(sAnime)

                    val title = details.title
                    val totalEps = episodes.size.toString()

                    val cachedCover = PrefManager
                        .getCustomVal<String>("local_cover_$title", "")
                        .takeIf { s -> s.isNotEmpty() && s != "null" }
                    val cachedBanner = PrefManager
                        .getCustomVal<String>("local_banner_$title", "")
                        .takeIf { s -> s.isNotEmpty() && s != "null" }

                    val coverUri = if (cachedCover != null) {
                        Uri.parse(cachedCover)
                    } else {
                        details.thumbnail_url?.let { Uri.parse(it) }
                    }
                    val bannerUri = cachedBanner?.let { Uri.parse(it) }

                    OfflineAnimeModel(
                        title = title,
                        folderName = sAnime.url ?: title,
                        score = "0",
                        totalEpisode = totalEps,
                        totalEpisodeList = totalEps,
                        watchedEpisode = "~",
                        type = "local",
                        episodes = " Episodes",
                        isOngoing = false,
                        isUserScored = false,
                        image = coverUri,
                        banner = bannerUri,
                        description = details.description,
                        genres = details.genre,
                        status = when (details.status) {
                            SAnime.ONGOING -> "RELEASING"
                            SAnime.COMPLETED -> "FINISHED"
                            SAnime.CANCELLED -> "CANCELLED"
                            SAnime.ON_HIATUS -> "HIATUS"
                            SAnime.UPCOMING -> "NOT_YET_RELEASED"
                            else -> "UNKNOWN"
                        },
                        author = details.author ?: details.artist
                    )
                } ?: emptyList()

                val appDownloadsManager = ani.dantotsu.download.DownloadsManager(appContext)
                val appDownloadedAnime = appDownloadsManager.animeDownloadedTypes
                    .groupBy { it.titleName }
                    .map { (title, eps) ->
                        val totalEps = eps.size.toString()
                        val cachedCover = PrefManager
                            .getCustomVal<String>("local_cover_$title", "")
                            .takeIf { s -> s.isNotEmpty() && s != "null" }
                        val coverUri = cachedCover?.let { Uri.parse(it) }

                        OfflineAnimeModel(
                            title = title,
                            folderName = title,
                            score = "0",
                            totalEpisode = totalEps,
                            totalEpisodeList = totalEps,
                            watchedEpisode = "~",
                            type = "downloaded",
                            episodes = " Episodes",
                            isOngoing = false,
                            isUserScored = false,
                            image = coverUri,
                            banner = null
                        )
                    }

                val merged = (appDownloadedAnime + localItems).distinctBy { it.title.trim().lowercase() }
                downloads = merged
                animeDownloadsCache = merged
                withContext(Dispatchers.Main) {
                    if (!isAdded || _binding == null) return@withContext
                    scanning = false
                    adapter.setItems(downloads)
                    if (currentQuery.isNotEmpty()) adapter.onSearchQuery(currentQuery)
                    updateEmptyState()
                }
            } catch (e: Exception) {
                Logger.log("Error scanning local anime: ${e.message}")
                Logger.log(e)
                withContext(Dispatchers.Main) {
                    if (!isAdded || _binding == null) return@withContext
                    scanning = false
                    updateEmptyState()
                }
            }
        }
    }

    private fun scanLocalManga() {
        if (mangaDownloadsCache != null) {
            scanning = false
            downloads = mangaDownloadsCache!!
            adapter.setItems(downloads)
            if (currentQuery.isNotEmpty()) adapter.onSearchQuery(currentQuery)
            updateEmptyState()
            return
        }

        downloads = listOf()
        adapter.setItems(downloads)
        scanning = true
        updateEmptyState()
        val appContext = context?.applicationContext ?: return
        if (scanJob.isActive) scanJob.cancel()
        scanJob = Job()
        CoroutineScope(Dispatchers.IO + scanJob).launch {
            try {
                val mangasPage = try { localMangaSource.getPopularManga(1) } catch (e: Exception) { null }
                val localItems = mangasPage?.mangas?.map { sManga ->
                    val details = localMangaSource.getMangaDetails(sManga)
                    val chapters = localMangaSource.getChapterList(sManga)

                    val title = details.title
                    val totalChaps = chapters.size.toString()

                    val cachedCover = PrefManager
                        .getCustomVal<String>("local_cover_$title", "")
                        .takeIf { s -> s.isNotEmpty() && s != "null" }
                    val cachedBanner = PrefManager
                        .getCustomVal<String>("local_banner_$title", "")
                        .takeIf { s -> s.isNotEmpty() && s != "null" }

                    val coverUri = if (cachedCover != null) {
                        Uri.parse(cachedCover)
                    } else {
                        details.thumbnail_url?.let { Uri.parse(it) }
                    }
                    val bannerUri = cachedBanner?.let { Uri.parse(it) }

                    OfflineAnimeModel(
                        title = title,
                        folderName = sManga.url ?: title,
                        score = "0",
                        totalEpisode = totalChaps,
                        totalEpisodeList = totalChaps,
                        watchedEpisode = "~",
                        type = "local",
                        episodes = " Chapters",
                        isOngoing = false,
                        isUserScored = false,
                        image = coverUri,
                        banner = bannerUri,
                        description = details.description,
                        genres = details.genre,
                        status = when (details.status) {
                            SManga.ONGOING -> "RELEASING"
                            SManga.COMPLETED -> "FINISHED"
                            SManga.CANCELLED -> "CANCELLED"
                            SManga.ON_HIATUS -> "HIATUS"
                            else -> "UNKNOWN"
                        },
                        author = details.author ?: details.artist
                    )
                } ?: emptyList()

                val appDownloadsManager = ani.dantotsu.download.DownloadsManager(appContext)
                val appDownloadedManga = appDownloadsManager.mangaDownloadedTypes
                    .groupBy { it.titleName }
                    .map { (title, chaps) ->
                        val totalChaps = chaps.size.toString()
                        val cachedCover = PrefManager
                            .getCustomVal<String>("local_cover_$title", "")
                            .takeIf { s -> s.isNotEmpty() && s != "null" }
                        val coverUri = cachedCover?.let { Uri.parse(it) }

                        OfflineAnimeModel(
                            title = title,
                            folderName = title,
                            score = "0",
                            totalEpisode = totalChaps,
                            totalEpisodeList = totalChaps,
                            watchedEpisode = "~",
                            type = "downloaded",
                            episodes = " Chapters",
                            isOngoing = false,
                            isUserScored = false,
                            image = coverUri,
                            banner = null
                        )
                    }

                val merged = (appDownloadedManga + localItems).distinctBy { it.title.trim().lowercase() }
                downloads = merged
                mangaDownloadsCache = merged
                withContext(Dispatchers.Main) {
                    if (!isAdded || _binding == null) return@withContext
                    scanning = false
                    adapter.setItems(downloads)
                    if (currentQuery.isNotEmpty()) adapter.onSearchQuery(currentQuery)
                    updateEmptyState()
                }
            } catch (e: Exception) {
                Logger.log("Error scanning local manga: ${e.message}")
                Logger.log(e)
                withContext(Dispatchers.Main) {
                    if (!isAdded || _binding == null) return@withContext
                    scanning = false
                    updateEmptyState()
                }
            }
        }
    }

    private fun createMediaFromLocalAnime(item: OfflineAnimeModel): Media {
        val localSourceIndex = AnimeSources.list.indexOfFirst { it.name == "Local" }
            .takeIf { it >= 0 } ?: 0

        return Media(
            id = 0,
            name = item.title,
            nameRomaji = item.title,
            userPreferredName = item.title,
            isAdult = false,
            description = item.description,
            status = item.status ?: "UNKNOWN",
            genres = item.genres?.split(",")?.map { it.trim() } as? ArrayList<String> ?: arrayListOf(),
            anime = ani.dantotsu.media.anime.Anime(
                totalEpisodes = item.totalEpisode.toIntOrNull()
            ),
        ).also {
            it.format = "LOCAL"
            it.folderName = item.folderName
            it.cover = PrefManager.getCustomVal<String>("local_cover_${item.title}", "")
                .takeIf { s -> s.isNotEmpty() && s != "null" } ?: item.image?.toString()
            it.banner = PrefManager.getCustomVal<String>("local_banner_${item.title}", "")
                .takeIf { s -> s.isNotEmpty() && s != "null" } ?: item.banner?.toString()
            it.selected = Selected(
                sourceIndex = localSourceIndex,
                server = "Local"
            )
        }
    }

    private fun createMediaFromLocalManga(item: OfflineAnimeModel): Media {
        val localSourceIndex = MangaSources.list.indexOfFirst { it.name == "Local" }
            .takeIf { it >= 0 } ?: 0

        return Media(
            id = 0,
            name = item.title,
            nameRomaji = item.title,
            userPreferredName = item.title,
            isAdult = false,
            description = item.description,
            status = item.status ?: "UNKNOWN",
            genres = item.genres?.split(",")?.map { it.trim() } as? ArrayList<String> ?: arrayListOf(),
            manga = ani.dantotsu.media.manga.Manga(
                totalChapters = item.totalEpisode.toIntOrNull()
            ),
        ).also {
            it.format = "LOCAL"
            it.folderName = item.folderName
            it.cover = PrefManager.getCustomVal<String>("local_cover_${item.title}", "")
                .takeIf { s -> s.isNotEmpty() && s != "null" } ?: item.image?.toString()
            it.banner = PrefManager.getCustomVal<String>("local_banner_${item.title}", "")
                .takeIf { s -> s.isNotEmpty() && s != "null" } ?: item.banner?.toString()
            it.selected = Selected(
                sourceIndex = localSourceIndex,
                server = "Local"
            )
        }
    }

    private fun scanLocalNovel() {
        if (novelDownloadsCache != null) {
            scanning = false
            downloads = novelDownloadsCache!!
            adapter.setItems(downloads)
            if (currentQuery.isNotEmpty()) adapter.onSearchQuery(currentQuery)
            updateEmptyState()
            return
        }

        downloads = listOf()
        adapter.setItems(downloads)
        scanning = true
        updateEmptyState()
        if (scanJob.isActive) scanJob.cancel()
        scanJob = Job()
        CoroutineScope(Dispatchers.IO + scanJob).launch {
            try {
                val mangasPage = localNovelSource.getPopularManga(1)
                val newDownloads = mangasPage.mangas.map { sManga ->
                    val details = localNovelSource.getMangaDetails(sManga)
                    val chapters = localNovelSource.getChapterList(sManga)

                    val title = details.title
                    val totalChaps = chapters.size.toString()

                    val cachedCover = PrefManager
                        .getCustomVal<String>("local_cover_$title", "")
                        .takeIf { s -> s.isNotEmpty() && s != "null" }
                    val cachedBanner = PrefManager
                        .getCustomVal<String>("local_banner_$title", "")
                        .takeIf { s -> s.isNotEmpty() && s != "null" }

                    val coverUri = if (cachedCover != null) {
                        Uri.parse(cachedCover)
                    } else {
                        details.thumbnail_url?.let { Uri.parse(it) }
                    }
                    val bannerUri = cachedBanner?.let { Uri.parse(it) }

                    OfflineAnimeModel(
                        title = title,
                        folderName = sManga.url ?: title,
                        score = "0",
                        totalEpisode = totalChaps,
                        totalEpisodeList = totalChaps,
                        watchedEpisode = "~",
                        type = "local",
                        episodes = " Volumes",
                        isOngoing = false,
                        isUserScored = false,
                        image = coverUri,
                        banner = bannerUri,
                        description = details.description,
                        genres = details.genre,
                        status = when (details.status) {
                            SManga.ONGOING -> "RELEASING"
                            SManga.COMPLETED -> "FINISHED"
                            SManga.CANCELLED -> "CANCELLED"
                            SManga.ON_HIATUS -> "HIATUS"
                            else -> "UNKNOWN"
                        },
                        author = details.author ?: details.artist
                    )
                }
                downloads = newDownloads
                novelDownloadsCache = newDownloads
                withContext(Dispatchers.Main) {
                    if (!isAdded || _binding == null) return@withContext
                    scanning = false
                    adapter.setItems(downloads)
                    if (currentQuery.isNotEmpty()) adapter.onSearchQuery(currentQuery)
                    updateEmptyState()
                }
            } catch (e: Exception) {
                Logger.log("Error scanning local novel: ${e.message}")
                Logger.log(e)
                withContext(Dispatchers.Main) {
                    if (!isAdded || _binding == null) return@withContext
                    scanning = false
                    updateEmptyState()
                }
            }
        }
    }

    private fun createMediaFromLocalNovel(item: OfflineAnimeModel): Media {
        val localSourceIndex = ani.dantotsu.parsers.NovelSources.list.indexOfFirst { it.name == "Local" }
            .takeIf { it >= 0 } ?: 0

        return Media(
            id = 0,
            name = item.title,
            nameRomaji = item.title,
            userPreferredName = item.title,
            isAdult = false,
            description = item.description,
            status = item.status ?: "UNKNOWN",
            genres = item.genres?.split(",")?.map { it.trim() } as? ArrayList<String> ?: arrayListOf(),
            anime = null,
            manga = ani.dantotsu.media.manga.Manga(
                totalChapters = item.totalEpisode.toIntOrNull()
            )
        ).also {
            it.format = "LOCAL_NOVEL"
            it.folderName = item.folderName
            it.cover = PrefManager.getCustomVal<String>("local_cover_${item.title}", "")
                .takeIf { s -> s.isNotEmpty() && s != "null" } ?: item.image?.toString()
            it.banner = PrefManager.getCustomVal<String>("local_banner_${item.title}", "")
                .takeIf { s -> s.isNotEmpty() && s != "null" } ?: item.banner?.toString()
            it.selected = Selected(
                sourceIndex = localSourceIndex,
                server = "Local"
            )
        }
    }

    companion object {
        var animeDownloadsCache: List<OfflineAnimeModel>? = null
        var mangaDownloadsCache: List<OfflineAnimeModel>? = null
        var novelDownloadsCache: List<OfflineAnimeModel>? = null
    }
}
