package ani.dantotsu.media.anime.cast

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.BitmapFactory
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import ani.dantotsu.R
import ani.dantotsu.util.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import okhttp3.OkHttpClient

class CastProxyServerService : Service() {

    private var server: CastProxyServer? = null
    private var ownedGeneration = -1L

    override fun onBind(p0: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // A null intent is a system restart after the process died: there is no cast
        // session to serve, so do not come back.
        if (intent == null || intent.action == ACTION_STOP || !access.isOpen) {
            stopSelf()
            return START_NOT_STICKY
        }
        ownedGeneration = access.generation

        if (isRunning.value) return START_NOT_STICKY

        val address = intent.getStringExtra(EXTRA_ADDRESS) ?: CastProxyServer.getLocalIpAddress()
        server = CastProxyServer(
            baseClient = OkHttpClient(),
            contentResolver = contentResolver,
            access = access,
            port = CastProxyServer.DEFAULT_PORT
        )

        try {
            server?.start()
            Logger.log("CastProxyServerService: Started proxy on $address:${CastProxyServer.DEFAULT_PORT}")
        } catch (e: Exception) {
            Logger.log("CastProxyServerService: Failed to start proxy server: ${e.message}")
            stopSelf()
            return START_NOT_STICKY
        }

        _isRunning.update { true }

        val stopIntent = Intent(this, CastProxyServerService::class.java).apply {
            action = ACTION_STOP
        }
        val pendingStopIntent = PendingIntent.getService(
            this,
            0,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        createNotificationChannel()

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_round_cast_24)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("Cast Stream Proxy Active")
            .setOngoing(true)
            .setAutoCancel(false)
            .addAction(
                R.drawable.ic_round_close_24,
                "Stop",
                pendingStopIntent
            )
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        return START_NOT_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Cast Proxy Service",
                NotificationManager.IMPORTANCE_LOW
            )
            notificationManager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        _isRunning.update { false }
        access.closeSession(ownedGeneration)
        try {
            server?.stop()
        } catch (e: Exception) {
            Logger.log("CastProxyServerService: Error stopping server: ${e.message}")
        }
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "cast_proxy_channel"
        private const val NOTIFICATION_ID = 445566
        const val EXTRA_ADDRESS = "EXTRA_ADDRESS"
        const val ACTION_STOP = "ani.dantotsu.cast.ACTION_STOP"

        /** Token and allow-list shared by the server and the code that builds cast urls. */
        val access = CastProxyAccess()

        private val _isRunning = MutableStateFlow(false)
        val isRunning = _isRunning.asStateFlow()

        /**
         * Starts the proxy. [newSession] rotates the token and clears the allow-list
         * (a new cast session); otherwise an open session is kept.
         */
        fun start(context: Context, newSession: Boolean = false) {
            val address = CastProxyServer.getLocalIpAddress()
            if (newSession) {
                access.openSession(address, CastProxyServer.DEFAULT_PORT)
            } else {
                access.ensureSession(address, CastProxyServer.DEFAULT_PORT)
            }
            val intent = Intent(context, CastProxyServerService::class.java).apply {
                putExtra(EXTRA_ADDRESS, address)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, CastProxyServerService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
}
