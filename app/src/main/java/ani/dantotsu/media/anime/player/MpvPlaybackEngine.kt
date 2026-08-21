package ani.dantotsu.media.anime.player

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.view.Surface
import `is`.xyz.mpv.MPV
import `is`.xyz.mpv.MPVNode
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean

interface EngineDispatcher {
    fun post(action: () -> Unit)
    fun quit()
}

class HandlerEngineDispatcher(name: String = "MpvEngineDispatcher") : EngineDispatcher {
    private val thread = HandlerThread(name).apply { start() }
    private val handler = Handler(thread.looper)

    override fun post(action: () -> Unit) {
        handler.post(action)
    }

    override fun quit() {
        thread.quitSafely()
    }
}

class DirectEngineDispatcher : EngineDispatcher {
    override fun post(action: () -> Unit) {
        action()
    }

    override fun quit() {}
}

sealed interface EngineLifecycle {
    data object New : EngineLifecycle
    data object Initializing : EngineLifecycle
    data object Ready : EngineLifecycle
    data class Failed(val cause: Throwable?) : EngineLifecycle
    data object Released : EngineLifecycle
}

private fun createDefaultMainDispatcher(context: Context?): (Runnable) -> Unit {
    return if (context != null) {
        val mainHandler = Handler(Looper.getMainLooper())
        val dispatcher: (Runnable) -> Unit = { r -> mainHandler.post(r) }
        dispatcher
    } else {
        val dispatcher: (Runnable) -> Unit = { r -> r.run() }
        dispatcher
    }
}

class MpvPlaybackEngine(
    private val context: Context? = null,
    private val clientFactory: MpvClientFactory = RealMpvClientFactory(),
    private val engineDispatcher: EngineDispatcher = if (context != null) HandlerEngineDispatcher() else DirectEngineDispatcher(),
    private val postToMain: (Runnable) -> Unit = createDefaultMainDispatcher(context)
) : PlaybackEngine, MPV.EventObserver {

    // Backward-compatible constructor for direct MpvClient injection (e.g. testing)
    constructor(
        context: Context?,
        mpvClient: MpvClient,
        engineDispatcher: EngineDispatcher = if (context != null) HandlerEngineDispatcher() else DirectEngineDispatcher(),
        postToMain: (Runnable) -> Unit = createDefaultMainDispatcher(context)
    ) : this(
        context = context,
        clientFactory = object : MpvClientFactory {
            override fun createFresh(): MpvClient = mpvClient
        },
        engineDispatcher = engineDispatcher,
        postToMain = postToMain
    )

    companion object {
        const val MPV_FORMAT_NONE = 0
        const val MPV_FORMAT_STRING = 1
        const val MPV_FORMAT_OSD_STRING = 2
        const val MPV_FORMAT_FLAG = 3
        const val MPV_FORMAT_INT64 = 4
        const val MPV_FORMAT_DOUBLE = 5
        const val MPV_FORMAT_NODE = 6

        const val MPV_EVENT_NONE = 0
        const val MPV_EVENT_START_FILE = 6
        const val MPV_EVENT_END_FILE = 7
        const val MPV_EVENT_FILE_LOADED = 8
        const val MPV_EVENT_PLAYBACK_RESTART = 21
    }

    enum class LoadOperationState {
        CREATED,
        SUBMITTED,
        BOUND,
        STARTED,
        LOADED,
        RETIRING,
        ENDED,
        CANCELLED
    }

    private class LoadOperation(
        val generationId: Long,
        val request: PlaybackRequest,
        val lease: AutoCloseable?,
        var playlistEntryId: Long? = null,
        var state: LoadOperationState = LoadOperationState.CREATED,
        var externalSubtitlesAttached: Boolean = false,
        var durationKnown: Boolean = false
    )

    private val listeners = CopyOnWriteArraySet<PlaybackListener>()

    private val releaseRequested = AtomicBoolean(false)
    private var isInitialized = false
    private var observerRegistered = false

    private var mpvClient: MpvClient = clientFactory.createFresh()
    private var ownerToken: MpvOwnerToken? = null
    private var ownerRequestId: Long? = null

    var engineLifecycle: EngineLifecycle = EngineLifecycle.New
        private set

    private var generationCounter = 0L
    private val operationsByGen = ConcurrentHashMap<Long, LoadOperation>()
    private val operationsByEntryId = ConcurrentHashMap<Long, LoadOperation>()
    private val supersededLeases = mutableListOf<AutoCloseable>()
    private var activeOperation: LoadOperation? = null
    private var activeEntryId: Long? = null

    private var pausedForCache = false
    private var isPausedInternal = true
    private var sessionSubtitleStyle: SubtitleStyle = SubtitleStyle()

    @Volatile
    var currentSnapshot: PlaybackSnapshot = PlaybackSnapshot()
        private set

    override val positionMs: Long
        get() = currentSnapshot.positionMs
    override val durationMs: Long
        get() = currentSnapshot.durationMs
    override val isPlaying: Boolean
        get() = currentSnapshot.isActuallyPlaying
    override val playWhenReady: Boolean
        get() = currentSnapshot.userPlayIntent
    override val playbackSpeed: Float
        get() = currentSnapshot.playbackSpeed
    override val playbackState: PlaybackState
        get() = currentSnapshot.playbackState
    override val availableTracks: List<PlayerTrack>
        get() = currentSnapshot.tracks
    override val currentAudioTrack: PlayerTrack?
        get() = currentSnapshot.tracks.firstOrNull { it.type == TrackType.AUDIO && it.id == currentSnapshot.selectedAudioTrackId }
    override val currentSubtitleTrack: PlayerTrack?
        get() = currentSnapshot.tracks.firstOrNull { it.type == TrackType.SUBTITLE && it.id == currentSnapshot.selectedSubtitleTrackId }
    override val videoDimensions: VideoDimensions?
        get() = currentSnapshot.videoDimensions
    override val lastError: PlaybackError?
        get() = currentSnapshot.error
    override val currentRequest: PlaybackRequest?
        get() = activeOperation?.request

    init {
        engineDispatcher.post {
            initMpv()
        }
    }

    private fun ensureInitialized(): Boolean {
        if (!isInitialized && !releaseRequested.get() && engineLifecycle !is EngineLifecycle.Failed) {
            initMpv()
        }
        return isInitialized && !releaseRequested.get()
    }

    private fun initMpv() {
        if (isInitialized || releaseRequested.get() || engineLifecycle is EngineLifecycle.Failed) return
        engineLifecycle = EngineLifecycle.Initializing
        try {
            val targetContext = context?.applicationContext ?: context
            ani.dantotsu.util.Logger.log("MPVSTEP 01 before create")
            mpvClient.create(targetContext)
            ani.dantotsu.util.Logger.log("MPVSTEP 02 after create")

            val settings = if (context != null) {
                buildCurrentBootstrapSettings(context)
            } else {
                listOf(
                    ResolvedInitSetting.Apply(MpvInitOption("config", MpvSettingValue.StringValue("no"))),
                    ResolvedInitSetting.Apply(MpvInitOption("vo", MpvSettingValue.StringValue("gpu"))),
                    ResolvedInitSetting.Apply(MpvInitOption("hwdec", MpvSettingValue.StringValue("auto"))),
                    ResolvedInitSetting.Apply(MpvInitOption("hwdec-codecs", MpvSettingValue.StringValue("all"))),
                    ResolvedInitSetting.Apply(MpvInitOption("sub-auto", MpvSettingValue.StringValue("no"))),
                    ResolvedInitSetting.Apply(MpvInitOption("keep-open", MpvSettingValue.StringValue("no"))),
                    ResolvedInitSetting.Apply(MpvInitOption("ytdl", MpvSettingValue.StringValue("no"))),
                    ResolvedInitSetting.Apply(MpvInitOption("force-window", MpvSettingValue.StringValue("no"))),
                    ResolvedInitSetting.Apply(MpvInitOption("idle", MpvSettingValue.StringValue("yes")))
                )
            }

            ani.dantotsu.util.Logger.log("MPVSTEP 03 before options")
            settings.filterIsInstance<ResolvedInitSetting.Apply>()
                .filter { it.phase == BootstrapPhase.PRE_INIT }
                .forEach { applySetting(mpvClient, it.option) }
            ani.dantotsu.util.Logger.log("MPVSTEP 04 after options")

            ani.dantotsu.util.Logger.log("MPVSTEP 05 before init")
            mpvClient.init()
            ani.dantotsu.util.Logger.log("MPVSTEP 06 after init")

            ani.dantotsu.util.Logger.log("MPVSTEP 07 before addObserver")
            mpvClient.addObserver(this)
            observerRegistered = true
            ani.dantotsu.util.Logger.log("MPVSTEP 08 after addObserver")

            // Register observers
            ani.dantotsu.util.Logger.log("MPVSTEP 09 before observeProperty")
            mpvClient.observeProperty("time-pos", MPV_FORMAT_DOUBLE)
            mpvClient.observeProperty("duration", MPV_FORMAT_DOUBLE)
            mpvClient.observeProperty("pause", MPV_FORMAT_FLAG)
            mpvClient.observeProperty("paused-for-cache", MPV_FORMAT_FLAG)
            mpvClient.observeProperty("speed", MPV_FORMAT_DOUBLE)
            mpvClient.observeProperty("track-list", MPV_FORMAT_NODE)
            mpvClient.observeProperty("sid", MPV_FORMAT_INT64)
            mpvClient.observeProperty("aid", MPV_FORMAT_INT64)
            mpvClient.observeProperty("dwidth", MPV_FORMAT_INT64)
            mpvClient.observeProperty("dheight", MPV_FORMAT_INT64)
            ani.dantotsu.util.Logger.log("MPVSTEP 10 after observeProperty")

            // Start paused so playback only begins when coordinator.play()
            // explicitly requests audio focus and unpauses. This keeps
            // isPausedInternal = true in sync with MPV's actual state.
            ani.dantotsu.util.Logger.log("MPVSTEP 11 before initial pause property set")
            mpvClient.setPropertyBoolean("pause", true)
            ani.dantotsu.util.Logger.log("MPVSTEP 12 after initial pause property set")

            isInitialized = true
            engineLifecycle = EngineLifecycle.Ready
            ani.dantotsu.util.Logger.log("MPVSTEP 13 initialization complete")
        } catch (t: Throwable) {
            ani.dantotsu.util.Logger.log("MPVSTEP ERROR in initMpv: ${t.message}")
            try {
                if (observerRegistered) {
                    mpvClient.removeObserver(this)
                    observerRegistered = false
                }
            } catch (_: Throwable) {}
            try {
                mpvClient.destroy()
            } catch (_: Throwable) {}
            engineLifecycle = EngineLifecycle.Failed(t)
            val error = PlaybackError(
                category = ErrorCategory.UNKNOWN,
                message = "Failed to initialize MPV: ${t.message}"
            )
            updateSnapshot(currentSnapshot.copy(
                playbackState = PlaybackState.Error(error),
                error = error
            ))
            notifyPlaybackStateChanged(PlaybackState.Error(error))
        }
    }

    override fun play() {
        engineDispatcher.post {
            if (!ensureInitialized()) return@post
            updateSnapshot(currentSnapshot.copy(userPlayIntent = true))
            notifyPlayWhenReadyChanged(true)
            mpvClient.setPropertyBoolean("pause", false)
        }
    }

    override fun pause() {
        engineDispatcher.post {
            if (!ensureInitialized()) return@post
            updateSnapshot(currentSnapshot.copy(userPlayIntent = false))
            notifyPlayWhenReadyChanged(false)
            mpvClient.setPropertyBoolean("pause", true)
        }
    }

    override fun togglePlayPause() {
        engineDispatcher.post {
            if (currentSnapshot.userPlayIntent) {
                pause()
            } else {
                play()
            }
        }
    }

    override fun seekTo(positionMs: Long) {
        engineDispatcher.post {
            if (!ensureInitialized()) return@post
            val sec = TimeConverter.msToSeconds(positionMs)
            mpvClient.command("seek", sec.toString(), "absolute+exact")
        }
    }

    override fun seekRelative(offsetMs: Long) {
        engineDispatcher.post {
            if (!ensureInitialized()) return@post
            val sec = TimeConverter.msToSecondsSigned(offsetMs)
            ani.dantotsu.util.Logger.log("MpvPlaybackEngine: seekRelative offsetMs=$offsetMs -> ${sec}s")
            mpvClient.command("seek", sec.toString(), "relative+exact")
        }
    }

    override fun setPlaybackSpeed(speed: Float) {
        engineDispatcher.post {
            if (!ensureInitialized()) return@post
            val clamped = speed.coerceIn(0.25f, 50.0f)
            mpvClient.setPropertyDouble("speed", clamped.toDouble())
        }
    }

    override fun setVideoSurface(surface: Surface?) {
        engineDispatcher.post {
            if (releaseRequested.get()) return@post
            ani.dantotsu.util.Logger.log("MPVSTEP 30 before setVideoSurface (isValid=${surface?.isValid})")
            if (!ensureInitialized()) return@post
            if (surface != null && surface.isValid) {
                mpvClient.attachSurface(surface)
                try {
                    mpvClient.setPropertyString("force-window", "yes")
                    mpvClient.setPropertyString("vo", "gpu")
                } catch (_: Exception) {}
            } else {
                try {
                    mpvClient.setPropertyString("vo", "null")
                    mpvClient.setPropertyString("force-window", "no")
                } catch (_: Exception) {}
                mpvClient.detachSurface()
            }
            ani.dantotsu.util.Logger.log("MPVSTEP 31 after setVideoSurface")
        }
    }

    override fun setVideoSurfaceSize(width: Int, height: Int) {
        engineDispatcher.post {
            if (!ensureInitialized() || width <= 0 || height <= 0) return@post
            try {
                mpvClient.setPropertyString("android-surface-size", "${width}x${height}")
            } catch (_: Exception) {}
        }
    }

    override fun setResizeMode(mode: ResizeMode) {
        engineDispatcher.post {
            if (!ensureInitialized()) return@post
            when (mode) {
                ResizeMode.FIT -> {
                    mpvClient.setPropertyString("keepaspect", "yes")
                    mpvClient.setPropertyDouble("video-aspect-override", -1.0)
                    mpvClient.setPropertyDouble("video-zoom", 0.0)
                    mpvClient.setPropertyDouble("panscan", 0.0)
                }
                ResizeMode.ZOOM -> {
                    mpvClient.setPropertyString("keepaspect", "yes")
                    mpvClient.setPropertyDouble("video-aspect-override", -1.0)
                    mpvClient.setPropertyDouble("panscan", 1.0)
                }
                ResizeMode.STRETCH -> {
                    mpvClient.setPropertyString("keepaspect", "no")
                    mpvClient.setPropertyDouble("panscan", 0.0)
                    mpvClient.setPropertyDouble("video-zoom", 0.0)
                    mpvClient.setPropertyDouble("video-aspect-override", -1.0)
                }
                ResizeMode.FOUR_THREE -> {
                    mpvClient.setPropertyString("keepaspect", "yes")
                    mpvClient.setPropertyDouble("video-aspect-override", 4.0 / 3.0)
                    mpvClient.setPropertyDouble("panscan", 0.0)
                }
                ResizeMode.SIXTEEN_NINE -> {
                    mpvClient.setPropertyString("keepaspect", "yes")
                    mpvClient.setPropertyDouble("video-aspect-override", 16.0 / 9.0)
                    mpvClient.setPropertyDouble("panscan", 0.0)
                }
            }
        }
    }

    override fun loadMedia(request: PlaybackRequest) {
        engineDispatcher.post {
            ani.dantotsu.util.Logger.log("MPVSTEP 40 before loadMedia uri=${request.uri}")
            if (!ensureInitialized()) return@post

            generationCounter++
            val genId = generationCounter

            // Open content URI resource lease if needed
            var pfdLease: ParcelFileDescriptor? = null
            val loadUri = if (request.uri.startsWith("content://")) {
                try {
                    val pfd = context?.contentResolver?.openFileDescriptor(Uri.parse(request.uri), "r")
                    if (pfd == null) {
                        val error = PlaybackError(
                            category = ErrorCategory.SOURCE,
                            message = "Could not open content descriptor: ${request.uri}",
                            fatal = true,
                            retryable = false
                        )
                        updateSnapshot(currentSnapshot.copy(
                            playbackState = PlaybackState.Error(error),
                            error = error
                        ))
                        notifyPlaybackStateChanged(PlaybackState.Error(error))
                        return@post
                    }
                    pfdLease = pfd
                    "fd://${pfd.fd}"
                } catch (e: Exception) {
                    val error = PlaybackError(
                        category = ErrorCategory.SOURCE,
                        message = "Failed to open content URI (${e.message}): ${request.uri}",
                        fatal = true,
                        retryable = false
                    )
                    updateSnapshot(currentSnapshot.copy(
                        playbackState = PlaybackState.Error(error),
                        error = error
                    ))
                    notifyPlaybackStateChanged(PlaybackState.Error(error))
                    return@post
                }
            } else {
                request.uri
            }

            val operation = LoadOperation(
                generationId = genId,
                request = request,
                lease = pfdLease,
                state = LoadOperationState.SUBMITTED
            )
            operationsByGen[genId] = operation
            activeOperation = operation

            // Clean up any old cancelled operations that never started
            cleanupSupersededOperations(exceptGenId = genId)

            // Reset media-scoped snapshot
            updateSnapshot(currentSnapshot.copy(
                generationId = genId,
                playlistEntryId = null,
                playbackState = PlaybackState.Buffering,
                positionMs = 0L,
                durationMs = 0L,
                tracks = emptyList(),
                selectedAudioTrackId = null,
                selectedSubtitleTrackId = null,
                videoDimensions = null,
                error = null
            ))
            notifyPlaybackStateChanged(PlaybackState.Buffering)

            try {
                // Build per-file options
                val perFileOptions = MpvNetworkOptions.buildPerFileOptions(
                    headers = request.headers,
                    startPositionMs = request.startPositionMs
                )

                // Apply subtitle style if session-configured
                applySubtitleStyleInternal(sessionSubtitleStyle)

                ani.dantotsu.util.Logger.log("MPVSTEP 41 before loadfile command (options: ${MpvNetworkOptions.getRedactedOptionsDescription(request.headers, request.startPositionMs)})")
                if (perFileOptions.isNotBlank()) {
                    mpvClient.command("loadfile", loadUri, "replace", "-1", perFileOptions)
                } else {
                    mpvClient.command("loadfile", loadUri, "replace")
                }
                ani.dantotsu.util.Logger.log("MPVSTEP 42 after loadfile command")

                // Immediately bind playlist entry ID if available from MPV playlist
                val insertedId = getLatestPlaylistEntryId()
                if (insertedId != null) {
                    operation.playlistEntryId = insertedId
                    operation.state = LoadOperationState.BOUND
                    operationsByEntryId[insertedId] = operation
                }
            } catch (e: Exception) {
                ani.dantotsu.util.Logger.log("MPVSTEP ERROR in loadMedia: ${e.message}")
                val error = PlaybackError(
                    category = ErrorCategory.UNKNOWN,
                    message = "Failed to load media: ${e.message}",
                    fatal = true,
                    retryable = false
                )
                updateSnapshot(currentSnapshot.copy(
                    playbackState = PlaybackState.Error(error),
                    error = error
                ))
                notifyPlaybackStateChanged(PlaybackState.Error(error))
            }
        }
    }

    private fun cleanupSupersededOperations(exceptGenId: Long) {
        val iterator = operationsByGen.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val op = entry.value
            if (op.generationId < exceptGenId && (op.state == LoadOperationState.CREATED || op.state == LoadOperationState.SUBMITTED || op.state == LoadOperationState.BOUND)) {
                op.state = LoadOperationState.CANCELLED
                if (op.lease != null) {
                    supersededLeases.add(op.lease)
                }
                op.playlistEntryId?.let { operationsByEntryId.remove(it) }
                iterator.remove()
            }
        }
    }

    private fun drainSupersededLeases() {
        if (supersededLeases.isEmpty()) return
        val toClose = ArrayList(supersededLeases)
        supersededLeases.clear()
        toClose.forEach { lease ->
            try { lease.close() } catch (_: Exception) {}
        }
    }

    override fun selectAudioTrack(id: Int?) {
        engineDispatcher.post {
            if (!ensureInitialized()) return@post
            if (id == null) {
                mpvClient.setPropertyString("aid", "no")
            } else {
                mpvClient.setPropertyInt("aid", id)
            }
        }
    }

    override fun selectSubtitleTrack(id: Int?) {
        engineDispatcher.post {
            if (!ensureInitialized()) return@post
            if (id == null) {
                mpvClient.setPropertyString("sid", "no")
            } else {
                mpvClient.setPropertyInt("sid", id)
            }
        }
    }

    override fun addExternalSubtitle(
        uri: String,
        title: String?,
        language: String?,
        select: Boolean
    ) {
        engineDispatcher.post {
            if (!ensureInitialized()) return@post
            val flag = if (select) "select" else "auto"
            mpvClient.command("sub-add", uri, flag, title ?: "", language ?: "")
        }
    }

    override fun applySubtitleStyle(style: SubtitleStyle) {
        engineDispatcher.post {
            sessionSubtitleStyle = style
            if (ensureInitialized()) {
                applySubtitleStyleInternal(style)
            }
        }
    }

    private fun applySubtitleStyleInternal(style: SubtitleStyle) {
        when (style.mode) {
            SubtitleStyleMode.SOURCE -> {
                mpvClient.setPropertyString("sub-ass-override", "no")
            }
            SubtitleStyleMode.CUSTOM -> {
                mpvClient.setPropertyString("sub-ass-override", "force")
                val scaledFontSize = MpvSubtitleStyleMapper.spToMpvScaledSize(style.fontSizeSp)
                val scaledMargin = MpvSubtitleStyleMapper.dpToMpvScaledMargin(style.bottomMarginDp)
                val primaryHex = MpvSubtitleStyleMapper.colorToMpvHex(style.textColor)
                val outlineHex = MpvSubtitleStyleMapper.colorToMpvHex(style.borderColor)
                val backgroundHex = MpvSubtitleStyleMapper.colorToMpvHex(style.backgroundColor)

                mpvClient.setPropertyDouble("sub-font-size", scaledFontSize.toDouble())
                mpvClient.setPropertyDouble("sub-margin-y", scaledMargin.toDouble())
                mpvClient.setPropertyString("sub-color", primaryHex)
                mpvClient.setPropertyString("sub-border-color", outlineHex)
                mpvClient.setPropertyString("sub-back-color", backgroundHex)
                mpvClient.setPropertyDouble("sub-border-size", style.borderWidth.toDouble())

                if (!style.fontFamily.isNullOrBlank()) {
                    mpvClient.setPropertyString("sub-font", style.fontFamily)
                }
            }
        }
    }

    override fun addListener(listener: PlaybackListener) {
        listeners.add(listener)
    }

    override fun removeListener(listener: PlaybackListener) {
        listeners.remove(listener)
    }

    override fun stop() {
        engineDispatcher.post {
            if (!ensureInitialized()) return@post
            mpvClient.command("stop")
            updateSnapshot(currentSnapshot.copy(playbackState = PlaybackState.Idle))
            notifyPlaybackStateChanged(PlaybackState.Idle)
        }
    }

    override fun release() {
        if (releaseRequested.getAndSet(true)) return
        isInitialized = false
        engineLifecycle = EngineLifecycle.Released
        listeners.clear()

        engineDispatcher.post {
            try {
                if (observerRegistered) {
                    mpvClient.removeObserver(this)
                    observerRegistered = false
                }
                operationsByGen.values.forEach { op ->
                    try { op.lease?.close() } catch (_: Exception) {}
                }
                operationsByGen.clear()
                operationsByEntryId.clear()
                drainSupersededLeases()
                activeOperation = null
                activeEntryId = null
                mpvClient.destroy()
            } catch (_: Exception) {}
            engineDispatcher.quit()
        }

        updateSnapshot(currentSnapshot.copy(playbackState = PlaybackState.Idle))
    }

    // --- MPV EventObserver Native Callbacks ---

    override fun eventProperty(property: String) {
        engineDispatcher.post {
            handlePropertyDirty(property)
        }
    }

    override fun eventProperty(property: String, value: Long) {
        engineDispatcher.post {
            handlePropertyDirty(property)
        }
    }

    override fun eventProperty(property: String, value: Boolean) {
        engineDispatcher.post {
            handlePropertyDirty(property)
        }
    }

    override fun eventProperty(property: String, value: String) {
        engineDispatcher.post {
            handlePropertyDirty(property)
        }
    }

    override fun eventProperty(property: String, value: Double) {
        engineDispatcher.post {
            handlePropertyDirty(property)
        }
    }

    override fun eventProperty(property: String, value: MPVNode) {
        engineDispatcher.post {
            handlePropertyDirty(property)
        }
    }

    private fun getPlayingEntryId(): Long? {
        try {
            val node = mpvClient.getPropertyNode("playlist")
            val arr = node?.asArray()
            if (arr != null) {
                // First pass: strictly check playing entry
                for (item in arr) {
                    val map = item.asMap()
                    if (map?.get("playing")?.asBoolean() == true) {
                        val id = map["id"]?.asInt()?.toLong() ?: map["id"]?.asDouble()?.toLong()
                        if (id != null) return id
                    }
                }
                // Second pass: check current entry
                for (item in arr) {
                    val map = item.asMap()
                    if (map?.get("current")?.asBoolean() == true) {
                        val id = map["id"]?.asInt()?.toLong() ?: map["id"]?.asDouble()?.toLong()
                        if (id != null) return id
                    }
                }
            }
        } catch (_: Exception) {}
        return activeEntryId
    }

    private fun getLatestPlaylistEntryId(): Long? {
        try {
            val node = mpvClient.getPropertyNode("playlist")
            val arr = node?.asArray()
            if (arr != null && arr.isNotEmpty()) {
                var maxId: Long? = null
                for (item in arr) {
                    val map = item.asMap()
                    val id = map?.get("id")?.asInt()?.toLong() ?: map?.get("id")?.asDouble()?.toLong()
                    if (id != null) {
                        if (maxId == null || id > maxId) {
                            maxId = id
                        }
                    }
                }
                return maxId
            }
        } catch (_: Exception) {}
        return null
    }

    private fun handlePropertyDirty(propertyName: String) {
        if (releaseRequested.get() || !isInitialized) return
        val activeOp = activeOperation ?: return
        val playingId = getPlayingEntryId()
        if (playingId == null || (activeOp.playlistEntryId != null && playingId != activeOp.playlistEntryId)) {
            // Stale property from retired file
            return
        }

        when (propertyName) {
            "time-pos" -> {
                val sec = mpvClient.getPropertyDouble("time-pos") ?: 0.0
                val posMs = TimeConverter.secondsToMs(sec)
                if (currentSnapshot.positionMs != posMs) {
                    updateSnapshot(currentSnapshot.copy(positionMs = posMs))
                    notifyPositionChanged(posMs, currentSnapshot.durationMs)
                }
            }
            "duration" -> {
                val sec = mpvClient.getPropertyDouble("duration") ?: 0.0
                val durMs = TimeConverter.secondsToMs(sec)
                if (currentSnapshot.durationMs != durMs) {
                    updateSnapshot(currentSnapshot.copy(durationMs = durMs))
                    notifyPositionChanged(currentSnapshot.positionMs, durMs)
                }
                if (durMs > 0L && !activeOp.durationKnown) {
                    activeOp.durationKnown = true
                    notifyDurationKnown(activeOp.generationId, durMs)
                }
            }
            "pause" -> {
                val paused = mpvClient.getPropertyBoolean("pause") ?: true
                isPausedInternal = paused
                computeAndPublishPlayingState()
            }
            "paused-for-cache" -> {
                val pausedCache = mpvClient.getPropertyBoolean("paused-for-cache") ?: false
                pausedForCache = pausedCache
                val isLoaded = activeOp.state == LoadOperationState.LOADED
                if (isLoaded && currentSnapshot.playbackState is PlaybackState.Buffering && !pausedCache) {
                    updateSnapshot(currentSnapshot.copy(playbackState = PlaybackState.Ready))
                    notifyPlaybackStateChanged(PlaybackState.Ready)
                } else if (pausedCache && currentSnapshot.playbackState !is PlaybackState.Buffering) {
                    updateSnapshot(currentSnapshot.copy(playbackState = PlaybackState.Buffering))
                    notifyPlaybackStateChanged(PlaybackState.Buffering)
                }
                computeAndPublishPlayingState()
            }
            "speed" -> {
                val speed = mpvClient.getPropertyDouble("speed")?.toFloat() ?: 1.0f
                if (currentSnapshot.playbackSpeed != speed) {
                    updateSnapshot(currentSnapshot.copy(playbackSpeed = speed))
                    notifySpeedChanged(speed)
                }
            }
            "track-list" -> {
                val node = mpvClient.getPropertyNode("track-list")
                val array = node?.asArray()
                val rawList = array?.mapNotNull { itemNode ->
                    val map = itemNode.asMap() ?: return@mapNotNull null
                    val convertedMap = mutableMapOf<String, Any?>()
                    for ((k, v) in map) {
                        val str = v.asString()
                        val bool = v.asBoolean()
                        val num = v.asInt() ?: v.asDouble()
                        convertedMap[k] = str ?: bool ?: num
                    }
                    convertedMap
                } ?: emptyList()
                val parsedTracks = MpvTrackMapper.parseTrackList(rawList)
                val audioId = parsedTracks.firstOrNull { it.type == TrackType.AUDIO && it.selected }?.id
                val subId = parsedTracks.firstOrNull { it.type == TrackType.SUBTITLE && it.selected }?.id
                updateSnapshot(currentSnapshot.copy(
                    tracks = parsedTracks,
                    selectedAudioTrackId = audioId,
                    selectedSubtitleTrackId = subId
                ))
                notifyTracksChanged(parsedTracks)
            }
            "aid" -> {
                val aid = mpvClient.getPropertyInt("aid")
                updateSnapshot(currentSnapshot.copy(selectedAudioTrackId = aid))
            }
            "sid" -> {
                val sid = mpvClient.getPropertyInt("sid")
                updateSnapshot(currentSnapshot.copy(selectedSubtitleTrackId = sid))
            }
            "dwidth", "dheight" -> {
                val w = mpvClient.getPropertyInt("dwidth") ?: 0
                val h = mpvClient.getPropertyInt("dheight") ?: 0
                if (w > 0 && h > 0) {
                    val dims = VideoDimensions(w, h)
                    if (currentSnapshot.videoDimensions != dims) {
                        updateSnapshot(currentSnapshot.copy(videoDimensions = dims))
                        notifyVideoSizeChanged(w, h)
                    }
                }
            }
        }
    }

    override fun event(eventId: Int, data: MPVNode) {
        val map = try { data.asMap() } catch (_: Exception) { null }
        val playlistEntryId = try { map?.get("playlist_entry_id")?.asInt()?.toLong() ?: map?.get("playlist_entry_id")?.asDouble()?.toLong() } catch (_: Exception) { null }
        val reason = try { map?.get("reason")?.asString() } catch (_: Exception) { null }
        val fileError = try { map?.get("file_error")?.asString() } catch (_: Exception) { null }

        handleEngineEvent(eventId, playlistEntryId, reason, fileError)
    }

    internal fun handleEngineEvent(
        eventId: Int,
        playlistEntryId: Long? = null,
        reason: String? = null,
        fileError: String? = null
    ) {
        engineDispatcher.post {
            if (releaseRequested.get()) return@post
            when (eventId) {
                MPV_EVENT_START_FILE -> {
                    if (playlistEntryId != null) {
                        val op = operationsByEntryId[playlistEntryId]
                            ?: activeOperation?.takeIf { it.state == LoadOperationState.SUBMITTED || it.state == LoadOperationState.BOUND }
                            ?: operationsByGen[generationCounter]
                            ?: activeOperation

                        if (op != null) {
                            op.playlistEntryId = playlistEntryId
                            op.state = LoadOperationState.STARTED
                            operationsByEntryId[playlistEntryId] = op
                            activeOperation = op
                            activeEntryId = playlistEntryId

                            drainSupersededLeases()

                            updateSnapshot(currentSnapshot.copy(
                                generationId = op.generationId,
                                playlistEntryId = playlistEntryId
                            ))
                        }
                    }
                }

                MPV_EVENT_FILE_LOADED -> {
                    val playingId = getPlayingEntryId()
                    val op = if (playingId != null) operationsByEntryId[playingId] else activeOperation
                    if (op != null && (playingId == null || op.playlistEntryId == playingId)) {
                        op.state = LoadOperationState.LOADED

                        // Attach external subtitles for this operation once
                        if (!op.externalSubtitlesAttached) {
                            op.externalSubtitlesAttached = true
                            op.request.externalSubtitles.forEach { sub ->
                                val flag = if (sub.selected) "select" else "auto"
                                mpvClient.command("sub-add", sub.url, flag, sub.title ?: "", sub.language ?: "")
                            }
                        }

                        updateSnapshot(currentSnapshot.copy(
                            playbackState = PlaybackState.Ready
                        ))
                        notifyPlaybackStateChanged(PlaybackState.Ready)
                        computeAndPublishPlayingState()
                    }
                }

                MPV_EVENT_PLAYBACK_RESTART -> {
                    val playingId = getPlayingEntryId()
                    val op = if (playingId != null) operationsByEntryId[playingId] else activeOperation
                    if (op != null && (playingId == null || op.playlistEntryId == playingId)) {
                        updateSnapshot(currentSnapshot.copy(
                            playbackState = PlaybackState.Ready
                        ))
                        notifyPlaybackStateChanged(PlaybackState.Ready)
                        computeAndPublishPlayingState()
                    }
                }

                MPV_EVENT_END_FILE -> {
                    if (reason?.lowercase() == "redirect") {
                        val newEntryId = getPlayingEntryId() ?: getLatestPlaylistEntryId()
                        if (newEntryId != null && activeOperation != null) {
                            activeOperation?.playlistEntryId = newEntryId
                            operationsByEntryId[newEntryId] = activeOperation!!
                            activeEntryId = newEntryId
                        }
                        updateSnapshot(currentSnapshot.copy(playbackState = PlaybackState.Buffering))
                        notifyPlaybackStateChanged(PlaybackState.Buffering)
                        return@post
                    }

                    val op = if (playlistEntryId != null) {
                        operationsByEntryId.remove(playlistEntryId)
                    } else null

                    if (op != null) {
                        op.state = LoadOperationState.ENDED
                        try { op.lease?.close() } catch (_: Exception) {}
                        operationsByGen.remove(op.generationId)
                    }

                    // Guard: if END_FILE belongs to a superseded generation, do not overwrite new generation state
                    if (op != null && currentSnapshot.generationId != null && op.generationId < currentSnapshot.generationId!!) {
                        return@post
                    }

                    val result = MpvEventMapper.parseEndFile(
                        reason = reason,
                        fileError = fileError,
                        playlistEntryId = playlistEntryId,
                        activeEntryId = activeEntryId,
                        currentPositionMs = currentSnapshot.positionMs,
                        durationMs = currentSnapshot.durationMs
                    )

                    if (result.isStale) return@post

                    val errorObj = if (result.state is PlaybackState.Error) result.state.error else null
                    updateSnapshot(currentSnapshot.copy(
                        playbackState = result.state,
                        error = errorObj
                    ))
                    notifyPlaybackStateChanged(result.state)
                    computeAndPublishPlayingState()
                }
            }
        }
    }

    private fun computeAndPublishPlayingState() {
        val actuallyPlaying = !isPausedInternal && !pausedForCache && (currentSnapshot.playbackState is PlaybackState.Ready)
        if (currentSnapshot.isActuallyPlaying != actuallyPlaying) {
            updateSnapshot(currentSnapshot.copy(isActuallyPlaying = actuallyPlaying))
            notifyIsPlayingChanged(actuallyPlaying)
        }
    }

    private fun updateSnapshot(snapshot: PlaybackSnapshot) {
        currentSnapshot = snapshot
        postToMain {
            listeners.forEach { it.onSnapshotChanged(snapshot) }
        }
    }

    private fun notifyPlaybackStateChanged(state: PlaybackState) {
        postToMain {
            listeners.forEach { it.onPlaybackStateChanged(state) }
        }
    }

    private fun notifyPositionChanged(posMs: Long, durMs: Long) {
        postToMain {
            listeners.forEach { it.onPositionChanged(posMs, durMs) }
        }
    }

    private fun notifySpeedChanged(speed: Float) {
        postToMain {
            listeners.forEach { it.onPlaybackSpeedChanged(speed) }
        }
    }

    private fun notifyPlayWhenReadyChanged(playWhenReady: Boolean) {
        postToMain {
            listeners.forEach { it.onPlayWhenReadyChanged(playWhenReady) }
        }
    }

    private fun notifyTracksChanged(tracks: List<PlayerTrack>) {
        postToMain {
            listeners.forEach { it.onTracksChanged(tracks) }
        }
    }

    private fun notifyVideoSizeChanged(width: Int, height: Int) {
        postToMain {
            listeners.forEach { it.onVideoSizeChanged(width, height) }
        }
    }

    private fun notifyIsPlayingChanged(isPlaying: Boolean) {
        postToMain {
            listeners.forEach { it.onIsPlayingChanged(isPlaying) }
        }
    }

    private fun notifyDurationKnown(genId: Long, durationMs: Long) {
        postToMain {
            listeners.forEach { it.onDurationKnown(genId, durationMs) }
        }
    }
}
