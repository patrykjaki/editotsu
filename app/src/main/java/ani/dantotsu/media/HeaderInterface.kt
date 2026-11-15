package ani.dantotsu.media

import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ani.dantotsu.R
import ani.dantotsu.connections.anilist.AnilistSearch.SearchType
import ani.dantotsu.databinding.ItemSearchHeaderBinding
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName

abstract class HeaderInterface : RecyclerView.Adapter<HeaderInterface.SearchHeaderViewHolder>() {
    private val itemViewType = 6969
    var search: Runnable? = null
    var requestFocus: Runnable? = null
    protected var textWatcher: TextWatcher? = null
    protected lateinit var searchHistoryAdapter: SearchHistoryAdapter
    protected lateinit var binding: ItemSearchHeaderBinding

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SearchHeaderViewHolder {
        val binding =
            ItemSearchHeaderBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return SearchHeaderViewHolder(binding)
    }

    /** What every search type shares: back, the pill's hint and clear button, recents, and the type chips. */
    protected fun bindChrome(activity: SearchActivity, type: SearchType, onPick: (String) -> Unit) {
        binding.searchBack.setOnClickListener { activity.onBackPressedDispatcher.onBackPressed() }

        val typeName = activity.getString(
            when (type) {
                SearchType.ANIME -> R.string.anime
                SearchType.MANGA -> R.string.manga
                SearchType.CHARACTER -> R.string.characters
                SearchType.STAFF -> R.string.staff
                SearchType.STUDIO -> R.string.studios
                SearchType.USER -> R.string.users
            }
        ).lowercase()
        binding.searchBarText.hint = activity.getString(R.string.search_hint, typeName)
        if (PrefManager.getVal(PrefName.Incognito)) binding.searchLeadIcon.setImageResource(R.drawable.ic_incognito_24)

        binding.searchClear.isVisible = !binding.searchBarText.text.isNullOrEmpty()
        binding.searchBarText.doAfterTextChanged { binding.searchClear.isVisible = !it.isNullOrEmpty() }
        binding.searchClear.setOnClickListener {
            binding.searchBarText.setText("")
            binding.searchBarText.requestFocus()
        }

        searchHistoryAdapter = SearchHistoryAdapter(type, onPick)
        searchHistoryAdapter.onListChanged = { updateClearHistoryVisibility() }
        binding.searchHistoryList.layoutManager = LinearLayoutManager(binding.root.context)
        binding.searchHistoryList.adapter = searchHistoryAdapter
        binding.clearHistory.setOnClickListener { searchHistoryAdapter.clearHistory() }

    }

    /** The chip row: type, then (anime/manga) sort and filters. Rebuilt whenever a filter changes. */
    protected fun bindChips(activity: SearchActivity, type: SearchType, filters: ani.dantotsu.connections.anilist.AniMangaSearchResults?, onChanged: () -> Unit): SearchFilterChips =
        SearchFilterChips(activity, binding.searchChips, type, { binding.searchBarText.text?.toString().orEmpty() }, filters, onChanged)
            .also { it.build() }

    fun setHistoryVisibility(visible: Boolean) {
        if (visible) {
            if (binding.searchIdle.visibility != View.VISIBLE) {
                binding.searchResultLayout.startAnimation(fadeOutAnimation())
                binding.searchIdle.startAnimation(fadeInAnimation())
            }
            binding.searchResultLayout.visibility = View.GONE
            binding.searchIdle.visibility = View.VISIBLE
            updateClearHistoryVisibility()
        } else {
            if (binding.searchResultLayout.visibility != View.VISIBLE) {
                binding.searchResultLayout.startAnimation(fadeInAnimation())
                binding.searchIdle.startAnimation(fadeOutAnimation())
            }
            binding.searchResultLayout.visibility = View.VISIBLE
            binding.searchIdle.visibility = View.GONE
        }
    }

    private fun fadeInAnimation(): Animation {
        return AlphaAnimation(0f, 1f).apply {
            duration = 150
        }
    }

    protected fun fadeOutAnimation(): Animation {
        return AlphaAnimation(1f, 0f).apply {
            duration = 150
        }
    }

    protected fun updateClearHistoryVisibility() {
        if (!::binding.isInitialized || !::searchHistoryAdapter.isInitialized) return
        val any = searchHistoryAdapter.itemCount > 0
        binding.searchHistoryHeader.isVisible = any
        binding.searchHistoryList.isVisible = any
    }

    fun addHistory() {
        if (::searchHistoryAdapter.isInitialized && binding.searchBarText.text.toString()
                .isNotBlank()
        ) searchHistoryAdapter.add(binding.searchBarText.text.toString())
    }

    inner class SearchHeaderViewHolder(val binding: ItemSearchHeaderBinding) :
        RecyclerView.ViewHolder(binding.root)

    override fun getItemCount(): Int = 1

    override fun getItemViewType(position: Int): Int {
        return itemViewType
    }
}
