package ani.dantotsu.screenshots

import android.app.Activity
import android.app.Application
import android.os.Bundle
import ani.dantotsu.App
import ani.dantotsu.aniyomi.anime.custom.AppModule
import ani.dantotsu.aniyomi.anime.custom.PreferenceModule
import ani.dantotsu.settings.saving.PrefManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.InjektScope
import uy.kohesive.injekt.registry.default.DefaultRegistrar

/**
 * Application for Robolectric screen tests. The real [App] starts extension managers,
 * the torrent server, workers and network calls; this one only sets up prefs and the
 * Injekt modules (their factories are lazy, nothing starts until a screen asks).
 */
class ScreenshotTestApp : Application() {
    override fun onCreate() {
        super.onCreate()
        App.context = this
        resetPrefManager()
        PrefManager.init(this)
        // Injekt is global; a fresh scope keeps singletons (Application, prefs) from the previous test out.
        Injekt = InjektScope(DefaultRegistrar())
        Injekt.importModule(AppModule(this))
        Injekt.importModule(PreferenceModule(this))
        // App.currentContext() is the current activity in the app; code that resolves theme
        // attributes through it (source picker pills) needs the same here.
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(a: Activity, b: Bundle?) { App.context = a }
            override fun onActivityStarted(a: Activity) {}
            override fun onActivityResumed(a: Activity) { App.context = a }
            override fun onActivityPaused(a: Activity) {}
            override fun onActivityStopped(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) { if (App.context === a) App.context = this@ScreenshotTestApp }
        })
    }

    /** PrefManager keeps its SharedPreferences in an object; each test gets fresh ones. */
    private fun resetPrefManager() {
        listOf(
            "generalPreferences", "uiPreferences", "playerPreferences", "readerPreferences",
            "irrelevantPreferences", "animeDownloadsPreferences", "protectedPreferences",
        ).forEach { name ->
            PrefManager::class.java.getDeclaredField(name).apply { isAccessible = true }.set(PrefManager, null)
        }
    }
}
