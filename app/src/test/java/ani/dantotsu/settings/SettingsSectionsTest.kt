package ani.dantotsu.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsSectionsTest {

    private fun row(name: String, visible: Boolean = true) =
        Settings(type = SettingsRowType.CLICK, name = name, desc = "", icon = 0, isVisible = visible)

    @Test
    fun advancedRowsStayInTheirSectionWithOneToggleAtTheEnd() {
        val out = SettingsSections.flatten(
            listOf(
                SettingsSection("Storage", listOf(row("a")), listOf(row("x"))),
                SettingsSection("Downloads", listOf(row("b")), listOf(row("y"))),
            ),
            advancedLabel = "Show advanced settings",
        )
        assertEquals(
            listOf("Storage", "a", "x", "Downloads", "b", "y", "Show advanced settings"),
            out.map { it.name },
        )
        assertEquals(1, out.count { it.type == SettingsRowType.ADVANCED })
        val toggle = out.last()
        assertFalse(toggle.isChecked)
        assertFalse(out.first { it.name == "x" }.isVisible)
        assertFalse(out.first { it.name == "y" }.isVisible)
        assertEquals(listOf("x", "y"), toggle.advancedRows.map { it.name })
    }

    @Test
    fun emptySectionIsSkippedAndNoToggleWithoutAdvancedRows() {
        val out = SettingsSections.flatten(
            listOf(SettingsSection("Empty", emptyList()), SettingsSection("Search", listOf(row("s")))),
            advancedLabel = "Show advanced settings",
        )
        assertEquals(listOf("Search", "s"), out.map { it.name })
    }

    @Test
    fun sectionWithOnlyAdvancedRowsHidesItsHeaderUntilExpanded() {
        val out = SettingsSections.flatten(
            listOf(
                SettingsSection("Storage", listOf(row("a"))),
                SettingsSection("Maintenance", emptyList(), listOf(row("purge"))),
            ),
            advancedLabel = "Show advanced settings",
        )
        val header = out.first { it.name == "Maintenance" }
        assertFalse(header.isVisible)
        assertTrue(out.last().advancedRows.contains(header))
    }

    @Test
    fun searchHighlightOnAnAdvancedRowStartsExpanded() {
        val out = SettingsSections.flatten(
            listOf(
                SettingsSection("Sources", listOf(row("repos")), listOf(row("DNS"))),
                SettingsSection("Torrents", listOf(row("enable")), listOf(row("Port"), row("Hidden", visible = false))),
            ),
            advancedLabel = "Show advanced settings",
            expandName = "port",
        )
        val toggle = out.first { it.type == SettingsRowType.ADVANCED }
        assertTrue(toggle.isChecked)
        assertTrue(out.first { it.name == "Port" }.isVisible)
        assertTrue(out.first { it.name == "DNS" }.isVisible)
        // A row hidden for its own reason stays hidden, and keeps that condition for later.
        assertFalse(out.first { it.name == "Hidden" }.isVisible)
        assertEquals(listOf(true, true, false), toggle.advancedVisibility)
    }
}
