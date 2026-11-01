package ani.dantotsu.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class ExtensionUpdateAllTest {
    private data class Ext(val name: String, val hasUpdate: Boolean)

    @Test
    fun `only extensions with updates are queued, in list order`() {
        val list = listOf(Ext("a", true), Ext("b", false), Ext("c", true))
        assertEquals(listOf("a", "c"), ExtensionUpdateAll.pending(list) { it.hasUpdate }.map { it.name })
    }

    @Test
    fun `nothing pending gives an empty queue`() {
        assertEquals(emptyList<Ext>(), ExtensionUpdateAll.pending(listOf(Ext("a", false))) { it.hasUpdate })
    }
}
