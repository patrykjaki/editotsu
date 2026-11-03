package ani.dantotsu.media.anime

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import ani.dantotsu.R

@OptIn(UnstableApi::class)
class AnimePlayerService : MediaSessionService() {

    override fun onCreate() {
        super.onCreate()
        val channelId = "anime_player_channel"
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                getString(R.string.video),
                NotificationManager.IMPORTANCE_LOW
            )
            notificationManager.createNotificationChannel(channel)
        }

        val notificationProvider = DefaultMediaNotificationProvider.Builder(this)
            .setChannelId(channelId)
            .setChannelName(R.string.video)
            .build()
            .apply { setSmallIcon(R.drawable.notification_icon) }
        setMediaNotificationProvider(notificationProvider)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        sessions.filter { it !== activeSession }.forEach { removeSession(it) }
        activeSession?.let { session ->
            if (!sessions.contains(session)) {
                addSession(session)
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return activeSession
    }

    companion object {
        var activeSession: MediaSession? = null

        /**
         * Called while the app is in the foreground. A plain start: Media3 moves the
         * service to the foreground itself once the session plays, so there is no
         * 5-second startForeground deadline to miss while a stream buffers.
         */
        fun start(context: Context, session: MediaSession) {
            activeSession = session
            val intent = Intent(context, AnimePlayerService::class.java)
            try {
                context.startService(intent)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        fun stop(context: Context) {
            activeSession = null
            val intent = Intent(context, AnimePlayerService::class.java)
            try {
                context.stopService(intent)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}
