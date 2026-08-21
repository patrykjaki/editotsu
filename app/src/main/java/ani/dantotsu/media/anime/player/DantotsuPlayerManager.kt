package ani.dantotsu.media.anime.player

import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import ani.dantotsu.defaultHeaders
import ani.dantotsu.parsers.Subtitle
import ani.dantotsu.parsers.Video
import ani.dantotsu.toast
import okhttp3.OkHttpClient
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
        embedUrl: String? = null
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

        return PlaybackRequest(
            uri = video.file.url,
            startPositionMs = startPositionMs,
            headers = headers,
            title = title,
            seriesTitle = seriesTitle,
            episodeNumber = episodeNumber,
            coverUrl = coverUrl,
            mimeType = mimeType,
            externalSubtitles = externalSubs
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
