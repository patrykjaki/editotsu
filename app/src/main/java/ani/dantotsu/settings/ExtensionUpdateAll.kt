package ani.dantotsu.settings

import android.widget.Button
import androidx.core.view.isVisible
import ani.dantotsu.R
import ani.dantotsu.parsers.novel.NovelExtension

/**
 * "Update all" on the installed-extension lists (as in Aniyomi/Mihon).
 * Each pending update goes through the same per-row update callback, so
 * the existing installer queue installs them one by one; with the
 * PackageInstaller (Android 12+), Shizuku or private installer that needs
 * no prompt for extensions Editotsu installed itself.
 */
object ExtensionUpdateAll {
    fun <T> pending(items: List<T>, hasUpdate: (T) -> Boolean): List<T> = items.filter(hasUpdate)

    fun novelHasUpdate(ext: NovelExtension): Boolean = when (ext) {
        is NovelExtension.Installed -> ext.hasUpdate
        is NovelExtension.JsPlugin -> ext.hasUpdate
        else -> false
    }

    /** Shows the button with the count when [pending] is non-empty; a tap updates each one. */
    fun <T> bind(button: Button, pending: List<T>, update: (T) -> Unit) {
        button.isVisible = pending.isNotEmpty()
        button.text = button.context.getString(R.string.extensions_update_all, pending.size)
        button.setOnClickListener {
            button.isEnabled = false
            pending.forEach(update)
        }
        button.isEnabled = true
    }
}
