package ani.dantotsu.media

import android.content.Intent
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.core.view.isVisible
import androidx.recyclerview.widget.GridLayoutManager
import ani.dantotsu.connections.anilist.Anilist
import ani.dantotsu.connections.anilist.AnilistSearch.SearchType
import ani.dantotsu.others.imagesearch.ImageSearchActivity

/**
 * Controller for mixed "All" search (Home) as well as character, staff, studio and user search:
 * pinned search bar, type chip (no Filters button), recents, plus image search & browse-by-genre in All.
 */
class SupportingSearchAdapter(private val activity: SearchActivity, private val type: SearchType) :
    HeaderInterface(activity.binding) {

    private var currentSearchText: String?
        get() = when (type) {
            SearchType.ALL -> activity.mixedResult.search
            SearchType.CHARACTER -> activity.characterResult.search
            SearchType.STUDIO -> activity.studioResult.search
            SearchType.STAFF -> activity.staffResult.search
            SearchType.USER -> activity.userResult.search
            else -> throw IllegalArgumentException("Invalid search type")
        }
        set(value) {
            when (type) {
                SearchType.ALL -> activity.mixedResult.search = value
                SearchType.CHARACTER -> activity.characterResult.search = value
                SearchType.STUDIO -> activity.studioResult.search = value
                SearchType.STAFF -> activity.staffResult.search = value
                SearchType.USER -> activity.userResult.search = value
                else -> throw IllegalArgumentException("Invalid search type")
            }
        }

    private fun searchTitle(debounceMs: Long) {
        val searchText = binding.searchBarText.text?.toString()?.takeIf { it.isNotEmpty() }
        currentSearchText = searchText
        activity.search(debounceMs)
    }

    private fun bindGenres(onPick: (String) -> Unit) {
        val adapter = GenreAdapter("ANIME", big = true, onPick = onPick)
        val width = activity.resources.displayMetrics.run { widthPixels / density }
        binding.searchGenreList.layoutManager =
            GridLayoutManager(activity, (width / 180f).toInt().coerceAtLeast(2))
        binding.searchGenreList.adapter = adapter
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
        if (activity.searchType == SearchType.MANGA || activity.searchType == SearchType.ANIME) {
            throw IllegalArgumentException("Invalid search type (wrong adapter)")
        }
        val isAll = type == SearchType.ALL
        showsGenres = isAll

        bindChrome(activity, type) { picked ->
            setQueryText(picked)
            hideKeyboard(activity)
            addHistory()
            searchTitle(debounceMs = 0L)
        }

        binding.searchByImage.isVisible = isAll
        if (isAll) {
            binding.searchByImage.setOnClickListener {
                activity.startActivity(Intent(activity, ImageSearchActivity::class.java))
            }
        }
        if (isAll) {
            bindGenres { genre ->
                hideKeyboard(activity)
                activity.switchToType(
                    nextType = SearchType.ANIME,
                    genre = genre,
                    sortBy = Anilist.sortBy[2],
                    autoSearch = true,
                )
            }
        } else {
            binding.searchGenreHeader.visibility = View.GONE
            binding.searchGenreList.visibility = View.GONE
        }

        bindChips(activity, type, null) {}

        binding.searchBarText.removeTextChangedListener(textWatcher)
        setQueryText(currentSearchText)
        updateIdleSections()

        textWatcher = object : TextWatcher {
            override fun afterTextChanged(s: Editable) {}

            override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {}

            override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {
                if (programmaticTextChange) return
                if (s.toString().isBlank()) {
                    currentSearchText = null
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
                    if (binding.searchBarText.text.toString().isNotBlank()) {
                        searchTitle(debounceMs = 0L)
                    }
                    hideKeyboard(activity)
                    true
                }

                else -> false
            }
        }

        search = { debounceMs -> searchTitle(debounceMs) }
        requestFocus = Runnable { binding.searchBarText.requestFocus() }
    }
}
