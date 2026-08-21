package ani.dantotsu.media.anime.player

data class PlaybackSnapshot(
    val generationId: Long? = null,
    val playlistEntryId: Long? = null,
    val playbackState: PlaybackState = PlaybackState.Idle,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val userPlayIntent: Boolean = true,
    val isActuallyPlaying: Boolean = false,
    val playbackSpeed: Float = 1.0f,
    val tracks: List<PlayerTrack> = emptyList(),
    val selectedAudioTrackId: Int? = null,
    val selectedSubtitleTrackId: Int? = null,
    val videoDimensions: VideoDimensions? = null,
    val error: PlaybackError? = null
)
