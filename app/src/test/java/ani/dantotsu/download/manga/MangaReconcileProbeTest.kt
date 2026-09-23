package ani.dantotsu.download.manga

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Handoff-F regressions: authoritative chapter/page reconciliation.
 * [MangaReconcileProbe] decides per CP5 entry over direct provider queries:
 * Complete (succeeded query proves the exact page set), VerifiedIncomplete
 * (succeeded query proves a missing chapter or incomplete set — the ONLY
 * prunable state), Unavailable (any query failure — preserve).
 *
 * Legacy `pageCount == null` entries are trusted without consulting the probe.
 * No sleeps; the fake query observes every invocation.
 */
class MangaReconcileProbeTest {

    private fun child(id: String, name: String?, isDir: Boolean = true) =
        MangaSafProbe.ChildEntry(id, name, isDir)

    private fun page(name: String, size: Long) = PageFile(name, size)

    /** Fake provider tree: parentId -> children, or a scripted throw. */
    private class FakeTree(
        val tree: MutableMap<String, List<MangaSafProbe.ChildEntry>> = mutableMapOf(),
    ) : MangaSafProbe.ChildQuery {
        var throws: Exception? = null
        val queried = mutableListOf<String>()
        override fun listChildren(parentDocumentId: String): List<MangaSafProbe.ChildEntry> {
            queried.add(parentDocumentId)
            throws?.let { throw it }
            return tree[parentDocumentId] ?: emptyList()
        }
    }

    private fun statusOf(
        tree: FakeTree,
        pages: Map<String, List<PageFile>> = emptyMap(),
        title: String = "Title",
        chapter: String = "Ch1",
        pageCount: Int = 2,
    ) = MangaReconcileProbe.statusOf(
        query = tree,
        baseDocumentId = "b",
        safeTitle = title,
        safeChapter = chapter,
        pageCount = pageCount,
        pageLister = { id -> pages[id] ?: throw IllegalStateException("unexpected page list for $id") },
    )

    private fun prune(
        tree: FakeTree,
        pages: Map<String, List<PageFile>> = emptyMap(),
        pageCount: Int? = 2,
    ): Boolean = MangaReconcileProbe.shouldPrune(pageCount) { count ->
        MangaReconcileProbe.statusOf(
            query = tree,
            baseDocumentId = "b",
            safeTitle = "Title",
            safeChapter = "Ch1",
            pageCount = count,
            pageLister = { id -> pages[id] ?: throw IllegalStateException("unexpected page list for $id") },
        )
    }

    private fun completePages(): Map<String, List<PageFile>> = mapOf(
        "c" to listOf(page("000.jpg", 10), page("001.jpg", 20)),
    )

    private fun chapterTree(): FakeTree = FakeTree(
        mutableMapOf(
            "b" to listOf(child("t", "Title")),
            "t" to listOf(child("c", "Ch1")),
        )
    )

    // F1: base unavailable (probe throws at root) -> preserve.
    @Test
    fun f1_baseUnavailable_preserve() {
        val tree = chapterTree()
        tree.throws = SecurityException("root gone")
        assertFalse(prune(tree, completePages()))
    }

    // F2: title query throws -> preserve.
    @Test
    fun f2_titleQueryThrows_preserve() {
        val tree = FakeTree()
        tree.throws = SecurityException("title query denied")
        assertFalse(prune(tree, completePages()))
        assertTrue(tree.queried.isNotEmpty())
    }

    // F3: chapter query throws -> preserve.
    @Test
    fun f3_chapterQueryThrows_preserve() {
        var calls = 0
        val throwingChapters = object : MangaSafProbe.ChildQuery {
            override fun listChildren(parentDocumentId: String): List<MangaSafProbe.ChildEntry> {
                calls++
                if (parentDocumentId == "t") throw IllegalStateException("chapter query failed")
                return chapterTree().tree[parentDocumentId] ?: emptyList()
            }
        }
        val pruned = MangaReconcileProbe.shouldPrune(2) { count ->
            MangaReconcileProbe.statusOf(
                query = throwingChapters,
                baseDocumentId = "b",
                safeTitle = "Title",
                safeChapter = "Ch1",
                pageCount = count,
                pageLister = { throw AssertionError("pages must not list when chapter query fails") },
            )
        }
        assertFalse(pruned)
        assertTrue(calls >= 2)
    }

    // F4: page-list query throws -> preserve.
    @Test
    fun f4_pageListThrows_preserve() {
        val tree = chapterTree()
        assertFalse(
            prune(tree, pageCount = 2) // default pageLister throws: no pages listed
        )
        // Direct status shape: the throw propagates for the caller to preserve.
        try {
            statusOf(tree)
            fail("page-list failure must propagate")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("unexpected page list"))
        }
    }

    // F5: null cursor/provider failure -> preserve.
    @Test
    fun f5_nullCursor_preserve() {
        val tree = chapterTree()
        tree.throws = IllegalStateException("null cursor")
        assertFalse(prune(tree, completePages()))
    }

    // F6: successful query, chapter missing -> VerifiedIncomplete -> prune.
    @Test
    fun f6_missingChapter_prune() {
        val tree = FakeTree(
            mutableMapOf("b" to listOf(child("t", "Title")), "t" to emptyList())
        )
        assertTrue(prune(tree))
        assertEquals(MangaReconcileProbe.Status.VerifiedIncomplete, statusOf(tree))
    }

    // F7: successful query, exact pages complete non-zero -> preserve.
    @Test
    fun f7_completePages_preserve() {
        val tree = chapterTree()
        assertFalse(prune(tree, completePages()))
        assertEquals(MangaReconcileProbe.Status.Complete, statusOf(tree, completePages()))
    }

    // F8: successful query, one page missing -> prune.
    @Test
    fun f8_onePageMissing_prune() {
        val tree = chapterTree()
        val pages = mapOf("c" to listOf(page("000.jpg", 10)))
        assertTrue(prune(tree, pages))
    }

    // F9: successful query, zero-byte expected page -> prune.
    @Test
    fun f9_zeroBytePage_prune() {
        val tree = chapterTree()
        val pages = mapOf("c" to listOf(page("000.jpg", 10), page("001.jpg", 0)))
        assertTrue(prune(tree, pages))
    }

    // F10: pageCount==null -> preserve WITHOUT consulting the probe.
    @Test
    fun f10_legacyNullPageCount_preserveWithoutProbe() {
        val tree = chapterTree()
        var probed = false
        val pruned = MangaReconcileProbe.shouldPrune(null) {
            probed = true
            MangaReconcileProbe.Status.Complete
        }
        assertFalse(pruned)
        assertFalse("probe must never run for legacy entries", probed)
        assertTrue("no provider interaction for legacy entries", tree.queried.isEmpty())
    }
}
