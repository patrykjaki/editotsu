package ani.dantotsu.media.anime.player

import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import ani.dantotsu.media.Media
import ani.dantotsu.media.anime.CustomCastButton
import ani.dantotsu.parsers.Subtitle
import ani.dantotsu.parsers.Video

/** The fdroid build has no cast: the button stays hidden and nothing plays remotely. */
@UnstableApi
object PlayerCastFactory {
    fun create(
        activity: AppCompatActivity,
        playerView: View,
        onCastStateChanged: (isPlaying: Boolean) -> Unit,
    ): PlayerCastController = NoCast()
}

@UnstableApi
private class NoCast : PlayerCastController {
    override var castScreenView: CastScreenView? = null
    override var onSessionStartedListener: ((String?, PlaybackEngine?) -> Unit)? = null
    override var onSessionEndedListener: ((PlaybackEngine?, Long, Boolean) -> Unit)? = null
    override val currentPositionMs: Long = 0L

    override fun isCasting(): Boolean = false
    override fun isRemotePlaying(): Boolean = false
    override fun pause() {}
    override fun play() {}
    override fun togglePlayPause() {}
    override fun seekTo(positionMs: Long) {}
    override fun setRemoteVolume(volumeFraction: Float) {}
    override fun disconnect() {}

    override fun setupCastButton(
        castButton: CustomCastButton,
        media: Media?,
        video: Video?,
        subtitle: Subtitle?,
        hasExtSubtitles: Boolean,
        episodeTitle: String?
    ) {
        castButton.visibility = View.GONE
    }

    override fun updateCurrentMedia(
        mediaItem: MediaItem?,
        engine: PlaybackEngine?,
        video: Video?,
        coordinator: PlaybackCoordinator?,
        startPositionMs: Long,
    ): Boolean = false

    override fun release() {
        castScreenView = null
    }
}
