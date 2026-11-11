package ani.dantotsu.settings.data

import android.app.Activity
import android.content.Context
import android.content.Intent
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.util.Logger
import java.io.File
import kotlin.system.exitProcess

/**
 * Restarts the app process. Needed when a value is read once per process: the HTTP client
 * (DNS, proxy), notification schedules, caches and everything a restore replaces.
 * Relaunching the activity alone keeps all of that on the old values.
 */
object ProcessRestart {

    /**
     * Writes pending preference changes to disk, relaunches the app and kills this process.
     * [screen] is reopened on top of the main screen, unless the app lock is set: the unlock
     * state lives in memory, so after a restart the app has to start at the lock screen.
     */
    fun restart(activity: Activity, screen: Intent? = null) {
        flushPreferences(activity)
        val launch = activity.packageManager.getLaunchIntentForPackage(activity.packageName)
        if (launch == null) {
            Logger.log("Restart: no launch intent")
            return
        }
        val main = Intent.makeRestartActivityTask(launch.component)
        val stack = if (screen != null && reopensScreen(appLockSet())) arrayOf(main, screen) else arrayOf(main)
        activity.startActivities(stack)
        // Exit before the new activities resume: the system then starts them in a new process.
        exitProcess(0)
    }

    /** Whether a restart may reopen the screen it was started from. */
    internal fun reopensScreen(appLockSet: Boolean): Boolean = !appLockSet

    private fun appLockSet(): Boolean =
        runCatching { PrefManager.getVal<String>(PrefName.AppPassword).isNotEmpty() }.getOrDefault(true)

    /**
     * `apply()` writes in the background; killing the process can drop them. An empty
     * `commit()` on each file waits until its earlier writes are on disk.
     */
    private fun flushPreferences(context: Context) {
        val dir = File(context.applicationInfo.dataDir, "shared_prefs")
        dir.listFiles { _, name -> name.endsWith(".xml") }?.forEach { file ->
            runCatching {
                context.getSharedPreferences(file.name.removeSuffix(".xml"), Context.MODE_PRIVATE)
                    .edit().commit()
            }.onFailure { Logger.log("Restart: ${file.name} not flushed: ${it.message}") }
        }
    }
}
