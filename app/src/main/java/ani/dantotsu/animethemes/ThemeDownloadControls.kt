package ani.dantotsu.animethemes

import android.content.Intent
import android.content.res.ColorStateList
import android.view.View
import android.widget.ImageButton
import android.widget.PopupMenu
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.core.widget.ImageViewCompat
import androidx.lifecycle.lifecycleScope
import ani.dantotsu.R
import ani.dantotsu.media.MediaDetailsActivity
import ani.dantotsu.toast
import ani.dantotsu.util.StoragePermissions
import ani.dantotsu.util.customAlertDialog
import com.google.android.material.color.MaterialColors
import com.google.android.material.progressindicator.CircularProgressIndicator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Download button of a theme row, shared by the Themes tab and the Themes screen:
 * reads the download states off the main thread, polls while any download runs, and
 * opens the row menu (download audio or video, delete the saved files, open the show).
 */
class ThemeDownloadControls(
    private val activity: AppCompatActivity,
    private val onChanged: () -> Unit,
) {
    val downloads = ThemeDownloads(activity)
    private var states: Map<String, ThemeDownloadState> = emptyMap()
    private var tracks: List<ThemeTrack> = emptyList()
    private var job: Job? = null

    /** Bytes of every saved theme, read with the last [refresh]. */
    var savedBytes = 0L
        private set

    /** Reads the states of [tracks], calls [onChanged], and polls again while any runs. */
    fun refresh(tracks: List<ThemeTrack> = this.tracks) {
        this.tracks = tracks
        job?.cancel()
        job = activity.lifecycleScope.launch {
            while (true) {
                val current = this@ThemeDownloadControls.tracks
                val (map, bytes) = withContext(Dispatchers.IO) {
                    val m = mutableMapOf<String, ThemeDownloadState>()
                    current.forEach { t ->
                        ThemeFileKind.entries.forEach { k ->
                            runCatching { downloads.state(t, k) }.getOrNull()?.let { m[ThemeDownloadNames.key(t, k)] = it }
                        }
                    }
                    m to runCatching { downloads.totalBytes() }.getOrDefault(0L)
                }
                states = map
                savedBytes = bytes
                onChanged()
                if (map.values.none { it.status == ThemeDownloadState.Status.RUNNING }) break
                delay(POLL_MS)
            }
        }
    }

    fun stop() {
        job?.cancel()
    }

    fun state(track: ThemeTrack, kind: ThemeFileKind): ThemeDownloadState =
        states[ThemeDownloadNames.key(track, kind)] ?: ThemeDownloadState(ThemeDownloadState.Status.NONE)

    /** "Audio saved" / "Video saved" / both, or null when nothing is saved. */
    fun savedLabel(track: ThemeTrack): Int? {
        val audio = state(track, ThemeFileKind.AUDIO).status == ThemeDownloadState.Status.SAVED
        val video = state(track, ThemeFileKind.VIDEO).status == ThemeDownloadState.Status.SAVED
        return when {
            audio && video -> R.string.themes_saved_both
            audio -> R.string.themes_saved_audio
            video -> R.string.themes_saved_video
            else -> null
        }
    }

    fun running(track: ThemeTrack): ThemeDownloadState? =
        ThemeFileKind.entries.map { state(track, it) }.firstOrNull { it.status == ThemeDownloadState.Status.RUNNING }

    fun failed(track: ThemeTrack): Boolean =
        ThemeFileKind.entries.any { state(track, it).status == ThemeDownloadState.Status.FAILED }

    /** Icon (download, or a check when saved), progress ring while it runs, menu on tap. */
    fun bind(button: ImageButton, progress: CircularProgressIndicator, track: ThemeTrack, mediaId: Int) {
        val saved = savedLabel(track) != null
        val running = running(track)
        progress.isVisible = running != null
        if (running != null) {
            progress.isIndeterminate = running.percent == 0
            progress.setProgressCompat(running.percent, true)
        }
        button.setImageResource(if (saved) R.drawable.ic_circle_check else R.drawable.ic_download_24)
        ImageViewCompat.setImageTintList(button, ColorStateList.valueOf(MaterialColors.getColor(button,
            if (saved) androidx.appcompat.R.attr.colorPrimary else com.google.android.material.R.attr.colorOnSurfaceVariant)))
        val label = activity.getString(
            when {
                running != null -> R.string.theme_downloading_more
                saved -> R.string.themes_more
                else -> R.string.theme_download
            }
        )
        button.contentDescription = label
        button.tooltipText = label
        button.setOnClickListener { showMenu(it, track, mediaId) }
    }

    fun download(track: ThemeTrack, kind: ThemeFileKind, quiet: Boolean = false) {
        if (!StoragePermissions.downloadsPermission(activity)) return
        val started = runCatching { downloads.enqueue(track, kind) }.getOrDefault(false)
        if (!started && !quiet) toast(activity.getString(R.string.themes_download_failed))
        if (!quiet) refresh(tracks.takeIf { track in it } ?: (tracks + track))
    }

    fun chooseKind(onKind: (ThemeFileKind) -> Unit) {
        activity.customAlertDialog()
            .setTitle(R.string.themes_download_folder)
            .singleChoiceItems(arrayOf(activity.getString(R.string.themes_download_audio), activity.getString(R.string.themes_download_video))) { which ->
                onKind(if (which == 0) ThemeFileKind.AUDIO else ThemeFileKind.VIDEO)
            }
            .show()
    }

    /** [mediaId] > 0 adds "Open show" (the Themes screen; the tab is already on the show). */
    fun showMenu(anchor: View, track: ThemeTrack, mediaId: Int) {
        val menu = PopupMenu(activity, anchor)
        val audio = state(track, ThemeFileKind.AUDIO).status
        val video = state(track, ThemeFileKind.VIDEO).status
        val canStart = { s: ThemeDownloadState.Status -> s == ThemeDownloadState.Status.NONE || s == ThemeDownloadState.Status.FAILED }
        val has = { s: ThemeDownloadState.Status -> s == ThemeDownloadState.Status.SAVED || s == ThemeDownloadState.Status.RUNNING }
        if (track.audioUrl != null && canStart(audio)) menu.menu.add(0, 1, 0, R.string.themes_download_audio)
        if (canStart(video)) menu.menu.add(0, 2, 1, R.string.themes_download_video)
        if (has(audio) || has(video)) menu.menu.add(0, 3, 2, R.string.themes_delete_download)
        if (mediaId > 0) menu.menu.add(0, 4, 3, R.string.themes_open_show)
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> download(track, ThemeFileKind.AUDIO)
                2 -> download(track, ThemeFileKind.VIDEO)
                3 -> activity.customAlertDialog()
                    .setTitle(R.string.themes_delete_download)
                    .setMessage(ThemeDownloadNames.fileName(track, ThemeFileKind.VIDEO).substringBeforeLast('.'))
                    .setPosButton(R.string.themes_delete_download) {
                        ThemeFileKind.entries.forEach { runCatching { downloads.remove(track, it) } }
                        refresh()
                    }
                    .setNegButton(activity.getString(R.string.cancel))
                    .show()
                4 -> activity.startActivity(Intent(activity, MediaDetailsActivity::class.java).putExtra("mediaId", mediaId))
            }
            true
        }
        menu.show()
    }

    private companion object {
        const val POLL_MS = 1_500L
    }
}
