package ani.dantotsu.download

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import ani.dantotsu.R
import eu.kanade.tachiyomi.data.notification.Notifications

private const val TIMEOUT_NOTICE_ID = 1110

/** Tell the user downloads stopped because the dataSync time limit ran out. */
fun notifyTimeoutPause(context: Context) {
    if (ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
        != PackageManager.PERMISSION_GRANTED
    ) return
    val notification = NotificationCompat.Builder(context, Notifications.CHANNEL_DOWNLOADER_ERROR)
        .setSmallIcon(R.drawable.ic_download_24)
        .setContentTitle(context.getString(R.string.download_paused_fgs_limit))
        .setAutoCancel(true)
        .build()
    NotificationManagerCompat.from(context).notify(TIMEOUT_NOTICE_ID, notification)
}
