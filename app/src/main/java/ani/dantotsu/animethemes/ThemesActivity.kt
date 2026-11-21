package ani.dantotsu.animethemes

import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.format.Formatter
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ani.dantotsu.R
import ani.dantotsu.connections.anilist.Anilist
import ani.dantotsu.databinding.ActivityThemesBinding
import ani.dantotsu.databinding.ItemThemeFavouriteBinding
import ani.dantotsu.databinding.ItemThemesHeaderBinding
import ani.dantotsu.initActivity
import ani.dantotsu.media.anime.ExoplayerView
import ani.dantotsu.navBarHeight
import ani.dantotsu.settings.rows.markSegment
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.statusBarHeight
import ani.dantotsu.themes.ThemeManager
import ani.dantotsu.themes.followThemeStatusBarIcons
import ani.dantotsu.toast
import com.google.android.material.color.MaterialColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.random.Random

/**
 * Openings & endings screen (opened from the profile menu): shuffle openings/endings from
 * completed shows, filter favourites by All / OP / ED so what is shown is what plays,
 * play or shuffle favourites, and save favourites to Downloads/Editotsu/AnimeThemes.
 */
class ThemesActivity : AppCompatActivity() {
    private lateinit var binding: ActivityThemesBinding
    private lateinit var controls: ThemeDownloadControls
    private val adapter = Adapter()
    private var favourites: List<FavouriteTheme> = emptyList()
    private var status: String? = null
    private var shuffleLoading = false
    private var shuffleJob: Job? = null
    private val kinds get() = ThemeKinds.fromInt(PrefManager.getVal(PrefName.AnimeThemesRadioKinds))
    private val visibleFavourites: List<FavouriteTheme>
        get() = ThemeRadio.filterFavourites(favourites, kinds)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager(this).applyTheme()
        binding = ActivityThemesBinding.inflate(layoutInflater)
        setContentView(binding.root)
        followThemeStatusBarIcons()
        initActivity(this)
        controls = ThemeDownloadControls(this) { adapter.notifyDataSetChanged() }
        binding.themesList.updatePadding(top = statusBarHeight, bottom = navBarHeight + binding.themesList.paddingBottom)
        binding.themesList.layoutManager = LinearLayoutManager(this)
        binding.themesList.adapter = adapter
        lifecycleScope.launch {
            ThemeFavourites.flow.collect { list ->
                favourites = list.orEmpty()
                adapter.notifyDataSetChanged()
                refreshStates()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStates()
    }

    override fun onPause() {
        controls.stop()
        super.onPause()
    }

    private fun refreshStates() = controls.refresh(favourites.map { it.track })

    private fun setStatus(text: String?, loading: Boolean = false) {
        status = text
        shuffleLoading = loading
        adapter.notifyItemChanged(0)
    }

    private fun emptyFavouritesMessage(): String = when {
        favourites.isEmpty() -> getString(R.string.themes_no_favourites)
        kinds == ThemeKinds.OPENINGS -> getString(R.string.themes_no_favourite_openings)
        kinds == ThemeKinds.ENDINGS -> getString(R.string.themes_no_favourite_endings)
        else -> getString(R.string.themes_no_favourites)
    }

    // ---- actions ----

    private fun playFavourites(shuffle: Boolean) {
        ThemeRadioSession.stop()
        val queue = ThemeRadio.favouritesQueue(favourites, kinds, shuffle, Random(System.nanoTime()))
        if (queue.isEmpty()) return setStatus(emptyFavouritesMessage())
        setStatus(null)
        ExoplayerView.openThemes(this, queue, 0, getString(R.string.themes_favourites))
    }

    private fun shuffleCompleted() {
        val userId = Anilist.userid
        if (Anilist.token == null || userId == null) return setStatus(getString(R.string.themes_login_needed))
        shuffleJob?.cancel()
        setStatus(getString(R.string.themes_finding), loading = true)
        shuffleJob = lifecycleScope.launch {
            // null = AniList didn't return the list (rate limit, timeout): say so instead of
            // claiming there are no completed shows
            val lists = runCatching {
                withContext(Dispatchers.IO) { Anilist.query.getMediaListsOrNull(anime = true, userId = userId) }
            }.getOrNull() ?: return@launch setStatus(getString(R.string.themes_list_failed))
            val shows = lists["All"].orEmpty()
                .filter { it.userStatus == "COMPLETED" }
                .map { RadioShow(it.id, it.idMAL, it.userPreferredName) }
            if (shows.isEmpty()) return@launch setStatus(getString(R.string.themes_no_completed))
            ThemeRadioSession.start(shows, kinds, MediaThemesFragment.themePrefs())
            val first = ThemeRadioSession.next()
            if (first == null) return@launch setStatus(getString(R.string.themes_none_found))
            setStatus(null)
            ExoplayerView.openThemes(this@ThemesActivity, listOf(first), 0, first.showTitle, radio = true)
        }
    }

    private fun downloadAll() {
        val shown = visibleFavourites
        if (shown.isEmpty()) return setStatus(emptyFavouritesMessage())
        controls.chooseKind { kind ->
            val tracks = shown.map { it.track }
            tracks.forEach { controls.download(it, kind, quiet = true) }
            refreshStates()
            val missing = ThemeDownloadNames.missingFiles(tracks, kind)
            if (missing > 0) toast(getString(R.string.themes_download_all_no_audio, missing, tracks.size))
        }
    }

    // ---- list ----

    private inner class Adapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = 1 + visibleFavourites.size
        override fun getItemViewType(position: Int) = if (position == 0) 0 else 1

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return if (viewType == 0) Header(ItemThemesHeaderBinding.inflate(inflater, parent, false))
            else Row(ItemThemeFavouriteBinding.inflate(inflater, parent, false))
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (holder) {
                is Header -> holder.bind()
                is Row -> holder.bind(position - 1)
            }
        }
    }

    private inner class Header(val b: ItemThemesHeaderBinding) : RecyclerView.ViewHolder(b.root) {
        init {
            b.themesBack.setOnClickListener { onBackPressedDispatcher.onBackPressed() }
            ThemeAudioToggle.bind(b.themesAudioOnly)
            b.actionShuffleCompleted.setOnClickListener { shuffleCompleted() }
            b.actionPlayFavourites.setOnClickListener { playFavourites(false) }
            b.actionShuffleFavourites.setOnClickListener { playFavourites(true) }
            b.actionDownloadAll.setOnClickListener { downloadAll() }
            b.themesEmptyShowAll.setOnClickListener { b.themesKinds.check(R.id.kindBoth) }
            b.themesKinds.check(
                when (kinds) {
                    ThemeKinds.OPENINGS -> R.id.kindOpenings
                    ThemeKinds.ENDINGS -> R.id.kindEndings
                    ThemeKinds.BOTH -> R.id.kindBoth
                }
            )
            listOf(b.kindBoth, b.kindOpenings, b.kindEndings).forEach { markSegment(it, null, it.isChecked) }
            b.themesKinds.addOnButtonCheckedListener { group, id, checked ->
                markSegment(group.findViewById(id), null, checked)
                if (!checked) return@addOnButtonCheckedListener
                val k = when (id) {
                    R.id.kindOpenings -> ThemeKinds.OPENINGS
                    R.id.kindEndings -> ThemeKinds.ENDINGS
                    else -> ThemeKinds.BOTH
                }
                if (k != kinds) {
                    PrefManager.setVal(PrefName.AnimeThemesRadioKinds, k.value)
                    shuffleJob?.cancel()
                    shuffleLoading = false
                    status = null
                    binding.themesList.post { adapter.notifyDataSetChanged() }
                }
            }
        }

        fun bind() {
            val shown = visibleFavourites
            ThemeAudioToggle.show(b.themesAudioOnly)
            b.themesSaved.isVisible = controls.savedBytes > 0
            b.themesSaved.text = getString(R.string.themes_saved_size, Formatter.formatShortFileSize(this@ThemesActivity, controls.savedBytes))
            b.themesStatusRow.isVisible = status != null
            b.themesStatusProgress.isVisible = shuffleLoading
            b.themesStatus.text = status

            val hasShown = shown.isNotEmpty()
            b.themesEmpty.isVisible = !hasShown
            if (!hasShown) {
                val filteredOut = favourites.isNotEmpty()
                b.themesEmptyTitle.setText(
                    when {
                        !filteredOut -> R.string.themes_no_favourites_title
                        kinds == ThemeKinds.OPENINGS -> R.string.themes_no_favourite_openings
                        kinds == ThemeKinds.ENDINGS -> R.string.themes_no_favourite_endings
                        else -> R.string.themes_no_favourites_title
                    }
                )
                b.themesEmptyBody.setText(
                    if (filteredOut) R.string.themes_no_favourites_filtered_body
                    else R.string.themes_no_favourites_body
                )
                b.themesEmptyShowAll.isVisible = filteredOut
            }

            // Hide play, shuffle and download actions when no matching favourites are shown.
            listOf(b.actionPlayFavourites, b.actionShuffleFavourites, b.actionDownloadAll).forEach { button ->
                button.isVisible = hasShown
            }

            // The count trails the heading in the quieter colour, not glued on with a dot.
            b.themesFavouritesLabel.text = if (shown.isEmpty()) getString(R.string.themes_favourites)
            else getString(R.string.themes_favourites).let { name ->
                SpannableString("$name  ${shown.size}").apply {
                    setSpan(
                        ForegroundColorSpan(
                            MaterialColors.getColor(b.themesFavouritesLabel, com.google.android.material.R.attr.colorOnSurfaceVariant)
                        ),
                        name.length, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                }
            }
        }
    }

    private inner class Row(val b: ItemThemeFavouriteBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(i: Int) {
            val shown = visibleFavourites
            val fav = shown[i]
            val t = fav.track
            b.favTag.text = if (t.versionCount > 1) "${t.tag} v${t.version}" else t.tag
            b.favSong.text = t.songTitle.ifBlank { t.tag }
            b.favMeta.text = listOf(fav.animeTitle, t.artist).filter { it.isNotBlank() }.joinToString(" · ")

            val saved = controls.savedLabel(t)
            val running = controls.running(t)
            val failed = controls.failed(t)
            b.favSaved.isVisible = saved != null || running != null || failed
            b.favSaved.text = when {
                running != null -> getString(R.string.themes_downloading, running.percent)
                saved != null -> getString(saved)
                else -> getString(R.string.themes_download_failed)
            }
            b.favSaved.setTextColor(
                MaterialColors.getColor(
                    b.favSaved,
                    when {
                        running != null -> com.google.android.material.R.attr.colorOnSurfaceVariant
                        saved != null -> androidx.appcompat.R.attr.colorPrimary
                        else -> androidx.appcompat.R.attr.colorError
                    },
                )
            )
            controls.bind(b.favDownload, b.favProgress, t, fav.mediaId)

            b.favHeart.setOnClickListener { ThemeFavourites.remove(t.themeId) }
            b.root.contentDescription = listOf(b.favTag.text, b.favSong.text, b.favMeta.text, b.favSaved.text.takeIf { b.favSaved.isVisible })
                .filterNotNull().joinToString(", ")
            b.favPlay.setOnClickListener { b.root.performClick() }
            b.root.setOnClickListener {
                ThemeRadioSession.stop()
                val queue = ThemeRadio.favouritesQueue(favourites, kinds, false, Random.Default)
                ExoplayerView.openThemes(this@ThemesActivity, queue, queue.indexOfFirst { it.themeId == t.themeId }.coerceAtLeast(0), getString(R.string.themes_favourites))
            }
            b.root.setOnLongClickListener { controls.showMenu(b.favDownload, t, fav.mediaId); true }
            ThemeRowShape.apply(b.root, i == 0, i == shown.lastIndex)
        }
    }
}
