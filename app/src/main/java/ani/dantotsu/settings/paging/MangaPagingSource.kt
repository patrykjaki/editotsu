package ani.dantotsu.settings.paging

import ani.dantotsu.settings.ExtensionRowText
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.ImageView
import androidx.core.view.isGone
import androidx.core.widget.ImageViewCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.PagingDataAdapter
import androidx.paging.PagingSource
import androidx.paging.PagingState
import androidx.paging.cachedIn
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import ani.dantotsu.R
import ani.dantotsu.databinding.ItemExtensionAllBinding
import ani.dantotsu.others.LanguageMapper
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import com.bumptech.glide.Glide
import com.google.android.material.color.MaterialColors
import eu.kanade.tachiyomi.extension.manga.MangaExtensionManager
import eu.kanade.tachiyomi.extension.manga.model.MangaExtension
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest

@Suppress("UNCHECKED_CAST")
class MangaExtensionsViewModelFactory(
    private val mangaExtensionManager: MangaExtensionManager
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return MangaExtensionsViewModel(mangaExtensionManager) as T
    }
}

class MangaExtensionsViewModel(
    mangaExtensionManager: MangaExtensionManager
) : ViewModel() {
    private val searchQuery = MutableStateFlow("")
    private var currentPagingSource: MangaExtensionPagingSource? = null

    fun setSearchQuery(query: String) {
        searchQuery.value = query
    }

    fun invalidatePager() {
        currentPagingSource?.invalidate()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    val pagerFlow: Flow<PagingData<MangaExtension.Available>> = combine(
        mangaExtensionManager.availableExtensionsFlow,
        mangaExtensionManager.installedExtensionsFlow,
        searchQuery
    ) { available, installed, query ->
        Triple(available, installed, query)
    }.flatMapLatest { (available, installed, query) ->
        Pager(
            PagingConfig(
                pageSize = 15,
                initialLoadSize = 15,
                prefetchDistance = 15
            )
        ) {
            val mEPS = MangaExtensionPagingSource(available, installed, query)
            currentPagingSource = mEPS
            mEPS
        }.flow
    }.cachedIn(viewModelScope)
}


class MangaExtensionPagingSource(
    private val availableExtensionsFlow: List<MangaExtension.Available>,
    private val installedExtensionsFlow: List<MangaExtension.Installed>,
    private val searchQuery: String
) : PagingSource<Int, MangaExtension.Available>() {

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, MangaExtension.Available> {
        val position = params.key ?: 0
        val installedExtensions = installedExtensionsFlow.map { it.pkgName }.toSet()
        val availableExtensions =
            availableExtensionsFlow.filterNot { it.pkgName in installedExtensions }
        val query = searchQuery
        val isNsfwEnabled: Boolean = PrefManager.getVal(PrefName.NSFWExtension)
        val filteredExtensions = if (query.isEmpty()) {
            availableExtensions
        } else {
            availableExtensions.filter { it.name.contains(query, ignoreCase = true) }
        }
        val lang: String = PrefManager.getVal(PrefName.LangSort)
        val langFilter =
            if (lang != "all") filteredExtensions.filter { it.lang == lang } else filteredExtensions
        val filternfsw = if (isNsfwEnabled) langFilter else langFilter.filterNot { it.isNsfw }
        return try {
            val sublist = filternfsw.subList(
                fromIndex = position,
                toIndex = (position + params.loadSize).coerceAtMost(filternfsw.size)
            )
            LoadResult.Page(
                data = sublist,
                prevKey = if (position == 0) null else position - params.loadSize,
                nextKey = if (position + params.loadSize >= filternfsw.size) null else position + params.loadSize
            )
        } catch (e: Exception) {
            LoadResult.Error(e)
        }
    }

    override fun getRefreshKey(state: PagingState<Int, MangaExtension.Available>): Int? {
        return null
    }
}

class MangaExtensionAdapter(private val clickListener: OnMangaInstallClickListener) :
    PagingDataAdapter<MangaExtension.Available, MangaExtensionAdapter.MangaExtensionViewHolder>(
        DIFF_CALLBACK
    ) {

    private val skipIcons: Boolean = PrefManager.getVal(PrefName.SkipExtensionIcons)
    private val installingKeys = HashSet<String>()

    fun clearInstalling(pkgName: String, repository: String) {
        if (installingKeys.remove("$pkgName|$repository")) {
            val index = snapshot().indexOfFirst { it?.pkgName == pkgName && it.repository == repository }
            if (index >= 0) notifyItemChanged(index)
        }
    }

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<MangaExtension.Available>() {
            override fun areItemsTheSame(
                oldItem: MangaExtension.Available,
                newItem: MangaExtension.Available
            ): Boolean {
                // Upstream 4b8d09be: same pkg from different repos coexists.
                return oldItem.pkgName == newItem.pkgName && oldItem.repository == newItem.repository
            }

            override fun areContentsTheSame(
                oldItem: MangaExtension.Available,
                newItem: MangaExtension.Available
            ): Boolean {
                return oldItem == newItem
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MangaExtensionViewHolder {
        val binding =
            ItemExtensionAllBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return MangaExtensionViewHolder(binding)
    }

    override fun onBindViewHolder(holder: MangaExtensionViewHolder, position: Int) {
        val extension = getItem(position)
        if (extension != null) {
            holder.extensionIconImageView.isGone = skipIcons
            if (!skipIcons) {
                Glide.with(holder.itemView.context)
                    .load(extension.iconUrl)
                    .into(holder.extensionIconImageView)
            }
            holder.bind(extension)
        }
    }

    inner class MangaExtensionViewHolder(private val binding: ItemExtensionAllBinding) :
        RecyclerView.ViewHolder(binding.root) {

        private var spinAnimator: ObjectAnimator? = null

        init {
            binding.closeTextView.setOnClickListener {
                if (bindingAdapterPosition == RecyclerView.NO_POSITION) return@setOnClickListener
                val extension = getItem(bindingAdapterPosition)
                if (extension != null) {
                    installingKeys += "${extension.pkgName}|${extension.repository}"
                    clickListener.onInstallClick(extension)
                    setInstalling(true)
                }
            }
        }

        val extensionIconImageView: ImageView = binding.extensionIconImageView

        fun bind(extension: MangaExtension.Available) {
            val lang = LanguageMapper.getLanguageName(extension.lang)
            binding.extensionNameTextView.text = extension.name
            val libStr = if (extension.libVersion == extension.libVersion.toLong().toDouble()) "${extension.libVersion.toLong()}" else "${extension.libVersion}"
            val displayVersion = if (extension.versionName.startsWith(libStr) || extension.versionName.startsWith("1.")) {
                extension.versionName
            } else {
                "$libStr.${extension.versionName}"
            }
            // Upstream 4b8d09be: origin-repo badge.
            val repo = extension.repoName ?: ani.dantotsu.parsers.ExtensionRepoMetaHelper.getRepoBadgeName(extension.repository)
            val versionText = ExtensionRowText.versionLine(lang, displayVersion, extension.isNsfw, repo)
            binding.extensionVersionTextView.text = versionText
            setInstalling(installingKeys.contains("${extension.pkgName}|${extension.repository}"))
        }

        private fun setInstalling(installing: Boolean) {
            spinAnimator?.cancel()
            spinAnimator = null
            binding.closeTextView.rotation = 0f
            val tintAttr = if (installing) androidx.appcompat.R.attr.colorPrimary
            else com.google.android.material.R.attr.colorOnSurfaceVariant
            ImageViewCompat.setImageTintList(
                binding.closeTextView,
                ColorStateList.valueOf(MaterialColors.getColor(binding.closeTextView, tintAttr))
            )
            if (installing) {
                binding.closeTextView.setImageResource(R.drawable.ic_round_sync_24)
                spinAnimator = ObjectAnimator.ofFloat(binding.closeTextView, View.ROTATION, 0f, 360f).apply {
                    duration = 1000L
                    repeatCount = ValueAnimator.INFINITE
                    interpolator = LinearInterpolator()
                    start()
                }
            } else {
                binding.closeTextView.setImageResource(R.drawable.ic_download_24)
            }
        }

        fun clear() {
            spinAnimator?.cancel()
            spinAnimator = null
            binding.closeTextView.rotation = 0f
        }
    }

    override fun onViewRecycled(holder: MangaExtensionViewHolder) {
        super.onViewRecycled(holder)
        holder.clear()
    }
}

interface OnMangaInstallClickListener {
    fun onInstallClick(pkg: MangaExtension.Available)
}
