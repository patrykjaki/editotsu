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
import ani.dantotsu.settings.screens.SettingsScreens
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

    @Test fun every_screen_advanced_toggle_shows_and_hides_rows() {
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
                    val chevron = lastChevron(list)
                    assertEquals("${screen.id}: chevron", 180f, chevron, 0.1f)
                    assertTrue(SettingsActions.toggleAdvanced(list) { idle() })
                    assertEquals("${screen.id}: hide again", before, list.rowAdapterKeys())
                    assertEquals("${screen.id}: chevron back", 0f, lastChevron(list), 0.1f)
                }
            }
        }
    }

    private fun lastChevron(list: RecyclerView): Float {
        val last = list.adapter!!.itemCount - 1
        list.scrollToPosition(last)
        idle(1000)
        return list.findViewHolderForAdapterPosition(last)!!.itemView.findViewById<android.view.View>(R.id.advancedChevron).rotation
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
}
