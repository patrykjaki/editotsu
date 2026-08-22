package ani.dantotsu.media.anime.player

import java.io.File

enum class PlaybackSourceClass {
    LOCAL_FILE,
    CONTENT_FD,
    DIRECT_HTTP,
    HLS,
    TORRENT_LOCALHOST
}

data class ExternalSubtitle(
    val url: String,
    val title: String? = null,
    val language: String? = null,
    val selected: Boolean = false,
    val id: String? = null
)

data class PlaybackRequest(
    val uri: String,
    val startPositionMs: Long = 0L,
    val headers: Map<String, String> = emptyMap(),
    val title: String? = null,
    val seriesTitle: String? = null,
    val episodeNumber: String? = null,
    val coverUrl: String? = null,
    val mimeType: String? = null,
    val subtitleStyle: SubtitleStyle = SubtitleStyle(),
    val externalSubtitles: List<ExternalSubtitle> = emptyList(),
    val metadata: Map<String, String> = emptyMap(),
    val sourceClass: PlaybackSourceClass = PlaybackSourceClass.LOCAL_FILE,
    val sourceLease: AutoCloseable? = null
)

enum class TrackType {
    VIDEO,
    AUDIO,
    SUBTITLE
}

data class PlayerTrack(
    val id: Int,
    val type: TrackType,
    val name: String? = null,
    val language: String? = null,
    val codec: String? = null,
    val selected: Boolean = false,
    val external: Boolean = false,
    val default: Boolean = false,
    val forced: Boolean = false
)

enum class ResizeMode {
    FIT,
    ZOOM,
    STRETCH,
    FOUR_THREE,
    SIXTEEN_NINE
}

enum class SubtitleStyleMode {
    SOURCE,
    CUSTOM
}

data class SubtitleStyle(
    val mode: SubtitleStyleMode = SubtitleStyleMode.SOURCE,
    val fontFamily: String? = null,
    val fontFile: File? = null,
    val fontSizeSp: Float = 20f,
    val textColor: Int = 0xFFFFFFFF.toInt(),
    val borderColor: Int = 0xFF000000.toInt(),
    val backgroundColor: Int = 0x00000000,
    val borderWidth: Float = 3f,
    val bottomMarginDp: Int = 24
)

enum class ErrorCategory {
    NETWORK,
    SOURCE,
    DEMUX,
    DECODER,
    OUTPUT,
    UNKNOWN
}

data class PlaybackError(
    val category: ErrorCategory,
    val message: String,
    val errorCode: Int = -1,
    val fatal: Boolean = true,
    val retryable: Boolean = false
)

sealed class PlaybackState {
    data object Idle : PlaybackState()
    data object Buffering : PlaybackState()
    data object Ready : PlaybackState()
    data class Ended(
        val reason: String = "eof",
        val positionMs: Long = 0L,
        val durationMs: Long = 0L
    ) : PlaybackState()
    data class Error(val error: PlaybackError) : PlaybackState()
}

data class VideoDimensions(
    val width: Int,
    val height: Int
)

data class CastMediaDescriptor(
    val url: String,
    val title: String? = null,
    val episodeNumber: String? = null,
    val animeTitle: String? = null,
    val bannerUrl: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val subtitles: List<ExternalSubtitle> = emptyList()
)
