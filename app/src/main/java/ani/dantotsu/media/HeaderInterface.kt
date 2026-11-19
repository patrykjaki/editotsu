package ani.dantotsu.media

import android.content.res.ColorStateList
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.InputMethodManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.core.widget.ImageViewCompat
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ani.dantotsu.R
import ani.dantotsu.connections.anilist.AniMangaSearchResults
import ani.dantotsu.connections.anilist.AnilistSearch.SearchType
import ani.dantotsu.databinding.ActivitySearchBinding
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import com.google.android.material.color.MaterialColors

/**
 * Shared controller for the pinned search top bar, filter chip strip, and idle view (recents + idle prompt).
 */
abstract class HeaderInterface(protected val binding: ActivitySearchBinding) {
    var search: ((Long) -> Unit)? = null
    var requestFocus: Runnable? = null
    protected var textWatcher: TextWatcher? = null
    protected var programmaticTextChange: Boolean = false
    protected lateinit var searchHistoryAdapter: SearchHistoryAdapter
    protected var showsGenres: Boolean = false

    abstract fun bind()

    /** Optional scrollable header placed above the results grid/list (grid/list switcher for media). */
    open fun resultsHeaderAdapter(): RecyclerView.Adapter<*>? = null

    open fun hasActiveFilters(): Boolean = false

    open fun resetFilters() {}

    /** What every search type shares: back, the pill's hint and clear button, incognito state, and recents. */
    protected fun bindChrome(activity: SearchActivity, type: SearchType, onPick: (String) -> Unit) {
        binding.searchBack.setOnClickListener { activity.onBackPressedDispatcher.onBackPressed() }

        val hintText = if (type == SearchType.ALL) {
            activity.getString(R.string.search_hint_all)
        } else {
            val typeName = activity.getString(
                when (type) {
                    SearchType.ALL -> R.string.all
                    SearchType.ANIME -> R.string.anime
                    SearchType.MANGA -> R.string.manga
                    SearchType.CHARACTER -> R.string.characters
                    SearchType.STAFF -> R.string.staff
                    SearchType.STUDIO -> R.string.studios
                    SearchType.USER -> R.string.users
                }
            ).lowercase()
            activity.getString(R.string.search_hint, typeName)
        }
        binding.searchBarText.hint = hintText
        binding.searchIdlePromptTitle.text = hintText

        if (PrefManager.getVal(PrefName.Incognito)) {
            binding.searchLeadIcon.setImageResource(R.drawable.ic_incognito_24)
            ImageViewCompat.setImageTintList(
                binding.searchLeadIcon,
                ColorStateList.valueOf(
                    MaterialColors.getColor(binding.searchLeadIcon, androidx.appcompat.R.attr.colorPrimary)
                )
            )
            binding.searchLeadIcon.contentDescription = activity.getString(R.string.incognito_mode)
            binding.searchLeadIcon.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }

        binding.searchClear.isVisible = !binding.searchBarText.text.isNullOrEmpty()
        binding.searchBarText.doAfterTextChanged { binding.searchClear.isVisible = !it.isNullOrEmpty() }
        binding.searchClear.setOnClickListener {
            binding.searchBarText.setText("")
            binding.searchBarText.requestFocus()
        }

        searchHistoryAdapter = SearchHistoryAdapter(type, activity, onPick)
        searchHistoryAdapter.onListChanged = { updateIdleSections() }
        binding.searchHistoryList.layoutManager = LinearLayoutManager(binding.root.context)
        binding.searchHistoryList.adapter = searchHistoryAdapter
        binding.searchHistoryList.itemAnimator = null
        binding.clearHistory.setOnClickListener { searchHistoryAdapter.clearHistory() }
    }

    /** The chip row: type, then (anime/manga) sort and filters. Rebuilt whenever a filter changes. */
    protected fun bindChips(
        activity: SearchActivity,
        type: SearchType,
        filters: AniMangaSearchResults?,
        onChanged: () -> Unit,
    ): SearchFilterChips =
        SearchFilterChips(
            activity,
            binding.searchChips,
            type,
            { binding.searchBarText.text?.toString().orEmpty() },
            filters,
            onChanged,
        ).also { it.build() }

    protected fun setQueryText(text: String?) {
        programmaticTextChange = true
        binding.searchBarText.setText(text.orEmpty())
        if (!text.isNullOrEmpty()) {
            binding.searchBarText.setSelection(text.length)
        }
        binding.searchClear.isVisible = !text.isNullOrEmpty()
        programmaticTextChange = false
    }

    fun currentQuery(): String = binding.searchBarText.text?.toString().orEmpty()

    fun updateIdleSections() {
        val hasHistory = ::searchHistoryAdapter.isInitialized && searchHistoryAdapter.itemCount > 0
        binding.searchHistoryHeader.isVisible = hasHistory
        binding.searchHistoryList.isVisible = hasHistory
        val hasGenres = showsGenres && (binding.searchGenreList.adapter?.itemCount ?: 0) > 0
        binding.searchGenreHeader.isVisible = hasGenres
        binding.searchGenreList.isVisible = hasGenres
        binding.searchIdlePrompt.isVisible = !hasHistory && !hasGenres
    }

    fun addHistory() {
        if (::searchHistoryAdapter.isInitialized) {
            val query = binding.searchBarText.text?.toString().orEmpty()
            if (query.isNotBlank()) searchHistoryAdapter.add(query)
        }
    }

    fun hideKeyboard(activity: SearchActivity) {
        binding.searchBarText.clearFocus()
        val imm = activity.getSystemService(AppCompatActivity.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(binding.searchBarText.windowToken, 0)
    }
}
