package ani.dantotsu.media.anime.player

import ani.dantotsu.R
import ani.dantotsu.snackString

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import androidx.media3.cast.CastPlayer
import androidx.media3.cast.SessionAvailabilityListener
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import ani.dantotsu.defaultHeaders
import ani.dantotsu.media.Media
import ani.dantotsu.media.anime.CustomCastButton
import ani.dantotsu.media.anime.CustomCastThemeFactory
import ani.dantotsu.parsers.Subtitle
import ani.dantotsu.parsers.Video
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.util.Logger
import com.google.android.gms.cast.framework.CastButtonFactory
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import java.util.concurrent.Executors

import androidx.mediarouter.media.MediaRouter
import androidx.mediarouter.media.MediaRouterParams
import ani.dantotsu.media.anime.cast.CastProxyServerService
import ani.dantotsu.media.anime.cast.CastSubtitleConverter
import com.google.android.gms.cast.framework.media.RemoteMediaClient

@UnstableApi
class PlayerCastManager(
    private val activity: AppCompatActivity,
    private val playerView: View,
    private val onCastStateChanged: (isPlaying: Boolean) -> Unit
) : SessionAvailabilityListener {

    var castPlayer: CastPlayer? = null
        private set
    var castContext: CastContext? = null
        private set
    var isCastApiAvailable = true
        private set

    var currentMediaItem: MediaItem? = null
        private set
    var currentVideo: Video? = null
        private set
    private var playbackEngine: PlaybackEngine? = null
    // The engine whose media was handed to the receiver, not the next local episode.
    private var remotePlaybackEngine: PlaybackEngine? = null
    private var playbackCoordinator: PlaybackCoordinator? = null

    var castScreenView: CastScreenView? = null

    private companion object {
        /** One thread for Cast init across player sessions (was one leaked thread per open). */
        val castInitExecutor = Executors.newSingleThreadExecutor()
    }

    var activeDeviceName: String? = null
        private set
    var currentPositionMs: Long = 0L
        private set
    var currentDurationMs: Long = 0L
        private set
    var isBuffering: Boolean = false
        private set

    var onSessionStartedListener: ((deviceName: String?, owner: PlaybackEngine?) -> Unit)? = null
    var onSessionEndedListener: ((owner: PlaybackEngine?, resumePositionMs: Long, shouldPlay: Boolean) -> Unit)? = null

    private val progressHandler = Handler(Looper.getMainLooper())
    private var isTrackingProgress = false

    private val progressRunnable = object : Runnable {
        override fun run() {
            if (isCasting() || castPlayer?.playbackState == Player.STATE_BUFFERING) {
                castPlayer?.let { cp ->
                    currentPositionMs = cp.currentPosition.coerceAtLeast(0L)
                    currentDurationMs = cp.duration.coerceAtLeast(0L)
                    isBuffering = cp.playbackState == Player.STATE_BUFFERING
                    castScreenView?.updateProgress(currentPositionMs, currentDurationMs)
                    castScreenView?.updatePlaybackState(cp.isPlaying, isBuffering)
                }
                progressHandler.postDelayed(this, 100)
            } else {
                isTrackingProgress = false
            }
        }
    }

    private val remoteProgressListener = RemoteMediaClient.ProgressListener { progressMs, durationMs ->
        currentPositionMs = progressMs.coerceAtLeast(0L)
        currentDurationMs = durationMs.coerceAtLeast(0L)
        castScreenView?.updateProgress(currentPositionMs, currentDurationMs)
    }

    private val sessionManagerListener = object : SessionManagerListener<CastSession> {
        override fun onSessionStarted(session: CastSession, sessionId: String) {
            activeDeviceName = session.castDevice?.friendlyName
            castScreenView?.updateDeviceName(activeDeviceName)
            session.remoteMediaClient?.addProgressListener(remoteProgressListener, 100)
            onSessionStartedListener?.invoke(activeDeviceName, remotePlaybackEngine)
        }

        override fun onSessionEnded(session: CastSession, error: Int) {
            val resumePos = currentPositionMs
            val wasPlaying = castPlayer?.isPlaying == true
            activeDeviceName = null
            session.remoteMediaClient?.removeProgressListener(remoteProgressListener)
            stopProgressTracking()
            CastProxyServerService.stop(activity)
            onSessionEndedListener?.invoke(remotePlaybackEngine, resumePos, wasPlaying)
        }

        override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) {
            activeDeviceName = session.castDevice?.friendlyName
            castScreenView?.updateDeviceName(activeDeviceName)
            session.remoteMediaClient?.addProgressListener(remoteProgressListener, 100)
            onSessionStartedListener?.invoke(activeDeviceName, remotePlaybackEngine)
        }

        override fun onSessionResumeFailed(session: CastSession, error: Int) {}
        override fun onSessionStartFailed(session: CastSession, error: Int) {
            CastProxyServerService.stop(activity)
        }
        override fun onSessionStarting(session: CastSession) {
            CastProxyServerService.start(activity, newSession = true)
        }
        override fun onSessionEnding(session: CastSession) {}
        override fun onSessionResuming(session: CastSession, sessionId: String) {}
        override fun onSessionSuspended(session: CastSession, reason: Int) {
            session.remoteMediaClient?.removeProgressListener(remoteProgressListener)
        }
    }

    private var pendingCastButtonSetup: (() -> Unit)? = null

    init {
        initCastApi()
    }

    private fun initCastApi() {
        try {
            MediaRouter.getInstance(activity).routerParams = MediaRouterParams.Builder()
                .setDialogType(MediaRouterParams.DIALOG_TYPE_DYNAMIC_GROUP)
                .setOutputSwitcherEnabled(true)
                .build()

            CastContext.getSharedInstance(activity, castInitExecutor)
                .addOnCompleteListener { task ->
                    if (task.isSuccessful && task.result != null) {
                        castContext = task.result
                        try {
                            castContext?.sessionManager?.addSessionManagerListener(sessionManagerListener, CastSession::class.java)
                            castPlayer = castContext?.let { CastPlayer(it, CastSubtitleConverter()) }
                            castPlayer?.setSessionAvailabilityListener(this)
                            setupCastPlayerListener()
                            activity.runOnUiThread {
                                pendingCastButtonSetup?.invoke()
                                pendingCastButtonSetup = null
                            }
                        } catch (e: Exception) {
                            isCastApiAvailable = false
                            activity.runOnUiThread {
                                pendingCastButtonSetup?.invoke()
                                pendingCastButtonSetup = null
                            }
                        }
                    } else {
                        isCastApiAvailable = false
                        activity.runOnUiThread {
                            pendingCastButtonSetup?.invoke()
                            pendingCastButtonSetup = null
                        }
                    }
                }
        } catch (e: Exception) {
            isCastApiAvailable = false
            activity.runOnUiThread {
                pendingCastButtonSetup?.invoke()
                pendingCastButtonSetup = null
            }
        }
    }

    private fun setupCastPlayerListener() {
        castPlayer?.addListener(object : Player.Listener {
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                onCastStateChanged(playWhenReady)
                castScreenView?.updatePlaybackState(playWhenReady, isBuffering)
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                onCastStateChanged(isPlaying)
                castScreenView?.updatePlaybackState(isPlaying, isBuffering)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                isBuffering = playbackState == Player.STATE_BUFFERING
                if (playbackState == Player.STATE_READY) {
                    castScreenView?.updatePlaybackState(true, isBuffering)
                }
                castScreenView?.updatePlaybackState(castPlayer?.isPlaying == true, isBuffering)
            }
        })
    }

    fun startProgressTracking() {
        if (!isTrackingProgress) {
            isTrackingProgress = true
            progressHandler.removeCallbacks(progressRunnable)
            progressHandler.post(progressRunnable)
        }
    }

    fun stopProgressTracking() {
        isTrackingProgress = false
        progressHandler.removeCallbacks(progressRunnable)
    }

    fun setupCastButton(
        castButton: CustomCastButton,
        media: Media?,
        video: Video?,
        subtitle: Subtitle?,
        hasExtSubtitles: Boolean,
        episodeTitle: String?
    ) {
        if (!PrefManager.getVal<Boolean>(PrefName.Cast)) {
            castButton.visibility = View.GONE
            return
        }

        castButton.visibility = View.VISIBLE
        castButton.setAlwaysVisible(true)

        if (PrefManager.getVal(PrefName.UseInternalCast)) {
            if (castContext == null && isCastApiAvailable) {
                pendingCastButtonSetup = {
                    setupCastButton(castButton, media, video, subtitle, hasExtSubtitles, episodeTitle)
                }
            } else if (castContext != null) {
                try {
                    CastButtonFactory.setUpMediaRouteButton(activity, castButton)
                    castButton.dialogFactory = CustomCastThemeFactory()
                    castButton.setAlwaysVisible(true)
                } catch (e: Exception) {
                    isCastApiAvailable = false
                    castButton.setCastCallback {
                        castExternal(media, video, subtitle, hasExtSubtitles, episodeTitle)
                    }
                }
            } else {
                castButton.setCastCallback {
                    castExternal(media, video, subtitle, hasExtSubtitles, episodeTitle)
                }
            }
        } else {
            castButton.setCastCallback {
                castExternal(media, video, subtitle, hasExtSubtitles, episodeTitle)
            }
        }
    }

    /**
     * The player loaded [mediaItem] in [engine]. While the TV plays an earlier item (next or
     * previous episode, autoplay, another server), the new item goes to the TV from
     * [startPositionMs] and the phone stays paused.
     *
     * @return true when the item was sent to the TV; the caller must not start local playback.
     */
    fun updateCurrentMedia(
        mediaItem: MediaItem?,
        engine: PlaybackEngine?,
        video: Video? = null,
        coordinator: PlaybackCoordinator? = null,
        startPositionMs: Long = 0L,
    ): Boolean {
        val wasCasting = isCasting()
        this.currentMediaItem = mediaItem
        this.playbackEngine = engine
        this.currentVideo = video
        this.playbackCoordinator = coordinator
        if (!wasCasting || mediaItem == null || engine == null || engine === remotePlaybackEngine) return false
        return sendToReceiver(mediaItem, startPositionMs)
    }

    fun isCasting(): Boolean = castPlayer?.isCastSessionAvailable == true && castPlayer?.currentMediaItem != null

    fun pause() {
        castPlayer?.pause()
    }

    fun play() {
        castPlayer?.play()
    }

    fun togglePlayPause() {
        castPlayer?.let { cp ->
            if (cp.isPlaying) {
                cp.pause()
            } else {
                cp.play()
            }
        }
    }

    fun seekTo(positionMs: Long) {
        currentPositionMs = positionMs
        castPlayer?.seekTo(positionMs)
    }

    fun setPlaybackSpeed(speed: Float) {
        castPlayer?.playbackParameters = PlaybackParameters(speed)
    }

    fun setRemoteVolume(volumeFraction: Float) {
        try {
            castContext?.sessionManager?.currentCastSession?.volume = volumeFraction.toDouble().coerceIn(0.0, 1.0)
        } catch (e: Exception) {
            Logger.log("Failed to set remote cast volume: ${e.message}")
        }
    }

    fun disconnect() {
        try {
            castContext?.sessionManager?.endCurrentSession(true)
        } catch (e: Exception) {
            Logger.log("Failed to end cast session: ${e.message}")
        }
    }

    fun castExternal(
        media: Media?,
        video: Video?,
        subtitle: Subtitle?,
        hasExtSubtitles: Boolean,
        episodeTitle: String?
    ) {
        val videoURL = video?.file?.url ?: return
        if (isTorrent(videoURL)) {
            snackString(activity.getString(R.string.cast_torrent_unsupported), activity)
            return
        }
        val subtitleUrl = if (!hasExtSubtitles || subtitle == null) video.file.url else subtitle.file.url
        val shareVideo = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(videoURL.toUri(), "video/*")
            setPackage("com.instantbits.cast.webvideo")
            if (subtitle != null) putExtra("subtitle", subtitleUrl)
            putExtra(
                "title",
                (media?.userPreferredName ?: "") + " : " + (episodeTitle ?: "")
            )
            putExtra("poster", media?.cover)
            val headers = Bundle().apply {
                defaultHeaders.forEach { putString(it.key, it.value) }
                video.file.headers?.forEach { putString(it.key, it.value) }
            }
            putExtra("android.media.intent.extra.HTTP_HEADERS", headers)
            putExtra("secure_uri", true)
        }

        try {
            activity.startActivity(shareVideo)
        } catch (ex: ActivityNotFoundException) {
            try {
                activity.startActivity(
                    Intent(Intent.ACTION_VIEW, "market://details?id=com.instantbits.cast.webvideo".toUri())
                )
            } catch (e: Exception) {
                activity.startActivity(
                    Intent(Intent.ACTION_VIEW, "https://play.google.com/store/apps/details?id=com.instantbits.cast.webvideo".toUri())
                )
            }
        }
    }

    /** Cast plays HTTP sources only; a torrent streams from this phone's own relay. */
    private fun isTorrent(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        if (url.startsWith("magnet:") || url.endsWith(".torrent")) return true
        return runCatching { ani.dantotsu.parsers.StreamTypeDetector.isTorrentStream(java.net.URL(url)) }
            .getOrDefault(false)
    }

    override fun onCastSessionAvailable() {
        val item = currentMediaItem ?: return
        sendToReceiver(item, playbackEngine?.positionMs ?: 0L)
    }

    /** Hands [item] to the receiver from [positionMs] and parks the local engine. */
    private fun sendToReceiver(item: MediaItem, positionMs: Long): Boolean {
        if (isTorrent(item.localConfiguration?.uri?.toString()) || isTorrent(currentVideo?.file?.url)) {
            snackString(activity.getString(R.string.cast_torrent_unsupported), activity)
            castContext?.sessionManager?.endCurrentSession(true)
            return false
        }
        if (!isCastApiAvailable || activity.isDestroyed) return false
        val cast = castPlayer ?: return false
        playbackCoordinator?.setPlaybackTarget(PlaybackTarget.REMOTE_CAST)
        playbackEngine?.pause()

        activeDeviceName = castContext?.sessionManager?.currentCastSession?.castDevice?.friendlyName
        castScreenView?.updateDeviceName(activeDeviceName)

        val proxiedItem = getProxiedMediaItem(item, currentVideo)

        remotePlaybackEngine = playbackEngine
        cast.setMediaItem(proxiedItem, positionMs)
        cast.prepare()
        cast.play()

        startProgressTracking()
        onSessionStartedListener?.invoke(activeDeviceName, remotePlaybackEngine)
        return true
    }

    private fun getProxiedMediaItem(item: MediaItem, video: Video?): MediaItem {
        val originalUri = item.localConfiguration?.uri ?: return item
        val uriStr = originalUri.toString()
        val headers = mutableMapOf<String, String>()
        headers.putAll(defaultHeaders)
        video?.file?.headers?.let { headers.putAll(it) }

        // Make sure the proxy runs and a session token exists (a resumed session may
        // reach here without onSessionStarting).
        val access = CastProxyServerService.access
        if (!access.isOpen || !CastProxyServerService.isRunning.value) {
            CastProxyServerService.start(activity)
        }

        // Exactly what this item may fetch through the proxy: its media and subtitles.
        val allowedProxy = mutableMapOf<String, Map<String, String>>()
        val allowedLocal = mutableListOf<String>()
        fun route(uri: android.net.Uri, isMedia: Boolean): android.net.Uri {
            val str = uri.toString()
            return when {
                str.startsWith("http://") || str.startsWith("https://") -> {
                    allowedProxy[str] = headers.toMap()
                    access.proxyUrl(str)?.toUri() ?: uri
                }
                str.startsWith("content://") || (!isMedia && str.startsWith("file://")) -> {
                    allowedLocal += str
                    access.localUrl(str)?.toUri() ?: uri
                }
                else -> uri
            }
        }

        val proxiedUri = route(originalUri, isMedia = true)
        val proxiedSubs = item.localConfiguration?.subtitleConfigurations?.map { sub ->
            sub.buildUpon().setUri(route(sub.uri, isMedia = false)).build()
        } ?: emptyList()
        access.setItem(allowedProxy, allowedLocal)

        return item.buildUpon()
            .setUri(proxiedUri)
            .setSubtitleConfigurations(proxiedSubs)
            .build()
    }

    override fun onCastSessionUnavailable() {
        val resumePosition = currentPositionMs
        val wasPlaying = castPlayer?.isPlaying == true
        stopProgressTracking()
        if (remotePlaybackEngine === playbackEngine) {
            playbackCoordinator?.setPlaybackTarget(PlaybackTarget.LOCAL)
        }
        onSessionEndedListener?.invoke(remotePlaybackEngine, resumePosition, wasPlaying)
    }

    fun release() {
        remotePlaybackEngine = null
        stopProgressTracking()
        try {
            castContext?.sessionManager?.removeSessionManagerListener(sessionManagerListener, CastSession::class.java)
        } catch (_: Exception) {}
        castPlayer?.setSessionAvailabilityListener(null)
        castPlayer?.release()
        castPlayer = null
        castScreenView = null
    }
}
