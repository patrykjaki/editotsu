package ani.dantotsu.media

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.os.Parcelable
import android.view.View
import android.view.WindowManager
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.core.view.updatePaddingRelative
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ani.dantotsu.R
import ani.dantotsu.connections.anilist.AniMangaSearchResults
import ani.dantotsu.connections.anilist.Anilist
import ani.dantotsu.connections.anilist.AnilistSearch
import ani.dantotsu.connections.anilist.AnilistSearch.SearchType
import ani.dantotsu.connections.anilist.AnilistSearch.SearchType.Companion.toAnilistString
import ani.dantotsu.connections.anilist.CharacterSearchResults
import ani.dantotsu.connections.anilist.MixedSearchResults
import ani.dantotsu.connections.anilist.StaffSearchResults
import ani.dantotsu.connections.anilist.StudioSearchResults
import ani.dantotsu.connections.anilist.UserSearchResults
import ani.dantotsu.databinding.ActivitySearchBinding
import ani.dantotsu.initActivity
import ani.dantotsu.navBarHeight
import ani.dantotsu.profile.UsersAdapter
import ani.dantotsu.px
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.statusBarHeight
import ani.dantotsu.themes.ThemeManager
import ani.dantotsu.themes.followThemeStatusBarIcons
import com.google.android.material.color.MaterialColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class SearchActivity : AppCompatActivity() {
    internal lateinit var binding: ActivitySearchBinding
    private val scope = lifecycleScope
    val model: AnilistSearch by viewModels()
    private val genresModel: ani.dantotsu.connections.anilist.GenresViewModel by viewModels()

    var style: Int = 0
    lateinit var searchType: SearchType
    private var screenWidth: Float = 0f

    private lateinit var mixedAdaptor: MixedSearchAdapter
    private lateinit var mediaAdaptor: MediaAdaptor
    private lateinit var characterAdaptor: CharacterAdapter
    private lateinit var studioAdaptor: StudioAdapter
    private lateinit var staffAdaptor: AuthorAdapter
    private lateinit var usersAdapter: UsersAdapter

    private lateinit var progressAdapter: ProgressAdapter
    private lateinit var concatAdapter: ConcatAdapter
    private lateinit var headerAdaptor: HeaderInterface
    private var resultsHeaderAdapter: RecyclerView.Adapter<*>? = null

    lateinit var mixedResult: MixedSearchResults
    lateinit var aniMangaResult: AniMangaSearchResults
    lateinit var characterResult: CharacterSearchResults
    lateinit var studioResult: StudioSearchResults
    lateinit var staffResult: StaffSearchResults
    lateinit var userResult: UserSearchResults

    lateinit var updateChips: (() -> Unit)

    private enum class UiState { IDLE, LOADING, RESULTS, EMPTY, ERROR }

    private var uiState: UiState = UiState.IDLE
    private var topBarScrolled: Boolean = false
    private var searchInFlight: Boolean = false
    private var loadingNextPage: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        ThemeManager(this).applyTheme()
        binding = ActivitySearchBinding.inflate(layoutInflater)
        setContentView(binding.root)
        followThemeStatusBarIcons()
        initActivity(this)
        screenWidth = resources.displayMetrics.run { widthPixels / density }

        binding.searchTopBar.updatePaddingRelative(top = statusBarHeight)
        binding.searchRecyclerView.updatePaddingRelative(bottom = navBarHeight + 80f.px)
        binding.searchIdleScroll.updatePaddingRelative(bottom = navBarHeight + 32f.px)

        val notSet = model.notSet
        searchType = SearchType.fromString(intent.getStringExtra("type") ?: "ANIME")
        when (searchType) {
            SearchType.ALL -> {
                if (model.notSet) {
                    model.notSet = false
                    model.mixedSearchResults = MixedSearchResults(
                        search = intent.getStringExtra("query"),
                    )
                }
                mixedResult = model.mixedSearchResults
                mixedAdaptor = MixedSearchAdapter(this) { targetType ->
                    val q = headerAdaptor.currentQuery()
                    switchToType(
                        nextType = targetType,
                        query = q.takeIf { it.isNotBlank() },
                        autoSearch = q.isNotBlank(),
                    )
                }
                mixedAdaptor.submit(model.mixedSearchResults)
                binding.searchRecyclerView.updatePaddingRelative(start = 0, end = 0)
            }

            SearchType.ANIME, SearchType.MANGA -> {
                style = PrefManager.getVal(PrefName.SearchStyle)
                var listOnly: Boolean? = intent.getBooleanExtra("listOnly", false)
                if (!listOnly!!) listOnly = null

                if (model.notSet) {
                    model.notSet = false
                    model.aniMangaSearchResults = AniMangaSearchResults(
                        intent.getStringExtra("type") ?: "ANIME",
                        isAdult = if (Anilist.adult) intent.getBooleanExtra(
                            "hentai",
                            false
                        ) else false,
                        onList = listOnly,
                        search = intent.getStringExtra("query"),
                        genres = intent.getStringExtra("genre")?.let { mutableListOf(it) },
                        tags = intent.getStringExtra("tag")?.let { mutableListOf(it) },
                        sort = intent.getStringExtra("sortBy"),
                        status = intent.getStringExtra("status"),
                        source = intent.getStringExtra("source"),
                        countryOfOrigin = intent.getStringExtra("country"),
                        season = intent.getStringExtra("season"),
                        seasonYear = if (intent.getStringExtra("type") == "ANIME") intent.getStringExtra(
                            "seasonYear"
                        )
                            ?.toIntOrNull() else null,
                        startYear = if (intent.getStringExtra("type") == "MANGA") intent.getStringExtra(
                            "seasonYear"
                        )
                            ?.toIntOrNull() else null,
                        results = mutableListOf(),
                        hasNextPage = false
                    )
                }

                aniMangaResult = model.aniMangaSearchResults
                mediaAdaptor =
                    MediaAdaptor(
                        style,
                        model.aniMangaSearchResults.results,
                        this,
                        matchParent = true
                    )
            }

            SearchType.CHARACTER -> {
                if (model.notSet) {
                    model.notSet = false
                    model.characterSearchResults = CharacterSearchResults(
                        search = intent.getStringExtra("query"),
                        results = mutableListOf(),
                        hasNextPage = false
                    )
                }
                characterResult = model.characterSearchResults
                characterAdaptor = CharacterAdapter(model.characterSearchResults.results)
            }

            SearchType.STUDIO -> {
                if (model.notSet) {
                    model.notSet = false
                    model.studioSearchResults = StudioSearchResults(
                        search = intent.getStringExtra("query"),
                        results = mutableListOf(),
                        hasNextPage = false
                    )
                }
                studioResult = model.studioSearchResults
                studioAdaptor = StudioAdapter(model.studioSearchResults.results)
            }

            SearchType.STAFF -> {
                if (model.notSet) {
                    model.notSet = false
                    model.staffSearchResults = StaffSearchResults(
                        search = intent.getStringExtra("query"),
                        results = mutableListOf(),
                        hasNextPage = false
                    )
                }
                staffResult = model.staffSearchResults
                staffAdaptor = AuthorAdapter(model.staffSearchResults.results)
            }

            SearchType.USER -> {
                if (model.notSet) {
                    model.notSet = false
                    model.userSearchResults = UserSearchResults(
                        search = intent.getStringExtra("query"),
                        results = mutableListOf(),
                        hasNextPage = false
                    )
                }
                userResult = model.userSearchResults
                usersAdapter = UsersAdapter(model.userSearchResults.results, grid = true)
            }
        }

        progressAdapter = ProgressAdapter(searched = model.searched)
        headerAdaptor = if (searchType == SearchType.ANIME || searchType == SearchType.MANGA) {
            SearchAdapter(this, searchType)
        } else {
            SupportingSearchAdapter(this, searchType)
        }
        headerAdaptor.bind()
        resultsHeaderAdapter = headerAdaptor.resultsHeaderAdapter()

        val gridSize = (screenWidth / 120f).toInt().coerceAtLeast(2)
        val gridLayoutManager = GridLayoutManager(this, gridSize)
        gridLayoutManager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int {
                val hasHeader = resultsHeaderAdapter != null
                return when {
                    searchType == SearchType.ALL -> gridSize
                    hasHeader && position == 0 -> gridSize
                    position == concatAdapter.itemCount - 1 -> gridSize
                    else -> when (style) {
                        0 -> 1
                        else -> gridSize
                    }
                }
            }
        }

        val contentAdapter: RecyclerView.Adapter<*> = when (searchType) {
            SearchType.ALL -> mixedAdaptor
            SearchType.ANIME, SearchType.MANGA -> mediaAdaptor
            SearchType.CHARACTER -> characterAdaptor
            SearchType.STUDIO -> studioAdaptor
            SearchType.STAFF -> staffAdaptor
            SearchType.USER -> usersAdapter
        }
        concatAdapter = resultsHeaderAdapter?.let {
            ConcatAdapter(it, contentAdapter, progressAdapter)
        } ?: ConcatAdapter(contentAdapter, progressAdapter)

        binding.searchRecyclerView.layoutManager = gridLayoutManager
        binding.searchRecyclerView.adapter = concatAdapter
        syncSkeletonMode()

        binding.searchIdleScroll.setOnScrollChangeListener(
            androidx.core.widget.NestedScrollView.OnScrollChangeListener { _, _, scrollY, _, _ ->
                if (uiState == UiState.IDLE) {
                    updateTopBarSurface(scrollY > 0)
                    if (scrollY > 16 && binding.searchBarText.hasFocus()) {
                        headerAdaptor.hideKeyboard(this)
                    }
                }
            }
        )

        binding.searchRecyclerView.addOnScrollListener(object :
            RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING && binding.searchBarText.hasFocus()) {
                    headerAdaptor.hideKeyboard(this@SearchActivity)
                }
            }

            override fun onScrolled(v: RecyclerView, dx: Int, dy: Int) {
                if (uiState == UiState.RESULTS) {
                    updateTopBarSurface(v.canScrollVertically(-1))
                }
                if (!v.canScrollVertically(1)) {
                    if (model.hasNextPage(searchType) && model.resultsIsNotEmpty(searchType) && !loading) {
                        loading = true
                        loadingNextPage = true
                        progressAdapter.bar?.isVisible = true
                        scope.launch(Dispatchers.IO) {
                            try {
                                model.loadNextPage(searchType)
                            } finally {
                                loading = false
                            }
                        }
                    }
                }
                super.onScrolled(v, dx, dy)
            }
        })

        when (searchType) {
            SearchType.ALL -> {
                model.getSearch<MixedSearchResults>(searchType).observe(this) {
                    if (it == null) {
                        handleNullEmission()
                        return@observe
                    }
                    if (uiState == UiState.IDLE) return@observe
                    model.mixedSearchResults.apply {
                        search = it.search
                        clear()
                        anime.addAll(it.anime)
                        manga.addAll(it.manga)
                        characters.addAll(it.characters)
                        staff.addAll(it.staff)
                        studios.addAll(it.studios)
                        users.addAll(it.users)
                    }
                    mixedAdaptor.submit(model.mixedSearchResults)
                    finishEmission(model.mixedSearchResults.totalCount(), hasNextPage = false)
                }
            }

            SearchType.ANIME, SearchType.MANGA -> {
                model.getSearch<AniMangaSearchResults>(searchType).observe(this) {
                    if (it == null) {
                        handleNullEmission()
                        return@observe
                    }
                    if (uiState == UiState.IDLE) return@observe
                    model.aniMangaSearchResults.apply {
                        onList = it.onList
                        isAdult = it.isAdult
                        perPage = it.perPage
                        search = it.search
                        sort = it.sort
                        genres = it.genres
                        excludedGenres = it.excludedGenres
                        excludedTags = it.excludedTags
                        tags = it.tags
                        season = it.season
                        startYear = it.startYear
                        seasonYear = it.seasonYear
                        status = it.status
                        source = it.source
                        format = it.format
                        countryOfOrigin = it.countryOfOrigin
                        page = it.page
                        hasNextPage = it.hasNextPage
                    }

                    val prev = model.aniMangaSearchResults.results.size
                    val newResults = it.results.distinctBy { item -> item.id }
                        .filter { newItem -> model.aniMangaSearchResults.results.none { oldItem -> oldItem.id == newItem.id } }
                    if (newResults.isNotEmpty()) {
                        model.aniMangaSearchResults.results.addAll(newResults)
                        mediaAdaptor.notifyItemRangeInserted(prev, newResults.size)
                    }
                    finishEmission(model.aniMangaSearchResults.results.size, it.hasNextPage)
                }
            }

            SearchType.CHARACTER -> {
                model.getSearch<CharacterSearchResults>(searchType).observe(this) {
                    if (it == null) {
                        handleNullEmission()
                        return@observe
                    }
                    if (uiState == UiState.IDLE) return@observe
                    model.characterSearchResults.apply {
                        search = it.search
                        page = it.page
                        hasNextPage = it.hasNextPage
                    }

                    val prev = model.characterSearchResults.results.size
                    val newResults = it.results.distinctBy { item -> item.id }
                        .filter { newItem -> model.characterSearchResults.results.none { oldItem -> oldItem.id == newItem.id } }
                    if (newResults.isNotEmpty()) {
                        model.characterSearchResults.results.addAll(newResults)
                        characterAdaptor.notifyItemRangeInserted(prev, newResults.size)
                    }
                    finishEmission(model.characterSearchResults.results.size, it.hasNextPage)
                }
            }

            SearchType.STUDIO -> {
                model.getSearch<StudioSearchResults>(searchType).observe(this) {
                    if (it == null) {
                        handleNullEmission()
                        return@observe
                    }
                    if (uiState == UiState.IDLE) return@observe
                    model.studioSearchResults.apply {
                        search = it.search
                        page = it.page
                        hasNextPage = it.hasNextPage
                    }

                    val prev = model.studioSearchResults.results.size
                    val newResults = it.results.distinctBy { item -> item.id }
                        .filter { newItem -> model.studioSearchResults.results.none { oldItem -> oldItem.id == newItem.id } }
                    if (newResults.isNotEmpty()) {
                        model.studioSearchResults.results.addAll(newResults)
                        studioAdaptor.notifyItemRangeInserted(prev, newResults.size)
                    }
                    finishEmission(model.studioSearchResults.results.size, it.hasNextPage)
                }
            }

            SearchType.STAFF -> {
                model.getSearch<StaffSearchResults>(searchType).observe(this) {
                    if (it == null) {
                        handleNullEmission()
                        return@observe
                    }
                    if (uiState == UiState.IDLE) return@observe
                    model.staffSearchResults.apply {
                        search = it.search
                        page = it.page
                        hasNextPage = it.hasNextPage
                    }

                    val prev = model.staffSearchResults.results.size
                    val newResults = it.results.distinctBy { item -> item.id }
                        .filter { newItem -> model.staffSearchResults.results.none { oldItem -> oldItem.id == newItem.id } }
                    if (newResults.isNotEmpty()) {
                        model.staffSearchResults.results.addAll(newResults)
                        staffAdaptor.notifyItemRangeInserted(prev, newResults.size)
                    }
                    finishEmission(model.staffSearchResults.results.size, it.hasNextPage)
                }
            }

            SearchType.USER -> {
                model.getSearch<UserSearchResults>(searchType).observe(this) {
                    if (it == null) {
                        handleNullEmission()
                        return@observe
                    }
                    if (uiState == UiState.IDLE) return@observe
                    model.userSearchResults.apply {
                        search = it.search
                        page = it.page
                        hasNextPage = it.hasNextPage
                    }

                    val prev = model.userSearchResults.results.size
                    val newResults = it.results.distinctBy { item -> item.id }
                        .filter { newItem -> model.userSearchResults.results.none { oldItem -> oldItem.id == newItem.id } }
                    if (newResults.isNotEmpty()) {
                        model.userSearchResults.results.addAll(newResults)
                        usersAdapter.notifyItemRangeInserted(prev, newResults.size)
                    }
                    finishEmission(model.userSearchResults.results.size, it.hasNextPage)
                }
            }
        }

        runInitialSearchActions(notSet)
    }

    private fun handleNullEmission() {
        when {
            searchInFlight -> {
                searchInFlight = false
                renderState(UiState.ERROR)
            }

            loadingNextPage -> {
                loadingNextPage = false
                progressAdapter.bar?.isVisible = false
            }
        }
    }

    private fun finishEmission(totalCount: Int, hasNextPage: Boolean) {
        searchInFlight = false
        loadingNextPage = false
        progressAdapter.bar?.isVisible = hasNextPage
        renderState(if (totalCount == 0) UiState.EMPTY else UiState.RESULTS)
    }

    private fun updateTopBarSurface(scrolled: Boolean) {
        if (scrolled == topBarScrolled) return
        topBarScrolled = scrolled
        val attr = if (scrolled) {
            com.google.android.material.R.attr.colorSurfaceContainer
        } else {
            com.google.android.material.R.attr.colorSurface
        }
        binding.searchTopBar.setBackgroundColor(MaterialColors.getColor(binding.searchTopBar, attr))
        binding.searchTopDivider.animate().alpha(if (scrolled) 1f else 0f).setDuration(150).start()
    }

    private fun syncSkeletonMode() {
        val isMediaList = searchType == SearchType.ALL ||
            (style == 1 && (searchType == SearchType.ANIME || searchType == SearchType.MANGA))
        binding.searchLoading.setMode(if (isMediaList) "media_list" else "grid")
    }

    private fun renderState(state: UiState) {
        uiState = state
        binding.searchIdleScroll.isVisible = state == UiState.IDLE
        binding.searchLoading.isVisible = state == UiState.LOADING
        binding.searchRecyclerView.isVisible = state == UiState.RESULTS
        binding.searchEmptyState.isVisible = state == UiState.EMPTY || state == UiState.ERROR

        when (state) {
            UiState.IDLE -> {
                headerAdaptor.updateIdleSections()
                updateTopBarSurface(binding.searchIdleScroll.scrollY > 0)
            }

            UiState.RESULTS -> {
                updateTopBarSurface(binding.searchRecyclerView.canScrollVertically(-1))
            }

            UiState.LOADING -> {
                syncSkeletonMode()
                updateTopBarSurface(false)
            }

            UiState.EMPTY -> {
                updateTopBarSurface(false)
                binding.searchEmptyTitle.setText(R.string.search_empty_title)
                if (headerAdaptor.hasActiveFilters()) {
                    binding.searchEmptyMessage.setText(R.string.search_empty_filtered)
                    binding.searchEmptyAction.isVisible = true
                    binding.searchEmptyAction.setText(R.string.reset_filters)
                    binding.searchEmptyAction.setOnClickListener { headerAdaptor.resetFilters() }
                } else {
                    binding.searchEmptyMessage.setText(R.string.search_empty_query)
                    binding.searchEmptyAction.isVisible = false
                }
            }

            UiState.ERROR -> {
                updateTopBarSurface(false)
                binding.searchEmptyTitle.setText(R.string.search_empty_title)
                binding.searchEmptyMessage.setText(R.string.search_failed)
                binding.searchEmptyAction.isVisible = true
                binding.searchEmptyAction.setText(R.string.picker_retry)
                binding.searchEmptyAction.setOnClickListener { search(debounceMs = 0L) }
            }
        }
    }

    /**
     * Genre tiles for the idle screen. Returns what's already loaded; [onEach] gets the rest as they arrive.
     * Thumbnails are cached for days, so after the first visit this is instant.
     */
    fun loadGenres(onEach: (Pair<String, String>) -> Unit): Map<String, String>? {
        genresModel.genres?.let { return it }
        lifecycleScope.launch(Dispatchers.IO) {
            val names = Anilist.genres ?: PrefManager.getVal<Set<String>>(PrefName.GenresList).sorted().let { ArrayList(it) }
            genresModel.loadGenres(names) { pair -> runOnUiThread { onEach(pair) } }
        }
        return null
    }

    fun showIdle() {
        emptyMediaAdapter()
        renderState(UiState.IDLE)
    }

    fun emptyMediaAdapter() {
        searchJob?.cancel()
        loading = false
        searchInFlight = false
        loadingNextPage = false
        clearCurrentResults()
        progressAdapter.bar?.visibility = View.GONE
    }

    private fun clearCurrentResults() {
        when (searchType) {
            SearchType.ALL -> {
                model.mixedSearchResults.clear()
                mixedAdaptor.submit(null)
            }

            SearchType.ANIME, SearchType.MANGA -> {
                val count = model.aniMangaSearchResults.results.size
                model.aniMangaSearchResults.results.clear()
                model.aniMangaSearchResults.page = 1
                model.aniMangaSearchResults.hasNextPage = false
                if (count > 0) mediaAdaptor.notifyItemRangeRemoved(0, count)
            }

            SearchType.CHARACTER -> {
                val count = model.characterSearchResults.results.size
                model.characterSearchResults.results.clear()
                model.characterSearchResults.page = 1
                model.characterSearchResults.hasNextPage = false
                if (count > 0) characterAdaptor.notifyItemRangeRemoved(0, count)
            }

            SearchType.STUDIO -> {
                val count = model.studioSearchResults.results.size
                model.studioSearchResults.results.clear()
                model.studioSearchResults.page = 1
                model.studioSearchResults.hasNextPage = false
                if (count > 0) studioAdaptor.notifyItemRangeRemoved(0, count)
            }

            SearchType.STAFF -> {
                val count = model.staffSearchResults.results.size
                model.staffSearchResults.results.clear()
                model.staffSearchResults.page = 1
                model.staffSearchResults.hasNextPage = false
                if (count > 0) staffAdaptor.notifyItemRangeRemoved(0, count)
            }

            SearchType.USER -> {
                val count = model.userSearchResults.results.size
                model.userSearchResults.results.clear()
                model.userSearchResults.page = 1
                model.userSearchResults.hasNextPage = false
                if (count > 0) usersAdapter.notifyItemRangeRemoved(0, count)
            }
        }
    }

    private var searchJob: Job? = null
    @Volatile
    private var loading = false

    fun search(debounceMs: Long = 400L) {
        searchJob?.cancel()
        clearCurrentResults()
        binding.searchRecyclerView.scrollToPosition(0)
        progressAdapter.bar?.visibility = View.GONE
        loadingNextPage = false
        searchInFlight = true
        model.searched = true
        renderState(UiState.LOADING)

        searchJob = scope.launch(Dispatchers.IO) {
            if (debounceMs > 0L) delay(debounceMs)
            try {
                loading = true
                model.loadSearch(searchType)
            } finally {
                loading = false
            }
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    fun recycler() {
        if (searchType == SearchType.ANIME || searchType == SearchType.MANGA) {
            mediaAdaptor.type = style
            mediaAdaptor.notifyDataSetChanged()
            binding.searchRecyclerView.requestLayout()
            syncSkeletonMode()
        }
    }

    private fun runInitialSearchActions(notSet: Boolean) {
        if (isFinishing || isDestroyed) return

        if (!notSet) {
            when {
                model.resultsIsNotEmpty(searchType) -> {
                    progressAdapter.bar?.isVisible = model.hasNextPage(searchType)
                    renderState(UiState.RESULTS)
                }

                headerAdaptor.currentQuery().isNotBlank() || headerAdaptor.hasActiveFilters() -> {
                    if (!model.searched) {
                        model.searched = true
                        headerAdaptor.search?.invoke(0L)
                    } else {
                        renderState(UiState.EMPTY)
                    }
                }

                else -> renderState(UiState.IDLE)
            }
            return
        }

        if (intent.getBooleanExtra("search", false)) {
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_UNCHANGED)
            search(debounceMs = 0L)
        } else {
            renderState(UiState.IDLE)
            binding.searchBarText.post { headerAdaptor.requestFocus?.run() }
        }
    }

    /** Switches to another search type (e.g. from the Type chip, mixed "See all", or a genre tile in All). */
    fun switchToType(
        nextType: SearchType,
        query: String? = null,
        genre: String? = null,
        sortBy: String? = null,
        autoSearch: Boolean = true,
    ) {
        val intent = Intent(this, SearchActivity::class.java)
            .putExtra("type", nextType.toAnilistString())
            .apply {
                if (!query.isNullOrBlank()) putExtra("query", query)
                if (!genre.isNullOrBlank()) putExtra("genre", genre)
                if (!sortBy.isNullOrBlank()) putExtra("sortBy", sortBy)
                if (autoSearch) putExtra("search", true)
            }
        startActivity(intent)
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
        finish()
    }

    var state: Parcelable? = null
    override fun onPause() {
        if (this::headerAdaptor.isInitialized) {
            headerAdaptor.addHistory()
        }
        super.onPause()
        state = binding.searchRecyclerView.layoutManager?.onSaveInstanceState()
    }

    override fun onResume() {
        super.onResume()
        binding.searchRecyclerView.layoutManager?.onRestoreInstanceState(state)
    }
}
