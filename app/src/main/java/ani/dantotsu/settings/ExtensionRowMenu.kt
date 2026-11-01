package ani.dantotsu.settings

import android.content.Context
import android.view.View
import androidx.appcompat.widget.PopupMenu
import ani.dantotsu.R
import ani.dantotsu.snackString
import ani.dantotsu.util.customAlertDialog
import eu.kanade.tachiyomi.util.system.isPackageInstalled

/**
 * Row actions for installed extensions: one ⋮ button opens Settings and
 * Uninstall, so each row has at most two trailing targets (update + ⋮).
 */
object ExtensionRowMenu {
    private const val ITEM_SETTINGS = 1
    private const val ITEM_UNINSTALL = 2

    fun show(anchor: View, onSettings: () -> Unit, onUninstall: () -> Unit) {
        PopupMenu(anchor.context, anchor).apply {
            menu.add(0, ITEM_SETTINGS, 0, R.string.extensions_row_settings)
            menu.add(0, ITEM_UNINSTALL, 1, R.string.extensions_row_uninstall)
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    ITEM_SETTINGS -> onSettings()
                    ITEM_UNINSTALL -> onUninstall()
                }
                true
            }
            show()
        }
    }

    /**
     * A shared (APK) extension is removed through Android's own uninstall
     * dialog, which already asks; confirming here too would ask twice. A
     * private extension (or a JS plugin) is deleted silently by the
     * manager, so ask first and confirm afterwards.
     */
    fun uninstall(
        context: Context,
        name: String,
        pkgName: String,
        privateOnly: Boolean = false,
        doUninstall: () -> Unit,
    ) {
        if (!privateOnly && context.isPackageInstalled(pkgName)) {
            doUninstall()
            return
        }
        context.customAlertDialog().apply {
            setTitle(context.getString(R.string.extensions_uninstall_title, name))
            setPosButton(R.string.extensions_row_uninstall) {
                doUninstall()
                snackString(context.getString(R.string.extensions_uninstalled))
            }
            setNegButton(R.string.cancel)
            show()
        }
    }
}
