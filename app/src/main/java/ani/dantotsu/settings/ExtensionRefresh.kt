package ani.dantotsu.settings

import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import ani.dantotsu.R
import ani.dantotsu.media.MediaType
import ani.dantotsu.parsers.novel.NovelExtensionManager
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.snackString
import ani.dantotsu.util.Logger
import eu.kanade.tachiyomi.extension.anime.AnimeExtensionManager
import eu.kanade.tachiyomi.extension.manga.MangaExtensionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/** One running job per key; a call while it runs gets the same job instead of a second one. */
internal class SharedRefresh<K : Any, V>(private val scope: CoroutineScope) {
    private val jobs = mutableMapOf<K, Deferred<V>>()

    @Synchronized
    fun running(key: K): Deferred<V>? = jobs[key]?.takeIf { it.isActive }

    @Synchronized
    fun start(key: K, block: suspend () -> V): Deferred<V> =
        running(key) ?: scope.async { block() }.also { jobs[key] = it }
}

/**
 * Pull to refresh on the extension lists: fetches every repository of that
 * type again (anime, manga, or novel including LnReader plugins), which also
 * marks installed extensions that have an update. The job runs outside the
 * fragment, so switching tabs does not cancel it.
 */
object ExtensionRefresh {
    private val refreshes =
        SharedRefresh<MediaType, Int?>(CoroutineScope(SupervisorJob() + Dispatchers.IO))

    fun bind(swipe: SwipeRefreshLayout, owner: LifecycleOwner, type: MediaType) {
        swipe.setColorSchemeColors(PrefManager.getVal<Int>(PrefName.PrimaryColor))
        swipe.setProgressBackgroundColorSchemeResource(R.color.nav_bg)
        swipe.setOnRefreshListener {
            val job = refreshes.start(type) { checkRepos(type) }
            owner.lifecycleScope.launch {
                val updates = job.await()
                swipe.isRefreshing = false
                // null: no repository answered; the manager already showed why.
                if (updates != null) snackString(message(swipe, updates))
            }
        }
        // A refresh started from the other tab of this type, or before a rotation.
        refreshes.running(type)?.let { job ->
            swipe.isRefreshing = true
            owner.lifecycleScope.launch {
                job.await()
                swipe.isRefreshing = false
            }
        }
    }

    private fun message(swipe: SwipeRefreshLayout, updates: Int): String {
        val res = swipe.resources
        return if (updates == 0) res.getString(R.string.extensions_refresh_none)
        else res.getQuantityString(R.plurals.extensions_refresh_updates, updates, updates)
    }

    /** Installed extensions with an update after the fetch; null when no repository answered. */
    private suspend fun checkRepos(type: MediaType): Int? = try {
        when (type) {
            MediaType.ANIME -> {
                val manager = Injekt.get<AnimeExtensionManager>()
                manager.findAvailableExtensions()
                if (manager.availableExtensionsFlow.value.isEmpty()) null
                else manager.installedExtensionsFlow.value.count { it.hasUpdate }
            }

            MediaType.MANGA -> {
                val manager = Injekt.get<MangaExtensionManager>()
                manager.findAvailableExtensions()
                if (manager.availableExtensionsFlow.value.isEmpty()) null
                else manager.installedExtensionsFlow.value.count { it.hasUpdate }
            }

            MediaType.NOVEL -> {
                val manager = Injekt.get<NovelExtensionManager>()
                manager.findAvailableExtensions()
                val apk = manager.danNovelManager
                val js = manager.lnReaderManager
                if (apk.availableExtensionsFlow.value.isEmpty() && js.availablePluginsFlow.value.isEmpty()) null
                else apk.installedExtensionsFlow.value.count { it.hasUpdate } +
                    js.installedPluginsFlow.value.count { it.hasUpdate }
            }
        }
    } catch (e: Exception) {
        Logger.log(e)
        null
    }
}
