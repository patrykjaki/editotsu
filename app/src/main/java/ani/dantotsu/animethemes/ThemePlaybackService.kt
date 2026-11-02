package ani.dantotsu.animethemes

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import ani.dantotsu.R
import ani.dantotsu.defaultHeaders
import ani.dantotsu.okHttpClient
import ani.dantotsu.util.Logger
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/**
 * Plays AnimeThemes videos. Runs as a media session service so a theme keeps
 * playing with the screen off or the app in the background, with lock-screen and
 * notification controls. Separate from the episode player.
 */
@OptIn(UnstableApi::class)
class ThemePlaybackService : MediaSessionService() {

    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.anime_themes_title), NotificationManager.IMPORTANCE_LOW)
            )
        }
        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this)
                .setChannelId(CHANNEL_ID)
                .setChannelName(R.string.anime_themes_title)
                .build()
        )

        val http = OkHttpDataSource.Factory(okHttpClient)
            .setUserAgent(defaultHeaders["User-Agent"])
        val sources = DefaultMediaSourceFactory(this)
            .setDataSourceFactory(http)
            .setLoadErrorHandlingPolicy(ThemeLoadErrorPolicy())
        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(sources)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        player.addListener(FallbackOnError(player))

        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, ThemePlayerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaSession.Builder(this, player)
            .setId(SESSION_ID)
            .setSessionActivity(open)
            .setCallback(ResolveUris)
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Swiping the app away ends theme playback.
        session?.player?.let { it.stop(); it.clearMediaItems() }
        stopSelf()
    }

    override fun onDestroy() {
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }

    /** Controllers send the URL in request metadata; the session turns it back into a playable item. */
    private object ResolveUris : MediaSession.Callback {
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> = Futures.immediateFuture(
            mediaItems.mapNotNull { item ->
                val uri = item.requestMetadata.mediaUri ?: item.localConfiguration?.uri ?: return@mapNotNull null
                item.buildUpon().setUri(uri).build()
            }.toMutableList()
        )
    }

    /**
     * When a video fails after the load retries, switch the same theme to its next
     * video (other resolution / source / credits) and continue from the same spot.
     */
    private class FallbackOnError(private val player: ExoPlayer) : Player.Listener {
        override fun onPlayerError(error: PlaybackException) {
            val index = player.currentMediaItemIndex
            val item = player.currentMediaItem ?: return
            val extras = item.mediaMetadata.extras ?: return
            val fallbacks = extras.getStringArrayList(EXTRA_FALLBACKS).orEmpty()
            val attempt = extras.getInt(EXTRA_ATTEMPT, 0)
            Logger.log("AnimeThemes: playback error ${error.errorCodeName} on item $index, fallback ${attempt + 1}/${fallbacks.size}")
            val next = fallbacks.getOrNull(attempt) ?: return
            val position = player.currentPosition
            val replaced = item.buildUpon()
                .setUri(next)
                .setRequestMetadata(item.requestMetadata.buildUpon().setMediaUri(android.net.Uri.parse(next)).build())
                .setMediaMetadata(
                    item.mediaMetadata.buildUpon()
                        .setExtras(Bundle(extras).apply { putInt(EXTRA_ATTEMPT, attempt + 1) })
                        .build()
                )
                .build()
            player.replaceMediaItem(index, replaced)
            player.seekTo(index, position)
            player.prepare()
            player.play()
        }
    }

    /**
     * The video CDN answers 503 / 429 under load: retry those with backoff.
     * Other HTTP errors (403, 404) fail at once so the fallback video is tried.
     */
    private class ThemeLoadErrorPolicy : DefaultLoadErrorHandlingPolicy(ThemeQueue.MAX_RETRIES) {
        override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
            val e = loadErrorInfo.exception
            if (e is HttpDataSource.InvalidResponseCodeException) {
                return ThemeQueue.retryDelayMs(
                    e.responseCode,
                    loadErrorInfo.errorCount,
                    e.headerFields["Retry-After"]?.firstOrNull() ?: e.headerFields["retry-after"]?.firstOrNull(),
                ) ?: C.TIME_UNSET
            }
            return super.getRetryDelayMsFor(loadErrorInfo)
        }
    }

    companion object {
        const val SESSION_ID = "animethemes"
        const val CHANNEL_ID = "anime_themes_channel"
        const val EXTRA_FALLBACKS = "animethemes.fallbacks"
        const val EXTRA_ATTEMPT = "animethemes.attempt"
    }
}
