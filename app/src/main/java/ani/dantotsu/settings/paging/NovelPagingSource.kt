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
import ani.dantotsu.parsers.novel.NovelExtension
import ani.dantotsu.parsers.novel.NovelExtensionManager
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import com.bumptech.glide.Glide
import com.google.android.material.color.MaterialColors
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest


class NovelExtensionsViewModelFactory(
    private val novelExtensionManager: NovelExtensionManager
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return NovelExtensionsViewModel(novelExtensionManager) as T
    }
}

class NovelExtensionsViewModel(
    novelExtensionManager: NovelExtensionManager
) : ViewModel() {
    private val searchQuery = MutableStateFlow("")
    private var currentPagingSource: NovelExtensionPagingSource? = null

    fun setSearchQuery(query: String) {
        searchQuery.value = query
    }

    fun invalidatePager() {
        currentPagingSource?.invalidate()

    }

    @OptIn(ExperimentalCoroutinesApi::class)
    val pagerFlow: Flow<PagingData<NovelExtension.Available>> = combine(
        novelExtensionManager.availableExtensionsFlow,
        novelExtensionManager.installedExtensionsFlow,
        novelExtensionManager.lnReaderManager.availablePluginsFlow,
        novelExtensionManager.lnReaderManager.installedPluginsFlow,
        searchQuery
    ) { available, installed, lnAvailable, lnInstalled, query ->
        val installedPkgs = installed.map { it.pkgName }.toSet()
        val installedLnIds = lnInstalled.map { it.id }.toSet()

        val lnAsAvailable = lnAvailable
            .filter { it.id !in installedLnIds }
            .map { plugin ->
                NovelExtension.Available(
                    name = plugin.name,
                    pkgName = "lnreader-${plugin.id}",
                    versionName = plugin.version,
                    versionCode = 0L,
                    repository = "lnreader",
                    sources = emptyList(),
                    iconUrl = plugin.iconUrl,
                    lang = plugin.lang,
                )
            }

        val combinedAvailable = available.filterNot { avail ->
            avail.pkgName.isBlank() || avail.versionName.isBlank() ||
            installed.any { inst -> ani.dantotsu.parsers.novel.isSameNovelExtension(avail.pkgName, avail.name, inst.pkgName, inst.name) }
        } + lnAsAvailable
        Pair(combinedAvailable, query)
    }.flatMapLatest { (combinedAvailable, query) ->
        Pager(
            PagingConfig(
                pageSize = 15,
                initialLoadSize = 15,
                prefetchDistance = 15
            )
        ) {
            val nEPS = NovelExtensionPagingSource(combinedAvailable, novelExtensionManager, query)
            currentPagingSource = nEPS
            nEPS
        }.flow
    }.cachedIn(viewModelScope)
}


class NovelExtensionPagingSource(
    private val availableExtensions: List<NovelExtension.Available>,
    private val novelExtensionManager: NovelExtensionManager,
    private val searchQuery: String
) : PagingSource<Int, NovelExtension.Available>() {

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, NovelExtension.Available> {
        val position = params.key ?: 0
        val installed = novelExtensionManager.installedExtensionsFlow.value
        val installedLnIds = novelExtensionManager.lnReaderManager.installedPluginsFlow.value.map { it.id }.toSet()
        val uninstalledAvailable = availableExtensions.filterNot { avail ->
            if (avail.pkgName.startsWith("lnreader-")) {
                avail.pkgName.removePrefix("lnreader-") in installedLnIds
            } else {
                installed.any { inst -> ani.dantotsu.parsers.novel.isSameNovelExtension(avail.pkgName, avail.name, inst.pkgName, inst.name) }
            }
        }
        val query = searchQuery
        val filteredExtensions = if (query.isEmpty()) {
            uninstalledAvailable
        } else {
            uninstalledAvailable.filter { it.name.contains(query, ignoreCase = true) }
        }
        val lang: String = PrefManager.getVal(PrefName.LangSort)
        val langFilter = if (lang != "all") {
            filteredExtensions.filter { ext ->
                val extLangCode = LanguageMapper.getLanguageCode(ext.lang)
                extLangCode.equals(lang, ignoreCase = true) || ext.lang.equals(lang, ignoreCase = true)
            }
        } else {
            filteredExtensions
        }
        return try {
            val sublist = langFilter.subList(
                fromIndex = position,
                toIndex = (position + params.loadSize).coerceAtMost(langFilter.size)
            )
            LoadResult.Page(
                data = sublist,
                prevKey = if (position == 0) null else position - params.loadSize,
                nextKey = if (position + params.loadSize >= langFilter.size) null else position + params.loadSize
            )
        } catch (e: Exception) {
            LoadResult.Error(e)
        }
    }

    override fun getRefreshKey(state: PagingState<Int, NovelExtension.Available>): Int? {
        return null
    }
}

class NovelExtensionAdapter(private val clickListener: OnNovelInstallClickListener) :
    PagingDataAdapter<NovelExtension.Available, NovelExtensionAdapter.NovelExtensionViewHolder>(
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
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<NovelExtension.Available>() {
            override fun areItemsTheSame(
                oldItem: NovelExtension.Available,
                newItem: NovelExtension.Available
            ): Boolean {
                // Upstream 4b8d09be: same pkg from different repos coexists.
                return oldItem.pkgName == newItem.pkgName && oldItem.repository == newItem.repository
            }

            override fun areContentsTheSame(
                oldItem: NovelExtension.Available,
                newItem: NovelExtension.Available
            ): Boolean {
                return oldItem == newItem
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): NovelExtensionViewHolder {
        val binding =
            ItemExtensionAllBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return NovelExtensionViewHolder(binding)
    }

    override fun onBindViewHolder(holder: NovelExtensionViewHolder, position: Int) {
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

    inner class NovelExtensionViewHolder(private val binding: ItemExtensionAllBinding) :
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
        fun bind(extension: NovelExtension.Available) {
            val lang = LanguageMapper.getLanguageName(extension.lang)
            binding.extensionNameTextView.text = extension.name
            // Upstream 4b8d09be: origin-repo badge.
            val repo = extension.repoName ?: ani.dantotsu.parsers.ExtensionRepoMetaHelper.getRepoBadgeName(extension.repository)
            val versionText = ExtensionRowText.versionLine(lang, extension.versionName, false, repo)
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

    override fun onViewRecycled(holder: NovelExtensionViewHolder) {
        super.onViewRecycled(holder)
        holder.clear()
    }
}

interface OnNovelInstallClickListener {
    fun onInstallClick(pkg: NovelExtension.Available)
}