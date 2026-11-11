package ani.dantotsu.connections.comments

import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName

/**
 * Comments use a server run by the Dantotsu project (api.dantotsu.app). Signing in there
 * sends the AniList login token, so nothing is sent until the user turns comments on.
 *
 * Stored [PrefName.CommentsEnabled] values: 0 never decided, 1 on, 2 off. Older versions
 * wrote 1 on first start without asking, and a value the user chose cannot be told apart
 * from that, so every install is reset to off once ([PrefName.CommentsOptInReset]).
 * The marker lives in the General prefs, which a backup restore replaces: restoring a backup
 * from before the reset brings the marker back as unset and comments stay off.
 */
object CommentsOptIn {
    const val UNSET = 0
    const val ON = 1
    const val OFF = 2

    /** Value after the one-time reset: an "on" written before it becomes off. */
    fun resetValue(stored: Int): Int = if (stored == ON) OFF else stored

    fun isEnabled(stored: Int, resetDone: Boolean): Boolean = resetDone && stored == ON

    fun isEnabled(): Boolean = isEnabled(
        PrefManager.getVal(PrefName.CommentsEnabled),
        PrefManager.getVal(PrefName.CommentsOptInReset)
    )

    /** Runs the one-time reset; returns true when it switched comments off. */
    fun resetOnce(): Boolean {
        if (PrefManager.getVal<Boolean>(PrefName.CommentsOptInReset)) return false
        val stored = PrefManager.getVal<Int>(PrefName.CommentsEnabled)
        val next = resetValue(stored)
        if (next != stored) PrefManager.setVal(PrefName.CommentsEnabled, next)
        PrefManager.setVal(PrefName.CommentsOptInReset, true)
        if (next != ON) CommentsAPI.logout()
        return next != stored
    }

    fun setEnabled(on: Boolean) {
        PrefManager.setVal(PrefName.CommentsOptInReset, true)
        PrefManager.setVal(PrefName.CommentsEnabled, if (on) ON else OFF)
        if (!on) CommentsAPI.logout()
    }
}
