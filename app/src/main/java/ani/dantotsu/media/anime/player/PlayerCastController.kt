package ani.dantotsu.media.anime.player

import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import ani.dantotsu.media.Media
import ani.dantotsu.media.anime.CustomCastButton
import ani.dantotsu.parsers.Subtitle
import ani.dantotsu.parsers.Video

/**
 * What the player needs from casting. The google build casts through
 * play-services-cast ([PlayerCastFactory] in src/google); the fdroid build has
 * no cast and hides the button.
 */
@UnstableApi
interface PlayerCastController {
    var castScreenView: CastScreenView?
    var onSessionStartedListener: ((deviceName: String?, owner: PlaybackEngine?) -> Unit)?
    var onSessionEndedListener: ((owner: PlaybackEngine?, resumePositionMs: Long, shouldPlay: Boolean) -> Unit)?
    val currentPositionMs: Long

    fun isCasting(): Boolean
    fun isRemotePlaying(): Boolean
    fun pause()
    fun play()
    fun togglePlayPause()
    fun seekTo(positionMs: Long)
    fun setRemoteVolume(volumeFraction: Float)
    fun disconnect()

    fun setupCastButton(
        castButton: CustomCastButton,
        media: Media?,
        video: Video?,
        subtitle: Subtitle?,
        hasExtSubtitles: Boolean,
        episodeTitle: String?
    )

    /** @return true when the item went to the TV; the caller must not start local playback. */
    fun updateCurrentMedia(
        mediaItem: MediaItem?,
        engine: PlaybackEngine?,
        video: Video? = null,
        coordinator: PlaybackCoordinator? = null,
        startPositionMs: Long = 0L,
    ): Boolean

    fun release()
}
