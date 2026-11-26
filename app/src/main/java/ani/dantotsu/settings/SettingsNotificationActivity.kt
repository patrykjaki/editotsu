package ani.dantotsu.settings

import ani.dantotsu.settings.rows.SettingsScreenActivity
import ani.dantotsu.settings.screens.NotificationsScreen

/** Settings > Notifications ([NotificationsScreen]). */
class SettingsNotificationActivity : SettingsScreenActivity() {
    override val screen get() = NotificationsScreen
}
