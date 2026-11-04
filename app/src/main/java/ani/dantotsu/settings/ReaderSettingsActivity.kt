package ani.dantotsu.settings

import ani.dantotsu.settings.rows.SettingsScreenActivity
import ani.dantotsu.settings.screens.ReaderScreen

/** Settings > Reading ([ReaderScreen]). */
class ReaderSettingsActivity : SettingsScreenActivity() {
    override val screen get() = ReaderScreen
}
