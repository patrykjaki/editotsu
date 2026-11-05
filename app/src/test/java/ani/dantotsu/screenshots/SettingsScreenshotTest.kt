package ani.dantotsu.screenshots

import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import ani.dantotsu.R
import ani.dantotsu.settings.AboutScreen
import ani.dantotsu.settings.AppearanceScreen
import ani.dantotsu.settings.SettingsActivity
import ani.dantotsu.BuildConfig
import ani.dantotsu.settings.data.DataScreen
import ani.dantotsu.settings.rows.SettingsScreen
import ani.dantotsu.settings.rows.SettingsScreenActivity
import ani.dantotsu.settings.screens.PlayerScreen
import ani.dantotsu.settings.screens.ReaderScreen
import ani.dantotsu.settings.screens.SettingsScreens
import ani.dantotsu.settings.screens.SourcesScreen
import ani.dantotsu.settings.screens.TrackingScreen
import org.junit.Assert.assertEquals
import org.junit.Test
import org.robolectric.annotation.Config

/** Settings home and every settings screen, advanced rows hidden and shown. */
class SettingsScreenshotTest : ScreenshotTest() {

    @Test fun home() {
        ActivityScenario.launch(SettingsActivity::class.java).use { shot("settings/home") }
    }

    @Test @Config(fontScale = LARGE_FONT)
    fun home_largeFont() {
        ActivityScenario.launch(SettingsActivity::class.java).use { shot("settings/home_font130") }
    }

    @Test fun every_screen_is_covered() {
        // A new screen in SettingsScreens.all needs its own test methods below.
        assertEquals(
            listOf(AppearanceScreen, PlayerScreen, ReaderScreen, SourcesScreen, TrackingScreen, DataScreen, AboutScreen),
            SettingsScreens.all,
        )
    }

    @Test fun appearance() = screen(AppearanceScreen, "appearance")
    @Test fun appearance_advanced() = screen(AppearanceScreen, "appearance", advanced = true)
    @Test fun player() = screen(PlayerScreen, "player")
    @Test fun player_advanced() = screen(PlayerScreen, "player", advanced = true)
    @Test fun reading() = screen(ReaderScreen, "reading")
    @Test fun reading_advanced() = screen(ReaderScreen, "reading", advanced = true)
    @Test fun sources() = screen(SourcesScreen, "sources", perFlavor = true)
    @Test fun sources_advanced() = screen(SourcesScreen, "sources", advanced = true, perFlavor = true)
    @Test fun tracking() = screen(TrackingScreen, "tracking")
    @Test fun tracking_advanced() = screen(TrackingScreen, "tracking", advanced = true)
    @Test fun data() = screen(DataScreen, "data", perFlavor = true)
    @Test fun data_advanced() = screen(DataScreen, "data", advanced = true, perFlavor = true)
    @Test fun about() = screen(AboutScreen, "about", perFlavor = true)

    @Test @Config(fontScale = LARGE_FONT)
    fun player_largeFont() = screen(PlayerScreen, "player_font130", advanced = true)

    @Test @Config(fontScale = LARGE_FONT)
    fun reading_largeFont() = screen(ReaderScreen, "reading_font130", advanced = true)

    @Test @Config(fontScale = LARGE_FONT)
    fun appearance_largeFont() = screen(AppearanceScreen, "appearance_font130", advanced = true)

    /** perFlavor: the fdroid build drops a few rows (update check, share username), so these keep one golden per flavor. */
    private fun screen(screen: SettingsScreen, name: String, advanced: Boolean = false, perFlavor: Boolean = false) {
        val intent = SettingsScreenActivity.intent(ApplicationProvider.getApplicationContext(), screen)
        ActivityScenario.launch<SettingsScreenActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                val list = activity.findViewById<RecyclerView>(R.id.settingScreenList)
                idle()
                if (advanced) SettingsActions.toggleAdvanced(list) { idle() }
                val flavor = if (perFlavor) "_${BuildConfig.FLAVOR}" else ""
                shotPages(list, "settings/${name}${if (advanced) "_advanced" else ""}$flavor")
            }
        }
    }
}

/** Shared by the screenshot and behaviour tests. */
object SettingsActions {
    /** Scrolls to the "show advanced settings" card and taps it. Returns false when the screen has none. */
    fun toggleAdvanced(list: RecyclerView, idle: () -> Unit): Boolean {
        val last = (list.adapter?.itemCount ?: 0) - 1
        list.scrollToPosition(last)
        idle()
        val toggle = list.findViewHolderForAdapterPosition(last)?.itemView
            ?.findViewById<android.view.View>(R.id.advancedToggle) ?: return false
        toggle.performClick()
        idle()
        list.scrollToPosition(0)
        idle()
        return true
    }
}
