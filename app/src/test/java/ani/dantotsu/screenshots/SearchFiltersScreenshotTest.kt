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
import com.google.android.material.chip.ChipGroup
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
        val pinnedScroll = dialog.findViewById<View>(R.id.genreTagSelectedScroll)!!
        val pinnedRow = dialog.findViewById<LinearLayout>(R.id.genreTagSelectedRow)!!
        assertEquals(View.GONE, pinnedScroll.visibility)

        // Position 0 is the open "Genres" wrapped ChipGroup; position 1+ are collapsible tag categories (closed by default).
        val genresSection = list.findViewHolderForAdapterPosition(0)!!.itemView
        val genresChips = genresSection.findViewById<ChipGroup>(R.id.genreTagChipGroup)!!
        assertEquals(View.VISIBLE, genresChips.visibility)
        val actionChip = (0 until genresChips.childCount)
            .map { genresChips.getChildAt(it) as Chip }
            .first { it.text.toString() == "Action" }

        val firstTagSection = list.findViewHolderForAdapterPosition(1)!!.itemView
        assertEquals(View.GONE, firstTagSection.findViewById<ChipGroup>(R.id.genreTagChipGroup)!!.visibility)

        actionChip.performClick() // blank -> INCLUDE
        idle()
        assertEquals(listOf("Action"), activity.aniMangaResult.genres)
        assertEquals(emptyList<String>(), activity.aniMangaResult.excludedGenres)
        assertEquals(View.VISIBLE, pinnedScroll.visibility)
        assertEquals(listOf("Action"), chips(pinnedRow).map { it.text.toString() })

        val afterInclude = chips(chipRow)
        assertEquals(3, afterInclude.size)
        assertEquals("Filters · 1", afterInclude[1].text.toString())
        assertTrue(afterInclude[1].isChecked)
        assertEquals("Action", afterInclude[2].text.toString())
        assertTrue(afterInclude[2].isCloseIconVisible)

        actionChip.performClick() // INCLUDE -> EXCLUDE
        idle()
        assertEquals(emptyList<String>(), activity.aniMangaResult.genres)
        assertEquals(listOf("Action"), activity.aniMangaResult.excludedGenres)
        assertEquals(listOf("\u2212Action"), chips(pinnedRow).map { it.text.toString() })
        val afterExclude = chips(chipRow)
        assertEquals("Filters · 1", afterExclude[1].text.toString())
        assertEquals("\u2212Action", afterExclude[2].text.toString())

        actionChip.performClick() // EXCLUDE -> NONE
        idle()
        assertEquals(emptyList<String>(), activity.aniMangaResult.genres)
        assertEquals(emptyList<String>(), activity.aniMangaResult.excludedGenres)
        assertEquals(View.GONE, pinnedScroll.visibility)
        val afterClear = chips(chipRow)
        assertEquals(2, afterClear.size)
        assertEquals("Filters", afterClear[1].text.toString())
        assertFalse(afterClear[1].isChecked)

        // Pinned chip close button removes the selection and updates the category count badge
        val fantasyIndex = (1 until list.adapter!!.itemCount).first { idx ->
            list.findViewHolderForAdapterPosition(idx)?.itemView
                ?.findViewById<TextView>(R.id.genreTagSectionTitle)?.text?.toString() == "Theme / Fantasy"
        }
        val fantasyHeader = list.findViewHolderForAdapterPosition(fantasyIndex)!!.itemView
            .findViewById<View>(R.id.genreTagSectionHeader)!!
        fantasyHeader.performClick()
        idle()
        settle(list)
        val fantasyChips = list.findViewHolderForAdapterPosition(fantasyIndex)!!.itemView
            .findViewById<ChipGroup>(R.id.genreTagChipGroup)!!
        assertEquals(View.VISIBLE, fantasyChips.visibility)
        (fantasyChips.getChildAt(0) as Chip).performClick() // Isekai -> INCLUDE
        idle()
        val fantasyCount = list.findViewHolderForAdapterPosition(fantasyIndex)!!.itemView
            .findViewById<TextView>(R.id.genreTagSectionCount)!!
        assertEquals(View.VISIBLE, fantasyCount.visibility)
        assertEquals("1", fantasyCount.text.toString())
        assertEquals(listOf("Isekai"), chips(pinnedRow).map { it.text.toString() })

        chips(pinnedRow).first().performCloseIconClick()
        idle()
        settle(list)
        assertEquals(emptyList<String>(), activity.aniMangaResult.tags)
        assertEquals(View.GONE, pinnedScroll.visibility)
        assertEquals(
            View.GONE,
            list.findViewHolderForAdapterPosition(fantasyIndex)!!.itemView
                .findViewById<TextView>(R.id.genreTagSectionCount)!!.visibility,
        )

        // Search box inside the sheet filters groups in place and auto-expands matching tag categories
        val searchField = dialog.findViewById<EditText>(R.id.genreTagSearchText)!!
        searchField.setText("isekai")
        idle()
        settle(list)
        assertEquals(1, list.adapter!!.itemCount) // 1 matching tag category ("Theme / Fantasy")
        val matchChips = list.findViewHolderForAdapterPosition(0)!!.itemView
            .findViewById<ChipGroup>(R.id.genreTagChipGroup)!!
        assertEquals(View.VISIBLE, matchChips.visibility)
        assertEquals(1, matchChips.childCount)
        assertEquals("Isekai", (matchChips.getChildAt(0) as Chip).text.toString())
        dialog.dismiss()
    }

    @Test
    fun `filters sheet shows a dropdown chip row and chip sections and reset clears them live`() {
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

        // Top row: Sort and Genres & tags as dropdown chips (label kept as the content description).
        val top = sheet.findViewById<ChipGroup>(R.id.searchFiltersTop)!!
        fun topChips() = (0 until top.childCount).map { top.getChildAt(it) as Chip }
        val defaultSort = SearchFiltersSheet.sortLabel(activity, SearchFiltersSheet.DEFAULT_SORT)
        assertEquals(listOf(defaultSort, "Action"), topChips().take(2).map { it.contentDescription.toString() })
        assertEquals(listOf(false, true), topChips().take(2).map { it.isChecked })

        // Format, status, season, year, ... are chip sections in the sheet; the set ones are checked.
        val sections = sheet.findViewById<LinearLayout>(R.id.searchFiltersSections)!!
        fun sectionChips() = (0 until sections.childCount).map { sections.getChildAt(it) }
            .filterIsInstance<ChipGroup>().flatMap { g -> (0 until g.childCount).map { g.getChildAt(it) as Chip } }
        fun checked() = sectionChips().filter { it.isChecked }.map { it.text.toString() }
        assertEquals(listOf("TV", "2024"), checked())
        // older years sit behind one dropdown chip after the recent ones
        assertTrue(sectionChips().any { it.contentDescription?.toString() == "Older" })

        // Tapping the chosen chip again goes back to Any; tapping another picks it.
        sectionChips().first { it.text.toString() == "TV" }.performClick()
        idle()
        assertEquals(null, activity.aniMangaResult.format)
        sectionChips().first { it.text.toString() == "Movie" }.performClick()
        idle()
        assertEquals("MOVIE", activity.aniMangaResult.format)
        assertEquals(listOf("Movie", "2024"), checked())

        val reset = sheet.findViewById<View>(R.id.searchFiltersReset)!!
        assertEquals(View.VISIBLE, reset.visibility)
        reset.performClick()
        idle()

        assertEquals(View.GONE, reset.visibility)
        assertEquals(emptyList<String>(), checked())
        assertEquals(2, chips(chipRow).size)
        assertEquals("Filters", chips(chipRow)[1].text.toString())
        assertEquals(listOf(defaultSort, "Genres & tags"), topChips().take(2).map { it.contentDescription.toString() })
        assertTrue(topChips().none { it.isChecked })
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
            settle(list)
            // Expand "Theme / Fantasy" so the screenshot exercises open tag chips and an excluded chip
            val fantasyHolder = (0 until list.adapter!!.itemCount)
                .mapNotNull { list.findViewHolderForAdapterPosition(it) }
                .firstOrNull {
                    it.itemView.findViewById<TextView>(R.id.genreTagSectionTitle)?.text?.toString() == "Theme / Fantasy"
                }
            fantasyHolder?.itemView?.findViewById<View>(R.id.genreTagSectionHeader)?.performClick()
            idle()
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
