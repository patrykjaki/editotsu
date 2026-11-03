package ani.dantotsu.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsSectionsTest {

    private fun row(name: String, visible: Boolean = true) =
        Settings(type = SettingsRowType.CLICK, name = name, desc = "", icon = 0, isVisible = visible)

    @Test
    fun headerRowsThenAdvancedToggleThenAdvancedRows() {
        val out = SettingsSections.flatten(
            listOf(SettingsSection("Extensions", listOf(row("a"), row("b")), listOf(row("x")))),
            advancedLabel = "Advanced",
        )
        assertEquals(
            listOf(SettingsRowType.HEADER, 1, 1, SettingsRowType.ADVANCED, 1),
            out.map { it.type },
        )
        assertEquals("Extensions", out[0].name)
        val toggle = out[3]
        assertFalse(toggle.isChecked)
        assertFalse(out[4].isVisible)
        assertEquals(listOf(out[4]), toggle.advancedRows)
    }

    @Test
    fun emptySectionIsSkippedAndNoToggleWithoutAdvancedRows() {
        val out = SettingsSections.flatten(
            listOf(SettingsSection("Empty", emptyList()), SettingsSection("Search", listOf(row("s")))),
            advancedLabel = "Advanced",
        )
        assertEquals(listOf("Search", "s"), out.map { it.name })
    }

    @Test
    fun searchHighlightOnAnAdvancedRowStartsExpanded() {
        val out = SettingsSections.flatten(
            listOf(SettingsSection("Torrents", listOf(row("enable")), listOf(row("Port"), row("Hidden", visible = false)))),
            advancedLabel = "Advanced",
            expandName = "port",
        )
        val toggle = out.first { it.type == SettingsRowType.ADVANCED }
        assertTrue(toggle.isChecked)
        assertTrue(out.first { it.name == "Port" }.isVisible)
        // A row hidden for its own reason stays hidden, and keeps that condition for later.
        assertFalse(out.first { it.name == "Hidden" }.isVisible)
        assertEquals(listOf(true, false), toggle.advancedVisibility)
    }
}
