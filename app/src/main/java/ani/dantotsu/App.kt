package ani.dantotsu

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.util.Log
import ani.dantotsu.addons.download.DownloadAddonManager
import ani.dantotsu.torrent.TorrentServerManager
import ani.dantotsu.aniyomi.anime.custom.AppModule
import ani.dantotsu.aniyomi.anime.custom.PreferenceModule
import ani.dantotsu.connections.comments.CommentsAPI
import ani.dantotsu.connections.comments.CommentsOptIn
import ani.dantotsu.connections.crashlytics.CrashlyticsInterface
import ani.dantotsu.notifications.TaskScheduler
import ani.dantotsu.others.CrashProcess
import ani.dantotsu.others.DisabledReports
import ani.dantotsu.parsers.AnimeSources
import ani.dantotsu.parsers.MangaSources
import ani.dantotsu.parsers.NovelSources
import ani.dantotsu.parsers.novel.NovelExtensionManager
import ani.dantotsu.media.anime.ExoplayerView
import ani.dantotsu.settings.SettingsActivity
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.util.FinalExceptionHandler
import ani.dantotsu.util.Logger
import com.google.android.material.color.DynamicColors
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.extension.anime.AnimeExtensionManager
import eu.kanade.tachiyomi.extension.manga.MangaExtensionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import logcat.AndroidLogcatLogger
import logcat.LogPriority
import logcat.LogcatLogger
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.addSingletonFactory
import uy.kohesive.injekt.api.get


@SuppressLint("StaticFieldLeak")
class App : Application() {
    private lateinit var animeExtensionManager: AnimeExtensionManager
    private lateinit var mangaExtensionManager: MangaExtensionManager
    private lateinit var novelExtensionManager: NovelExtensionManager
    private lateinit var torrentServerManager: TorrentServerManager
    private lateinit var downloadAddonManager: DownloadAddonManager

    init {
        instance = this
    }

    val mFTActivityLifecycleCallbacks = FTActivityLifecycleCallbacks()

    @OptIn(DelicateCoroutinesApi::class)
    override fun onCreate() {
        super.onCreate()
        PrefManager.init(this)
        // The crash screen runs in its own process. It needs prefs, theme and logging
        // only; running the rest of the init there could crash it the same way again.
        if (CrashProcess.isCrashProcess(currentProcessName())) {
            Logger.init(this)
            if (PrefManager.getVal<Boolean>(PrefName.UseMaterialYou)) {
                DynamicColors.applyToActivitiesIfAvailable(this)
            }
            return
        }
        ani.dantotsu.connections.discord.DiscordLegacyCleanup.cleanOnce(this)

        val crashlytics =
            ani.dantotsu.connections.crashlytics.CrashlyticsFactory.createCrashlytics()
        Injekt.addSingletonFactory<CrashlyticsInterface> { crashlytics }
        crashlytics.initialize(this)
        Logger.init(this)
        Thread.setDefaultUncaughtExceptionHandler(FinalExceptionHandler())
        if (BuildConfig.TORRENT_BENCH) {
            ani.dantotsu.torrent.bench.TorrentBenchHook.install(this)
        }
        Logger.log(Log.WARN, "App: Logging started")

        Injekt.importModule(AppModule(this))
        Injekt.importModule(PreferenceModule(this))


        val useMaterialYou: Boolean = PrefManager.getVal(PrefName.UseMaterialYou)
        if (useMaterialYou) {
            DynamicColors.applyToActivitiesIfAvailable(this)
        }
        registerActivityLifecycleCallbacks(mFTActivityLifecycleCallbacks)

        crashlytics.setCrashlyticsCollectionEnabled(!DisabledReports)
        (PrefManager.getVal(PrefName.SharedUserID) as Boolean).let {
            if (!it) return@let
            val aUsername = PrefManager.getVal(PrefName.AnilistUserName, null as String?)
            if (aUsername != null) {
                crashlytics.setCustomKey("aUsername", aUsername)
            }
        }
        crashlytics.setCustomKey("device Info", SettingsActivity.getDeviceInfo())

        initializeNetwork()

        // MAX MERGE M3 (upstream e954d9f1): re-assert auto-backup cadence
        // on cold start (idempotent UPDATE; cancels when OFF).
        try {
            ani.dantotsu.settings.data.AutoBackupWorker.schedule(
                this@App,
                PrefManager.getVal(PrefName.AutoBackupInterval)
            )
        } catch (e: Exception) {
            Logger.log("Failed to schedule auto backup: ${e.message}")
        }

        // daily list sync between the trackers, when the user turned it on
        try {
            ani.dantotsu.connections.mal.ListSyncWorker.apply(this@App)
        } catch (e: Exception) {
            Logger.log("Failed to schedule list sync: ${e.message}")
        }

        // MAX MERGE M5 (upstream 36d57c56): keep the offline AniSkip CSV
        // dump fresh in the background (weekly, no-op when current).
        CoroutineScope(Dispatchers.IO).launch {
            delay(10_000)
            runCatching {
                ani.dantotsu.others.AniskipCsvFallback.getInstance(this@App).syncIfNeeded()
            }.onFailure {
                Logger.log("AniSkip CSV sync failed: ${it.message}")
            }
        }

        setupNotificationChannels()
        if (!LogcatLogger.isInstalled) {
            LogcatLogger.install(AndroidLogcatLogger(LogPriority.VERBOSE))
        }

        // Comments are opt-in on both flavors; installs that got "on" from the old default
        // are switched off once.
        CommentsOptIn.resetOnce()

        val scope = CoroutineScope(Dispatchers.IO)
        scope.launch {
            animeExtensionManager = Injekt.get()
            launch {
                animeExtensionManager.findAvailableExtensions()
            }
            Logger.log("Anime Extensions: ${animeExtensionManager.installedExtensionsFlow.first()}")
            AnimeSources.init(animeExtensionManager.installedExtensionsFlow, animeExtensionManager)
        }
        scope.launch {
            mangaExtensionManager = Injekt.get()
            launch {
                mangaExtensionManager.findAvailableExtensions()
            }
            Logger.log("Manga Extensions: ${mangaExtensionManager.installedExtensionsFlow.first()}")
            MangaSources.init(mangaExtensionManager.installedExtensionsFlow, mangaExtensionManager)
        }
        scope.launch {
            novelExtensionManager = Injekt.get()
            launch {
                novelExtensionManager.findAvailableExtensions()
            }
            Logger.log("Novel Extensions: ${novelExtensionManager.installedExtensionsFlow.first()}")
            NovelSources.init(novelExtensionManager.allInstalledExtensionsFlow)
        }
        GlobalScope.launch {
            torrentServerManager = Injekt.get()
            downloadAddonManager = Injekt.get()
            if (torrentServerManager.isAvailable()) {
                torrentServerManager.start()
            }
            downloadAddonManager.init()

            try {
                val torrentCacheManager = ani.dantotsu.torrent.TorrentCacheManager.getInstance(this@App)
                val activeHashes = if (torrentServerManager.isAvailable()) {
                    torrentServerManager.getActiveTorrentHashes()
                } else {
                    emptySet()
                }
                torrentCacheManager.reconcileOnStartup(activeHashes)
                torrentCacheManager.cleanTransientFiles()
            } catch (e: Exception) {
                Logger.log("App: TorrentCache startup reconciliation failed: ${e.message}")
            }

            if (CommentsOptIn.isEnabled()) {
                CommentsAPI.fetchAuthToken(this@App)
            }

            val useAlarmManager = PrefManager.getVal<Boolean>(PrefName.UseAlarmManager)
            val scheduler = TaskScheduler.create(this@App, useAlarmManager)
            try {
                scheduler.scheduleAllTasks(this@App)
            } catch (e: IllegalStateException) {
                Logger.log("Failed to schedule tasks")
                Logger.log(e)
            }
        }
    }

    private fun currentProcessName(): String? =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            getProcessName()
        } else {
            runCatching {
                java.io.File("/proc/self/cmdline").readText().trimEnd(Char(0))
            }.getOrNull()
        }

    private fun setupNotificationChannels() {
        try {
            Notifications.createChannels(this)
        } catch (e: Exception) {
            Logger.log("Failed to modify notification channels")
            Logger.log(e)
        }
    }

    inner class FTActivityLifecycleCallbacks : ActivityLifecycleCallbacks {
        var currentActivity: Activity? = null
        var lastActivity: String? = null
        private var startedActivityCount = 0

        override fun onActivityCreated(p0: Activity, p1: Bundle?) {
            lastActivity = p0.javaClass.simpleName
        }

        val inBackground: Boolean get() = startedActivityCount <= 0
        private var afterPin = false

        override fun onActivityStarted(p0: Activity) {
            val fromBackground = startedActivityCount <= 0
            currentActivity = p0
            startedActivityCount++
            val playerTask = ExoplayerView.hiddenPlayerTaskId ?: return
            if (p0 is ExoplayerView) {
                if (p0.taskId == playerTask) ExoplayerView.hiddenPlayerTaskId = null
                return
            }
            // the PIN screen goes first; the player comes back after it
            if (p0 is ani.dantotsu.others.calc.CalcActivity) {
                if (fromBackground) afterPin = true
                return
            }
            if (!fromBackground && !afterPin) return
            afterPin = false
            ExoplayerView.hiddenPlayerTaskId = null
            // a link or a notification opened something specific: go there instead
            if (opensSomethingSpecific(p0)) return
            // The player is single-task: starting it brings its own task forward. The system
            // doesn't list that task once PiP is closed, so it can't be moved to the front directly.
            p0.window?.decorView?.post {
                runCatching {
                    p0.startActivity(
                        android.content.Intent(p0, ExoplayerView::class.java)
                            .putExtra(ExoplayerView.EXTRA_RETURN_TO_PLAYER, true)
                    )
                }
            }
        }

        private fun opensSomethingSpecific(a: Activity): Boolean {
            if (a is ani.dantotsu.connections.anilist.UrlMedia) return true
            // other screens keep their ids on the intent; only a fresh MainActivity routes
            if (a !is MainActivity) return false
            val intent = a.intent ?: return false
            if (intent.action == android.content.Intent.ACTION_VIEW) return true
            val extras = intent.extras ?: return false
            return listOf("mediaId", "media", "activityId", "FRAGMENT_TO_LOAD", "username").any(extras::containsKey)
        }

        override fun onActivityResumed(p0: Activity) {
            currentActivity = p0
            // MAX MERGE M3: device-font walk for already-inflated trees
            // (tag-guarded; cannot accumulate listeners).
            ani.dantotsu.applySystemFont(p0)
        }

        override fun onActivityPaused(p0: Activity) {}

        override fun onActivityStopped(p0: Activity) {
            startedActivityCount--
            // back in the restored player, left paused and the app closed again: same as a closed PiP
            if (startedActivityCount <= 0 && p0 is ExoplayerView && p0.isTaskRoot && !p0.isFinishing &&
                !(Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && p0.isInPictureInPictureMode)
            ) ExoplayerView.hiddenPlayerTaskId = p0.taskId
        }

        override fun onActivitySaveInstanceState(p0: Activity, p1: Bundle) {}
        override fun onActivityDestroyed(p0: Activity) {}
    }

    companion object {
        var instance: App? = null

        /** Reference to the application context.
         *
         * USE WITH EXTREME CAUTION!**/
        var context: Context? = null
        fun currentContext(): Context? {
            return instance?.mFTActivityLifecycleCallbacks?.currentActivity ?: context
        }

        fun currentActivity(): Activity? {
            return instance?.mFTActivityLifecycleCallbacks?.currentActivity
        }
    }
}
