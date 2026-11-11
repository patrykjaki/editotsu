package ani.dantotsu.download

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExactFolderDeleteTest {

    @Test
    fun `exact name matches after sanitizing, case-insensitive`() {
        assertTrue(isExactFolderName("Frieren", "frieren"))
        assertTrue(isExactFolderName("A_B", "A/B"))
        assertTrue(isExactFolderName("Two  spaces", "Two spaces"))
    }

    @Test
    fun `dedup suffix is a different folder`() {
        assertFalse(isExactFolderName("Frieren (2)", "Frieren"))
        assertFalse(isExactFolderName("Frieren", "Frieren (2)"))
    }

    @Test
    fun `near-identical novel chapter names are different folders`() {
        val a = "Chapter 101 - The Long Road Home To The Northern Kingdom"
        val b = "Chapter 102 - The Long Road Home To The Northern Kingdom"
        assertFalse(isExactFolderName(b, a))
    }

    @Test
    fun `null folder name never matches`() {
        assertFalse(isExactFolderName(null, "x"))
    }

    @Test
    fun `cleanup keeps the folder of a queued or running title`() {
        val live = listOf("Frieren", "Dungeon Meshi")
        assertTrue(isFolderOfLiveTask("frieren", live))
        assertFalse(isFolderOfLiveTask("Frieren (2)", live))
        assertFalse(isFolderOfLiveTask("Orphan", live))
        assertFalse(isFolderOfLiveTask("Orphan", emptyList()))
    }
}
