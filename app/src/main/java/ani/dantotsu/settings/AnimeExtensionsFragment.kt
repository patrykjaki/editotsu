package ani.dantotsu.settings

import android.app.NotificationManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.paging.CombinedLoadStates
import androidx.recyclerview.widget.LinearLayoutManager
import ani.dantotsu.databinding.FragmentExtensionsBinding
import ani.dantotsu.media.MediaType
import ani.dantotsu.settings.paging.AnimeExtensionAdapter
import ani.dantotsu.settings.paging.AnimeExtensionsViewModel
import ani.dantotsu.settings.paging.AnimeExtensionsViewModelFactory
import ani.dantotsu.settings.paging.OnAnimeInstallClickListener
import eu.kanade.tachiyomi.extension.anime.AnimeExtensionManager
import eu.kanade.tachiyomi.extension.anime.model.AnimeExtension
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import rx.android.schedulers.AndroidSchedulers
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class AnimeExtensionsFragment : Fragment(),
    SearchQueryHandler, OnAnimeInstallClickListener {
    private var _binding: FragmentExtensionsBinding? = null
    private val binding get() = _binding!!

    private var searchQuery = ""
    private val loadStateListener: (CombinedLoadStates) -> Unit = { states ->
        _binding?.let {
            ExtensionEmptyState.onLoadStates(
                ExtensionEmptyState.Views(it.extensionsEmpty, it.extensionsEmptyText, it.extensionsEmptyAction),
                states, adapter.itemCount, MediaType.ANIME, searchQuery
            )
        }
    }

    private val viewModel: AnimeExtensionsViewModel by viewModels {
        AnimeExtensionsViewModelFactory(animeExtensionManager)
    }

    private val adapter by lazy {
        AnimeExtensionAdapter(this)
    }

    private val animeExtensionManager: AnimeExtensionManager = Injekt.get()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentExtensionsBinding.inflate(inflater, container, false)

        binding.allExtensionsRecyclerView.isNestedScrollingEnabled = false
        binding.allExtensionsRecyclerView.adapter = adapter
        binding.allExtensionsRecyclerView.layoutManager = SafeLinearLayoutManager(context)
        (binding.allExtensionsRecyclerView.layoutManager as LinearLayoutManager).isItemPrefetchEnabled =
            true

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.pagerFlow.collectLatest { pagingData ->
                adapter.submitData(pagingData)
            }
        }

        viewModel.invalidatePager() // Force a refresh of the pager

        ExtensionRefresh.bind(binding.extensionsSwipeRefresh, viewLifecycleOwner, MediaType.ANIME)
        adapter.addLoadStateListener(loadStateListener)
        return binding.root
    }

    override fun updateContentBasedOnQuery(query: String?) {
        searchQuery = query?.trim().orEmpty()
        viewModel.setSearchQuery(query ?: "")
    }

    override fun notifyDataChanged() {
        viewModel.invalidatePager()
    }

    override fun onInstallClick(pkg: AnimeExtension.Available) {
        val context = requireContext()
        if (isAdded) {
            val notificationManager =
                requireContext().getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val installerSteps = InstallerSteps(notificationManager, context)
            // Start the installation process
            animeExtensionManager.installExtension(pkg)
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                    { installStep -> installerSteps.onInstallStep(installStep) {} },
                    { error -> installerSteps.onError(error) {} },
                    { installerSteps.onComplete { viewModel.invalidatePager() } }
                )
        }
    }

    override fun onDestroyView() {
        adapter.removeLoadStateListener(loadStateListener)
        super.onDestroyView();_binding = null
    }


}
