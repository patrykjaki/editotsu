package ani.dantotsu.screenshots

import android.content.Intent
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import ani.dantotsu.R
import ani.dantotsu.connections.anilist.Anilist
import ani.dantotsu.connections.anilist.AnilistSearch.SearchType
import ani.dantotsu.media.GenreTagSheet
import ani.dantotsu.media.SearchActivity
import ani.dantotsu.media.SearchFiltersSheet
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

/** Search screen header, compact Filters chip + active input chips, Filters grid sheet and Genres & tags sheet. */
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

    /** The chips in the filter row, without the divider after the type chip. */
    private fun chips(row: LinearLayout): List<Chip> = (0 until row.childCount).mapNotNull { row.getChildAt(it) as? Chip }

    @Test
    fun `chip row shows type and Filters only when unset, then removable input chips for set filters`() {
        val activity = Robolectric.buildActivity(SearchActivity::class.java).setup().get()
        idle()

        val chipRow = activity.findViewById<LinearLayout>(R.id.searchChips)
        val initialChips = chips(chipRow)
        assertEquals("only Type + Filters when nothing is set", 2, initialChips.size)
        assertTrue(initialChips[0].text.toString().startsWith("Anime"))
        assertEquals("Filters", initialChips[1].text.toString())
        assertFalse(initialChips[1].isChecked)

        // Open Genres & tags sheet and cycle Action: blank -> INCLUDE -> EXCLUDE -> NONE
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

        val afterInclude = chips(chipRow)
        assertEquals(3, afterInclude.size)
        assertEquals("Filters · 1", afterInclude[1].text.toString())
        assertTrue(afterInclude[1].isChecked)
        assertEquals("Action", afterInclude[2].text.toString())
        assertTrue(afterInclude[2].isCloseIconVisible)

        actionRow.performClick() // INCLUDE -> EXCLUDE
        idle()
        assertEquals(emptyList<String>(), activity.aniMangaResult.genres)
        assertEquals(listOf("Action"), activity.aniMangaResult.excludedGenres)
        val afterExclude = chips(chipRow)
        assertEquals("Filters · 1", afterExclude[1].text.toString())
        assertEquals("\u2212Action", afterExclude[2].text.toString())

        actionRow.performClick() // EXCLUDE -> NONE
        idle()
        assertEquals(emptyList<String>(), activity.aniMangaResult.genres)
        assertEquals(emptyList<String>(), activity.aniMangaResult.excludedGenres)
        val afterClear = chips(chipRow)
        assertEquals(2, afterClear.size)
        assertEquals("Filters", afterClear[1].text.toString())
        assertFalse(afterClear[1].isChecked)

        // Search box inside the sheet filters groups in place
        val searchField = dialog.findViewById<EditText>(R.id.genreTagSearchText)!!
        searchField.setText("isekai")
        idle()
        assertEquals(2, list.adapter!!.itemCount) // 1 group header ("Theme / Fantasy") + 1 option ("Isekai")
        dialog.dismiss()
    }

    @Test
    fun `filters sheet displays all filters in a grid and reset clears them live`() {
        val activity = Robolectric.buildActivity(SearchActivity::class.java).setup().get()
        activity.aniMangaResult.genres = mutableListOf("Action")
        activity.aniMangaResult.format = "TV"
        activity.aniMangaResult.seasonYear = 2024
        activity.updateChips()
        idle()

        val chipRow = activity.findViewById<LinearLayout>(R.id.searchChips)
        assertEquals("Filters · 3", chips(chipRow)[1].text.toString())
        assertEquals(listOf("Action", "TV", "2024"), chips(chipRow).drop(2).map { it.text.toString() })

        val sheet = SearchFiltersSheet.show(activity, SearchType.ANIME, activity.aniMangaResult) {
            activity.updateChips()
        }!!
        idle()

        val grid = sheet.findViewById<RecyclerView>(R.id.searchFiltersGrid)!!
        settle(grid)
        assertTrue("grid shows every anime filter tile", grid.adapter!!.itemCount >= 6)

        val reset = sheet.findViewById<View>(R.id.searchFiltersReset)!!
        assertEquals(View.VISIBLE, reset.visibility)
        reset.performClick()
        idle()

        assertEquals(View.GONE, reset.visibility)
        assertEquals(2, chips(chipRow).size)
        assertEquals("Filters", chips(chipRow)[1].text.toString())
        val sortValue = grid.findViewHolderForAdapterPosition(0)!!.itemView
            .findViewById<TextView>(R.id.filterTileValue).text.toString()
        assertEquals(SearchFiltersSheet.sortLabel(activity, SearchFiltersSheet.DEFAULT_SORT), sortValue)
        val genreValue = grid.findViewHolderForAdapterPosition(1)!!.itemView
            .findViewById<TextView>(R.id.filterTileValue).text.toString()
        assertEquals("Any", genreValue)
        sheet.dismiss()
    }

    @Test
    fun `mixed All search hides Filters chip and shows All type chip`() {
        val intent = Intent().putExtra("type", "ALL")
        val activity = Robolectric.buildActivity(SearchActivity::class.java, intent).setup().get()
        idle()

        val chipRow = activity.findViewById<LinearLayout>(R.id.searchChips)
        val rowChips = chips(chipRow)
        assertEquals("All mode has only the type chip", 1, rowChips.size)
        assertTrue(rowChips[0].text.toString().startsWith("All"))
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
        activity.aniMangaResult.format = "TV"
        activity.aniMangaResult.seasonYear = 2024
        activity.aniMangaResult.excludedTags = mutableListOf("Isekai")
        activity.updateChips()
        idle()

        shot("search/${shotName}_header")

        val filtersDialog = SearchFiltersSheet.show(activity, SearchType.ANIME, activity.aniMangaResult) {
            activity.updateChips()
        }!!
        idle()
        shot("search/${shotName}_filters_sheet")
        filtersDialog.dismiss()

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
