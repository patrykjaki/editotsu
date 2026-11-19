package ani.dantotsu.screenshots

import android.widget.AutoCompleteTextView
import ani.dantotsu.R
import ani.dantotsu.settings.ExtensionsActivity
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.tabs.TabLayout
import org.junit.Test
import org.robolectric.Robolectric

/** Extensions screen empty states: nothing installed, no repositories, no search match. */
class ExtensionsScreenshotTest : ScreenshotTest() {

    /** [type]: tab index, 0 anime, 1 manga, 2 novel. */
    private fun open(type: Int, available: Boolean, query: String? = null, name: String) {
        val activity = Robolectric.buildActivity(ExtensionsActivity::class.java).setup().get()
        idle()
        activity.findViewById<TabLayout>(R.id.extensionsTypeTabs).let { it.selectTab(it.getTabAt(type)) }
        activity.findViewById<MaterialButtonToggleGroup>(R.id.extensionsStatusToggle)
            .check(if (available) R.id.extensionsStatusAvailable else R.id.extensionsStatusInstalled)
        idle(1000)
        if (query != null) {
            activity.findViewById<AutoCompleteTextView>(R.id.searchViewText).setText(query)
            idle(1000)
        }
        shot(name)
    }

    private companion object { const val ANIME = 0; const val MANGA = 1; const val NOVEL = 2 }

    @Test fun anime_installed_none() = open(ANIME, available = false, name = "extensions/anime_installed_empty")
    @Test fun novel_installed_none() = open(NOVEL, available = false, name = "extensions/novel_installed_empty")
    @Test fun anime_no_repositories() = open(ANIME, available = true, name = "extensions/anime_no_repos")
    @Test fun manga_no_repositories() = open(MANGA, available = true, name = "extensions/manga_no_repos")
    @Test fun search_no_match() = open(ANIME, available = false, query = "zzz", name = "extensions/search_no_match")
}
