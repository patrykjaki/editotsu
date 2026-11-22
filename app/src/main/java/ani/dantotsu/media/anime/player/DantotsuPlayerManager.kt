package ani.dantotsu.media.anime.player

import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.util.UnstableApi
import android.os.Bundle
import androidx.media3.common.Player
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import ani.dantotsu.R
import ani.dantotsu.media.anime.pipSeekMs
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
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
) {

    var playbackEngine: PlaybackEngine? = null
        private set
    var playbackCoordinator: PlaybackCoordinator? = null
        private set
    var audioFocusController: AudioFocusController? = null
        private set
    var mediaSession: MediaSession? = null
        private set

    /** Playlist the session exposes (theme mode); set before [initPlayer]. */
    var mediaQueue: MediaQueue? = null
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
        fallbackSubLang: String? = null,
        embedUrl: String? = null,
        audioTracks: List<eu.kanade.tachiyomi.animesource.model.Track> = emptyList(),
        // Beta03: validated extension mpv per-file options for this extractor.
        mpvFileOptions: String = ""
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
                    selected = isSelected,
                    // Beta03: preserve the source header context (adapter now
                    // attaches video headers to sub.file) for sub-add transport.
                    headers = sub.file.headers,
                    // HOLD6: preserve authoritative parser MIME (Subtitle.type)
                    // so opaque URLs keep ASS/SRT instead of defaulting to VTT.
                    mimeType = DrmTransportPlan.subtitleMimeForType(sub.type),
                    // HOLD7: deterministic stable key for exact intent
                    // matching (propagated to SubtitleConfiguration.id).
                    id = resolvedUrl
                )
            }
        } ?: emptyList()

        val externalAudios = audioTracks.mapNotNull { audioTrack ->
            val audioUrl = audioTrack.url
            if (audioUrl.isNotBlank() && audioUrl != video.file.url) {
                ExternalAudioTrack(
                    url = audioUrl,
                    language = audioTrack.lang,
                    title = audioTrack.lang,
                    // Beta03: extension audio Tracks carry no headers of their
                    // own; inherit the video/source header context (same host).
                    headers = headers
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
                val index = ani.dantotsu.torrent.TorrentLinks.fileIndex(uri)
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
            mimeType = mimeType
                // HOLD5 B4 + HOLD6 hardening: authoritative primary MIME.
                // primaryMimeForFormat treats blank explicit as absent, so ""
                // can never suppress an authoritative DASH/M3U8 format.
                // MPV never reads request.mimeType — unaffected.
                .let { DrmTransportPlan.primaryMimeForFormat(it, video.format) },
            externalSubtitles = externalSubs,
            externalAudioTracks = externalAudios,
            preferredSubLang = preferredSubLang,
            fallbackSubLang = fallbackSubLang,
            sourceClass = sourceClass,
            // Beta03: validated extension mpv per-file options.
            extraMpvOptions = mpvFileOptions,
            sourceLease = sourceLease,
            // MAX MERGE M4B DRM: carried for backend selection + session config.
            drm = video.drm
        )
    }



    fun initPlayer(
        playbackPosition: Long = 0L,
        speed: Float = 1.0f,
        listener: PlaybackListener,
        // MAX MERGE M4B DRM: true creates the narrow Media3 DRM backend
        // instead of MPV. Caller decides via DrmBackendPolicy.selectBackend.
        preferDrmBackend: Boolean = false
    ): PlaybackEngine {
        releasePlayer()

        val engine: PlaybackEngine = if (preferDrmBackend) {
            Media3DrmPlaybackEngine(activity)
        } else {
            MpvPlaybackEngine(activity)
        }
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

        // CP2: settings → VideoPipelineConfig → engine at initialization + live re-apply support.
        engine.applyVideoPipeline(VideoPipelinePrefsMapper.fromPrefs())
        ActiveVideoPipeline.attach(engine)

        becomingNoisyReceiver = BecomingNoisyReceiver {
            coord.onBecomingNoisy()
        }
        becomingNoisyReceiver?.register(activity)

        try {
            // Episodes get seek back/forward on the session; theme mode keeps prev/next.
            val episodeSeek = mediaQueue == null
            val adapter = PlaybackEngineMedia3Player(
                engine, coord, queue = mediaQueue,
                seekIncrementMs = if (episodeSeek) {
                    { pipSeekMs(PrefManager.getVal(PrefName.SeekTime)) }
                } else null,
            )
            this.media3PlayerAdapter = adapter
            val rightNow = Calendar.getInstance()
            val builder = MediaSession.Builder(activity, adapter)
                .setId(rightNow.timeInMillis.toString())
            if (episodeSeek) {
                // System controls drawn from the session (the PiP window on newer Android,
                // media controls) only show prev/play/next and custom actions, never the
                // rewind/fast-forward actions, so seek goes in as two custom buttons.
                builder.setCallback(SeekButtonsCallback)
                    .setMediaButtonPreferences(seekButtons())
            }
            mediaSession = builder.build()
        } catch (e: Exception) {
            toast(e.toString())
        }

        engine.addListener(listener)
        // Beta07: style is applied by the caller once the PlaybackRequest
        // (and therefore the playback origin) exists; see ExoplayerView.
        // Applying here would run before classification with no context.
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
        playbackEngine?.let { ActiveVideoPipeline.detach(it) }
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

    private fun seekButtons(): ImmutableList<CommandButton> {
        val seconds = ani.dantotsu.media.anime.pipSeekSeconds(PrefManager.getVal(PrefName.SeekTime))
        return ImmutableList.of(
            CommandButton.Builder(CommandButton.ICON_SKIP_BACK)
                .setCustomIconResId(R.drawable.ic_round_fast_rewind_24)
                .setDisplayName(activity.getString(R.string.pip_rewind, seconds))
                .setSessionCommand(SEEK_BACK_COMMAND)
                .setSlots(CommandButton.SLOT_BACK)
                .build(),
            CommandButton.Builder(CommandButton.ICON_SKIP_FORWARD)
                .setCustomIconResId(R.drawable.ic_round_fast_forward_24)
                .setDisplayName(activity.getString(R.string.pip_forward, seconds))
                .setSessionCommand(SEEK_FORWARD_COMMAND)
                .setSlots(CommandButton.SLOT_FORWARD)
                .build(),
        )
    }

    private object SeekButtonsCallback : MediaSession.Callback {
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult =
            MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(
                    MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                        .add(SEEK_BACK_COMMAND)
                        .add(SEEK_FORWARD_COMMAND)
                        .build()
                )
                .build()

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            val player = session.player
            when (customCommand.customAction) {
                SEEK_BACK_COMMAND.customAction ->
                    if (player.isCommandAvailable(Player.COMMAND_SEEK_BACK)) player.seekBack()
                SEEK_FORWARD_COMMAND.customAction ->
                    if (player.isCommandAvailable(Player.COMMAND_SEEK_FORWARD)) player.seekForward()
                else -> return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED))
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }
    }

    private companion object {
        val SEEK_BACK_COMMAND = SessionCommand("ani.editotsu.SEEK_BACK", Bundle.EMPTY)
        val SEEK_FORWARD_COMMAND = SessionCommand("ani.editotsu.SEEK_FORWARD", Bundle.EMPTY)
    }
}
