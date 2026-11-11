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
    fun addExternalSubtitle(uri: String, title: String?, language: String?, select: Boolean, explicitUserAction: Boolean = false)
    fun setSubtitleDelay(delayMs: Long) {}
    fun setAudioDelay(delayMs: Long) {}
    /**
     * HOLD4 B6: delay capability gate. MPV supports both; backends without a
     * real delay path override to false so the Sync UI can refuse to display
     * an unapplied value (M4_DRM_SYNC_PARITY deferred follow-up).
     */
    val supportsSubtitleDelay: Boolean get() = true
    val supportsAudioDelay: Boolean get() = true
    fun applySubtitleStyle(style: SubtitleStyle)
    fun applyVideoPipeline(config: VideoPipelineConfig) {}
    /** Whether [applyVideoPipeline] does anything (night mode, colour, deband, upscaler). */
    val supportsVideoPipeline: Boolean get() = true
    fun setResizeMode(mode: ResizeMode)
    /** Whether [setResizeMode] can show [mode]; unsupported modes fall back to fit. */
    fun supportsResizeMode(mode: ResizeMode): Boolean = true

    fun loadMedia(request: PlaybackRequest)
    fun stop()
    fun release()

    /** Whether [captureScreenshot] can work on this engine at all. */
    val supportsScreenshot: Boolean get() = false

    /**
     * MAX MERGE M6: capture the current frame to [file] via the native screenshot path.
     * Runs on the engine's own thread; [onResult] is called on the main thread with true
     * when the file was written, false when unsupported or not ready.
     */
    fun captureScreenshot(file: java.io.File, onResult: (Boolean) -> Unit) {
        onResult(false)
    }

    fun addListener(listener: PlaybackListener)
    fun removeListener(listener: PlaybackListener)
}
