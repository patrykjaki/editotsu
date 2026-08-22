package ani.dantotsu.media.anime.player

import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import ani.dantotsu.defaultHeaders
import ani.dantotsu.parsers.Subtitle
import ani.dantotsu.parsers.Video
import ani.dantotsu.parsers.VideoType
import ani.dantotsu.toast
import ani.dantotsu.torrent.TorrentServerManager
import okhttp3.OkHttpClient
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.Calendar

@UnstableApi
class DantotsuPlayerManager(
    private val activity: AppCompatActivity,
    private val playerView: DantotsuPlayerView,
    private val subtitleManager: PlayerSubtitleManager,
    private val client: OkHttpClient,
    private val onPlayerErrorCallback: (error: PlaybackError) -> Unit
) {

    var playbackEngine: PlaybackEngine? = null
        private set
    var playbackCoordinator: PlaybackCoordinator? = null
        private set
    var audioFocusController: AudioFocusController? = null
        private set
    var mediaSession: MediaSession? = null
        private set
    var media3PlayerAdapter: PlaybackEngineMedia3Player? = null
        private set
    var becomingNoisyReceiver: BecomingNoisyReceiver? = null
        private set

    var isInitialized = false
        private set

    fun buildPlaybackRequest(
        video: Video,
        subtitles: List<Subtitle>? = null,
        startPositionMs: Long = 0L,
        title: String? = null,
        seriesTitle: String? = null,
        episodeNumber: String? = null,
        coverUrl: String? = null,
        mimeType: String? = null,
        preferredSubLang: String? = null,
        embedUrl: String? = null,
        audioTracks: List<eu.kanade.tachiyomi.animesource.model.Track> = emptyList()
    ): PlaybackRequest {
        val headers = mutableMapOf<String, String>()
        headers.putAll(defaultHeaders)
        video.file.headers?.let {
            headers.putAll(it)
        }

        val externalSubs = subtitles?.mapNotNull { sub ->
            val rawUrl = sub.file.url
            if (rawUrl.isBlank()) null
            else {
                val resolvedUrl = SubtitleSelectionResolver.resolveSubtitleUri(rawUrl, embedUrl, video.file.url)
                val isSelected = preferredSubLang != null && sub.language.equals(preferredSubLang, ignoreCase = true)
                ExternalSubtitle(
                    url = resolvedUrl,
                    title = sub.language,
                    language = sub.language,
                    selected = isSelected
                )
            }
        } ?: emptyList()

        val externalAudios = audioTracks.mapNotNull { audioTrack ->
            val audioUrl = audioTrack.url
            if (audioUrl.isNotBlank() && audioUrl != video.file.url) {
                ExternalAudioTrack(
                    url = audioUrl,
                    language = audioTrack.lang,
                    title = audioTrack.lang
                )
            } else null
        }

        val uri = video.file.url
        val sourceClass = when {
            uri.contains("127.0.0.1") && (uri.contains("/stream") || uri.contains("hash=")) -> PlaybackSourceClass.TORRENT_LOCALHOST
            video.format == VideoType.M3U8 || uri.contains(".m3u8", ignoreCase = true) -> PlaybackSourceClass.HLS
            video.format == VideoType.DASH || uri.startsWith("http://", ignoreCase = true) || uri.startsWith("https://", ignoreCase = true) -> PlaybackSourceClass.DIRECT_HTTP
            uri.startsWith("content://", ignoreCase = true) -> PlaybackSourceClass.CONTENT_FD
            else -> PlaybackSourceClass.LOCAL_FILE
        }

        val sourceLease: AutoCloseable? = if (sourceClass == PlaybackSourceClass.TORRENT_LOCALHOST) {
            try {
                val hash = uri.substringAfter("hash=").substringBefore("&")
                val index = uri.substringAfter("index=").substringBefore("&").toIntOrNull() ?: 0
                if (hash.isNotEmpty()) {
                    Injekt.get<TorrentServerManager>().adoptPrebufferLease(hash, index)
                } else null
            } catch (_: Exception) {
                null
            }
        } else null

        return PlaybackRequest(
            uri = video.file.url,
            startPositionMs = startPositionMs,
            headers = headers,
            title = title,
            seriesTitle = seriesTitle,
            episodeNumber = episodeNumber,
            coverUrl = coverUrl,
            mimeType = mimeType,
            externalSubtitles = externalSubs,
            externalAudioTracks = externalAudios,
            sourceClass = sourceClass,
            sourceLease = sourceLease
        )
    }



    fun initPlayer(
        playbackPosition: Long = 0L,
        speed: Float = 1.0f,
        listener: PlaybackListener
    ): PlaybackEngine {
        releasePlayer()

        val engine = MpvPlaybackEngine(activity)
        this.playbackEngine = engine

        val focusController = AndroidAudioFocusController(activity)
        this.audioFocusController = focusController

        val coord = PlaybackCoordinator(
            getEngine = { playbackEngine },
            audioFocusController = focusController
        )
        this.playbackCoordinator = coord

        playerView.bindEngine(engine)
        engine.setPlaybackSpeed(speed)

        becomingNoisyReceiver = BecomingNoisyReceiver {
            coord.onBecomingNoisy()
        }
        becomingNoisyReceiver?.register(activity)

        try {
            val adapter = PlaybackEngineMedia3Player(engine, coord)
            this.media3PlayerAdapter = adapter
            val rightNow = Calendar.getInstance()
            mediaSession = MediaSession.Builder(activity, adapter)
                .setId(rightNow.timeInMillis.toString())
                .build()
        } catch (e: Exception) {
            toast(e.toString())
        }

        engine.addListener(listener)
        subtitleManager.applySubtitlePreferences()
        isInitialized = true
        return engine
    }

    fun releasePlayer() {
        becomingNoisyReceiver?.unregister(activity)
        becomingNoisyReceiver = null

        playbackCoordinator?.release()
        playbackCoordinator = null

        audioFocusController?.abandonFocus()
        audioFocusController = null

        isInitialized = false
        playerView.unbindEngine()

        mediaSession?.release()
        mediaSession = null

        media3PlayerAdapter?.releaseAdapter()
        media3PlayerAdapter = null

        playbackEngine?.release()
        playbackEngine = null
    }

    fun pause() {
        playbackCoordinator?.pause() ?: playbackEngine?.pause()
    }

    fun play() {
        playbackCoordinator?.play() ?: playbackEngine?.play()
    }

    fun isPlaying(): Boolean = playbackEngine?.isPlaying == true
}
