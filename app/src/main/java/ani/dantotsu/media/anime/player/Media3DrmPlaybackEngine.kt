package ani.dantotsu.media.anime.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.Surface
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.common.TrackSelectionOverride
import ani.dantotsu.parsers.DrmInfo
import ani.dantotsu.util.Logger
import okhttp3.OkHttpClient
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.concurrent.CopyOnWriteArraySet

/**
 * MAX MERGE M4B DRM (HOLD2, upstream be6c4d99 adapted): narrow DRM-capable
 * [PlaybackEngine] for DRM-tagged content. Clear content stays on
 * [MpvPlaybackEngine]; see [DrmBackendPolicy.selectBackend].
 *
 * - Playback via ExoPlayer + Android CDM (Widevine/PlayReady/ClearKey).
 *   Device keys are never extracted for libmpv.
 * - License envelope normalization via [WrappedDrmCallback].
 * - Same [PlaybackEngine] contract as MPV, so episode/engine ownership
 *   ([EpisodePlaybackSession]), the coordinator, the MediaSession adapter,
 *   and the player view all work unchanged.
 * - External subtitles attach as MediaItem subtitle configurations;
 *   rendered cue output is exposed via [setCuesListener] for the player
 *   view's subtitle overlay. MPV-only features (video pipeline, screenshot)
 *   keep their interface defaults.
 * - No suspend IO of its own: cancellation/out-of-scope work ends at
 *   [stop]/[release], which tear down the player synchronously.
 */
@OptIn(UnstableApi::class)
class Media3DrmPlaybackEngine(
    private val context: Context,
    private val httpClient: OkHttpClient = runCatching { Injekt.get<OkHttpClient>() }.getOrElse {
        OkHttpClient()
    },
) : PlaybackEngine {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<PlaybackListener>()
    private val trackSelector = DefaultTrackSelector(context)
    private var player: ExoPlayer? = null
    private var activeRequest: PlaybackRequest? = null
    private var state: PlaybackState = PlaybackState.Idle
    private var error: PlaybackError? = null
    private var style: SubtitleStyle = SubtitleStyle()
    private var generation = 0L
    private var released = false

    private var audioTracks: List<PlayerTrack> = emptyList()
    private var subtitleTracks: List<PlayerTrack> = emptyList()
    private var audioGroups: List<Pair<Int, Int>> = emptyList()
    private var subtitleGroups: List<Pair<Int, Int>> = emptyList()
    private var cuesListener: ((List<androidx.media3.common.text.Cue>) -> Unit)? = null

    fun setCuesListener(listener: ((List<androidx.media3.common.text.Cue>) -> Unit)?) {
        cuesListener = listener
    }

    private val exoListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            val p = player ?: return
            val mapped = when (playbackState) {
                Player.STATE_BUFFERING -> PlaybackState.Buffering
                Player.STATE_READY -> PlaybackState.Ready
                Player.STATE_ENDED -> PlaybackState.Ended()
                else -> PlaybackState.Idle
            }
            if (mapped != state) {
                state = mapped
                listeners.forEach { it.onPlaybackStateChanged(mapped) }
            }
            if (mapped is PlaybackState.Ready) {
                publishPosition()
                publishDuration()
            }
        }

        override fun onTracksChanged(tracks: Tracks) {
            rebuildTracks(tracks)
        }

        override fun onPlayerError(playerError: androidx.media3.common.PlaybackException) {
            val mapped = PlaybackError(
                category = ErrorCategory.SOURCE,
                message = playerError.message ?: "DRM playback error",
                errorCode = playerError.errorCode,
                fatal = true,
                retryable = false
            )
            error = mapped
            state = PlaybackState.Error(mapped)
            listeners.forEach { it.onPlaybackStateChanged(state) }
            Logger.log("Media3DrmPlaybackEngine error: ${playerError.message}")
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            listeners.forEach { it.onIsPlayingChanged(isPlaying) }
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            listeners.forEach { it.onPlayWhenReadyChanged(playWhenReady) }
        }

        override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
            listeners.forEach { it.onVideoSizeChanged(videoSize.width, videoSize.height) }
        }

        override fun onCues(cues: List<androidx.media3.common.text.Cue>) {
            cuesListener?.invoke(cues)
        }
    }

    private fun rebuildTracks(tracks: Tracks) {
        val audios = mutableListOf<PlayerTrack>()
        val subs = mutableListOf<PlayerTrack>()
        val audioIdx = mutableListOf<Pair<Int, Int>>()
        val subIdx = mutableListOf<Pair<Int, Int>>()
        for ((groupIndex, group) in tracks.groups.withIndex()) {
            val trackType = group.type
            for (trackIndex in 0 until group.length) {
                val format = group.getTrackFormat(trackIndex)
                val selected = group.isTrackSelected(trackIndex)
                when (trackType) {
                    C.TRACK_TYPE_AUDIO -> {
                        audioIdx.add(groupIndex to trackIndex)
                        audios.add(
                            PlayerTrack(
                                id = audios.size,
                                type = TrackType.AUDIO,
                                name = format.label ?: format.sampleMimeType,
                                language = format.language,
                                codec = format.sampleMimeType,
                                selected = selected
                            )
                        )
                    }
                    C.TRACK_TYPE_TEXT -> {
                        subIdx.add(groupIndex to trackIndex)
                        subs.add(
                            PlayerTrack(
                                id = subs.size,
                                type = TrackType.SUBTITLE,
                                name = format.label ?: format.language,
                                language = format.language,
                                codec = format.sampleMimeType,
                                selected = selected
                            )
                        )
                    }
                    else -> Unit
                }
            }
        }
        audioTracks = audios
        subtitleTracks = subs
        audioGroups = audioIdx
        subtitleGroups = subIdx
        listeners.forEach { it.onTracksChanged(audioTracks + subtitleTracks) }
    }

    override val positionMs: Long get() = player?.currentPosition ?: 0L
    override val durationMs: Long
        get() = player?.duration?.takeIf { it != C.TIME_UNSET } ?: 0L
    override val isPlaying: Boolean get() = player?.isPlaying == true
    override val playWhenReady: Boolean get() = player?.playWhenReady == true
    override val playbackSpeed: Float get() = player?.playbackParameters?.speed ?: 1f
    override val playbackState: PlaybackState get() = state
    override val currentAudioTrack: PlayerTrack? get() = audioTracks.firstOrNull { it.selected }
    override val currentSubtitleTrack: PlayerTrack? get() = subtitleTracks.firstOrNull { it.selected }
    override val availableTracks: List<PlayerTrack> get() = audioTracks + subtitleTracks
    override val videoDimensions: VideoDimensions?
        get() = player?.videoSize?.let {
            if (it.width > 0 && it.height > 0) VideoDimensions(it.width, it.height) else null
        }
    override val lastError: PlaybackError? get() = error
    override val currentRequest: PlaybackRequest? get() = activeRequest

    override fun play() {
        mainHandler.post { player?.play() }
    }

    override fun pause() {
        mainHandler.post { player?.pause() }
    }

    override fun togglePlayPause() {
        mainHandler.post {
            val p = player ?: return@post
            if (p.isPlaying) p.pause() else p.play()
        }
    }

    override fun seekTo(positionMs: Long) {
        mainHandler.post { player?.seekTo(positionMs.coerceAtLeast(0L)) }
    }

    override fun seekRelative(offsetMs: Long) {
        mainHandler.post {
            val p = player ?: return@post
            p.seekTo((p.currentPosition + offsetMs).coerceAtLeast(0L))
        }
    }

    override fun setPlaybackSpeed(speed: Float) {
        mainHandler.post {
            player?.setPlaybackSpeed(speed.coerceIn(0.25f, 4f))
            listeners.forEach { it.onPlaybackSpeedChanged(speed) }
        }
    }

    override fun setVideoSurface(surface: Surface?) {
        mainHandler.post { player?.setVideoSurface(surface) }
    }

    override fun setVideoSurfaceSize(width: Int, height: Int) {
        // No-op: the player view's SurfaceView layout owns sizing; ExoPlayer
        // scales into whatever surface is attached.
    }

    override fun selectAudioTrack(id: Int?) {
        mainHandler.post {
            val pair: Pair<Int, Int>? = if (id == null) null else audioGroups.getOrNull(id)
            val params = trackSelector.parameters.buildUpon()
            val groupIndex = pair?.first
            val trackIndex = pair?.second
            if (groupIndex == null || trackIndex == null) {
                params.clearOverridesOfType(C.TRACK_TYPE_AUDIO)
            } else {
                player?.currentTracks?.groups?.getOrNull(groupIndex)?.let { group ->
                    params.setOverrideForType(
                        TrackSelectionOverride(group.mediaTrackGroup, trackIndex)
                    )
                }
            }
            trackSelector.parameters = params.build()
        }
    }

    override fun selectSubtitleTrack(id: Int?) {
        mainHandler.post {
            val params = trackSelector.parameters.buildUpon()
            if (id == null) {
                params.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            } else {
                params.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                subtitleGroups.getOrNull(id)?.let { (groupIndex, trackIndex) ->
                    player?.currentTracks?.groups?.getOrNull(groupIndex)?.let { group ->
                        params.setOverrideForType(
                            TrackSelectionOverride(group.mediaTrackGroup, trackIndex)
                        )
                    }
                }
            }
            trackSelector.parameters = params.build()
        }
    }

    override fun addExternalSubtitle(
        uri: String,
        title: String?,
        language: String?,
        select: Boolean,
        explicitUserAction: Boolean
    ) {
        mainHandler.post {
            val p = player ?: return@post
            val req = activeRequest ?: return@post
            val config = MediaItem.SubtitleConfiguration.Builder(android.net.Uri.parse(uri))
                .setMimeType(guessSubtitleMime(uri))
                .setLanguage(language)
                .setLabel(title)
                .apply { if (select) setSelectionFlags(C.SELECTION_FLAG_DEFAULT) }
                .build()
            val position = p.currentPosition
            val autoplay = p.playWhenReady
            p.setMediaItem(buildMediaItem(req, req.drm, (req.subtitleConfigs + config)))
            p.prepare()
            p.seekTo(position)
            p.playWhenReady = autoplay
        }
    }

    override fun applySubtitleStyle(style: SubtitleStyle) {
        this.style = style
    }

    override fun setResizeMode(mode: ResizeMode) {
        // Aspect handling stays with the player view's SurfaceView layout.
    }

    override fun loadMedia(request: PlaybackRequest) {
        activeRequest = request
        error = null
        generation += 1
        mainHandler.post {
            if (released) return@post
            ensurePlayer()
            val p = player ?: return@post
            p.setMediaItem(buildMediaItem(request, request.drm, request.subtitleConfigs))
            p.prepare()
            p.seekTo(request.startPositionMs.coerceAtLeast(0L))
            state = PlaybackState.Buffering
            listeners.forEach { it.onPlaybackStateChanged(state) }
        }
    }

    override fun stop() {
        mainHandler.post {
            stopTicker()
            player?.stop()
            state = PlaybackState.Idle
            listeners.forEach { it.onPlaybackStateChanged(state) }
        }
    }

    override fun release() {
        released = true
        mainHandler.post {
            stopTicker()
            try {
                player?.removeListener(exoListener)
                player?.release()
            } catch (_: Exception) {
            }
            player = null
            cuesListener = null
            listeners.clear()
        }
    }

    override fun addListener(listener: PlaybackListener) {
        listeners.add(listener)
    }

    override fun removeListener(listener: PlaybackListener) {
        listeners.remove(listener)
    }

    private fun ensurePlayer() {
        if (player != null) return
        val licenseFactory = OkHttpDataSource.Factory(httpClient)
        val mediaFactory = OkHttpDataSource.Factory(httpClient).apply {
            setUserAgent("Editotsu/${android.os.Build.MODEL}")
        }
        val exo = ExoPlayer.Builder(context)
            .setTrackSelector(trackSelector)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(context)
                    .setDataSourceFactory(mediaFactory)
                    .setDrmSessionManagerProvider(WrappedDrmCallback.providerFor(licenseFactory))
            )
            .setLoadControl(DefaultLoadControl())
            .build()
        exo.addListener(exoListener)
        player = exo
        startTicker()
    }

    private fun buildMediaItem(
        request: PlaybackRequest,
        drm: DrmInfo?,
        subtitleConfigs: List<MediaItem.SubtitleConfiguration>,
    ): MediaItem {
        val builder = MediaItem.Builder()
            .setUri(request.uri)
            .setSubtitleConfigurations(subtitleConfigs)
        request.mimeType?.takeIf { it.isNotBlank() }?.let { builder.setMimeType(it) }
        drm?.let { info ->
            buildDrmConfiguration(info)?.let(builder::setDrmConfiguration)
        }
        return builder.build()
    }

    private fun guessSubtitleMime(uri: String): String {
        val lower = uri.substringBefore("?").lowercase()
        return when {
            lower.endsWith(".vtt") || lower.endsWith(".webvtt") -> MimeTypes.TEXT_VTT
            lower.endsWith(".srt") -> MimeTypes.APPLICATION_SUBRIP
            lower.endsWith(".ssa") || lower.endsWith(".ass") -> MimeTypes.TEXT_SSA
            else -> MimeTypes.TEXT_VTT
        }
    }

    private val ticker = object : Runnable {
        override fun run() {
            val p = player
            if (p != null && !released && (p.isPlaying || p.playbackState == Player.STATE_BUFFERING)) {
                publishPosition()
            }
            if (!released && player != null) {
                mainHandler.postDelayed(this, 500L)
            }
        }
    }

    private var tickerRunning = false

    private fun startTicker() {
        if (tickerRunning) return
        tickerRunning = true
        mainHandler.postDelayed(ticker, 500L)
    }

    private fun stopTicker() {
        tickerRunning = false
        mainHandler.removeCallbacks(ticker)
    }

    private fun publishPosition() {
        val p = player ?: return
        val duration = p.duration.takeIf { it != C.TIME_UNSET } ?: 0L
        listeners.forEach { it.onPositionChanged(p.currentPosition, duration) }
    }

    private fun publishDuration() {
        val duration = durationMs
        if (duration > 0L) {
            listeners.forEach { it.onDurationKnown(generation, duration) }
        }
    }
}

/**
 * Shared DRM session configuration builder: the engine and host tests use
 * the same mapping (scheme → CDM UUID, license URL/headers, multi-session).
 * Returns null when the metadata cannot back a session (caller stays clear).
 */
internal fun buildDrmConfiguration(drm: ani.dantotsu.parsers.DrmInfo): MediaItem.DrmConfiguration? {
    val scheme = DrmBackendPolicy.mapScheme(drm.scheme) ?: return null
    val license = DrmBackendPolicy.licenseRequest(drm) ?: return null
    return MediaItem.DrmConfiguration.Builder(scheme.uuid)
        .setLicenseUri(license.url)
        .setLicenseRequestHeaders(license.headers)
        .setMultiSession(true)
        .build()
}

private val PlaybackRequest.subtitleConfigs: List<MediaItem.SubtitleConfiguration>
    get() = externalSubtitles.map { sub ->
        MediaItem.SubtitleConfiguration.Builder(android.net.Uri.parse(sub.url))
            .setMimeType(MimeTypes.TEXT_VTT)
            .setLanguage(sub.language)
            .setLabel(sub.title)
            .apply { if (sub.selected) setSelectionFlags(C.SELECTION_FLAG_DEFAULT) }
            .build()
    }
