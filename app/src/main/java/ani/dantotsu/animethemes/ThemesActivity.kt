package ani.dantotsu.animethemes

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
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
import ani.dantotsu.media.MediaDetailsActivity
import ani.dantotsu.media.anime.ExoplayerView
import ani.dantotsu.navBarHeight
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.statusBarHeight
import ani.dantotsu.themes.ThemeManager
import ani.dantotsu.toast
import ani.dantotsu.util.StoragePermissions
import ani.dantotsu.util.customAlertDialog
import com.google.android.material.color.MaterialColors
import com.google.android.material.shape.MaterialShapeDrawable
import com.google.android.material.shape.ShapeAppearanceModel
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
    private lateinit var downloads: ThemeDownloads
    private val adapter = Adapter()
    private var favourites: List<FavouriteTheme> = emptyList()
    private var states: Map<String, ThemeDownloadState> = emptyMap()
    private var savedBytes = 0L
    private var status: String? = null
    private var shuffleJob: Job? = null
    private val poll = Handler(Looper.getMainLooper())
    private val pollTick = object : Runnable {
        override fun run() {
            refreshStates()
            if (states.values.any { it.status == ThemeDownloadState.Status.RUNNING }) poll.postDelayed(this, 1_500L)
        }
    }

    private val kinds get() = ThemeKinds.fromInt(PrefManager.getVal(PrefName.AnimeThemesRadioKinds))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager(this).applyTheme()
        binding = ActivityThemesBinding.inflate(layoutInflater)
        setContentView(binding.root)
        initActivity(this)
        downloads = ThemeDownloads(this)
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
        poll.removeCallbacks(pollTick)
        super.onPause()
    }

    /** Reads download states off the main thread, then redraws; keeps polling while any runs. */
    private fun refreshStates() {
        val current = favourites
        lifecycleScope.launch {
            val (map, bytes) = withContext(Dispatchers.IO) {
                val m = mutableMapOf<String, ThemeDownloadState>()
                current.forEach { f ->
                    ThemeFileKind.entries.forEach { k ->
                        runCatching { downloads.state(f.track, k) }.getOrNull()?.let { m[ThemeDownloadNames.key(f.track, k)] = it }
                    }
                }
                m to runCatching { downloads.totalBytes() }.getOrDefault(0L)
            }
            states = map
            savedBytes = bytes
            adapter.notifyDataSetChanged()
            poll.removeCallbacks(pollTick)
            if (map.values.any { it.status == ThemeDownloadState.Status.RUNNING }) poll.postDelayed(pollTick, 1_500L)
        }
    }

    private fun state(track: ThemeTrack, kind: ThemeFileKind) =
        states[ThemeDownloadNames.key(track, kind)] ?: ThemeDownloadState(ThemeDownloadState.Status.NONE)

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
        chooseKind { kind -> favourites.forEach { download(it.track, kind, quiet = true) }; refreshStates() }
    }

    private fun chooseKind(onKind: (ThemeFileKind) -> Unit) {
        customAlertDialog()
            .setTitle(R.string.themes_download_folder)
            .singleChoiceItems(arrayOf(getString(R.string.themes_download_audio), getString(R.string.themes_download_video))) { which ->
                onKind(if (which == 0) ThemeFileKind.AUDIO else ThemeFileKind.VIDEO)
            }
            .show()
    }

    private fun download(track: ThemeTrack, kind: ThemeFileKind, quiet: Boolean = false) {
        if (!StoragePermissions.downloadsPermission(this)) return
        val started = runCatching { downloads.enqueue(track, kind) }.getOrDefault(false)
        if (!started && !quiet) toast(getString(R.string.themes_download_failed))
        if (!quiet) refreshStates()
    }

    private fun showRowMenu(anchor: View, fav: FavouriteTheme) {
        val menu = PopupMenu(this, anchor)
        val audio = state(fav.track, ThemeFileKind.AUDIO).status
        val video = state(fav.track, ThemeFileKind.VIDEO).status
        if (fav.track.audioUrl != null && audio == ThemeDownloadState.Status.NONE || audio == ThemeDownloadState.Status.FAILED)
            menu.menu.add(0, 1, 0, R.string.themes_download_audio)
        if (video == ThemeDownloadState.Status.NONE || video == ThemeDownloadState.Status.FAILED)
            menu.menu.add(0, 2, 1, R.string.themes_download_video)
        if (audio == ThemeDownloadState.Status.SAVED || audio == ThemeDownloadState.Status.RUNNING ||
            video == ThemeDownloadState.Status.SAVED || video == ThemeDownloadState.Status.RUNNING
        ) menu.menu.add(0, 3, 2, R.string.themes_delete_download)
        if (fav.mediaId > 0) menu.menu.add(0, 4, 3, R.string.themes_open_show)
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> download(fav.track, ThemeFileKind.AUDIO)
                2 -> download(fav.track, ThemeFileKind.VIDEO)
                3 -> customAlertDialog()
                    .setTitle(R.string.themes_delete_download)
                    .setMessage(ThemeDownloadNames.fileName(fav.track, ThemeFileKind.VIDEO).substringBeforeLast('.'))
                    .setPosButton(R.string.themes_delete_download) {
                        ThemeFileKind.entries.forEach { runCatching { downloads.remove(fav.track, it) } }
                        refreshStates()
                    }
                    .setNegButton(getString(R.string.cancel))
                    .show()
                4 -> startActivity(Intent(this, MediaDetailsActivity::class.java).putExtra("mediaId", fav.mediaId))
            }
            true
        }
        menu.show()
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
            b.themesSaved.isVisible = savedBytes > 0
            b.themesSaved.text = getString(R.string.themes_saved_size, Formatter.formatShortFileSize(this@ThemesActivity, savedBytes))
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

            val audio = state(t, ThemeFileKind.AUDIO)
            val video = state(t, ThemeFileKind.VIDEO)
            val saved = when {
                audio.status == ThemeDownloadState.Status.SAVED && video.status == ThemeDownloadState.Status.SAVED -> R.string.themes_saved_both
                audio.status == ThemeDownloadState.Status.SAVED -> R.string.themes_saved_audio
                video.status == ThemeDownloadState.Status.SAVED -> R.string.themes_saved_video
                else -> null
            }
            val running = listOf(audio, video).firstOrNull { it.status == ThemeDownloadState.Status.RUNNING }
            val failed = listOf(audio, video).any { it.status == ThemeDownloadState.Status.FAILED }
            b.favSaved.isVisible = saved != null || running != null || failed
            b.favSaved.text = when {
                running != null -> getString(R.string.themes_downloading, running.percent)
                saved != null -> getString(saved)
                else -> getString(R.string.themes_download_failed)
            }
            b.favProgress.isVisible = running != null
            if (running != null) {
                b.favProgress.isIndeterminate = running.percent == 0
                b.favProgress.setProgressCompat(running.percent, true)
            }
            b.favDownload.setImageResource(if (saved != null) R.drawable.ic_circle_check else R.drawable.ic_download_24)
            b.favDownload.imageTintList = ColorStateList.valueOf(MaterialColors.getColor(b.root,
                if (saved != null) androidx.appcompat.R.attr.colorPrimary else com.google.android.material.R.attr.colorOnSurfaceVariant))
            val dl = getString(if (saved != null) R.string.themes_more else R.string.themes_download_video)
            b.favDownload.contentDescription = dl
            b.favDownload.tooltipText = dl
            b.favDownload.setOnClickListener { showRowMenu(it, fav) }

            b.favHeart.setOnClickListener { ThemeFavourites.remove(t.themeId) }
            b.root.contentDescription = listOf(b.favTag.text, b.favSong.text, b.favMeta.text, b.favSaved.text.takeIf { b.favSaved.isVisible })
                .filterNotNull().joinToString(", ")
            b.root.setOnClickListener {
                ThemeRadioSession.stop()
                val queue = ThemeRadio.favouritesQueue(favourites, ThemeKinds.BOTH, false, Random.Default)
                ExoplayerView.openThemes(this@ThemesActivity, queue, queue.indexOfFirst { it.themeId == t.themeId }.coerceAtLeast(0), getString(R.string.themes_favourites))
            }
            b.root.setOnLongClickListener { showRowMenu(b.favDownload, fav); true }
            shape(b.root, i == 0, i == favourites.lastIndex)
        }
    }

    private fun shape(view: View, first: Boolean, last: Boolean) {
        val d = resources.displayMetrics.density
        val big = 20 * d
        val small = 4 * d
        val model = ShapeAppearanceModel.builder()
            .setTopLeftCornerSize(if (first) big else small).setTopRightCornerSize(if (first) big else small)
            .setBottomLeftCornerSize(if (last) big else small).setBottomRightCornerSize(if (last) big else small)
            .build()
        view.background = android.graphics.drawable.RippleDrawable(
            ColorStateList.valueOf(MaterialColors.getColor(view, androidx.appcompat.R.attr.colorControlHighlight)),
            MaterialShapeDrawable(model).apply {
                fillColor = ColorStateList.valueOf(MaterialColors.getColor(view, com.google.android.material.R.attr.colorSurfaceVariant))
            },
            MaterialShapeDrawable(model),
        )
        view.clipToOutline = true
    }
}
