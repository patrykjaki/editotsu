package ani.dantotsu.media

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import ani.dantotsu.R
import ani.dantotsu.connections.anilist.AnilistSearch.SearchType
import ani.dantotsu.databinding.ItemSearchHistoryBinding
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefManager.asLiveClass
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.settings.saving.SharedPreferenceClassLiveData
import java.io.Serializable

data class SearchHistory(val search: String, val time: Long) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}

class SearchHistoryAdapter(type: SearchType, private val searchClicked: (String) -> Unit) :
    ListAdapter<String, SearchHistoryAdapter.SearchHistoryViewHolder>(
        DIFF_CALLBACK_INSTALLED
    ) {
    private var searchHistoryLiveData: SharedPreferenceClassLiveData<List<SearchHistory>>? = null
    private var searchHistory: MutableList<SearchHistory>? = null
    private var historyType: PrefName = when (type) {
        SearchType.ANIME -> PrefName.SortedAnimeSH
        SearchType.MANGA -> PrefName.SortedMangaSH
        SearchType.CHARACTER -> PrefName.SortedCharacterSH
        SearchType.STAFF -> PrefName.SortedStaffSH
        SearchType.STUDIO -> PrefName.SortedStudioSH
        SearchType.USER -> PrefName.SortedUserSH
    }

    private fun MutableList<SearchHistory>?.sorted(): List<String>? =
        this?.sortedByDescending { it.time }?.map { it.search }

    var onListChanged: (() -> Unit)? = null

    override fun onCurrentListChanged(previousList: MutableList<String>, currentList: MutableList<String>) {
        onListChanged?.invoke()
    }

    // the idle screen shows the latest few; older ones stay saved
    private fun shown(): List<String>? = searchHistory?.sorted()?.take(6)

    init {
        searchHistoryLiveData =
            PrefManager.getLiveVal(historyType, mutableListOf<SearchHistory>()).asLiveClass()
        searchHistoryLiveData?.observeForever { data ->
            searchHistory = data.toMutableList()
            submitList(shown())
        }
    }

    fun remove(item: String) {
        searchHistory?.let { list ->
            list.removeAll { it.search == item }
        }
        PrefManager.setVal(historyType, searchHistory)
        submitList(shown())
    }

    fun add(item: String) {
        val maxSize = 25
        if (searchHistory?.any { it.search == item } == true || item.isBlank()) return
        if (PrefManager.getVal(PrefName.Incognito)) return
        searchHistory?.add(SearchHistory(item, System.currentTimeMillis()))
        if ((searchHistory?.size ?: 0) > maxSize) {
            searchHistory?.removeAt(
                searchHistory?.sorted()?.lastIndex ?: 0
            )
        }
        submitList(shown())
        PrefManager.setVal(historyType, searchHistory)
    }

    fun clearHistory() {
        searchHistory?.clear()
        PrefManager.setVal(historyType, searchHistory)
        submitList(shown())
    }

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int
    ): SearchHistoryAdapter.SearchHistoryViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_search_history, parent, false)
        return SearchHistoryViewHolder(view)
    }

    override fun onBindViewHolder(
        holder: SearchHistoryAdapter.SearchHistoryViewHolder,
        position: Int
    ) {
        val item = getItem(position)
        holder.binding.searchHistoryTextView.text = item
        holder.binding.closeTextView.setOnClickListener {
            val currentPosition = holder.bindingAdapterPosition
            if (currentPosition >= itemCount || currentPosition < 0) return@setOnClickListener
            remove(getItem(currentPosition))
        }
        holder.binding.searchHistoryTextView.setOnClickListener {
            val currentPosition = holder.bindingAdapterPosition
            if (currentPosition >= itemCount || currentPosition < 0) return@setOnClickListener
            searchClicked(getItem(currentPosition))
        }
    }

    inner class SearchHistoryViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val binding = ItemSearchHistoryBinding.bind(view)
    }

    companion object {
        val DIFF_CALLBACK_INSTALLED = object : DiffUtil.ItemCallback<String>() {
            override fun areItemsTheSame(
                oldItem: String,
                newItem: String
            ): Boolean {
                return oldItem == newItem
            }

            override fun areContentsTheSame(
                oldItem: String,
                newItem: String
            ): Boolean {
                return oldItem == newItem
            }
        }
    }
}
