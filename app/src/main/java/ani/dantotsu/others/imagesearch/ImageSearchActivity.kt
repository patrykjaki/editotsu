package ani.dantotsu.others.imagesearch

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.lifecycleScope
import ani.dantotsu.R
import ani.dantotsu.connections.anilist.Anilist
import ani.dantotsu.databinding.ActivityImageSearchBinding
import ani.dantotsu.initActivity
import ani.dantotsu.media.MediaDetailsActivity
import ani.dantotsu.navBarHeight
import ani.dantotsu.statusBarHeight
import ani.dantotsu.themes.ThemeManager
import ani.dantotsu.toast
import com.bumptech.glide.Glide
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ani.dantotsu.themes.followThemeStatusBarIcons

/**
 * Find the show, episode and moment a screenshot is from. One screen, four states: nothing picked
 * (a big pick card), searching (the picked image + a progress bar), matches, and nothing found or
 * failed (the pick card again, saying what happened).
 */
class ImageSearchActivity : AppCompatActivity() {
    private lateinit var binding: ActivityImageSearchBinding
    private val viewModel: ImageSearchViewModel by viewModels()

    private val imageSelectionLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            uri ?: return@registerForActivityResult
            viewModel.picked = uri
            showSearching(uri)
            lifecycleScope.launch(Dispatchers.IO) {
                val stream = runCatching { contentResolver.openInputStream(uri) }.getOrNull()
                if (stream == null) {
                    withContext(Dispatchers.Main) { showProblem(getString(R.string.error_loading_image), null) }
                    return@launch
                }
                stream.use { viewModel.analyzeImage(it) }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        initActivity(this)
        ThemeManager(this).applyTheme()
        binding = ActivityImageSearchBinding.inflate(layoutInflater)
        setContentView(binding.root)
        followThemeStatusBarIcons()

        binding.imageSearchBar.updateLayoutParams<ViewGroup.MarginLayoutParams> { topMargin = statusBarHeight }
        binding.imageSearchScroll.updatePadding(bottom = binding.imageSearchScroll.paddingBottom + navBarHeight)
        binding.uploadImage.updateLayoutParams<ViewGroup.MarginLayoutParams> { bottomMargin += navBarHeight }

        binding.imageSearchBack.setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        binding.imageSearchPick.setOnClickListener { pick() }
        binding.imageSearchPickButton.setOnClickListener { pick() }
        binding.uploadImage.setOnClickListener { pick() }
        // the pick-another button gets out of the way while reading matches
        binding.imageSearchScroll.setOnScrollChangeListener(NestedScrollView.OnScrollChangeListener { _, _, y, _, oldY ->
            if (y > oldY + 4) binding.uploadImage.shrink() else if (y < oldY - 4) binding.uploadImage.extend()
        })

        showEmpty()
        viewModel.searchResultLiveData.observe(this) { result -> result?.let { show(it) } }
    }

    private fun pick() = imageSelectionLauncher.launch("image/*")

    private fun showEmpty() {
        binding.imageSearchPick.isVisible = true
        binding.imageSearchPickIcon.setImageResource(R.drawable.ic_round_image_search_24)
        binding.imageSearchPickTitle.setText(R.string.image_search_pick_title)
        binding.imageSearchPickBody.setText(R.string.image_search_pick_body)
        binding.imageSearchPickButton.setText(R.string.image_search_pick)
        binding.imageSearchQuery.isVisible = false
        binding.imageSearchProgress.isVisible = false
        binding.uploadImage.isVisible = false
        binding.recyclerView.adapter = null
    }

    private fun showSearching(uri: Uri) {
        binding.imageSearchPick.isVisible = false
        binding.imageSearchQuery.isVisible = true
        Glide.with(this).load(uri).centerCrop().into(binding.imageSearchQueryImage)
        binding.imageSearchQueryState.setText(R.string.image_search_searching)
        binding.imageSearchProgress.isVisible = true
        binding.uploadImage.isVisible = false
        binding.recyclerView.adapter = null
    }

    /** Nothing found, or the search failed: the pick card again, with what happened. */
    private fun showProblem(title: String, body: String?) {
        binding.imageSearchProgress.isVisible = false
        binding.imageSearchQuery.isVisible = false
        binding.uploadImage.isVisible = false
        binding.recyclerView.adapter = null
        binding.imageSearchPick.isVisible = true
        binding.imageSearchPickIcon.setImageResource(R.drawable.ic_round_image_search_24)
        binding.imageSearchPickTitle.text = title
        binding.imageSearchPickBody.text = body ?: getString(R.string.image_search_none_body)
        binding.imageSearchPickButton.setText(R.string.image_search_change)
    }

    private fun show(result: ImageSearchViewModel.SearchResult) {
        if (result.error.isNullOrBlank() && result.result == null) return
        viewModel.picked?.let { if (!binding.imageSearchQuery.isVisible) showSearching(it) }
        if (!result.error.isNullOrBlank()) {
            showProblem(getString(R.string.image_search_failed), getString(R.string.image_search_failed_body))
            return
        }
        // adult titles only for accounts that show adult content
        // the service returns the same scene a second apart several times: keep the best hit per show + episode
        val matches = result.result.orEmpty()
            .filter { Anilist.adult || it.anilist?.isAdult != true }
            .groupBy { it.anilist?.id to it.episode }
            .map { (_, hits) -> hits.maxBy { it.similarity ?: 0.0 } }
        if (matches.isEmpty()) {
            showProblem(getString(R.string.image_search_none), null)
            return
        }
        binding.imageSearchProgress.isVisible = false
        binding.imageSearchQueryState.text =
            resources.getQuantityString(R.plurals.image_search_matches, matches.size, matches.size)
        binding.uploadImage.isVisible = true
        binding.uploadImage.extend()
        binding.recyclerView.adapter = ImageSearchResultAdapter(matches) { open(it) }
    }

    private fun open(match: ImageSearchViewModel.ImageResult) {
        val id = match.anilist?.id?.toInt()
        if (id == null) {
            toast(getString(R.string.no_anilist_id_found))
            return
        }
        lifecycleScope.launch(Dispatchers.IO) {
            val media = Anilist.query.getMedia(id, false) ?: return@launch
            withContext(Dispatchers.Main) {
                startActivity(Intent(this@ImageSearchActivity, MediaDetailsActivity::class.java).putExtra("media", media))
            }
        }
    }
}
