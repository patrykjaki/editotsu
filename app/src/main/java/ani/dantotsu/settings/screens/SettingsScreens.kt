package ani.dantotsu.settings.screens

import ani.dantotsu.settings.AboutScreen
import ani.dantotsu.settings.AppearanceScreen
import ani.dantotsu.settings.data.DataScreen
import ani.dantotsu.settings.rows.SettingsScreen

/** Every settings group screen, in the order of the settings home list. Search reads this. */
object SettingsScreens {
    val all: List<SettingsScreen> get() = listOf(
        AppearanceScreen,
        PlayerScreen,
        ReaderScreen,
        SourcesScreen,
        TrackingScreen,
        DataScreen,
        AboutScreen,
    )
}
