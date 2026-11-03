package ani.dantotsu.media.anime.player

import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Player.Commands
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import androidx.core.net.toUri
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/**
 * A list the session shows as its playlist (theme mode: the openings and endings).
 * Previous/next on the notification and lock screen call [skipTo].
 */
interface MediaQueue {
    val titles: List<String>
    val currentIndex: Int
    fun skipTo(index: Int)
}

@UnstableApi
class PlaybackEngineMedia3Player(
    private val engine: PlaybackEngine,
    private val coordinator: PlaybackCoordinator? = null,
    looper: Looper = Looper.getMainLooper(),
    private val queue: MediaQueue? = null,
) : SimpleBasePlayer(looper), PlaybackListener {

    private val availableCommands = Commands.Builder()
        .add(Player.COMMAND_PLAY_PAUSE)
        .add(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
        .add(Player.COMMAND_GET_CURRENT_MEDIA_ITEM)
        .add(Player.COMMAND_GET_METADATA)
        .add(Player.COMMAND_SET_SPEED_AND_PITCH)
        .add(Player.COMMAND_STOP)
        .apply {
            if (queue != null) {
                add(Player.COMMAND_GET_TIMELINE)
                add(Player.COMMAND_SEEK_TO_MEDIA_ITEM)
                add(Player.COMMAND_SEEK_TO_PREVIOUS)
                add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                add(Player.COMMAND_SEEK_TO_NEXT)
                add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
            }
        }
        .build()

    private var lastPositionInvalidateTime = 0L

    init {
        engine.addListener(this)
    }

    override fun getState(): State {
        val request = engine.currentRequest
        val playerState = if (request == null) {
            Player.STATE_IDLE
        } else {
            when (engine.playbackState) {
                is PlaybackState.Idle -> Player.STATE_IDLE
                is PlaybackState.Buffering -> Player.STATE_BUFFERING
                is PlaybackState.Ready -> Player.STATE_READY
                is PlaybackState.Ended -> Player.STATE_ENDED
                is PlaybackState.Error -> Player.STATE_IDLE
            }
        }

        val mediaItem = if (request != null) {
            val metaBuilder = MediaMetadata.Builder()
                .setTitle(request.title)
            if (!request.seriesTitle.isNullOrBlank()) {
                metaBuilder.setArtist(request.seriesTitle)
                metaBuilder.setAlbumTitle(request.seriesTitle)
            }
            if (!request.coverUrl.isNullOrBlank()) {
                try {
                    metaBuilder.setArtworkUri(request.coverUrl.toUri())
                } catch (_: Exception) {}
            }
            if (!request.episodeNumber.isNullOrBlank()) {
                metaBuilder.setDisplayTitle("${request.title ?: ""} Ep ${request.episodeNumber}")
            }
            MediaItem.Builder()
                .setUri(request.uri)
                .setMediaMetadata(metaBuilder.build())
                .build()
        } else {
            MediaItem.EMPTY
        }

        val durationUs = if (engine.durationMs > 0L) {
            engine.durationMs * 1000L
        } else {
            C.TIME_UNSET
        }

        val queueTitles = queue?.titles.orEmpty()
        val queueIndex = queue?.currentIndex ?: -1
        val playlist = if (queue != null && queueIndex in queueTitles.indices) {
            queueTitles.mapIndexed { i, title ->
                if (i == queueIndex && request != null) {
                    MediaItemData.Builder("queue-$i")
                        .setMediaItem(mediaItem)
                        .setDurationUs(durationUs)
                        .build()
                } else {
                    MediaItemData.Builder("queue-$i")
                        .setMediaItem(
                            MediaItem.Builder()
                                .setMediaId("queue-$i")
                                .setMediaMetadata(
                                    MediaMetadata.Builder()
                                        .setTitle(title)
                                        .setArtist(request?.seriesTitle)
                                        .build()
                                )
                                .build()
                        )
                        .build()
                }
            }
        } else if (request != null) {
            listOf(
                MediaItemData.Builder(request.uri)
                    .setMediaItem(mediaItem)
                    .setDurationUs(durationUs)
                    .build()
            )
        } else {
            emptyList()
        }

        val playWhenReady = coordinator?.userPlayIntent ?: engine.playWhenReady

        val stateBuilder = State.Builder()
            .setAvailableCommands(availableCommands)
            .setPlayWhenReady(playWhenReady, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaybackState(playerState)
            .setPlaybackParameters(PlaybackParameters(engine.playbackSpeed))
            .setPlaylist(playlist)

        if (playlist.isNotEmpty()) {
            stateBuilder.setCurrentMediaItemIndex(if (playlist.size > 1) queueIndex else 0)
            stateBuilder.setContentPositionMs(engine.positionMs.coerceAtLeast(0L))
        } else {
            stateBuilder.setCurrentMediaItemIndex(C.INDEX_UNSET)
        }

        return stateBuilder.build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        if (playWhenReady) {
            coordinator?.play() ?: engine.play()
        } else {
            coordinator?.pause() ?: engine.pause()
        }
        invalidateState()
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(
        mediaItemIndex: Int,
        positionMs: Long,
        seekCommand: Int
    ): ListenableFuture<*> {
        if (queue != null && mediaItemIndex != C.INDEX_UNSET && mediaItemIndex != queue.currentIndex) {
            queue.skipTo(mediaItemIndex)
        } else if (positionMs != C.TIME_UNSET) {
            engine.seekTo(positionMs)
        }
        invalidateState()
        return Futures.immediateVoidFuture()
    }

    override fun handleSetPlaybackParameters(playbackParameters: PlaybackParameters): ListenableFuture<*> {
        engine.setPlaybackSpeed(playbackParameters.speed)
        invalidateState()
        return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> {
        coordinator?.pause() ?: engine.pause()
        engine.stop()
        invalidateState()
        return Futures.immediateVoidFuture()
    }

    override fun onPlaybackStateChanged(state: PlaybackState) {
        invalidateState()
    }

    override fun onPositionChanged(positionMs: Long, durationMs: Long) {
        val now = System.currentTimeMillis()
        if (now - lastPositionInvalidateTime >= 1000L) {
            lastPositionInvalidateTime = now
            invalidateState()
        }
    }

    override fun onPlaybackSpeedChanged(speed: Float) {
        invalidateState()
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        invalidateState()
    }

    override fun onPlayWhenReadyChanged(playWhenReady: Boolean) {
        invalidateState()
    }

    /** Re-reads the queue after the app changed the current item. */
    fun refresh() = invalidateState()

    fun releaseAdapter() {
        engine.removeListener(this)
    }

    internal fun getStateForTesting(): State = getState()
}
