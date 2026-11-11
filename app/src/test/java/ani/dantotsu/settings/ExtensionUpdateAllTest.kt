package ani.dantotsu.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
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

    private fun ok(name: String) = ExtensionUpdateAll.Outcome(name, null)
    private fun fail(name: String, why: String = "timeout") = ExtensionUpdateAll.Outcome(name, why)

    @Test
    fun `batch of three all updated gives one count`() {
        assertEquals(
            ExtensionUpdateAll.Summary.AllUpdated(3),
            ExtensionUpdateAll.summarize(listOf(ok("A"), ok("B"), ok("C"))),
        )
    }

    @Test
    fun `failures are named in the summary`() {
        assertEquals(
            ExtensionUpdateAll.Summary.SomeFailed(1, listOf("B", "C")),
            ExtensionUpdateAll.summarize(listOf(ok("A"), fail("B"), fail("C"))),
        )
    }

    @Test
    fun `a single update names its extension`() {
        assertEquals(ExtensionUpdateAll.Summary.OneUpdated("A"), ExtensionUpdateAll.summarize(listOf(ok("A"))))
        assertEquals(
            ExtensionUpdateAll.Summary.OneFailed("A", "timeout"),
            ExtensionUpdateAll.summarize(listOf(fail("A"))),
        )
        assertNull(ExtensionUpdateAll.summarize(emptyList()))
    }

    @Test
    fun `each package gets its own notification id`() {
        val a = ExtensionUpdateAll.notificationId("eu.kanade.tachiyomi.animeextension.en.a")
        val b = ExtensionUpdateAll.notificationId("eu.kanade.tachiyomi.animeextension.en.b")
        assertNotEquals(a, b)
        assertEquals(a, ExtensionUpdateAll.notificationId("eu.kanade.tachiyomi.animeextension.en.a"))
    }
}
