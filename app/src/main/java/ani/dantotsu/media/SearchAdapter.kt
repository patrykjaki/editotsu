package ani.dantotsu.media

import android.annotation.SuppressLint
import android.content.Intent
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import ani.dantotsu.connections.anilist.Anilist
import ani.dantotsu.connections.anilist.AnilistSearch.SearchType
import ani.dantotsu.openLinkInBrowser
import ani.dantotsu.others.imagesearch.ImageSearchActivity
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Header for anime and manga search: the bar, the filter chips, and the idle screen (recents + genre tiles). */
class SearchAdapter(private val activity: SearchActivity, private val type: SearchType) :
    HeaderInterface() {

    private var chips: SearchFilterChips? = null

    /** The idle screen's artwork tiles: one per genre, banner from a trending show in it. */
    private fun bindGenres(onPick: (String) -> Unit) {
        val adapter = GenreAdapter(activity.aniMangaResult.type, big = true, onPick = onPick)
        val width = activity.resources.displayMetrics.run { widthPixels / density }
        binding.searchGenreList.layoutManager =
            GridLayoutManager(activity, (width / 180f).toInt().coerceAtLeast(2))
        binding.searchGenreList.adapter = adapter
        // the adult genre only for accounts that show adult content
        fun shown(genre: String) = Anilist.adult || !genre.equals("hentai", true)
        activity.loadGenres { if (shown(it.first)) adapter.addGenre(it) }?.let { known ->
            val keep = known.filterKeys(::shown)
            adapter.genres = keep.toMutableMap()
            adapter.pos = ArrayList(keep.keys)
        }
    }

    /** Any filter set besides the sort order: then an empty query still lists results. */
    private fun filtered() = activity.aniMangaResult.run {
        !genres.isNullOrEmpty() || !tags.isNullOrEmpty() || !excludedGenres.isNullOrEmpty() ||
            !excludedTags.isNullOrEmpty() || format != null || status != null || source != null ||
            countryOfOrigin != null || season != null || seasonYear != null || startYear != null || onList != null
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onBindViewHolder(holder: SearchHeaderViewHolder, position: Int) {
        binding = holder.binding

        bindChrome(activity, type) {
            binding.searchBarText.setText(it)
            binding.searchBarText.setSelection(it.length)
        }

        val imm: InputMethodManager =
            activity.getSystemService(AppCompatActivity.INPUT_METHOD_SERVICE) as InputMethodManager

        if (activity.searchType != SearchType.MANGA && activity.searchType != SearchType.ANIME) {
            throw IllegalArgumentException("Invalid search type (wrong adapter)")
        }

        when (activity.style) {
            0 -> {
                binding.searchResultGrid.alpha = 1f
                binding.searchResultList.alpha = 0.33f
            }

            1 -> {
                binding.searchResultList.alpha = 1f
                binding.searchResultGrid.alpha = 0.33f
            }
        }

        binding.searchBarText.removeTextChangedListener(textWatcher)
        binding.searchBarText.setText(activity.aniMangaResult.search)

        fun searchTitle() {
            activity.aniMangaResult.search =
                binding.searchBarText.text.toString().takeIf { it.isNotEmpty() }
            if (binding.searchBarText.text.toString().equals("hentai", true)) {
                openLinkInBrowser("https://www.youtube.com/watch?v=GgJrEOo0QoA")
            }
            activity.search()
        }

        fun applyFiltersChanged() {
            chips?.build()
            if (binding.searchBarText.text.toString().isBlank() && !filtered()) {
                activity.aniMangaResult.search = null
                activity.emptyMediaAdapter()
                setHistoryVisibility(true)
            } else {
                setHistoryVisibility(false)
                searchTitle()
            }
        }

        chips = bindChips(activity, type, activity.aniMangaResult) { applyFiltersChanged() }
        activity.updateChips = { chips?.build() }

        if (activity.aniMangaResult.type != "ANIME") {
            binding.searchByImage.visibility = View.GONE
        }
        binding.searchByImage.setOnClickListener {
            activity.startActivity(Intent(activity, ImageSearchActivity::class.java))
        }
        updateClearHistoryVisibility()

        textWatcher = object : TextWatcher {
            override fun afterTextChanged(s: Editable) {}

            override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {}

            override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {
                if (s.toString().isBlank() && !filtered()) {
                    activity.aniMangaResult.search = null
                    activity.emptyMediaAdapter()
                    CoroutineScope(Dispatchers.IO).launch {
                        delay(200)
                        activity.runOnUiThread {
                            setHistoryVisibility(true)
                        }
                    }
                } else {
                    setHistoryVisibility(false)
                    searchTitle()
                }
            }
        }
        binding.searchBarText.addTextChangedListener(textWatcher)

        binding.searchBarText.setOnEditorActionListener { _, actionId, _ ->
            return@setOnEditorActionListener when (actionId) {
                EditorInfo.IME_ACTION_SEARCH -> {
                    searchTitle()
                    binding.searchBarText.clearFocus()
                    imm.hideSoftInputFromWindow(binding.searchBarText.windowToken, 0)
                    true
                }

                else -> false
            }
        }

        binding.searchResultGrid.setOnClickListener {
            it.alpha = 1f
            binding.searchResultList.alpha = 0.33f
            activity.style = 0
            PrefManager.setVal(PrefName.SearchStyle, 0)
            activity.recycler()
        }
        binding.searchResultList.setOnClickListener {
            it.alpha = 1f
            binding.searchResultGrid.alpha = 0.33f
            activity.style = 1
            PrefManager.setVal(PrefName.SearchStyle, 1)
            activity.recycler()
        }

        bindGenres { genre ->
            activity.aniMangaResult.genres = mutableListOf(genre)
            activity.aniMangaResult.sort = Anilist.sortBy[2]
            chips?.build()
            searchTitle()
        }

        search = Runnable { searchTitle() }
        requestFocus = Runnable { binding.searchBarText.requestFocus() }
    }
}
