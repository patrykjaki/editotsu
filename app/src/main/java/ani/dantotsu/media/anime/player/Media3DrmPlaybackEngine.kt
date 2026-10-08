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
    // HOLD7: parallel external-id list for exact intent matching
    // (Format.id propagated from SubtitleConfiguration.id).
    private var subtitleExternalIds: List<String?> = emptyList()
    private val subtitleIntent = DrmSubtitleIntentMachine()
    private var cuesListener: ((List<androidx.media3.common.text.Cue>) -> Unit)? = null
    private var styleApplier: ((SubtitleStyle) -> Unit)? = null
    // HOLD10 §5: last applied subtitle action; identical consecutive writes
    // are skipped. Reset on release (fresh selector state afterwards).
    private var lastSubtitleAction: DrmSubtitleIntentMachine.Action? = null
    // HOLD4 B1/B2: output-affecting commands arrive before ExoPlayer exists
    // (bind time) and must survive creation + header rebuilds. The real
    // Surface stays here; everything replayable lives in [pendingOutput].
    private var boundSurface: Surface? = null
    private val pendingOutput = PendingDrmOutput()
    // HOLD3 D1: request-scoped transport. The player (and its factories)
    // are rebuilt when the request headers change so source headers always
    // reach manifest/segment/subtitle loads exactly as carried.
    private var activeHeaderPlan: DrmTransportPlan.SourceHeaderPlan? = null
    private var mediaSourceFactory: DefaultMediaSourceFactory? = null

    fun setCuesListener(listener: ((List<androidx.media3.common.text.Cue>) -> Unit)?) {
        cuesListener = listener
    }

    fun setStyleApplier(applier: ((SubtitleStyle) -> Unit)?) {
        styleApplier = applier
        applier?.invoke(style)
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
        subtitleExternalIds = tracks.groups.flatMap { group ->
            if (group.type != C.TRACK_TYPE_TEXT) emptyList()
            else (0 until group.length).map { group.getTrackFormat(it).id }
        }
        listeners.forEach { it.onTracksChanged(audioTracks + subtitleTracks) }
        evaluateSubtitleIntent()
    }

    /**
     * HOLD7: single evaluation point mapping intent state to track-selector
     * commands. Stale generations are ignored inside the machine.
     */
    private fun evaluateSubtitleIntent() {
        val req = activeRequest ?: return
        val refs = subtitleTracks.mapIndexed { index, track ->
            val (groupIndex, trackIndex) = subtitleGroups.getOrNull(index) ?: (0 to 0)
            DrmSubtitleIntentMachine.TrackRef(
                id = track.id,
                externalId = subtitleExternalIds.getOrNull(index),
                language = track.language,
                name = track.name,
                default = track.default,
                forced = track.forced,
                groupIndex = groupIndex,
                indexInGroup = trackIndex
            )
        }
        val autoId = resolveDrmAutoSubtitleId(refs, req.preferredSubLang)
        val currentSelectedId = subtitleTracks.firstOrNull { it.selected }?.id
        // HOLD9: authority is computed, never assumed. Only a settled
        // (READY), text-bearing enumeration with every attached external
        // present may mutate explicit intent; prepare/rebuild/attach
        // transients defer. Pending adoption needs no authority (presence
        // is proof) and is handled inside the machine.
        val attachedIds = req.externalSubtitles.map { it.id ?: it.url }.toSet()
        val settled = player?.playbackState == androidx.media3.common.Player.STATE_READY
        val authoritative = settled && computeTracksAuthoritative(refs, attachedIds)
        // HOLD8-A: a saved preference beats Media3's automatic choice.
        applySubtitleAction(
            subtitleIntent.onTracksAvailable(
                generation,
                refs,
                autoId,
                currentSelectedId,
                tracksAuthoritative = authoritative,
                preferExplicit = !req.preferredSubLang.isNullOrBlank()
            )
        )
    }

    private fun applySubtitleAction(action: DrmSubtitleIntentMachine.Action) {
        // HOLD10 §5: never write an identical consecutive selector command.
        // Identical override writes risk an onTracksChanged feedback loop;
        // the machine already returns NoOp for steady states, and this guard
        // covers repeats across evaluations (e.g. Off re-confirmations).
        if (action != DrmSubtitleIntentMachine.Action.NoOp && action == lastSubtitleAction) return
        if (action != DrmSubtitleIntentMachine.Action.NoOp) lastSubtitleAction = action
        when (action) {
            is DrmSubtitleIntentMachine.Action.NoOp -> Unit
            is DrmSubtitleIntentMachine.Action.DisableText -> {
                val params = trackSelector.parameters.buildUpon()
                params.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                trackSelector.parameters = params.build()
            }
            is DrmSubtitleIntentMachine.Action.EnableTextAuto -> {
                val params = trackSelector.parameters.buildUpon()
                params.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                params.clearOverridesOfType(C.TRACK_TYPE_TEXT)
                trackSelector.parameters = params.build()
            }
            is DrmSubtitleIntentMachine.Action.SelectTrack -> {
                val params = trackSelector.parameters.buildUpon()
                params.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                subtitleGroups.getOrNull(action.id)?.let { (groupIndex, trackIndex) ->
                    player?.currentTracks?.groups?.getOrNull(groupIndex)?.let { group ->
                        params.setOverrideForType(
                            TrackSelectionOverride(group.mediaTrackGroup, trackIndex)
                        )
                    }
                }
                trackSelector.parameters = params.build()
            }
        }
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
        // Single clamp site: applied and reported values are identical.
        val applied = PendingDrmOutput.clampSpeed(speed)
        pendingOutput.speed = applied
        mainHandler.post {
            player?.setPlaybackSpeed(applied)
            listeners.forEach { it.onPlaybackSpeedChanged(applied) }
        }
    }

    override fun setVideoSurface(surface: Surface?) {
        boundSurface = surface
        if (surface == null) pendingOutput.clearSurface()
        else pendingOutput.hasSurface = true
        mainHandler.post { player?.setVideoSurface(surface) }
    }

    override fun setVideoSurfaceSize(width: Int, height: Int) {
        pendingOutput.surfaceWidth = width
        pendingOutput.surfaceHeight = height
        // No ExoPlayer size API: the view's SurfaceView layout owns sizing.
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
        // HOLD7: explicit user intent routes through the machine (newest
        // intent wins, Off disables); never touches the selector directly.
        // HOLD9: carry the full reference so rebuilds follow stable identity
        // instead of recycled numeric ids.
        mainHandler.post {
            val ref = if (id == null) {
                null
            } else {
                val index = subtitleTracks.indexOfFirst { it.id == id }
                if (index < 0) {
                    null
                } else {
                    val (groupIndex, trackIndex) = subtitleGroups.getOrNull(index) ?: (0 to 0)
                    val track = subtitleTracks[index]
                    DrmSubtitleIntentMachine.TrackRef(
                        id = track.id,
                        externalId = subtitleExternalIds.getOrNull(index),
                        language = track.language,
                        name = track.name,
                        default = track.default,
                        forced = track.forced,
                        groupIndex = groupIndex,
                        indexInGroup = trackIndex
                    )
                }
            }
            applySubtitleAction(subtitleIntent.onSelectTrack(id, ref))
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
            // HOLD5 B3: live attachment rebuilds transport from the merged
            // request (the new URI joins its source plan) while preserving
            // position, play intent, output state, and the engine binding.
            // Same MIME inference as initial subtitles.
            val req = activeRequest ?: return@post
            val merged = DrmTransportPlan.attachLiveSubtitle(
                req,
                ExternalSubtitle(
                    url = uri,
                    title = title,
                    language = language,
                    selected = select,
                    id = uri
                )
            )
            if (merged.externalSubtitles.size == req.externalSubtitles.size) {
                // Already attached: re-assert selection when explicitly
                // requested instead of no-opping (HOLD7 case 6).
                if (select && explicitUserAction) {
                    val refs = subtitleTracks.mapIndexed { index, track ->
                        DrmSubtitleIntentMachine.TrackRef(
                            id = track.id,
                            externalId = subtitleExternalIds.getOrNull(index),
                            language = track.language,
                            name = track.name
                        )
                    }
                    applySubtitleAction(subtitleIntent.onLiveExternal(uri, select, explicitUserAction, refs))
                }
                return@post
            }
            val position = player?.currentPosition ?: req.startPositionMs
            val autoplay = player?.playWhenReady ?: true
            ensurePlayer(merged)
            activeRequest = merged
            val p = player ?: return@post
            p.setMediaSource(buildMediaSource(merged))
            p.prepare()
            p.seekTo(position.coerceAtLeast(0L))
            p.playWhenReady = autoplay
            // HOLD7: drive intent for the newly attached subtitle against
            // currently known tracks; not-yet-materialized explicit picks
            // become pending and win on arrival.
            val refs = subtitleTracks.mapIndexed { index, track ->
                DrmSubtitleIntentMachine.TrackRef(
                    id = track.id,
                    externalId = subtitleExternalIds.getOrNull(index),
                    language = track.language,
                    name = track.name
                )
            }
            applySubtitleAction(subtitleIntent.onLiveExternal(uri, select, explicitUserAction, refs))
        }
    }

    override fun applySubtitleStyle(style: SubtitleStyle) {
        this.style = style
        styleApplier?.invoke(style)
    }

    override fun setResizeMode(mode: ResizeMode) {
        // MPV-only aspects (stretch, fixed-ratio overrides) have no Media3
        // equivalent and keep aspect on this backend; FIT/ZOOM map to the
        // matching scaling modes. See matrix for the exact gap list.
        pendingOutput.resize = mode
        mainHandler.post { applyResizeModeToPlayer(mode) }
    }

    private fun applyResizeModeToPlayer(mode: ResizeMode) {
        player?.videoScalingMode = when (mode) {
            ResizeMode.FIT -> C.VIDEO_SCALING_MODE_SCALE_TO_FIT
            ResizeMode.ZOOM -> C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING
            else -> {
                Logger.log("Media3DrmPlaybackEngine: $mode has no scaling equivalent, keeping aspect")
                C.VIDEO_SCALING_MODE_SCALE_TO_FIT
            }
        }
    }

    override fun setSubtitleDelay(delayMs: Long) {
        // B6: no Media3 cue-offset path; the capability gate in
        // PlayerSubtitleManager keeps this unreachable in product flows.
        Logger.log("Media3DrmPlaybackEngine: subtitle delay unsupported, ignoring ${delayMs}ms")
    }

    override fun setAudioDelay(delayMs: Long) {
        Logger.log("Media3DrmPlaybackEngine: audio delay unsupported, ignoring ${delayMs}ms")
    }

    override val supportsSubtitleDelay: Boolean get() = false
    override val supportsAudioDelay: Boolean get() = false

    override fun loadMedia(request: PlaybackRequest) {
        activeRequest = request
        error = null
        generation += 1
        // HOLD7: initialize intent from persisted preference. None starts
        // Off (applied immediately, even pre-player, via the bare
        // trackSelector); request-carried selected externals become pending
        // adoption candidates that can never beat Off.
        val initialAction = subtitleIntent.onLoad(
            generation,
            request.preferredSubLang,
            request.externalSubtitles.filter { it.selected }.map { it.id ?: it.url }
        )
        applySubtitleAction(initialAction)
        mainHandler.post {
            if (released) return@post
            ensurePlayer(request)
            val p = player ?: return@post
            p.setMediaSource(buildMediaSource(request))
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
            releasePlayerInternal()
            boundSurface = null
            pendingOutput.clearSurface()
            cuesListener = null
            styleApplier = null
            lastSubtitleAction = null
            listeners.clear()
        }
    }

    override fun addListener(listener: PlaybackListener) {
        listeners.add(listener)
    }

    override fun removeListener(listener: PlaybackListener) {
        listeners.remove(listener)
    }

    private fun ensurePlayer(request: PlaybackRequest) {
        // HOLD5: source-scoped factories. Each source (primary, every side,
        // every subtitle) resolves its EXACT header set through its own
        // multi-scheme factory, so child segments inherit their manifest's
        // context and primary credentials can never leak cross-host.
        // License loads stay on a separate header-free factory.
        val headerPlan = DrmTransportPlan.planSourceHeaders(
            request.uri,
            request.headers,
            DrmTransportPlan.planSubtitleAttachments(request.externalSubtitles),
            DrmTransportPlan.planAudioMerge(request.uri, request.externalAudioTracks).sides,
            defaultDrmUa()
        )
        if (player != null && activeHeaderPlan == headerPlan) {
            return
        }
        releasePlayerInternal()
        activeHeaderPlan = headerPlan
        val licenseFactory = OkHttpDataSource.Factory(httpClient)
        mediaSourceFactory = DefaultMediaSourceFactory(context)
            .setDataSourceFactory(scopedFactory(headerPlan.primary))
            .setDrmSessionManagerProvider(WrappedDrmCallback.providerFor(licenseFactory))
        val exo = ExoPlayer.Builder(context)
            .setTrackSelector(trackSelector)
            .setMediaSourceFactory(mediaSourceFactory!!)
            .setLoadControl(DefaultLoadControl())
            .build()
        exo.addListener(exoListener)
        player = exo
        // Fresh selector state on the new player: forget applied actions so
        // intent is re-asserted (idempotently) on the next evaluation.
        lastSubtitleAction = null
        // HOLD4 B1/B2: replay everything bound before creation (surface,
        // speed, resize) so a header rebuild never loses output state.
        exo.setVideoSurface(boundSurface)
        exo.setPlaybackSpeed(pendingOutput.speed)
        listeners.forEach { it.onPlaybackSpeedChanged(pendingOutput.speed) }
        applyResizeModeToPlayer(pendingOutput.resize)
        startTicker()
    }

    private fun defaultDrmUa(): String = "Editotsu/${android.os.Build.MODEL}"

    private fun releasePlayerInternal() {
        try {
            player?.removeListener(exoListener)
            player?.stop()
            player?.release()
        } catch (_: Exception) {
        }
        player = null
    }

    /**
     * Source-scoped multi-scheme factory: exact headers for its source,
     * file:// / content:// / asset transparently, http(s) through OkHttp.
     */
    private fun scopedFactory(headers: Map<String, String>): androidx.media3.datasource.DefaultDataSource.Factory {
        val http = OkHttpDataSource.Factory(httpClient).apply {
            setDefaultRequestProperties(headers)
        }
        return androidx.media3.datasource.DefaultDataSource.Factory(context, http)
    }

    /**
     * HOLD5: primary DRM source merged with 0..N external-audio side
     * sources (per-kind adaptive or progressive, each with its own header
     * context) and 0..N single-sample subtitle sources (each with its own
     * context, local or remote). Period offsets adjust across the merge;
     * durations are not clipped.
     */
    private fun buildMediaSource(request: PlaybackRequest): androidx.media3.exoplayer.source.MergingMediaSource {
        val factory = mediaSourceFactory
            ?: throw IllegalStateException("player must be ensured before building sources")
        val headerPlan = activeHeaderPlan
            ?: throw IllegalStateException("player must be ensured before building sources")
        val primary = factory.createMediaSource(buildMediaItem(request, request.drm))
        val plan = DrmTransportPlan.planAudioMerge(request.uri, request.externalAudioTracks)
        val audioSources = plan.sides.map { side ->
            val sideHeaders = headerPlan.audioSides[side.url] ?: side.headers
            val item = MediaItem.Builder()
                .setUri(side.url)
                .apply { side.mime?.let(::setMimeType) }
                .build()
            when (side.kind) {
                DrmTransportPlan.AudioSourceKind.HLS ->
                    androidx.media3.exoplayer.hls.HlsMediaSource.Factory(scopedFactory(sideHeaders)).createMediaSource(item)
                DrmTransportPlan.AudioSourceKind.DASH ->
                    androidx.media3.exoplayer.dash.DashMediaSource.Factory(scopedFactory(sideHeaders)).createMediaSource(item)
                DrmTransportPlan.AudioSourceKind.PROGRESSIVE ->
                    androidx.media3.exoplayer.source.ProgressiveMediaSource.Factory(scopedFactory(sideHeaders)).createMediaSource(item)
            }
        }
        val subtitleSingles = DrmTransportPlan.planSubtitleAttachments(request.externalSubtitles).map { sub ->
            val subHeaders = headerPlan.subtitles[sub.url] ?: sub.headers
            val config = MediaItem.SubtitleConfiguration.Builder(android.net.Uri.parse(sub.url))
                .setMimeType(sub.mime)
                .setLanguage(sub.language)
                .setLabel(sub.title)
                // HOLD7: stable intent key for exact external matching.
                .setId(sub.id)
                .apply { if (sub.selected) setSelectionFlags(C.SELECTION_FLAG_DEFAULT) }
                .build()
            androidx.media3.exoplayer.source.SingleSampleMediaSource.Factory(scopedFactory(subHeaders))
                .createMediaSource(config, C.TIME_UNSET)
        }
        val all = audioSources + subtitleSingles
        if (all.isEmpty()) return androidx.media3.exoplayer.source.MergingMediaSource(primary)
        return androidx.media3.exoplayer.source.MergingMediaSource(
            true,
            false,
            primary,
            *all.toTypedArray()
        )
    }

    private fun buildMediaItem(
        request: PlaybackRequest,
        drm: DrmInfo?,
    ): MediaItem {
        val builder = MediaItem.Builder()
            .setUri(request.uri)
        request.mimeType?.takeIf { it.isNotBlank() }?.let { builder.setMimeType(it) }
        drm?.let { info ->
            buildDrmConfiguration(info)?.let(builder::setDrmConfiguration)
        }
        return builder.build()
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
