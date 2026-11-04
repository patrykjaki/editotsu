package ani.dantotsu.settings.rows

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenItemsTest {

    private fun row(id: String, advanced: Boolean = false, visible: Boolean = true) = Row(
        id = id, title = 0, advanced = advanced, visible = { visible },
        kind = Kind.Action(onClick = { _, _ -> }),
    )

    private fun keys(items: List<ScreenItem>) = items.map {
        when (it) {
            is ScreenItem.Header -> "H${it.title}"
            is ScreenItem.RowItem -> it.row.id
            is ScreenItem.AdvancedToggle -> if (it.expanded) "ADV+" else "ADV-"
        }
    }

    @Test
    fun advancedRowsHiddenUntilToggledAndToggleIsLast() {
        val sections = listOf(
            Section(1, listOf(row("a"), row("x", advanced = true), row("b"))),
            Section(2, listOf(row("c"))),
        )
        assertEquals(listOf("H1", "a", "b", "H2", "c", "ADV-"), keys(ScreenItems.build(sections, false)))
        assertEquals(listOf("H1", "a", "x", "b", "H2", "c", "ADV+"), keys(ScreenItems.build(sections, true)))
    }

    @Test
    fun sectionWithOnlyAdvancedRowsHidesItsHeaderWhenCollapsed() {
        val sections = listOf(Section(1, listOf(row("a"))), Section(2, listOf(row("x", advanced = true))))
        assertEquals(listOf("H1", "a", "ADV-"), keys(ScreenItems.build(sections, false)))
        assertEquals(listOf("H1", "a", "H2", "x", "ADV+"), keys(ScreenItems.build(sections, true)))
    }

    @Test
    fun noToggleWithoutVisibleAdvancedRows() {
        val sections = listOf(Section(1, listOf(row("a"), row("x", advanced = true, visible = false))))
        val items = ScreenItems.build(sections, false)
        assertFalse(items.any { it is ScreenItem.AdvancedToggle })
        assertEquals(listOf("H1", "a"), keys(items))
    }

    @Test
    fun hiddenRowsAndEmptySectionsAreDropped() {
        val sections = listOf(Section(1, listOf(row("a", visible = false))), Section(2, listOf(row("b"))))
        assertEquals(listOf("H2", "b"), keys(ScreenItems.build(sections, true)))
    }

    @Test
    fun snapKeepsSliderValuesOnTheStepGrid() {
        assertEquals(1.25f, RowAdapter.snap(1.3f, 0.25f, 2f, 0.25f), 0.0001f)
        assertEquals(2f, RowAdapter.snap(9f, 0.25f, 2f, 0.25f), 0.0001f)
        assertEquals(0.25f, RowAdapter.snap(-1f, 0.25f, 2f, 0.25f), 0.0001f)
        assertTrue(RowAdapter.snap(37f, 0f, 100f, 5f) == 35f)
    }
}
