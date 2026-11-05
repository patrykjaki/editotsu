package ani.dantotsu.media.anime.player

import android.content.Context
import ani.dantotsu.R
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.util.customAlertDialog

/** The "Hidden controls" sheet: the player gestures that have no button, as the user set them. */
object PlayerShortcuts {
    fun text(context: Context): String {
        val lines = buildList {
            if (PrefManager.getVal<Boolean>(PrefName.DoubleTap)) {
                add(context.getString(R.string.player_shortcuts_seek, PrefManager.getVal<Int>(PrefName.SeekTime)))
            } else {
                add(context.getString(R.string.player_shortcuts_seek_off))
            }
            if (PrefManager.getVal<Boolean>(PrefName.FastForward)) add(context.getString(R.string.player_shortcuts_hold))
            if (PrefManager.getVal<Boolean>(PrefName.Gestures)) add(context.getString(R.string.player_shortcuts_swipe))
            add(context.getString(R.string.player_shortcuts_skip))
            add(context.getString(R.string.player_shortcuts_title_tap))
            add(context.getString(R.string.player_shortcuts_lock))
        }
        return lines.joinToString("\n\n") { "• $it" } + "\n\n" + context.getString(R.string.player_shortcuts_settings)
    }

    fun show(context: Context) {
        context.customAlertDialog().apply {
            setTitle(R.string.player_shortcuts_title)
            setMessage(text(context))
            setPosButton(R.string.ok)
            show()
        }
    }
}
