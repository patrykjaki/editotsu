package ani.dantotsu.media

import android.content.Intent
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.core.view.isVisible
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ani.dantotsu.R
import ani.dantotsu.connections.anilist.Anilist
import ani.dantotsu.connections.anilist.AnilistSearch.SearchType
import ani.dantotsu.databinding.ItemSearchHeaderBinding
import ani.dantotsu.openLinkInBrowser
import ani.dantotsu.others.imagesearch.ImageSearchActivity
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName

/** Controller for anime and manga search: pinned bar, filter chips, reset button, recents + genre tiles, and results header. */
class SearchAdapter(private val activity: SearchActivity, private val type: SearchType) :
    HeaderInterface(activity.binding) {

    private var chips: SearchFilterChips? = null
    private val resultsHeader = ResultsHeaderAdapter(activity)

    override fun resultsHeaderAdapter(): RecyclerView.Adapter<*> = resultsHeader

    override fun hasActiveFilters(): Boolean =
        SearchStatePolicy.hasActiveFilters(activity.aniMangaResult)

    override fun resetFilters() {
        SearchStatePolicy.clearFilters(activity.aniMangaResult)
        applyFiltersChanged()
    }

    private fun refreshChips() {
        chips?.build()
    }

    private fun searchTitle(debounceMs: Long) {
        val raw = binding.searchBarText.text?.toString().orEmpty()
        activity.aniMangaResult.search = raw.takeIf { it.isNotEmpty() }
        if (raw.equals("hentai", true)) {
            openLinkInBrowser("https://www.youtube.com/watch?v=GgJrEOo0QoA")
        }
        activity.search(debounceMs)
    }

    private fun applyFiltersChanged() {
        refreshChips()
        if (binding.searchBarText.text.toString().isBlank() && !hasActiveFilters()) {
            activity.aniMangaResult.search = null
            activity.showIdle()
        } else {
            searchTitle(debounceMs = 0L)
        }
    }

    /** The idle screen's artwork tiles: one per genre, banner from a trending show in it. */
    private fun bindGenres(onPick: (String) -> Unit) {
        val adapter = GenreAdapter(activity.aniMangaResult.type, big = true, onPick = onPick)
        val width = activity.resources.displayMetrics.run { widthPixels / density }
        binding.searchGenreList.layoutManager =
            GridLayoutManager(activity, (width / 180f).toInt().coerceAtLeast(2))
        binding.searchGenreList.adapter = adapter
        // the adult genre only for accounts that show adult content
        fun shown(genre: String) = Anilist.adult || !genre.equals("hentai", true)
        activity.loadGenres { pair ->
            if (shown(pair.first)) {
                adapter.addGenre(pair)
                updateIdleSections()
            }
        }?.let { known ->
            val keep = known.filterKeys(::shown)
            adapter.genres = keep.toMutableMap()
            adapter.pos = ArrayList(keep.keys)
        }
    }

    override fun bind() {
        if (activity.searchType != SearchType.MANGA && activity.searchType != SearchType.ANIME) {
            throw IllegalArgumentException("Invalid search type (wrong adapter)")
        }
        showsGenres = true

        bindChrome(activity, type) { picked ->
            setQueryText(picked)
            hideKeyboard(activity)
            addHistory()
            searchTitle(debounceMs = 0L)
        }

        binding.searchBarText.removeTextChangedListener(textWatcher)
        setQueryText(activity.aniMangaResult.search)

        chips = bindChips(activity, type, activity.aniMangaResult) { applyFiltersChanged() }
        activity.updateChips = { refreshChips() }

        binding.searchByImage.isVisible = activity.aniMangaResult.type == "ANIME"
        binding.searchByImage.setOnClickListener {
            activity.startActivity(Intent(activity, ImageSearchActivity::class.java))
        }

        textWatcher = object : TextWatcher {
            override fun afterTextChanged(s: Editable) {}

            override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {}

            override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {
                if (programmaticTextChange) return
                if (s.toString().isBlank() && !hasActiveFilters()) {
                    activity.aniMangaResult.search = null
                    activity.showIdle()
                } else {
                    searchTitle(debounceMs = 400L)
                }
            }
        }
        binding.searchBarText.addTextChangedListener(textWatcher)

        binding.searchBarText.setOnEditorActionListener { _, actionId, _ ->
            when (actionId) {
                EditorInfo.IME_ACTION_SEARCH -> {
                    addHistory()
                    if (binding.searchBarText.text.toString().isNotBlank() || hasActiveFilters()) {
                        searchTitle(debounceMs = 0L)
                    }
                    hideKeyboard(activity)
                    true
                }

                else -> false
            }
        }

        bindGenres { genre ->
            activity.aniMangaResult.genres = mutableListOf(genre)
            activity.aniMangaResult.sort = Anilist.sortBy[2]
            refreshChips()
            hideKeyboard(activity)
            searchTitle(debounceMs = 0L)
        }
        updateIdleSections()

        search = { debounceMs -> searchTitle(debounceMs) }
        requestFocus = Runnable { binding.searchBarText.requestFocus() }
    }

    /** Scrollable row above anime/manga results: heading + M3 segmented grid/list switcher. */
    private class ResultsHeaderAdapter(private val activity: SearchActivity) :
        RecyclerView.Adapter<ResultsHeaderAdapter.ViewHolder>() {

        override fun getItemCount(): Int = 1

        override fun getItemViewType(position: Int): Int = VIEW_TYPE

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val binding = ItemSearchHeaderBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false,
            )
            return ViewHolder(binding)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val toggle = holder.binding.searchViewToggle
            toggle.clearOnButtonCheckedListeners()
            toggle.check(if (activity.style == 1) R.id.searchResultList else R.id.searchResultGrid)
            toggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
                if (!isChecked) return@addOnButtonCheckedListener
                val next = if (checkedId == R.id.searchResultList) 1 else 0
                if (next != activity.style) {
                    activity.style = next
                    PrefManager.setVal(PrefName.SearchStyle, next)
                    activity.recycler()
                }
            }
        }

        class ViewHolder(val binding: ItemSearchHeaderBinding) :
            RecyclerView.ViewHolder(binding.root)

        companion object {
            private const val VIEW_TYPE = 6969
        }
    }
}
