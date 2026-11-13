package ani.dantotsu.media

import org.junit.Assert.assertEquals
import org.junit.Test

class SelectedSourceTest {
    private val before = listOf("Alpha", "Beta", "Local", "Downloaded")
    private val reordered = listOf("Beta", "Gamma", "Alpha", "Local", "Downloaded")

    @Test
    fun `a saved name follows its source when the list is reordered or grows`() {
        assertEquals(2, SelectedSource.resolve("Alpha", 0, reordered))
        assertEquals(0, SelectedSource.resolve("Beta", 1, reordered))
        assertEquals(4, SelectedSource.resolve("Downloaded", 3, reordered))
    }

    @Test
    fun `without a name or with a removed source the saved position stays`() {
        assertEquals(1, SelectedSource.resolve(null, 1, reordered))
        assertEquals(1, SelectedSource.resolve("Removed", 1, reordered))
    }

    @Test
    fun `a name is only recorded from a loaded list and a valid position`() {
        assertEquals("Beta", SelectedSource.nameFor(1, before, listReady = true))
        assertEquals(null, SelectedSource.nameFor(1, before, listReady = false))
        assertEquals(null, SelectedSource.nameFor(9, before, listReady = true))
    }
}
