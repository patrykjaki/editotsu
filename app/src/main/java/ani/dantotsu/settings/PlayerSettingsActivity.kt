package ani.dantotsu.settings

import android.os.Bundle
import ani.dantotsu.R
import ani.dantotsu.settings.rows.SettingsScreenActivity
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.settings.screens.PlayerScreen
import eltos.simpledialogfragment.SimpleDialog
import eltos.simpledialogfragment.color.SimpleColorWheelDialog

/**
 * Settings > Player ([PlayerScreen]). The player opens it for a result and re-reads the
 * subtitle and playback prefs when it closes.
 */
class PlayerSettingsActivity : SettingsScreenActivity(), SimpleDialog.OnDialogResultListener {
    override val screen get() = PlayerScreen

    /** Colour wheel for an Int colour pref. The key rides in the dialog tag, so it survives recreation. */
    internal fun pickColor(key: PrefName, title: Int) {
        SimpleColorWheelDialog()
            .title(getString(title))
            .color(PrefManager.getVal<Int>(key))
            .alpha(true)
            .neg()
            .theme(R.style.MyPopup)
            .show(this, COLOR_TAG + key.name)
    }

    override fun onResult(dialogTag: String, which: Int, extras: Bundle): Boolean {
        if (!dialogTag.startsWith(COLOR_TAG)) return false
        if (which == SimpleDialog.OnDialogResultListener.BUTTON_POSITIVE) {
            val key = PrefName.entries.firstOrNull { it.name == dialogTag.removePrefix(COLOR_TAG) }
            if (key != null) {
                PrefManager.setVal(key, extras.getInt(SimpleColorWheelDialog.COLOR))
                refresh()
            }
        }
        return true
    }

    private companion object {
        const val COLOR_TAG = "subColor:"
    }
}
