package ani.dantotsu.screenshots

import android.graphics.Color
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import androidx.recyclerview.widget.RecyclerView
import ani.dantotsu.R
import ani.dantotsu.connections.anilist.Anilist
import ani.dantotsu.media.GenreTagGroups
import ani.dantotsu.media.GenreTagGroups.Mark
import ani.dantotsu.media.GenreTagSheet
import ani.dantotsu.media.SearchActivity
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import com.google.android.material.chip.Chip
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.robolectric.Robolectric
import org.robolectric.annotation.Config

/** Search screen header, filter chips and unified Genres & tags sheet across the theme check set. */
class SearchFiltersScreenshotTest : ScreenshotTest() {

    @Before
    fun seedGenresAndTags() {
        Anilist.genres = arrayListOf("Action", "Adventure", "Comedy", "Drama", "Fantasy", "Romance", "Sci-Fi")
        Anilist.tags = mapOf(
            false to listOf("Female Protagonist", "Male Protagonist", "Time Travel", "Isekai", "Swordplay", "Cyberpunk"),
            true to listOf("Female Protagonist", "Male Protagonist", "Time Travel", "Isekai", "Swordplay", "Cyberpunk"),
        )
        Anilist.tagCategories = mapOf(
            "Female Protagonist" to "Cast-Main Cast",
            "Male Protagonist" to "Cast-Main Cast",
            "Time Travel" to "Theme-Sci-Fi",
            "Cyberpunk" to "Theme-Sci-Fi",
            "Isekai" to "Theme-Fantasy",
            "Swordplay" to "Theme-Action",
        )
    }

    @After
    fun restoreThemePrefs() {
        PrefManager.setVal(PrefName.Theme, "OCEAN")
        PrefManager.setVal(PrefName.UseOLED, false)
        PrefManager.setVal(PrefName.UseCustomTheme, false)
    }

    @Test
    fun `genre tag sheet cycles blank to include to exclude and updates chip`() {
        val activity = Robolectric.buildActivity(SearchActivity::class.java).setup().get()
        idle()

        // one way into the sheet: the labelled chip (the search bar has no separate filter icon)

        val chipRow = activity.findViewById<LinearLayout>(R.id.searchChips)
        val chipLabelsBefore = (0 until chipRow.childCount).map { (chipRow.getChildAt(it) as Chip).text.toString() }
        assertTrue("unified Genres & tags chip present", chipLabelsBefore.any { it.startsWith("Genres & tags") })
        assertFalse("separate Tags chip removed", chipLabelsBefore.any { it.startsWith("Tags") })
        assertFalse("separate Exclude chip removed", chipLabelsBefore.any { it.startsWith("Exclude") })

        val dialog = GenreTagSheet.show(activity, activity.aniMangaResult) { activity.updateChips() }
        assertNotNull(dialog)
        idle()

        val list = dialog!!.findViewById<RecyclerView>(R.id.genreTagList)!!
        settle(list)

        // Position 0 is "Genres" header, position 1 is "Action"
        val actionRow = list.findViewHolderForAdapterPosition(1)!!.itemView
        actionRow.performClick() // blank -> INCLUDE
        idle()
        assertEquals(listOf("Action"), activity.aniMangaResult.genres)
        assertEquals(emptyList<String>(), activity.aniMangaResult.excludedGenres)

        val gtChipAfterInclude = (chipRow.getChildAt(2) as Chip)
        assertTrue(gtChipAfterInclude.isChecked)
        assertTrue(gtChipAfterInclude.text.toString().startsWith("Action"))

        actionRow.performClick() // INCLUDE -> EXCLUDE
        idle()
        assertEquals(emptyList<String>(), activity.aniMangaResult.genres)
        assertEquals(listOf("Action"), activity.aniMangaResult.excludedGenres)
        assertTrue((chipRow.getChildAt(2) as Chip).text.toString().startsWith("\u2212Action"))

        actionRow.performClick() // EXCLUDE -> NONE
        idle()
        assertEquals(emptyList<String>(), activity.aniMangaResult.genres)
        assertEquals(emptyList<String>(), activity.aniMangaResult.excludedGenres)
        assertFalse((chipRow.getChildAt(2) as Chip).isChecked)

        // Search box inside the sheet filters groups in place
        val searchField = dialog.findViewById<EditText>(R.id.genreTagSearchText)!!
        searchField.setText("isekai")
        idle()
        assertEquals(2, list.adapter!!.itemCount) // 1 group header ("Theme / Fantasy") + 1 option ("Isekai")
        dialog.dismiss()
    }

    private fun renderSheetInTheme(
        theme: String,
        oled: Boolean,
        shotName: String,
        scrollToTags: Boolean = false,
    ) {
        PrefManager.setVal(PrefName.Theme, theme)
        PrefManager.setVal(PrefName.UseOLED, oled)
        PrefManager.setVal(PrefName.UseCustomTheme, false)

        val activity = Robolectric.buildActivity(SearchActivity::class.java).setup().get()
        activity.aniMangaResult.genres = mutableListOf("Action")
        activity.aniMangaResult.excludedGenres = mutableListOf("Adventure")
        activity.aniMangaResult.tags = mutableListOf("Female Protagonist")
        activity.aniMangaResult.excludedTags = mutableListOf("Male Protagonist")
        activity.updateChips()
        idle()

        shot("search/${shotName}_header")

        val dialog = GenreTagSheet.show(activity, activity.aniMangaResult) { activity.updateChips() }!!
        idle()
        if (scrollToTags) {
            val list = dialog.findViewById<RecyclerView>(R.id.genreTagList)!!
            list.scrollToPosition(8)
            settle(list)
        }
        shot("search/${shotName}_sheet")
        dialog.dismiss()
    }

    @Test
    @Config(qualifiers = "+night")
    fun theme_ocean_dark() = renderSheetInTheme("OCEAN", oled = false, shotName = "ocean_dark")

    @Test
    @Config(qualifiers = "+night")
    fun theme_monochrome_dark() = renderSheetInTheme("MONOCHROME (BETA)", oled = false, shotName = "monochrome_dark")

    @Test
    fun theme_pink_light() = renderSheetInTheme("PINK", oled = false, shotName = "pink_light")

    @Test
    @Config(qualifiers = "+night")
    fun theme_oled() = renderSheetInTheme("OCEAN", oled = true, shotName = "oled")

    @Test
    @Config(qualifiers = "+night")
    fun theme_red_tags() = renderSheetInTheme("RED", oled = false, shotName = "red_tags", scrollToTags = true)
}
