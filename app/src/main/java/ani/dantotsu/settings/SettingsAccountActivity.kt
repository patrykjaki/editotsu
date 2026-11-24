package ani.dantotsu.settings

import ani.dantotsu.settings.rows.SettingsScreenActivity
import ani.dantotsu.settings.screens.TrackingScreen

/** Settings > Accounts & tracking ([TrackingScreen]). */
class SettingsAccountActivity : SettingsScreenActivity() {
    override val screen get() = TrackingScreen
}
