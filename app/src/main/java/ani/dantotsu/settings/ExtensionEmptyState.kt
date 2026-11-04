package ani.dantotsu.settings

import android.content.Context
import android.content.ContextWrapper
import android.view.View
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.paging.CombinedLoadStates
import androidx.paging.LoadState
import ani.dantotsu.R
import ani.dantotsu.media.MediaType
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import com.google.android.material.button.MaterialButton

/** Text and action shown in place of an empty extension list. */
object ExtensionEmptyState {
    enum class Kind { NONE, NO_MATCH, NO_INSTALLED, NO_REPOS, NO_AVAILABLE }

    fun kind(itemCount: Int, query: String, installedPage: Boolean, hasRepos: Boolean): Kind =
        when {
            itemCount > 0 -> Kind.NONE
            query.isNotBlank() -> Kind.NO_MATCH
            installedPage -> Kind.NO_INSTALLED
            !hasRepos -> Kind.NO_REPOS
            else -> Kind.NO_AVAILABLE
        }

    fun repositories(type: MediaType): Set<String> = when (type) {
        MediaType.ANIME -> PrefManager.getVal(PrefName.AnimeExtensionRepos)
        MediaType.MANGA -> PrefManager.getVal(PrefName.MangaExtensionRepos)
        MediaType.NOVEL -> PrefManager.getVal(PrefName.NovelExtensionRepos)
    }

    private fun MaterialButton.setAction(icon: Int, label: Int, onClick: () -> Unit) {
        setIconResource(icon)
        setText(label)
        setOnClickListener { onClick() }
    }

    private fun Context.extensionsActivity(): ExtensionsActivity? {
        var c: Context? = this
        while (c is ContextWrapper) {
            if (c is ExtensionsActivity) return c
            c = c.baseContext
        }
        return null
    }

    class Views(val root: View, val text: TextView, val action: MaterialButton)

    /** Paging lists: shown only once a refresh has finished. */
    fun onLoadStates(
        views: Views,
        states: CombinedLoadStates,
        itemCount: Int,
        type: MediaType,
        query: String,
    ) {
        if (states.refresh is LoadState.Loading) {
            views.root.isVisible = false
            return
        }
        render(views, type, query, kind(itemCount, query, false, repositories(type).isNotEmpty()))
    }

    fun onInstalledList(views: Views, itemCount: Int, type: MediaType, query: String) {
        render(views, type, query, kind(itemCount, query, true, true))
    }

    private fun render(views: Views, type: MediaType, query: String, kind: Kind) {
        views.root.isVisible = kind != Kind.NONE
        if (kind == Kind.NONE) return
        val context = views.root.context
        views.text.text = when (kind) {
            Kind.NO_MATCH -> context.getString(R.string.extensions_empty_search, query.trim())
            Kind.NO_INSTALLED -> context.getString(
                when (type) {
                    MediaType.ANIME -> R.string.extensions_empty_installed_anime
                    MediaType.MANGA -> R.string.extensions_empty_installed_manga
                    MediaType.NOVEL -> R.string.extensions_empty_installed_novel
                }
            )
            Kind.NO_REPOS -> context.getString(
                when (type) {
                    MediaType.ANIME -> R.string.extensions_empty_repos_anime
                    MediaType.MANGA -> R.string.extensions_empty_repos_manga
                    MediaType.NOVEL -> R.string.extensions_empty_repos_novel
                }
            )
            else -> context.getString(R.string.extensions_empty_available)
        }
        val activity = context.extensionsActivity()
        when (kind) {
            Kind.NO_INSTALLED -> views.action.setAction(
                R.drawable.ic_download_24, R.string.extensions_empty_show_available
            ) { activity?.showAvailable() }
            Kind.NO_REPOS -> views.action.setAction(
                R.drawable.ic_add, R.string.extensions_empty_add_repository
            ) { activity?.openRepositories(type) }
            else -> views.action.setOnClickListener(null)
        }
        views.action.isVisible = activity != null &&
            (kind == Kind.NO_INSTALLED || kind == Kind.NO_REPOS)
    }
}
