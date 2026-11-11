package ani.dantotsu.notifications.subscription

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import ani.dantotsu.App
import ani.dantotsu.FileUrl
import ani.dantotsu.R
import ani.dantotsu.connections.anilist.UrlMedia
import ani.dantotsu.hasNotificationPermission
import ani.dantotsu.notifications.Task
import ani.dantotsu.parsers.AnimeSources
import ani.dantotsu.parsers.Episode
import ani.dantotsu.parsers.MangaChapter
import ani.dantotsu.parsers.MangaSources
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.util.Logger
import eu.kanade.tachiyomi.data.notification.Notifications.CHANNEL_SUBSCRIPTION_CHECK
import eu.kanade.tachiyomi.data.notification.Notifications.CHANNEL_SUBSCRIPTION_CHECK_PROGRESS
import eu.kanade.tachiyomi.data.notification.Notifications.ID_SUBSCRIPTION_CHECK_PROGRESS
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean


class SubscriptionNotificationTask : Task {

    @SuppressLint("MissingPermission")
    override suspend fun execute(context: Context): Boolean {
        PrefManager.init(context)
        if (SubscriptionAppLockHelper.isAppLocked()) {
            Logger.log("SubscriptionNotificationTask: skipped (calculator lock enabled)")
            return true
        }
        if (running.compareAndSet(false, true)) {
            try {
                return withContext(Dispatchers.IO) {
                    App.context = context
                    Logger.log("SubscriptionNotificationTask: execute")
                    // A cold background process loads every extension first; 15 s was too short with many installed.
                    var timeout = 60_000L
                    do {
                        delay(1000)
                        timeout -= 1000
                    } while (timeout > 0 && (!AnimeSources.isInitialized || !MangaSources.isInitialized))
                    Logger.log("SubscriptionNotificationTask: timeout: $timeout")
                    if (timeout <= 0 && (!AnimeSources.isInitialized || !MangaSources.isInitialized)) {
                        Logger.log("SubscriptionNotificationTask: timed out waiting for sources initialization")
                        return@withContext false
                    }
                    val subscriptions = SubscriptionHelper.getSubscriptions()
                    var i = 0
                    val index = subscriptions.map { i++; it.key to i }.toMap()
                    val notificationManager = NotificationManagerCompat.from(context)

                    val progressEnabled: Boolean =
                        PrefManager.getVal(PrefName.SubscriptionCheckingNotifications)
                    val progressNotification = if (progressEnabled) getProgressNotification(
                        context,
                        subscriptions.size
                    ) else null
                    if (progressNotification != null && hasNotificationPermission(context)) {
                        notificationManager.notify(
                            ID_SUBSCRIPTION_CHECK_PROGRESS,
                            progressNotification.build()
                        )
                        //Seems like if the parent coroutine scope gets cancelled, the notification stays
                        //So adding this as a safeguard? dk if this will be useful
                        CoroutineScope(Dispatchers.Main).launch {
                            delay(5 * subscriptions.size * 1000L)
                            notificationManager.cancel(ID_SUBSCRIPTION_CHECK_PROGRESS)
                        }
                    }

                    fun progress(progress: Int, parser: String, media: String) {
                        if (progressNotification != null && hasNotificationPermission(context))
                            notificationManager.notify(
                                ID_SUBSCRIPTION_CHECK_PROGRESS,
                                progressNotification
                                    .setProgress(subscriptions.size, progress, false)
                                    .setContentText("$media on $parser")
                                    .build()
                            )
                    }

                    val aired = SubscriptionHelper.airedLimits(
                        subscriptions.values.filter { m -> m.isAnime }.map { m -> m.id }
                    )
                    var newSubscriptionCount = 0
                    subscriptions.toList().map {
                        val media = it.second
                        val text = if (media.isAnime) {
                            val parser =
                                SubscriptionHelper.getAnimeParser(media.id)
                            progress(index[it.first]!!, parser.name, media.name)
                            val ep: Episode? =
                                SubscriptionHelper.getEpisode(
                                    parser,
                                    media,
                                    aired[media.id],
                                )
                            if (ep != null) Triple(context.getString(R.string.episode) + "${ep.number}${
                                SubscriptionHelper.episodeTitle(ep.number, ep.title)?.let { ": $it" } ?: ""
                            }${
                                if (ep.isFiller) " [Filler]" else ""
                            } " + context.getString(R.string.just_released), ep.thumbnail, ep.number)
                            else null
                        } else {
                            val parser =
                                SubscriptionHelper.getMangaParser(media.id)
                            progress(index[it.first]!!, parser.name, media.name)
                            val ep: MangaChapter? =
                                SubscriptionHelper.getChapter(
                                    parser,
                                    media
                                )
                            if (ep != null) Triple(ep.number + " " + context.getString(R.string.just_released), null, ep.number)
                            else null
                        } ?: return@map
                        addSubscriptionToStore(
                            SubscriptionStore(
                                media.name,
                                text.first,
                                media.id,
                                image = media.image,
                                banner = media.banner
                            )
                        )
                        newSubscriptionCount++
                        val notification = createNotification(
                            context.applicationContext,
                            media,
                            text.first,
                            text.second
                        )
                        if (hasNotificationPermission(context)) {
                            NotificationManagerCompat.from(context)
                                .notify(
                                    CHANNEL_SUBSCRIPTION_CHECK,
                                    notificationId(media.id, text.third),
                                    notification
                                )
                        }
                    }

                    if (newSubscriptionCount > 0) {
                        val currentSubsCount = PrefManager.getVal<Int>(PrefName.UnreadSubscriptionNotifications)
                        PrefManager.setVal(PrefName.UnreadSubscriptionNotifications, currentSubsCount + newSubscriptionCount)
                    }

                    if (progressNotification != null) notificationManager.cancel(
                        ID_SUBSCRIPTION_CHECK_PROGRESS
                    )
                    true
                }
            } catch (e: CancellationException) {
                // Cancellation is not a check failure; the flag still resets below.
                throw e
            } catch (e: Exception) {
                Logger.log("SubscriptionNotificationTask: ${e.message}")
                Logger.log(e)
                return false
            } finally {
                running.set(false)
            }
        } else {
            // Another check (worker or alarm job) is running and covers this one.
            Logger.log("SubscriptionNotificationTask: skipped, a check is already running")
            return true
        }
    }

    companion object {
        /** One check at a time per process; every run creates a new task instance. */
        private val running = AtomicBoolean(false)

        /** Stable per media and episode, so one release replaces itself and two never collide. */
        internal fun notificationId(mediaId: Int, number: String): Int = (mediaId * 1_000_003) xor number.trim().hashCode()
    }

    @SuppressLint("MissingPermission")
    private fun createNotification(
        context: Context,
        media: SubscriptionHelper.Companion.SubscribeMedia,
        text: String,
        thumbnail: FileUrl?
    ): android.app.Notification {
        val pendingIntent = getIntent(context, media)
        val icon =
            if (media.isAnime) R.drawable.ic_round_movie_filter_24 else R.drawable.ic_round_menu_book_24

        val builder = NotificationCompat.Builder(context, CHANNEL_SUBSCRIPTION_CHECK)
            .setSmallIcon(icon)
            .setContentTitle(media.name)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)

        if (thumbnail != null) {
            val bitmap = getBitmapFromUrl(thumbnail.url)
            if (bitmap != null) {
                builder.setLargeIcon(bitmap)
            }
        }

        return builder.build()

    }

    private fun getProgressNotification(
        context: Context,
        size: Int
    ): NotificationCompat.Builder {
        return NotificationCompat.Builder(context, CHANNEL_SUBSCRIPTION_CHECK_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setSmallIcon(R.drawable.notification_icon)
            .setContentTitle(context.getString(R.string.checking_subscriptions_title))
            .setProgress(size, 0, false)
            .setOngoing(true)
            .setAutoCancel(false)
    }

    private fun getBitmapFromUrl(url: String): Bitmap? {
        return try {
            val inputStream = java.net.URL(url).openStream()
            BitmapFactory.decodeStream(inputStream)
        } catch (e: Exception) {
            null
        }
    }


    private fun getIntent(
        context: Context,
        media: SubscriptionHelper.Companion.SubscribeMedia
    ): PendingIntent {
        // Upstream ac76c79e (adapted): carry BOTH id keys so every reader
        // (UrlMedia "media", MainActivity "mediaId") resolves, plus type and
        // continue behavior; UPDATE_CURRENT instead of ONE_SHOT so repeat
        // notifications cannot deliver stale extras. Local invariant kept:
        // route via UrlMedia rather than MainActivity directly.
        val notifyIntent = Intent(context, UrlMedia::class.java)
            .putExtra("mediaId", media.id)
            .putExtra("media", media.id)
            .putExtra("mediaType", if (media.isAnime) "ANIME" else "MANGA")
            .putExtra("continue", true)
            .setAction(media.id.toString())
            .apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
        return PendingIntent.getActivity(
            context, media.id, notifyIntent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
        )
    }

    private fun addSubscriptionToStore(notification: SubscriptionStore) {
        val notificationStore = PrefManager.getNullableVal<List<SubscriptionStore>>(
            PrefName.SubscriptionNotificationStore,
            null
        ) ?: listOf()
        val newStore = notificationStore.toMutableList()
        if (newStore.size >= 100) {
            newStore.remove(newStore.minByOrNull { it.time })
        }
        if (newStore.any { it.title == notification.title && it.content == notification.content }) {
            return
        }

        newStore.add(notification)
        PrefManager.setVal(PrefName.SubscriptionNotificationStore, newStore)
    }
}
