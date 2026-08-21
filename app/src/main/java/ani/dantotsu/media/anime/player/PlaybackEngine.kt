package ani.dantotsu.media.anime.player

import android.view.Surface

interface PlaybackListener {
    fun onPlaybackStateChanged(state: PlaybackState) {}
    fun onPositionChanged(positionMs: Long, durationMs: Long) {}
    fun onPlaybackSpeedChanged(speed: Float) {}
    fun onTracksChanged(tracks: List<PlayerTrack>) {}
    fun onVideoSizeChanged(width: Int, height: Int) {}
    fun onIsPlayingChanged(isPlaying: Boolean) {}
    fun onPlayWhenReadyChanged(playWhenReady: Boolean) {}
    fun onDurationKnown(generationId: Long, durationMs: Long) {}
    fun onSnapshotChanged(snapshot: PlaybackSnapshot) {}
}

interface PlaybackEngine {

    val positionMs: Long
    val durationMs: Long
    val isPlaying: Boolean
    val playWhenReady: Boolean
    val isBuffering: Boolean
        get() = playbackState is PlaybackState.Buffering
    val playbackSpeed: Float
    val playbackState: PlaybackState
    val currentAudioTrack: PlayerTrack?
    val currentSubtitleTrack: PlayerTrack?
    val availableTracks: List<PlayerTrack>
    val videoDimensions: VideoDimensions?
    val lastError: PlaybackError?
    val currentRequest: PlaybackRequest?

    fun play()
    fun pause()
    fun togglePlayPause()
    fun seekTo(positionMs: Long)
    fun seekRelative(offsetMs: Long)
    fun setPlaybackSpeed(speed: Float)

    fun setVideoSurface(surface: Surface?)
    fun setVideoSurfaceSize(width: Int, height: Int)

    fun selectAudioTrack(id: Int?)
    fun selectSubtitleTrack(id: Int?)
    fun addExternalSubtitle(uri: String, title: String?, language: String?, select: Boolean)
    fun applySubtitleStyle(style: SubtitleStyle)
    fun setResizeMode(mode: ResizeMode)

    fun loadMedia(request: PlaybackRequest)
    fun stop()
    fun release()

    fun addListener(listener: PlaybackListener)
    fun removeListener(listener: PlaybackListener)
}
