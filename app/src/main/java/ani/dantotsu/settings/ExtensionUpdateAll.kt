package ani.dantotsu.settings

import android.app.NotificationManager
import android.content.Context
import android.widget.Button
import androidx.core.app.NotificationCompat
import androidx.core.view.isVisible
import ani.dantotsu.R
import ani.dantotsu.parsers.novel.NovelExtension
import ani.dantotsu.snackString
import ani.dantotsu.util.Logger
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.extension.InstallStep
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import rx.Observable
import rx.Subscription
import rx.android.schedulers.AndroidSchedulers
import java.util.concurrent.ConcurrentHashMap

/**
 * "Update all" on the installed-extension lists (as in Aniyomi/Mihon), and the
 * single-row update, which is a batch of one.
 *
 * Each extension gets its own notification (id from its package) with its name.
 * When the whole batch has ended, one snackbar sums it up. Updates run to the end
 * on the application context: unsubscribing cancels an install, so they are not
 * tied to the screen; each subscription is dropped when its update ends.
 */
object ExtensionUpdateAll {
    fun <T> pending(items: List<T>, hasUpdate: (T) -> Boolean): List<T> = items.filter(hasUpdate)

    fun novelHasUpdate(ext: NovelExtension): Boolean = when (ext) {
        is NovelExtension.Installed -> ext.hasUpdate
        is NovelExtension.JsPlugin -> ext.hasUpdate
        else -> false
    }

    /** One extension to update: its package, shown name, and the install stream. */
    class Item(val pkg: String, val name: String, val update: () -> Observable<InstallStep>)

    /** Result of one update: null [error] means updated. */
    data class Outcome(val name: String, val error: String?)

    /** What the summary snackbar says, without Android resources (tested on the JVM). */
    sealed class Summary {
        data class OneUpdated(val name: String) : Summary()
        data class OneFailed(val name: String, val reason: String?) : Summary()
        data class AllUpdated(val count: Int) : Summary()
        data class SomeFailed(val updated: Int, val failedNames: List<String>) : Summary()
    }

    fun summarize(outcomes: List<Outcome>): Summary? {
        if (outcomes.isEmpty()) return null
        if (outcomes.size == 1) {
            val o = outcomes[0]
            return if (o.error == null) Summary.OneUpdated(o.name) else Summary.OneFailed(o.name, o.error)
        }
        val failed = outcomes.filter { it.error != null }.map { it.name }
        return if (failed.isEmpty()) Summary.AllUpdated(outcomes.size)
        else Summary.SomeFailed(outcomes.size - failed.size, failed)
    }

    /** Why an update whose install stream has ended did not install; null when it did. */
    enum class Failure { NOT_LISTED, CANCELLED, FAILED }

    /**
     * The stream ends after its last step whatever that step is, so ending says nothing:
     * only [InstallStep.Installed] is an update. No step at all means no repository lists
     * the extension any more; Idle is a dismissed install prompt.
     */
    fun failureOf(last: InstallStep?): Failure? = when (last) {
        InstallStep.Installed -> null
        null -> Failure.NOT_LISTED
        InstallStep.Idle -> Failure.CANCELLED
        else -> Failure.FAILED
    }

    private fun failureText(context: Context, failure: Failure): String = context.getString(
        when (failure) {
            Failure.NOT_LISTED -> R.string.extension_update_not_listed
            Failure.CANCELLED -> R.string.extension_update_cancelled
            Failure.FAILED -> R.string.extension_update_install_failed
        }
    )

    fun notificationId(pkg: String): Int = "extension-update:$pkg".hashCode()

    private val running = ConcurrentHashMap<String, Subscription>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /**
     * Starts every update not already running. [onFinished] runs on the main thread
     * once all of them ended, with true when at least one failed.
     */
    fun run(
        context: Context,
        items: List<Item>,
        refresh: (suspend () -> Unit)? = null,
        onFinished: (anyFailed: Boolean) -> Unit = {},
    ) {
        if (refresh == null) return start(context, items, onFinished)
        // The repository list in memory can be hours old, and a repository replaces its apk
        // files on every release: an update started from it downloads a file that is gone.
        // So the list is fetched again first, as opening the Available tab does.
        scope.launch {
            runCatching { refresh() }.onFailure { Logger.log(it) }
            start(context, items, onFinished)
        }
    }

    private fun start(context: Context, items: List<Item>, onFinished: (anyFailed: Boolean) -> Unit) {
        val app = context.applicationContext
        val toStart = items.filter { !running.containsKey(it.pkg) }
        if (toStart.isEmpty()) {
            onFinished(false)
            return
        }
        val outcomes = ArrayList<Outcome>()
        val endedPkgs = HashSet<String>()
        fun ended(item: Item, reason: String?) {
            endedPkgs += item.pkg
            running.remove(item.pkg)
            outcomes += Outcome(item.name, reason)
            if (reason == null) notifyDone(app, item) else notifyFailed(app, item, reason)
            if (outcomes.size == toStart.size) {
                summarize(outcomes)?.let { snackString(text(app, it)) }
                onFinished(outcomes.any { it.error != null })
            }
        }
        for (item in toStart) {
            var last: InstallStep? = null
            val subscription = item.update()
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                    { step ->
                        last = step
                        notifyProgress(app, item, step)
                    },
                    { error ->
                        Logger.log(error)
                        ended(item, error.message ?: app.getString(R.string.extension_update_unknown_error))
                    },
                    { ended(item, failureOf(last)?.let { failureText(app, it) }) },
                )
            // a stream that ended synchronously already called ended()
            if (item.pkg !in endedPkgs) {
                running[item.pkg] = subscription
            }
        }
    }

    fun text(context: Context, summary: Summary): String = when (summary) {
        is Summary.OneUpdated -> context.getString(R.string.extension_update_done_title, summary.name)
        is Summary.OneFailed -> context.getString(
            R.string.extension_update_one_failed, summary.name,
            summary.reason ?: context.getString(R.string.extension_update_unknown_error),
        )
        is Summary.AllUpdated -> context.resources.getQuantityString(
            R.plurals.extension_update_summary_done, summary.count, summary.count,
        )
        is Summary.SomeFailed -> context.getString(
            R.string.extension_update_summary_failed, summary.updated, summary.failedNames.size,
            summary.failedNames.joinToString(", "),
        )
    }

    private fun manager(context: Context) =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun stepText(context: Context, step: InstallStep): String? = when (step) {
        InstallStep.Pending -> context.getString(R.string.extension_update_step_pending)
        InstallStep.Downloading -> context.getString(R.string.extension_update_step_downloading)
        InstallStep.Installing -> context.getString(R.string.extension_update_step_installing)
        else -> null
    }

    private fun notifyProgress(context: Context, item: Item, step: InstallStep) {
        val text = stepText(context, step) ?: return
        val n = NotificationCompat.Builder(context, Notifications.CHANNEL_DOWNLOADER_PROGRESS)
            .setSmallIcon(R.drawable.ic_round_sync_24)
            .setContentTitle(context.getString(R.string.extension_update_progress_title, item.name))
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        manager(context).notify(notificationId(item.pkg), n)
    }

    private fun notifyFailed(context: Context, item: Item, reason: String) {
        val n = NotificationCompat.Builder(context, Notifications.CHANNEL_DOWNLOADER_ERROR)
            .setSmallIcon(R.drawable.ic_round_info_24)
            .setContentTitle(context.getString(R.string.extension_update_failed_title, item.name))
            .setContentText(reason)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        manager(context).notify(notificationId(item.pkg), n)
    }

    private fun notifyDone(context: Context, item: Item) {
        val n = NotificationCompat.Builder(context, Notifications.CHANNEL_DOWNLOADER_PROGRESS)
            .setSmallIcon(R.drawable.ic_circle_check)
            .setContentTitle(context.getString(R.string.extension_update_done_title, item.name))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        manager(context).notify(notificationId(item.pkg), n)
    }

    /**
     * Shows the button with the count when [pending] is non-empty; a tap updates each
     * one. The button turns back on when an update fails (the list does not change then).
     */
    fun <T> bind(button: Button, pending: List<T>, refresh: (suspend () -> Unit)? = null, toItem: (T) -> Item) {
        button.isVisible = pending.isNotEmpty()
        button.text = button.context.getString(R.string.extensions_update_all, pending.size)
        button.setOnClickListener {
            button.isEnabled = false
            run(button.context, pending.map(toItem), refresh) { anyFailed ->
                if (anyFailed) button.isEnabled = true
            }
        }
        button.isEnabled = true
    }
}
