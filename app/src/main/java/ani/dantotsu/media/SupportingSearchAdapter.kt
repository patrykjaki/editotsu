package ani.dantotsu.media

import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.EditorInfo
import ani.dantotsu.connections.anilist.AnilistSearch.SearchType
import ani.dantotsu.connections.anilist.SearchResults

/** Controller for character, staff, studio and user search: pinned bar, type chip, recents and idle guidance. */
class SupportingSearchAdapter(private val activity: SearchActivity, private val type: SearchType) :
    HeaderInterface(activity.binding) {

    private fun currentResult(): SearchResults<*> = when (type) {
        SearchType.CHARACTER -> activity.characterResult
        SearchType.STUDIO -> activity.studioResult
        SearchType.STAFF -> activity.staffResult
        SearchType.USER -> activity.userResult
        else -> throw IllegalArgumentException("Invalid search type")
    }

    private fun searchTitle(debounceMs: Long) {
        val searchText = binding.searchBarText.text?.toString()?.takeIf { it.isNotEmpty() }
        currentResult().search = searchText
        activity.search(debounceMs)
    }

    override fun bind() {
        if (activity.searchType == SearchType.MANGA || activity.searchType == SearchType.ANIME) {
            throw IllegalArgumentException("Invalid search type (wrong adapter)")
        }
        showsGenres = false

        bindChrome(activity, type) { picked ->
            setQueryText(picked)
            hideKeyboard(activity)
            addHistory()
            searchTitle(debounceMs = 0L)
        }

        binding.searchByImage.visibility = View.GONE
        binding.searchResetFilters.visibility = View.GONE
        binding.searchGenreHeader.visibility = View.GONE
        binding.searchGenreList.visibility = View.GONE
        bindChips(activity, type, null) {}

        binding.searchBarText.removeTextChangedListener(textWatcher)
        setQueryText(currentResult().search)
        updateIdleSections()

        textWatcher = object : TextWatcher {
            override fun afterTextChanged(s: Editable) {}

            override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {}

            override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {
                if (programmaticTextChange) return
                if (s.toString().isBlank()) {
                    currentResult().search = null
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
