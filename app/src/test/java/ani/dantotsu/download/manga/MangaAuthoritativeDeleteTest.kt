package ani.dantotsu.download.manga

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.io.FileNotFoundException

/**
 * Handoff-A regressions: authoritative provider-level deletion with
 * post-verification ([MangaSafDelete]), end to end through the real
 * orchestration.
 *
 * v7 proved the target through an authoritative lookup but then trusted
 * `DocumentFile.deleteRecursively()`'s boolean, which can collapse provider
 * failures into success. Now the provider's own return value never decides:
 * pre-probe Missing → VerifiedAbsent; direct provider delete (throws →
 * Unavailable); post-probe Missing → Deleted, Present → Failed, throw →
 * Unavailable.
 *
 * The fake provider below is a stateful in-memory tree (not canned answers):
 * listings reflect prior deletes unless scripted otherwise, so pre/post
 * probes observe real state transitions. No sleeps.
 */
class MangaAuthoritativeDeleteTest {

    private fun entry(id: String, name: String, isDir: Boolean = true) =
        MangaSafProbe.ChildEntry(id, name, isDir)

    /**
     * Stateful fake provider tree. Deletes mutate the tree (unless scripted);
     * listings observe current state (unless scripted to fail).
     *
     * STRICT: deleting an already-deleted id throws FileNotFoundException —
     * exactly what a conforming provider may do — so any duplicate provider
     * delete fails the test instead of silently succeeding.
     */
    private class FakeFs(
        val tree: MutableMap<String, MutableList<MangaSafProbe.ChildEntry>> = mutableMapOf(),
    ) : MangaSafDelete.Provider {
        var listThrows: Exception? = null
        var failListAfterDeletes = false
        var deleteThrows: Exception? = null
        var failFirstDeleteOnly: Exception? = null
        var deleteFalse = false
        var mutateOnFalse = false
        var keepTargetOnSuccess: Boolean = false
        val listedParents = mutableListOf<String>()
        val deletedIds = mutableListOf<String>()

        override fun listChildren(parentDocumentId: String): List<MangaSafProbe.ChildEntry> {
            listedParents.add(parentDocumentId)
            listThrows?.let { throw it }
            if (failListAfterDeletes && deletedIds.isNotEmpty()) {
                throw SecurityException("post-delete probe denied")
            }
            return tree[parentDocumentId]?.toList() ?: emptyList()
        }

        override fun deleteDocument(documentId: String): Boolean {
            if (documentId in deletedIds) {
                throw FileNotFoundException("duplicate provider delete of $documentId")
            }
            if (deletedIds.isEmpty()) failFirstDeleteOnly?.let { throw it }
            deleteThrows?.let { throw it }
            deletedIds.add(documentId)
            if (deleteFalse) {
                if (mutateOnFalse) removeId(documentId)
                return false
            }
            if (!keepTargetOnSuccess) removeId(documentId)
            return true
        }

        private fun removeId(documentId: String) {
            tree.values.forEach { it.removeAll { e -> e.documentId == documentId } }
            tree.remove(documentId)
        }
    }

    /** Chapter fixture: base b -> Title t -> Ch1 c -> pages p1, p2. */
    private fun chapterFs() = FakeFs(
        mutableMapOf(
            "b" to mutableListOf(entry("t", "Title")),
            "t" to mutableListOf(entry("c", "Ch1")),
            "c" to mutableListOf(
                entry("p1", "001.jpg", isDir = false),
                entry("p2", "002.jpg", isDir = false),
            ),
        )
    )

    private fun chapterTarget(fs: FakeFs) =
        MangaSafDelete.chapterLookup(fs, "b", "Title", "Ch1")

    private fun runChapter(fs: FakeFs) = MangaSafDelete.runAuthoritativeDelete(
        preProbe = chapterTarget(fs),
        deleteTree = { id -> MangaSafDelete.deleteTree(fs, id) },
        postProbe = chapterTarget(fs),
    )

    // A1: Present -> provider delete succeeds -> post-probe Missing -> Deleted.
    @Test
    fun a1_presentDeletePostMissing_isDeleted() {
        val fs = chapterFs()
        val outcome = runChapter(fs)
        assertEquals(MangaPhysicalDelete.Outcome.Deleted, outcome)
        assertTrue(outcome.permitsMetadataCleanup)
        // Whole subtree went through the provider (pages + chapter).
        assertTrue(fs.deletedIds.containsAll(listOf("p1", "p2", "c")))
    }

    // A2: delete returns false -> post-probe still Present -> Failed.
    @Test
    fun a2_deleteFalsePostPresent_isFailed() {
        val fs = chapterFs()
        fs.deleteFalse = true
        val outcome = runChapter(fs)
        assertTrue(outcome is MangaPhysicalDelete.Outcome.Failed)
        assertFalse(outcome.permitsMetadataCleanup)
    }

    // A3: delete throws SecurityException -> Unavailable, metadata preserved.
    @Test
    fun a3_deleteThrowsSecurity_isUnavailable() {
        val fs = chapterFs()
        fs.deleteThrows = SecurityException("provider revoked mid-delete")
        val outcome = runChapter(fs)
        assertTrue(outcome is MangaPhysicalDelete.Outcome.Unavailable)
        assertFalse(outcome.permitsMetadataCleanup)
    }

    // A4: delete throws representative provider exception -> Unavailable.
    @Test
    fun a4_deleteThrowsProvider_isUnavailable() {
        val fs = chapterFs()
        fs.deleteThrows = IllegalStateException("representative provider failure")
        val outcome = runChapter(fs)
        assertTrue(outcome is MangaPhysicalDelete.Outcome.Unavailable)
        assertFalse(outcome.permitsMetadataCleanup)
    }

    // A5: provider reports success but the target remains -> Failed.
    // The boolean is not decisive: only the post-probe is.
    @Test
    fun a5_successButTargetRemains_isFailed() {
        val fs = chapterFs()
        fs.keepTargetOnSuccess = true
        val outcome = runChapter(fs)
        assertTrue(outcome is MangaPhysicalDelete.Outcome.Failed)
        assertFalse(outcome.permitsMetadataCleanup)
    }

    // A6: provider reports success but the post-probe throws -> Unavailable.
    @Test
    fun a6_successButPostProbeThrows_isUnavailable() {
        val fs = chapterFs()
        fs.failListAfterDeletes = true
        val outcome = runChapter(fs)
        assertTrue(outcome is MangaPhysicalDelete.Outcome.Unavailable)
        assertFalse(outcome.permitsMetadataCleanup)
    }

    // A7: pre-delete authoritative probe Missing -> VerifiedAbsent, delete never runs.
    @Test
    fun a7_preProbeMissing_isVerifiedAbsent() {
        val fs = chapterFs()
        fs.tree["t"]?.clear() // chapter already gone, verified by query
        val outcome = runChapter(fs)
        assertEquals(MangaPhysicalDelete.Outcome.VerifiedAbsent, outcome)
        assertTrue(outcome.permitsMetadataCleanup)
        assertTrue("delete must not run for a missing target", fs.deletedIds.isEmpty())
    }

    /**
     * A8: chapter/title/purge production adapters all route through the
     * authoritative seam (the SAME named adapter functions production calls).
     */
    @Test
    fun a8_titleAdapter_routesThroughSeam() {
        val fs = chapterFs()
        val target = MangaSafDelete.titleLookup(fs, "b", "Title")
        val outcome = MangaSafDelete.runAuthoritativeDelete(
            preProbe = target,
            deleteTree = { id -> MangaSafDelete.deleteTree(fs, id) },
            postProbe = target,
        )
        assertEquals(MangaPhysicalDelete.Outcome.Deleted, outcome)
        assertTrue(outcome.permitsMetadataCleanup)
        assertTrue(fs.deletedIds.containsAll(listOf("p1", "p2", "c", "t")))
    }

    @Test
    fun a8_purgeAdapter_routesThroughSeam() {
        val fs = FakeFs(
            mutableMapOf(
                "b" to mutableListOf(entry("t1", "TitleOne"), entry("t2", "TitleTwo")),
                "t1" to mutableListOf(entry("c1", "Ch1")),
                "t2" to mutableListOf(entry("c2", "Ch1")),
            )
        )
        val purge = MangaSafDelete.runPurgeChildren(
            listChildren = { fs.listChildren("b") },
            deleteTree = { id -> MangaSafDelete.deleteTree(fs, id) },
        )
        assertEquals(MangaPhysicalDelete.Outcome.Deleted, purge.outcome)
        assertTrue(purge.outcome.permitsMetadataCleanup)
        assertTrue(purge.hadChildren)
        assertTrue(fs.deletedIds.containsAll(listOf("t1", "t2", "c1", "c2")))
    }

    @Test
    fun a8_purgeAdapter_postNonEmpty_isFailed() {
        val fs = FakeFs(
            mutableMapOf("b" to mutableListOf(entry("t1", "TitleOne")))
        )
        fs.keepTargetOnSuccess = true // provider "succeeds" but nothing vanishes
        val purge = MangaSafDelete.runPurgeChildren(
            listChildren = { fs.listChildren("b") },
            deleteTree = { id -> MangaSafDelete.deleteTree(fs, id) },
        )
        assertTrue(purge.outcome is MangaPhysicalDelete.Outcome.Failed)
        assertFalse(purge.outcome.permitsMetadataCleanup)
        assertTrue(purge.hadChildren)
    }

    /** Walks up from the test working directory to the repo checkout root. */
    private fun repoRoot(): File {
        var dir: File? = File(System.getProperty("user.dir"))
        while (dir != null) {
            if (File(dir, "app/src/main/java/ani/dantotsu/download/DownloadsManager.kt").exists()) {
                return dir
            }
            dir = dir.parentFile
        }
        throw AssertionError("repo root not found from user.dir")
    }

    /**
     * A9: no CP5 Manga authoritative delete path uses
     * `deleteRecursively()` as its decisive success signal: the three
     * authoritative function bodies route through the seam functions and
     * contain no `deleteRecursively` call. (Legacy anime/novel/maintenance
     * paths are out of scope and untouched.)
     */
    @Test
    fun a9_authoritativeBodies_useSeam_notDeleteRecursively() {
        val text = File(
            repoRoot(),
            "app/src/main/java/ani/dantotsu/download/DownloadsManager.kt"
        ).readText()
        val bodies = mapOf(
            "removeMangaChapterBlocking" to "runAuthoritativeDelete",
            "removeMangaTitleBlocking" to "runAuthoritativeDelete",
            "purgeMangaBlocking" to "runPurgeChildren",
        )
        for ((fn, seam) in bodies) {
            // Comments are stripped: the property is about calls, and KDoc
            // legitimately names the forbidden helper to document its absence.
            val body = stripComments(functionBody(text, "suspend fun $fn("))
            assertTrue("$fn must route through $seam", body.contains(seam))
            assertFalse("$fn must not call deleteRecursively", body.contains("deleteRecursively"))
        }
    }

    private fun stripComments(code: String): String =
        code.replace(Regex("/\\*[\\s\\S]*?\\*/"), "")
            .lines()
            .joinToString("\n") { it.substringBefore("//") }

    /** Extracts the body of a function declaration by brace matching. */
    private fun functionBody(text: String, declaration: String): String {
        val start = text.indexOf(declaration)
        assertTrue("declaration $declaration found", start >= 0)
        val open = text.indexOf('{', start)
        var depth = 0
        var i = open
        while (i < text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return text.substring(open, i + 1)
                }
            }
            i++
        }
        fail("unbalanced braces in $declaration")
        return ""
    }

    /**
     * V9: duplicate provider deletes are caught by the strict fake (which
     * throws FileNotFoundException on a second delete of the same id, exactly
     * as a conforming provider may). Every document below must reach
     * `deleteDocument` exactly once.
     */

    // V9-1: nested chapter hierarchy (chapter -> nested dir + file).
    @Test
    fun v9_nestedChapter_everyIdDeletedExactlyOnce() {
        val fs = FakeFs(
            mutableMapOf(
                "b" to mutableListOf(entry("t", "Title")),
                "t" to mutableListOf(entry("c", "Ch1")),
                "c" to mutableListOf(
                    entry("d", "Extras"),
                    entry("p0", "000.jpg", isDir = false),
                ),
                "d" to mutableListOf(entry("p1", "001.jpg", isDir = false)),
            )
        )
        val target = MangaSafDelete.chapterLookup(fs, "b", "Title", "Ch1")
        val outcome = MangaSafDelete.runAuthoritativeDelete(
            preProbe = target,
            deleteTree = { id -> MangaSafDelete.deleteTree(fs, id) },
            postProbe = target,
        )
        assertEquals(MangaPhysicalDelete.Outcome.Deleted, outcome)
        assertTrue(outcome.permitsMetadataCleanup)
        assertEquals(
            "every document id deleted exactly once",
            fs.deletedIds.size,
            fs.deletedIds.toSet().size,
        )
        assertEquals(setOf("p1", "d", "p0", "c"), fs.deletedIds.toSet())
    }

    // V9-2: nested title hierarchy (title -> chapters -> pages).
    @Test
    fun v9_nestedTitle_everyIdDeletedExactlyOnce() {
        val fs = FakeFs(
            mutableMapOf(
                "b" to mutableListOf(entry("t", "Title")),
                "t" to mutableListOf(entry("c1", "Ch1"), entry("c2", "Ch2")),
                "c1" to mutableListOf(entry("p1", "001.jpg", isDir = false)),
                "c2" to mutableListOf(entry("p2", "001.jpg", isDir = false)),
            )
        )
        val target = MangaSafDelete.titleLookup(fs, "b", "Title")
        val outcome = MangaSafDelete.runAuthoritativeDelete(
            preProbe = target,
            deleteTree = { id -> MangaSafDelete.deleteTree(fs, id) },
            postProbe = target,
        )
        assertEquals(MangaPhysicalDelete.Outcome.Deleted, outcome)
        assertTrue(outcome.permitsMetadataCleanup)
        assertEquals(fs.deletedIds.size, fs.deletedIds.toSet().size)
        assertEquals(setOf("p1", "c1", "p2", "c2", "t"), fs.deletedIds.toSet())
    }

    // V9-3: purge with nested titles/chapters: at most once per id + Deleted.
    @Test
    fun v9_purgeNested_everyIdDeletedAtMostOnce() {
        val fs = FakeFs(
            mutableMapOf(
                "b" to mutableListOf(entry("t1", "TitleOne"), entry("t2", "TitleTwo")),
                "t1" to mutableListOf(entry("c1", "Ch1")),
                "t2" to mutableListOf(entry("c2", "Ch1")),
                "c1" to mutableListOf(entry("p1", "001.jpg", isDir = false)),
                "c2" to mutableListOf(entry("p2", "001.jpg", isDir = false)),
            )
        )
        val purge = MangaSafDelete.runPurgeChildren(
            listChildren = { fs.listChildren("b") },
            deleteTree = { id -> MangaSafDelete.deleteTree(fs, id) },
        )
        assertEquals(MangaPhysicalDelete.Outcome.Deleted, purge.outcome)
        assertTrue(purge.outcome.permitsMetadataCleanup)
        assertTrue(purge.hadChildren)
        assertEquals(fs.deletedIds.size, fs.deletedIds.toSet().size)
        assertEquals(
            setOf("t1", "c1", "p1", "t2", "c2", "p2"),
            fs.deletedIds.toSet(),
        )
    }

    // V9-4: genuine provider throw on the FIRST delete -> Unavailable, no cleanup.
    @Test
    fun v9_genuineFirstDeleteThrow_isUnavailable() {
        val fs = chapterFs()
        fs.failFirstDeleteOnly = FileNotFoundException("genuine first-delete failure")
        val outcome = runChapter(fs)
        assertTrue(outcome is MangaPhysicalDelete.Outcome.Unavailable)
        assertFalse(outcome.permitsMetadataCleanup)
    }

    // V9-5: provider false return with a vanished target -> post-probe decides
    // Deleted (exactly the v8 rule: the boolean is never authoritative).
    @Test
    fun v9_falseReturnVanishedTarget_postProbeDecidesDeleted() {
        val fs = chapterFs()
        fs.deleteFalse = true
        fs.mutateOnFalse = true
        val outcome = runChapter(fs)
        assertEquals(MangaPhysicalDelete.Outcome.Deleted, outcome)
        assertTrue(outcome.permitsMetadataCleanup)
    }

    // V9-6 source-level regression: the recursive directory branch cannot fall
    // through to a second `deleteDocument(child.documentId)` call — exactly one
    // such call site may exist in deleteTree (the file branch).
    @Test
    fun v9_deleteTreeSource_noFallthroughDuplicate() {
        val text = File(
            repoRoot(),
            "app/src/main/java/ani/dantotsu/download/manga/MangaSafDelete.kt"
        ).readText()
        val body = stripComments(functionBody(text, "fun deleteTree("))
        assertEquals(
            "exactly one child delete call site (file branch only)",
            1,
            body.split("deleteDocument(child.documentId)").size - 1,
        )
        assertTrue(
            "target deleted once after its children",
            body.contains("deleteDocument(targetDocumentId)"),
        )
    }
}
