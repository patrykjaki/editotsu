package ani.dantotsu.settings

import ani.dantotsu.settings.rows.ChoiceSheet
import ani.dantotsu.settings.extensionprefs.sourceChoiceLabels
import ani.dantotsu.settings.rows.ChoiceOption
import android.app.NotificationManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import ani.dantotsu.R
import ani.dantotsu.connections.crashlytics.CrashlyticsInterface
import ani.dantotsu.databinding.FragmentExtensionsBinding
import ani.dantotsu.media.MediaType
import ani.dantotsu.others.LanguageMapper.Companion.getLanguageName
import ani.dantotsu.parsers.AnimeSources
import ani.dantotsu.settings.extensionprefs.AnimeSourcePreferencesFragment
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.snackString
import ani.dantotsu.util.Logger

import com.google.android.material.tabs.TabLayout
import com.google.android.material.textfield.TextInputLayout
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.extension.anime.AnimeExtensionManager
import eu.kanade.tachiyomi.extension.anime.model.AnimeExtension
import kotlinx.coroutines.launch
import rx.android.schedulers.AndroidSchedulers
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.Locale


class InstalledAnimeExtensionsFragment : Fragment(), SearchQueryHandler {
    private var _binding: FragmentExtensionsBinding? = null
    private var searchQuery = ""
    private val binding get() = _binding!!
    private lateinit var extensionsRecyclerView: RecyclerView
    private val skipIcons: Boolean = PrefManager.getVal(PrefName.SkipExtensionIcons)
    private val animeExtensionManager: AnimeExtensionManager = Injekt.get()
    private fun updateItem(ext: AnimeExtension.Installed) =
        ExtensionUpdateAll.Item(ext.pkgName, ext.name) { animeExtensionManager.updateExtension(ext) }

    private val updateOne: (AnimeExtension.Installed) -> Unit = { pkg ->
        if (isAdded) {
            if (pkg.hasUpdate) ExtensionUpdateAll.run(requireContext(), listOf(updateItem(pkg)))
            else snackString(getString(R.string.extension_update_no_update))
        }
    }

    private val extensionsAdapter = AnimeExtensionsAdapter(
        { pkg ->
            val name = pkg.name
            val changeUIVisibility: (Boolean) -> Unit = { show ->
                (activity as? ExtensionsActivity)?.showSourcePreferences(!show, name)
            }
            val allSettings = pkg.sources.filterIsInstance<ConfigurableAnimeSource>()
            if (allSettings.isNotEmpty()) {
                val openPrefFragment: (ConfigurableAnimeSource) -> Unit = { source ->
                    changeUIVisibility(false)
                    val fragment = AnimeSourcePreferencesFragment().getInstance(source.id) {
                        changeUIVisibility(true)
                    }
                    parentFragmentManager.beginTransaction()
                        .setCustomAnimations(R.anim.slide_up, R.anim.slide_down)
                        .replace(R.id.fragmentExtensionsContainer, fragment)
                        .addToBackStack(null)
                        .commit()
                }

                if (allSettings.size > 1) {
                    // One extension, several sources (languages): pick whose settings to open.
                    ChoiceSheet.single(
                        requireContext(), getString(R.string.extensions_select_source),
                        sourceChoiceLabels(allSettings.map { it.name }, allSettings.map { it.lang }).map { ChoiceOption(it) }, -1,
                        onCancel = { changeUIVisibility(true) },
                    ) { which -> openPrefFragment(allSettings[which]) }
                } else {
                    openPrefFragment(allSettings[0])
                }
            } else {
                Toast.makeText(requireContext(), getString(R.string.extensions_not_configurable), Toast.LENGTH_SHORT)
                    .show()
            }
        },
        { pkg ->
            if (isAdded) {
                ExtensionRowMenu.uninstall(requireContext(), pkg.name, pkg.pkgName) {
                    animeExtensionManager.uninstallExtension(pkg.pkgName)
                }
            }
        }, updateOne, skipIcons
    )

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentExtensionsBinding.inflate(inflater, container, false)

        extensionsRecyclerView = binding.allExtensionsRecyclerView
        extensionsRecyclerView.layoutManager = SafeLinearLayoutManager(requireContext())
        extensionsRecyclerView.addItemDecoration(ExtensionCardDecoration())
        extensionsRecyclerView.adapter = extensionsAdapter

        val itemTouchHelperCallback = object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0
        ) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val fromPosition = viewHolder.absoluteAdapterPosition
                val toPosition = target.absoluteAdapterPosition
                val newList = extensionsAdapter.currentList.toMutableList().apply {
                    add(toPosition, removeAt(fromPosition))
                }
                extensionsAdapter.submitList(newList)
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}

            override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
                super.onSelectedChanged(viewHolder, actionState)
                if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
                    viewHolder?.itemView?.let { ExtensionCardDecoration.lift(it) }
                }
            }

            override fun clearView(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder
            ) {
                super.clearView(recyclerView, viewHolder)
                extensionsAdapter.updatePref()
                ExtensionCardDecoration.drop(viewHolder.itemView)
            }
        }
        ExtensionReorder.attach(itemTouchHelperCallback, extensionsRecyclerView, binding.extensionsSwipeRefresh)


        lifecycleScope.launch {
            animeExtensionManager.installedExtensionsFlow.collect { extensions ->
                extensionsAdapter.updateData(sortToAnimeSourcesList(extensions))
                ExtensionUpdateAll.bind(
                    _binding?.updateAllButton ?: return@collect,
                    ExtensionUpdateAll.pending(extensions) { it.hasUpdate },
                    ::updateItem
                )
            }
        }
        ExtensionRefresh.bind(binding.extensionsSwipeRefresh, viewLifecycleOwner, MediaType.ANIME)
        extensionsAdapter.onListChanged = { count ->
            _binding?.let {
                ExtensionEmptyState.onInstalledList(emptyViews(it), count, MediaType.ANIME, searchQuery)
            }
        }
        // The installed list can load before this hook is set; show its state now too.
        extensionsAdapter.onListChanged?.invoke(extensionsAdapter.currentList.size)
        return binding.root
    }


    private fun sortToAnimeSourcesList(inpt: List<AnimeExtension.Installed>): List<AnimeExtension.Installed> {
        val sourcesMap = inpt.associateBy { it.name }
        val orderedSources = AnimeSources.pinnedAnimeSources.mapNotNull { name ->
            sourcesMap[name]
        }
        return orderedSources + inpt.filter { !AnimeSources.pinnedAnimeSources.contains(it.name) }
    }

    override fun onDestroyView() {
        extensionsAdapter.onListChanged = null
        super.onDestroyView();_binding = null
    }

    private fun emptyViews(b: FragmentExtensionsBinding) = ExtensionEmptyState.Views(
        b.extensionsEmpty, b.extensionsEmptyText, b.extensionsEmptyAction
    )

    override fun updateContentBasedOnQuery(query: String?) {
        searchQuery = query?.trim().orEmpty()
        extensionsAdapter.filter(
            query ?: "",
            sortToAnimeSourcesList(animeExtensionManager.installedExtensionsFlow.value)
        )
    }

    override fun notifyDataChanged() { // Do nothing
    }

    private class AnimeExtensionsAdapter(
        private val onSettingsClicked: (AnimeExtension.Installed) -> Unit,
        private val onUninstallClicked: (AnimeExtension.Installed) -> Unit,
        private val onUpdateClicked: (AnimeExtension.Installed) -> Unit,
        val skipIcons: Boolean
    ) : ListAdapter<AnimeExtension.Installed, AnimeExtensionsAdapter.ViewHolder>(
        DIFF_CALLBACK_INSTALLED
    ) {

        var onListChanged: ((Int) -> Unit)? = null

        override fun onCurrentListChanged(
            previousList: MutableList<AnimeExtension.Installed>,
            currentList: MutableList<AnimeExtension.Installed>
        ) {
            onListChanged?.invoke(currentList.size)
        }

        fun updateData(newExtensions: List<AnimeExtension.Installed>) {
            submitList(newExtensions)
        }

        fun updatePref() {
            val map = currentList.map { it.name }
            PrefManager.setVal(PrefName.AnimeSourcesOrder, map)
            AnimeSources.pinnedAnimeSources = map
            AnimeSources.performReorderAnimeSources()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_extension, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val extension = getItem(position)
            val lang = getLanguageName(extension.lang)
            holder.extensionNameTextView.text = extension.name
            val majorLib = extension.libVersion.toInt()
            val displayVersion = if (extension.versionName.startsWith("$majorLib.") || extension.versionName.startsWith("$majorLib")) {
                extension.versionName
            } else {
                "$majorLib.${extension.versionName}"
            }
            // Upstream 4b8d09be: origin-repo badge (installed may predate tracking).
            val repo = extension.repoName ?: extension.repository?.let { ani.dantotsu.parsers.ExtensionRepoMetaHelper.getRepoBadgeName(it) } ?: ani.dantotsu.parsers.ExtensionRepoMetaHelper.getInstalledRepo(extension.pkgName)?.let { ani.dantotsu.parsers.ExtensionRepoMetaHelper.getRepoBadgeName(it) }
            val versionText = ExtensionRowText.versionLine(lang, displayVersion, extension.isNsfw, repo)
            holder.extensionVersionTextView.text = versionText
            if (!skipIcons) {
                holder.extensionIconImageView.setImageDrawable(extension.icon)
            }
            if (extension.hasUpdate) {
                holder.updateView.isVisible = true
            } else {
                holder.updateView.isVisible = false
            }
            // Redesign: uninstall lives in the row's ⋮ menu with settings.
            holder.deleteView.isVisible = false
            holder.updateView.contentDescription =
                holder.itemView.context.getString(R.string.extensions_row_update)
            holder.updateView.setOnClickListener {
                onUpdateClicked(extension)
            }
            holder.settingsImageView.contentDescription =
                holder.itemView.context.getString(R.string.extensions_more_options)
            holder.settingsImageView.setOnClickListener {
                ExtensionRowMenu.show(
                    it,
                    onSettings = { onSettingsClicked(extension) },
                    onUninstall = { onUninstallClicked(extension) }
                )
            }
        }

        fun filter(query: String, currentList: List<AnimeExtension.Installed>) {
            val filteredList = ArrayList<AnimeExtension.Installed>()
            for (extension in currentList) {
                if (extension.name.lowercase(Locale.ROOT).contains(query.lowercase(Locale.ROOT))) {
                    filteredList.add(extension)
                }
            }
            if (filteredList != currentList)
                submitList(filteredList)
        }

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val extensionNameTextView: TextView = view.findViewById(R.id.extensionNameTextView)
            val extensionVersionTextView: TextView =
                view.findViewById(R.id.extensionVersionTextView)
            val settingsImageView: ImageView = view.findViewById(R.id.settingsImageView)
            val extensionIconImageView: ImageView = view.findViewById(R.id.extensionIconImageView)
            val deleteView: ImageView = view.findViewById(R.id.deleteTextView)
            val updateView: ImageView = view.findViewById(R.id.updateTextView)
        }

        companion object {
            val DIFF_CALLBACK_INSTALLED =
                object : DiffUtil.ItemCallback<AnimeExtension.Installed>() {
                    override fun areItemsTheSame(
                        oldItem: AnimeExtension.Installed,
                        newItem: AnimeExtension.Installed
                    ): Boolean {
                        return oldItem.pkgName == newItem.pkgName && oldItem.repository == newItem.repository
                    }

                    override fun areContentsTheSame(
                        oldItem: AnimeExtension.Installed,
                        newItem: AnimeExtension.Installed
                    ): Boolean {
                        return oldItem == newItem
                    }
                }
        }
    }

}
