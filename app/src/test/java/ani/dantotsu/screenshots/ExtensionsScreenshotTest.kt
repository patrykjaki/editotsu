package ani.dantotsu.screenshots

import android.widget.AutoCompleteTextView
import ani.dantotsu.R
import ani.dantotsu.settings.ExtensionsActivity
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.chip.ChipGroup
import org.junit.Test
import org.robolectric.Robolectric

/** Extensions screen empty states: nothing installed, no repositories, no search match. */
class ExtensionsScreenshotTest : ScreenshotTest() {

    private fun open(type: Int, available: Boolean, query: String? = null, name: String) {
        val activity = Robolectric.buildActivity(ExtensionsActivity::class.java).setup().get()
        idle()
        activity.findViewById<MaterialButtonToggleGroup>(R.id.extensionsTypeToggle).check(type)
        activity.findViewById<ChipGroup>(R.id.extensionsStatusChips)
            .check(if (available) R.id.extensionsChipAvailable else R.id.extensionsChipInstalled)
        idle(1000)
        if (query != null) {
            activity.findViewById<AutoCompleteTextView>(R.id.searchViewText).setText(query)
            idle(1000)
        }
        shot(name)
    }

    @Test fun anime_installed_none() = open(R.id.extensionsTypeAnime, available = false, name = "extensions/anime_installed_empty")
    @Test fun novel_installed_none() = open(R.id.extensionsTypeNovel, available = false, name = "extensions/novel_installed_empty")
    @Test fun anime_no_repositories() = open(R.id.extensionsTypeAnime, available = true, name = "extensions/anime_no_repos")
    @Test fun manga_no_repositories() = open(R.id.extensionsTypeManga, available = true, name = "extensions/manga_no_repos")
    @Test fun search_no_match() = open(R.id.extensionsTypeAnime, available = false, query = "zzz", name = "extensions/search_no_match")
}
