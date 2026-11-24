package ani.dantotsu.screenshots

import android.content.Context
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import ani.dantotsu.R
import ani.dantotsu.settings.AboutScreen
import ani.dantotsu.settings.rows.ScreenItem
import ani.dantotsu.settings.rows.SettingsIndex
import ani.dantotsu.settings.rows.SettingsScreenActivity
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.settings.saving.internal.BackupSection
import ani.dantotsu.settings.saving.internal.Location
import ani.dantotsu.settings.saving.internal.PreferencePackager
import ani.dantotsu.settings.screens.NotificationsScreen
import ani.dantotsu.settings.screens.SettingsScreens
import ani.dantotsu.settings.screens.SourcesScreen
import ani.dantotsu.settings.screens.TrackingScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Settings behaviour from the phone test list, on the JVM: search jump, advanced toggle, backup round trip. */
class SettingsBehaviourTest : ScreenshotTest() {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun RecyclerView.rowAdapterKeys(): List<String> {
        val concat = adapter as androidx.recyclerview.widget.ConcatAdapter
        val rows = concat.adapters[1] as androidx.recyclerview.widget.ListAdapter<*, *>
        return rows.currentList.map { (it as ScreenItem).key }
    }

    /**
     * The row a search for [query] leads to opens its screen scrolled so the row is on screen.
     * Looks the row up in the index directly: SettingsIndex.search itself crashes (see search_does_not_crash).
     */
    private fun assertSearchJumps(query: String) {
        val entry = SettingsIndex.entries().firstOrNull { context.getString(it.row.title).lowercase().contains(query) }
        assertNotNull("no settings row titled like '$query'", entry)
        entry!!
        val intent = SettingsScreenActivity.intent(context, entry.screen, entry.row.id)
        ActivityScenario.launch<SettingsScreenActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                val list = activity.findViewById<RecyclerView>(R.id.settingScreenList)
                idle()
                val index = list.rowAdapterKeys().indexOf(entry.row.id)
                assertTrue("'${entry.row.id}' not listed (advanced=${entry.row.advanced})", index >= 0)
                val holder = list.findViewHolderForAdapterPosition(index + 1) // +1: title item
                assertNotNull("'${entry.row.id}' not laid out after the jump", holder)
                val v = holder!!.itemView
                assertTrue("'${entry.row.id}' off screen: ${v.top}..${v.bottom} of ${list.height}", v.top >= 0 && v.bottom <= list.height)
            }
        }
    }

    /** About has one untitled section; search used to crash on it (Resources.NotFoundException #0x0). */
    @Test fun search_does_not_crash() {
        val hits = SettingsIndex.search(context, "backup")
        assertTrue("no hit for backup", hits.isNotEmpty())
        // About is the untitled section that used to crash. "share" hits its share-username row,
        // which the fdroid build hides, so fdroid checks "log to file" instead.
        val aboutQuery = if (ani.dantotsu.BuildConfig.FLAVOR.contains("fdroid")) "log to file" else "share"
        assertTrue("no About hit for $aboutQuery", SettingsIndex.search(context, aboutQuery).any { it.entry.screen == AboutScreen })
    }

    @Test fun search_backup_jumps_to_its_row() = assertSearchJumps("backup")

    @Test fun search_advanced_row_opens_with_advanced_shown() = assertSearchJumps("user agent")

    @Test fun search_moved_rows_jump_to_new_screens() {
        assertSearchJumps("smart download (next episode)")
        assertSearchJumps("hide notification dot")
    }

    @Test fun search_tracking_still_finds_accounts_and_tracking_rows() {
        val hits = SettingsIndex.search(context, "tracking")
        assertTrue("no TrackingScreen hit for 'tracking'", hits.any { it.entry.screen == TrackingScreen })
    }

    @Test fun notifications_signed_out_is_not_empty_and_drops_hidden_headers() {
        val intent = SettingsScreenActivity.intent(context, NotificationsScreen)
        ActivityScenario.launch<SettingsScreenActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                val list = activity.findViewById<RecyclerView>(R.id.settingScreenList)
                idle()
                assertEquals(
                    listOf(
                        "header:${R.string.settings_section_anilist_comments}:",
                        PrefName.ShowNotificationRedDot.name,
                    ),
                    list.rowAdapterKeys(),
                )
            }
        }
    }

    @Test fun every_screen_more_rows_show_and_hide_advanced_rows() {
        SettingsScreenActivity.closeAllSections()
        for (screen in SettingsScreens.all) {
            if (screen.rows().none { it.advanced && runCatching { it.visible() }.getOrDefault(false) }) continue
            ActivityScenario.launch<SettingsScreenActivity>(SettingsScreenActivity.intent(context, screen)).use { scenario ->
                scenario.onActivity { activity ->
                    val list = activity.findViewById<RecyclerView>(R.id.settingScreenList)
                    idle()
                    val before = list.rowAdapterKeys()
                    assertTrue(SettingsActions.toggleAdvanced(list) { idle() })
                    val open = list.rowAdapterKeys()
                    assertTrue("${screen.id}: no rows added", open.size > before.size)
                    assertTrue("${screen.id}: rows lost", open.containsAll(before))
                    assertTrue("${screen.id}: a section still closed",
                        SettingsActions.items(list).filterIsInstance<ScreenItem.More>().all { it.expanded })
                    assertTrue(SettingsActions.toggleAdvanced(list) { idle() })
                    assertEquals("${screen.id}: hide again", before, list.rowAdapterKeys())
                }
            }
        }
    }

    /** DNS and proxy restart the app's task on change; the section they are in stays open. */
    @Test fun open_section_survives_the_screen_being_recreated() {
        SettingsScreenActivity.closeAllSections()
        val intent = SettingsScreenActivity.intent(context, SourcesScreen)
        ActivityScenario.launch<SettingsScreenActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                val list = activity.findViewById<RecyclerView>(R.id.settingScreenList)
                idle()
                assertTrue(SettingsActions.toggleAdvanced(list) { idle() })
            }
        }
        ActivityScenario.launch<SettingsScreenActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                val list = activity.findViewById<RecyclerView>(R.id.settingScreenList)
                idle()
                assertTrue("DohProvider hidden after reopening", "DohProvider" in list.rowAdapterKeys())
                // Close them again so other tests start from the default.
                SettingsActions.toggleAdvanced(list) { idle() }
            }
        }
    }

    private fun rowKeys(reader: Boolean) = SettingsScreens.all.flatMap { s -> s.rows().flatMap { it.keys } }.toSet()
        .filter { it.data.prefLocation !in setOf(Location.Irrelevant, Location.Protected, Location.AnimeDownloads, Location.ExtensionSettings) }
        .filter { (it.data.prefLocation == Location.Reader || it.data.prefLocation == Location.NovelReader) == reader }
        .filter { it.data.default.let { d -> d is Boolean || d is Int || d is Long || d is Float || d is String || d is Set<*> } }

    private fun sample(k: PrefName): Any = when (val d = k.data.default) {
        is Boolean -> !d
        is Int -> d + 1
        is Long -> d + 1
        is Float -> d + 0.5f
        is String -> d + "x"
        is Set<*> -> setOf("a", "b")
        else -> error(k.name)
    }

    /** Changes every key, backs up the Settings section, resets, restores; returns keys that came back wrong. */
    private fun roundTrip(keys: List<PrefName>): List<String> {
        assertTrue("too few keys: ${keys.size}", keys.size > 10)
        keys.forEach { PrefManager.setVal(it, sample(it)) }
        val backup = PreferencePackager.packSections(setOf(BackupSection.Settings))
        keys.forEach { PrefManager.setVal(it, null) }
        assertEquals(keys.first().data.default, PrefManager.getVal<Any>(keys.first()))
        assertTrue("restore rejected", PreferencePackager.unpack(backup))
        return keys.filter { PrefManager.getVal<Any>(it) != sample(it) }
            .map { "${it.name}=${PrefManager.getVal<Any>(it)} (want ${sample(it)})" }
    }

    /** Appearance, player, sources, tracking, data rows: backup then restore brings them back. */
    @Test fun backup_then_restore_brings_settings_back() {
        assertEquals(emptyList<String>(), roundTrip(rowKeys(reader = false)))
    }

    /** Reading rows too: Reader and NovelReader share one file and must restore together. */
    @Test fun backup_then_restore_brings_reading_settings_back() {
        assertEquals(emptyList<String>(), roundTrip(rowKeys(reader = true)))
    }

    /** Icon-only segments keep their own icon when selected; text segments show a check icon when selected. */
    @Test fun segmented_toggles_keep_icon_on_icon_only_segments_and_show_check_on_text_segments() {
        val intent = SettingsScreenActivity.intent(context, ani.dantotsu.settings.screens.ReaderScreen)
        ActivityScenario.launch<SettingsScreenActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                val list = activity.findViewById<RecyclerView>(R.id.settingScreenList)
                idle()
                // LayoutReader: icon-only segments
                val layoutIdx = list.rowAdapterKeys().indexOf(PrefName.LayoutReader.name)
                assertTrue(layoutIdx >= 0)
                list.scrollToPosition(layoutIdx + 1)
                idle()
                val layoutHolder = list.findViewHolderForAdapterPosition(layoutIdx + 1)!!
                val layoutGroup = layoutHolder.itemView.findViewById<com.google.android.material.button.MaterialButtonToggleGroup>(R.id.rowSegments)
                val firstIconBtn = layoutGroup.getChildAt(0) as com.google.android.material.button.MaterialButton
                val secondIconBtn = layoutGroup.getChildAt(1) as com.google.android.material.button.MaterialButton
                val iconResBefore = org.robolectric.Shadows.shadowOf(secondIconBtn.icon).createdFromResId
                layoutGroup.check(secondIconBtn.id)
                assertTrue(secondIconBtn.isChecked)
                assertEquals(R.drawable.ic_round_view_array_24, iconResBefore)
                assertEquals(iconResBefore, org.robolectric.Shadows.shadowOf(secondIconBtn.icon).createdFromResId)
                assertTrue(firstIconBtn.text.isNullOrBlank())

                // MangaDefaultView: text segments
                val viewIdx = list.rowAdapterKeys().indexOf(PrefName.MangaDefaultView.name)
                assertTrue(viewIdx >= 0)
                list.scrollToPosition(viewIdx + 1)
                idle()
                val viewHolder = list.findViewHolderForAdapterPosition(viewIdx + 1)!!
                val viewGroup = viewHolder.itemView.findViewById<com.google.android.material.button.MaterialButtonToggleGroup>(R.id.rowSegments)
                val firstTextBtn = viewGroup.getChildAt(0) as com.google.android.material.button.MaterialButton
                val secondTextBtn = viewGroup.getChildAt(1) as com.google.android.material.button.MaterialButton
                viewGroup.check(firstTextBtn.id)
                assertNotNull("selected text segment must show check icon", firstTextBtn.icon)
                assertEquals(R.drawable.ic_chip_check, org.robolectric.Shadows.shadowOf(firstTextBtn.icon).createdFromResId)
                assertEquals("unselected text segment must have no icon", null, secondTextBtn.icon)
            }
        }
    }

    @Test fun narrow_text_segment_drops_the_check_instead_of_cutting_the_label() {
        val d = context.resources.displayMetrics.density
        fun laidOut(widthDp: Int) = com.google.android.material.button.MaterialButton(
            androidx.appcompat.view.ContextThemeWrapper(context, R.style.Theme_Dantotsu_NoActionBar),
            null, com.google.android.material.R.attr.materialButtonOutlinedStyle,
        ).apply {
            text = "Original"
            isAllCaps = false
            isCheckable = true
            val pad = (12 * d).toInt()
            setPadding(pad, paddingTop, pad, paddingBottom)
            measure(
                android.view.View.MeasureSpec.makeMeasureSpec((widthDp * d).toInt(), android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED),
            )
            layout(0, 0, measuredWidth, measuredHeight)
        }
        val narrow = laidOut(80)
        narrow.isChecked = true
        ani.dantotsu.settings.rows.markSegment(narrow, null, true)
        assertEquals("narrow segment: selection by fill only", null, narrow.icon)
        val wide = laidOut(200)
        wide.isChecked = true
        ani.dantotsu.settings.rows.markSegment(wide, null, true)
        assertEquals(R.drawable.ic_chip_check, org.robolectric.Shadows.shadowOf(wide.icon).createdFromResId)
    }
}
