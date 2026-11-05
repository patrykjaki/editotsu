package ani.dantotsu.screenshots

import android.view.View
import androidx.recyclerview.widget.RecyclerView
import ani.dantotsu.R
import ani.dantotsu.animethemes.AnimeThemesList
import ani.dantotsu.animethemes.FavouriteTheme
import ani.dantotsu.animethemes.ThemeDownloadControls
import ani.dantotsu.animethemes.ThemeFavourites
import ani.dantotsu.animethemes.ThemeQueue
import ani.dantotsu.animethemes.ThemesActivity
import ani.dantotsu.databinding.FragmentMediaThemesBinding
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Before
import org.junit.Test
import org.robolectric.Robolectric
import org.robolectric.annotation.Config

/** AnimeThemes: the Themes tab on anime details and the themes screen (header + favourites). */
class ThemesScreenshotTest : ScreenshotTest() {

    @Before fun clearFavouritesCache() = ThemeTestState.reset()

    /** The Themes tab as MediaThemesFragment fills it once AnimeThemes answered. */
    private fun themesTab(name: String, shieldOn: Boolean = false, expand: Boolean = false) {
        val activity = Robolectric.buildActivity(ScreenHostActivity::class.java).setup().get()
        val tab = FragmentMediaThemesBinding.inflate(activity.layoutInflater)
        activity.setContentView(tab.root)
        tab.themesLoading.visibility = View.GONE
        val media = Fixtures.media()
        val shielded = { t: ani.dantotsu.animethemes.ThemeTrack -> ThemeQueue.shielded(shieldOn, t.episodes, media.userProgress) }
        val controls = ThemeDownloadControls(activity) {}
        val list = AnimeThemesList.inflate(tab.themesContent, Fixtures.themes, shielded, onPlay = { _, _ -> }, downloads = controls)
        tab.themesContent.addView(list.root)
        controls.refresh(list.shownTracks())
        idle()
        // Openings come first; its "Show all" is the first section toggle.
        if (expand) tab.root.findViewById<View>(R.id.sectionToggle).performClick()
        shot(name)
    }

    @Test fun tab() = themesTab("themes/tab")
    @Test fun tab_expanded() = themesTab("themes/tab_expanded", expand = true)
    @Test fun tab_spoilerShield() = themesTab("themes/tab_shield", shieldOn = true, expand = true)

    @Test @Config(fontScale = LARGE_FONT)
    fun tab_largeFont() = themesTab("themes/tab_font130", expand = true)

    private fun favourites(vararg picks: Int) {
        Fixtures.themes.filter { it.auto.themeId in picks }.forEach {
            ThemeFavourites.toggle(it.auto.mediaId, it.auto.showTitle, it.auto)
        }
    }

    private fun themesScreen(name: String) {
        val activity = Robolectric.buildActivity(ThemesActivity::class.java).setup().get()
        idle()
        shotPages(activity.findViewById<RecyclerView>(R.id.themesList), name, maxPages = 3)
    }

    @Test fun screen_empty() = themesScreen("themes/screen_empty")

    @Test fun screen_favourites() {
        favourites(1, 2, 4, 11, 12)
        themesScreen("themes/screen_favourites")
    }

    @Test @Config(fontScale = LARGE_FONT)
    fun screen_favourites_largeFont() {
        favourites(1, 2, 4, 11, 12)
        themesScreen("themes/screen_favourites_font130")
    }
}

/** Objects that cache prefs between tests. */
object ThemeTestState {
    fun reset() {
        val field = ThemeFavourites::class.java.getDeclaredField("state").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        (field.get(ThemeFavourites) as MutableStateFlow<List<FavouriteTheme>?>).value = null
        PrefManager.setVal(PrefName.AnimeThemesFavourites, PrefName.AnimeThemesFavourites.data.default as String)
    }
}
