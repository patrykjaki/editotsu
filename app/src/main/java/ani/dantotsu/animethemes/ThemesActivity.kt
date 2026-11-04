package ani.dantotsu.animethemes

import android.os.Bundle
import android.text.format.Formatter
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
import ani.dantotsu.databinding.ItemThemesActionBinding
import ani.dantotsu.databinding.ItemThemesHeaderBinding
import ani.dantotsu.initActivity
import ani.dantotsu.media.anime.ExoplayerView
import ani.dantotsu.navBarHeight
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.statusBarHeight
import ani.dantotsu.themes.ThemeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.random.Random

/**
 * Themes screen, opened from the anime home top bar: shuffle openings/endings from the
 * user's completed shows, play or shuffle the favourite themes, and save favourites to
 * Downloads/Editotsu/AnimeThemes (audio or video).
 */
class ThemesActivity : AppCompatActivity() {
    private lateinit var binding: ActivityThemesBinding
    private lateinit var controls: ThemeDownloadControls
    private val adapter = Adapter()
    private var favourites: List<FavouriteTheme> = emptyList()
    private var status: String? = null
    private var shuffleJob: Job? = null
    private val kinds get() = ThemeKinds.fromInt(PrefManager.getVal(PrefName.AnimeThemesRadioKinds))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager(this).applyTheme()
        binding = ActivityThemesBinding.inflate(layoutInflater)
        setContentView(binding.root)
        initActivity(this)
        controls = ThemeDownloadControls(this) { adapter.notifyDataSetChanged() }
        binding.themesList.updatePadding(top = statusBarHeight, bottom = navBarHeight + binding.themesList.paddingBottom)
        binding.themesList.layoutManager = LinearLayoutManager(this)
        binding.themesList.adapter = adapter
        lifecycleScope.launch {
            ThemeFavourites.flow.collect { list ->
                favourites = list.orEmpty()
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

    private fun setStatus(text: String?) {
        status = text
        adapter.notifyItemChanged(0)
    }

    // ---- actions ----

    private fun playFavourites(shuffle: Boolean) {
        ThemeRadioSession.stop()
        val queue = ThemeRadio.favouritesQueue(favourites, kinds, shuffle, Random(System.nanoTime()))
        if (queue.isEmpty()) return setStatus(getString(R.string.themes_no_favourites))
        setStatus(null)
        ExoplayerView.openThemes(this, queue, 0, getString(R.string.themes_favourites))
    }

    private fun shuffleCompleted() {
        val userId = Anilist.userid
        if (Anilist.token == null || userId == null) return setStatus(getString(R.string.themes_login_needed))
        shuffleJob?.cancel()
        setStatus(getString(R.string.themes_finding))
        shuffleJob = lifecycleScope.launch {
            val shows = runCatching {
                withContext(Dispatchers.IO) { Anilist.query.getMediaLists(anime = true, userId = userId) }["All"].orEmpty()
                    .filter { it.userStatus == "COMPLETED" }
                    .map { RadioShow(it.id, it.idMAL, it.userPreferredName) }
            }.getOrDefault(emptyList())
            if (shows.isEmpty()) return@launch setStatus(getString(R.string.themes_no_completed))
            ThemeRadioSession.start(shows, kinds, MediaThemesFragment.themePrefs())
            val first = ThemeRadioSession.next()
            if (first == null) return@launch setStatus(getString(R.string.themes_none_found))
            setStatus(null)
            ExoplayerView.openThemes(this@ThemesActivity, listOf(first), 0, first.showTitle, radio = true)
        }
    }

    private fun downloadAll() {
        if (favourites.isEmpty()) return setStatus(getString(R.string.themes_no_favourites))
        controls.chooseKind { kind -> favourites.forEach { controls.download(it.track, kind, quiet = true) }; refreshStates() }
    }

    // ---- list ----

    private inner class Adapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = 1 + favourites.size
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
            action(b.actionShuffleCompleted, R.drawable.ic_shuffle_24, R.string.themes_shuffle_completed) { shuffleCompleted() }
            action(b.actionPlayFavourites, R.drawable.ic_round_playlist_play_24, R.string.themes_play_favourites) { playFavourites(false) }
            action(b.actionShuffleFavourites, R.drawable.ic_round_favorite_24, R.string.themes_shuffle_favourites) { playFavourites(true) }
            action(b.actionDownloadAll, R.drawable.ic_download_24, R.string.themes_download_all) { downloadAll() }
            b.themesKinds.check(
                when (kinds) {
                    ThemeKinds.OPENINGS -> R.id.kindOpenings
                    ThemeKinds.ENDINGS -> R.id.kindEndings
                    ThemeKinds.BOTH -> R.id.kindBoth
                }
            )
            b.themesKinds.addOnButtonCheckedListener { _, id, checked ->
                if (!checked) return@addOnButtonCheckedListener
                val k = when (id) {
                    R.id.kindOpenings -> ThemeKinds.OPENINGS
                    R.id.kindEndings -> ThemeKinds.ENDINGS
                    else -> ThemeKinds.BOTH
                }
                PrefManager.setVal(PrefName.AnimeThemesRadioKinds, k.value)
            }
        }

        private fun action(a: ItemThemesActionBinding, icon: Int, label: Int, onClick: () -> Unit) {
            a.actionIcon.setImageResource(icon)
            a.actionLabel.setText(label)
            a.root.contentDescription = getString(label)
            a.root.isClickable = true
            a.root.isFocusable = true
            a.actionIcon.setOnClickListener { onClick() }
            a.root.setOnClickListener { onClick() }
        }

        fun bind() {
            ThemeAudioToggle.show(b.themesAudioOnly)
            b.themesSaved.isVisible = controls.savedBytes > 0
            b.themesSaved.text = getString(R.string.themes_saved_size, Formatter.formatShortFileSize(this@ThemesActivity, controls.savedBytes))
            b.themesStatus.isVisible = status != null
            b.themesStatus.text = status
            b.themesEmpty.isVisible = favourites.isEmpty()
            b.themesFavouritesLabel.text = if (favourites.isEmpty()) getString(R.string.themes_favourites)
            else "${getString(R.string.themes_favourites)} · ${favourites.size}"
        }
    }

    private inner class Row(val b: ItemThemeFavouriteBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(i: Int) {
            val fav = favourites[i]
            val t = fav.track
            b.favTag.text = if (t.versionCount > 1) "${t.tag} v${t.version}" else t.tag
            b.favSong.text = t.songTitle.ifBlank { t.tag }
            b.favMeta.text = listOf(fav.animeTitle, t.artist).filter { it.isNotBlank() }.joinToString(" · ")

            val saved = controls.savedLabel(t)
            val running = controls.running(t)
            b.favSaved.isVisible = saved != null || running != null || controls.failed(t)
            b.favSaved.text = when {
                running != null -> getString(R.string.themes_downloading, running.percent)
                saved != null -> getString(saved)
                else -> getString(R.string.themes_download_failed)
            }
            controls.bind(b.favDownload, b.favProgress, t, fav.mediaId)

            b.favHeart.setOnClickListener { ThemeFavourites.remove(t.themeId) }
            b.root.contentDescription = listOf(b.favTag.text, b.favSong.text, b.favMeta.text, b.favSaved.text.takeIf { b.favSaved.isVisible })
                .filterNotNull().joinToString(", ")
            b.root.setOnClickListener {
                ThemeRadioSession.stop()
                val queue = ThemeRadio.favouritesQueue(favourites, ThemeKinds.BOTH, false, Random.Default)
                ExoplayerView.openThemes(this@ThemesActivity, queue, queue.indexOfFirst { it.themeId == t.themeId }.coerceAtLeast(0), getString(R.string.themes_favourites))
            }
            b.root.setOnLongClickListener { controls.showMenu(b.favDownload, t, fav.mediaId); true }
            ThemeRowShape.apply(b.root, i == 0, i == favourites.lastIndex)
        }
    }
}
