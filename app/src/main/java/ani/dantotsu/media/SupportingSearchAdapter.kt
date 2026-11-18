package ani.dantotsu.media

import android.annotation.SuppressLint
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.appcompat.app.AppCompatActivity
import ani.dantotsu.connections.anilist.AnilistSearch.SearchType
import ani.dantotsu.connections.anilist.SearchResults
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class SupportingSearchAdapter(private val activity: SearchActivity, private val type: SearchType) :
    HeaderInterface() {

    @SuppressLint("ClickableViewAccessibility")
    override fun onBindViewHolder(holder: SearchHeaderViewHolder, position: Int) {
        binding = holder.binding

        bindChrome(activity, type) {
            binding.searchBarText.setText(it)
            binding.searchBarText.setSelection(it.length)
        }

        val imm: InputMethodManager =
            activity.getSystemService(AppCompatActivity.INPUT_METHOD_SERVICE) as InputMethodManager

        if (activity.searchType == SearchType.MANGA || activity.searchType == SearchType.ANIME) {
            throw IllegalArgumentException("Invalid search type (wrong adapter)")
        }

        binding.searchByImage.visibility = View.GONE
        binding.searchResultGrid.visibility = View.GONE
        binding.searchResultList.visibility = View.GONE
        bindChips(activity, type, null) {}
        binding.searchGenreHeader.visibility = View.GONE
        binding.searchGenreList.visibility = View.GONE

        binding.searchBarText.removeTextChangedListener(textWatcher)
        when (type) {
            SearchType.CHARACTER -> {
                binding.searchBarText.setText(activity.characterResult.search)
            }

            SearchType.STUDIO -> {
                binding.searchBarText.setText(activity.studioResult.search)
            }

            SearchType.STAFF -> {
                binding.searchBarText.setText(activity.staffResult.search)
            }

            SearchType.USER -> {
                binding.searchBarText.setText(activity.userResult.search)
            }

            else -> throw IllegalArgumentException("Invalid search type")
        }

        updateClearHistoryVisibility()
        fun searchTitle() {
            val searchText = binding.searchBarText.text.toString().takeIf { it.isNotEmpty() }

            val result: SearchResults<*> = when (type) {
                SearchType.CHARACTER -> activity.characterResult
                SearchType.STUDIO -> activity.studioResult
                SearchType.STAFF -> activity.staffResult
                SearchType.USER -> activity.userResult
                else -> throw IllegalArgumentException("Invalid search type")
            }

            result.search = searchText
            activity.search()
        }

        textWatcher = object : TextWatcher {
            override fun afterTextChanged(s: Editable) {}

            override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {}

            override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {
                if (s.toString().isBlank()) {
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

        search = Runnable { searchTitle() }
        requestFocus = Runnable { binding.searchBarText.requestFocus() }
    }
}
