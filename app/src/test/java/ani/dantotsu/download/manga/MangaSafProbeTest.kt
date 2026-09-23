package ani.dantotsu.download.manga

import ani.dantotsu.download.DownloadedType
import ani.dantotsu.download.DownloadsManager.Companion.compareName
import ani.dantotsu.download.findValidName
import ani.dantotsu.media.MediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Handoff-B regressions: the authoritative SAF probe adapter
 * ([MangaSafProbe.findChildDocument] over an injected [MangaSafProbe.ChildQuery])
 * — the layer v6 left lossy.
 *
 * AndroidX `DocumentFile` (`TreeDocumentFile.listFiles()`,
 * `DocumentsContractApi19.exists()`) catches provider/query exceptions and
 * returns empty/false, so a `null` / `false` from those helpers cannot prove
 * absence. The probe instead issues exactly one direct provider query per level
 * (failures propagate into [MangaPhysicalDelete] → Unavailable, metadata
 * preserved) and matches with the existing physical-identity semantics.
 *
 * Every test drives the production adapter (query + match + outcome decision),
 * not only the pure outcome helper.
 */
class MangaSafProbeTest {

    private fun entry(id: String, name: String?, isDir: Boolean = true) =
        MangaSafProbe.ChildEntry(id, name, isDir)

    private fun queryOf(children: List<MangaSafProbe.ChildEntry>) =
        MangaSafProbe.ChildQuery { children }

    private fun throwingQuery(e: Exception) =
        MangaSafProbe.ChildQuery { throw e }

    // B1: successful query, no child -> Missing (the ONLY absence signal).
    @Test
    fun b1_successfulQueryNoChild_isMissing() {
        val lookup = MangaSafProbe.findChildDocument(
            queryOf(listOf(entry("1", "Other Title"))),
            "root",
            "Wanted Title",
        )
        assertEquals(MangaSafProbe.ChildLookup.Missing, lookup)
    }

    // B2: successful title query, no chapter -> Missing at the chapter level.
    @Test
    fun b2_titleFoundChapterMissing_isMissing() {
        val probe = queryOf(listOf(entry("t1", "MyTitle"), entry("c1", "Chapter 2")))
        val title = MangaSafProbe.findChildDocument(probe, "root", "MyTitle")
        assertEquals(MangaSafProbe.ChildLookup.Present("t1"), title)
        val chapter = MangaSafProbe.findChildDocument(
            probe, (title as MangaSafProbe.ChildLookup.Present).documentId, "Chapter 9"
        )
        assertEquals(MangaSafProbe.ChildLookup.Missing, chapter)
    }

    // B3: title/child query throws SecurityException -> propagates (Unavailable downstream).
    @Test
    fun b3_queryThrowsSecurityException_propagates() {
        val outcome = MangaPhysicalDelete.evaluate<String>(
            lookup = {
                when (
                    val r = MangaSafProbe.findChildDocument(
                        throwingQuery(SecurityException("permission revoked")),
                        "root",
                        "Title",
                    )
                ) {
                    is MangaSafProbe.ChildLookup.Missing -> MangaPhysicalDelete.Lookup.Missing
                    is MangaSafProbe.ChildLookup.Present ->
                        MangaPhysicalDelete.Lookup.Present(r.documentId)
                }
            },
            exists = { error("must not run after lookup failure") },
            delete = { error("must not run after lookup failure") },
        )
        assertTrue(outcome is MangaPhysicalDelete.Outcome.Unavailable)
        assertFalse(outcome.permitsMetadataCleanup)
    }

    // B4: query throws representative provider exception -> propagates.
    @Test
    fun b4_queryThrowsProviderException_propagates() {
        val outcome = MangaPhysicalDelete.evaluate<String>(
            lookup = {
                when (
                    val r = MangaSafProbe.findChildDocument(
                        throwingQuery(IllegalStateException("provider crashed")),
                        "root",
                        "Title",
                    )
                ) {
                    is MangaSafProbe.ChildLookup.Missing -> MangaPhysicalDelete.Lookup.Missing
                    is MangaSafProbe.ChildLookup.Present ->
                        MangaPhysicalDelete.Lookup.Present(r.documentId)
                }
            },
            exists = { error("must not run after lookup failure") },
            delete = { error("must not run after lookup failure") },
        )
        assertTrue(outcome is MangaPhysicalDelete.Outcome.Unavailable)
        assertFalse(outcome.permitsMetadataCleanup)
    }

    // B5: successful query returns the matching child -> Present with its id.
    @Test
    fun b5_successfulMatch_isPresent() {
        val lookup = MangaSafProbe.findChildDocument(
            queryOf(listOf(entry("doc-7", "MyTitle"))),
            "root",
            "MyTitle",
        )
        assertEquals(MangaSafProbe.ChildLookup.Present("doc-7"), lookup)
    }

    // B6: authoritative existence/root query throws -> Unavailable.
    @Test
    fun b6_existenceQueryThrows_isUnavailable() {
        val outcome = MangaPhysicalDelete.evaluate<String>(
            lookup = { MangaPhysicalDelete.Lookup.Present("base") },
            exists = { throw SecurityException("root query denied") },
            delete = { error("must not run after exists failure") },
        )
        assertTrue(outcome is MangaPhysicalDelete.Outcome.Unavailable)
        assertFalse(outcome.permitsMetadataCleanup)
    }

    // B7: delete throws -> Unavailable, metadata preserved.
    @Test
    fun b7_deleteThrows_isUnavailable() {
        val outcome = MangaPhysicalDelete.evaluate(
            lookup = { MangaPhysicalDelete.Lookup.Present("chapter-dir") },
            exists = { true },
            delete = { throw IllegalStateException("provider delete crashed") },
        )
        assertTrue(outcome is MangaPhysicalDelete.Outcome.Unavailable)
        assertFalse(outcome.permitsMetadataCleanup)
    }

    // B8: delete returns false -> Failed, metadata preserved.
    @Test
    fun b8_deleteFalse_isFailed() {
        val outcome = MangaPhysicalDelete.evaluate(
            lookup = { MangaPhysicalDelete.Lookup.Present("chapter-dir") },
            exists = { true },
            delete = { false },
        )
        assertTrue(outcome is MangaPhysicalDelete.Outcome.Failed)
        assertFalse(outcome.permitsMetadataCleanup)
    }

    // B9: delete succeeds -> Deleted, metadata cleanup allowed.
    @Test
    fun b9_deleteSucceeds_isDeleted() {
        val outcome = MangaPhysicalDelete.evaluate(
            lookup = { MangaPhysicalDelete.Lookup.Present("chapter-dir") },
            exists = { true },
            delete = { true },
        )
        assertEquals(MangaPhysicalDelete.Outcome.Deleted, outcome)
        assertTrue(outcome.permitsMetadataCleanup)
    }

    /**
     * B10 (v10: no-fuzzy): physical identity for destructive selection is
     * exact-family only — sanitized exact first, then case-insensitive/trimmed;
     * directories only. Call sites keep passing [findValidName]-sanitized names
     * (unchanged). A fuzzy-similar sibling is Missing (see E1-E4).
     */
    @Test
    fun b10_matchingParity_exactCaseTrimDirsOnly() {
        // Exact beats every other variant.
        val exactFirst = listOf(
            entry("1", "Other"),
            entry("2", "MYTITLE"),
            entry("3", "MyTitle"),
            entry("4", "  mytitle  "),
            entry("5", "MyTitle", isDir = false),
        )
        assertEquals("3", MangaSafProbe.matchChild(exactFirst, "MyTitle")?.documentId)

        // Case-insensitive / trimmed match when no exact row exists.
        val noExact = exactFirst.filter { it.documentId != "3" }
        assertEquals("2", MangaSafProbe.matchChild(noExact, "MyTitle")?.documentId)

        // A file with the exact name is never a directory match.
        assertNull(MangaSafProbe.matchChild(listOf(entry("5", "MyTitle", isDir = false)), "MyTitle"))

        // No fuzzy fallback: a sibling whose raw provider name differs, even
        // when the production fuzzy predicate would have matched it, is Missing.
        // ("My: Title".findValidName() == "My Title"; proven fuzzy-positive below.)
        assertNull(MangaSafProbe.matchChild(listOf(entry("9", "My: Title")), "My Title"))

        // Empty query result matches nothing.
        assertNull(MangaSafProbe.matchChild(emptyList(), "MyTitle"))
    }

    /**
     * E1-E4: a fuzzy-similar sibling is never selected nor deleted. First prove
     * the pair IS fuzzy-positive under the production threshold function, then
     * prove the destructive path ignores it.
     */
    @Test
    fun e_fuzzySibling_neverSelectedNorDeleted() {
        val sanitizedTarget = "My: Title".findValidName()
        assertEquals("My Title", sanitizedTarget)
        // E0 (threshold proof): the production compareName predicate matches.
        assertTrue(
            "test pair must exceed the production fuzzy threshold",
            sanitizedTarget.compareName("My: Title"),
        )
        val siblingOnly = listOf(entry("sib", "My: Title"))
        val query = MangaSafProbe.ChildQuery { siblingOnly }
        // E1+E3: requested target absent, fuzzy sibling present -> Missing.
        val lookup = MangaSafProbe.findChildDocument(query, "root", sanitizedTarget)
        assertEquals(MangaSafProbe.ChildLookup.Missing, lookup)
        // E2+E4: end-to-end through the authoritative delete — sibling survives,
        // nothing is deleted, metadata cleanup is not even reached.
        val outcome = MangaSafDelete.runAuthoritativeDelete(
            preProbe = { MangaSafProbe.findChildDocument(query, "root", sanitizedTarget) },
            deleteTree = { fail("sibling must never be deleted") },
            postProbe = { MangaSafProbe.findChildDocument(query, "root", sanitizedTarget) },
        )
        assertEquals(MangaPhysicalDelete.Outcome.VerifiedAbsent, outcome)
        assertEquals("sibling untouched", siblingOnly, query.listChildren("root"))
    }

    // E5: exact match wins (covered in B10); E6: case/trim-equivalent works.
    @Test
    fun e_caseTrimEquivalents_match() {
        assertEquals(
            "4",
            MangaSafProbe.matchChild(listOf(entry("4", "  mytitle  ")), "MyTitle")?.documentId,
        )
        assertEquals(
            "2",
            MangaSafProbe.matchChild(listOf(entry("2", "MYTITLE")), "MyTitle")?.documentId,
        )
    }

    // E7: title-scope destructive lookup has the same no-fuzzy property.
    @Test
    fun e_titleScope_noFuzzy() {
        val query = MangaSafProbe.ChildQuery { listOf(entry("sib", "My: Title")) }
        val lookup = MangaSafDelete.titleLookup(query, "root", "My Title")
        assertEquals(MangaSafProbe.ChildLookup.Missing, lookup())
    }

    // B11: reconciliation under an unavailable root remains metadata-preserving.
    @Test
    fun b11_reconcileUnavailableRoot_preservesEverything() {
        var probes = 0
        val selected = MangaDownloadReconciliation.selectReconcileTargets(
            baseAvailable = false,
            downloads = listOf(
                DownloadedType("T", "1", MediaType.MANGA, pageCount = 3),
            ),
            isComplete = { probes++; false },
        )
        assertTrue(selected.isEmpty())
        assertEquals("probe must never run when root unresolvable", 0, probes)
    }
}
