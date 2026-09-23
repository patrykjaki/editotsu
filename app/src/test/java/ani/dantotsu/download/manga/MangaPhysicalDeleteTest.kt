package ani.dantotsu.download.manga

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Handoff-B regressions: SAF child lookup / existence exceptions must never
 * become verified absence.
 *
 * v5 made only the ROOT resolver exception-safe; a throwing title/chapter
 * lookup or `exists()` after a resolved root still collapsed into
 * `dirExisted=false` → metadata removal + Deleted/AlreadyAbsent. Now the
 * authoritative functions decide through the pure [MangaPhysicalDelete] seam:
 *
 * - VerifiedAbsent (lookup cleanly missing, or existence cleanly false) and
 *   Deleted (existed + delete succeeded) permit metadata cleanup;
 * - Unavailable (root null/unresolvable, resolver throws, child lookup throws,
 *   existence verification throws, delete throws) and Failed (delete false)
 *   forbid it ([Outcome.permitsMetadataCleanup]).
 *
 * All states use injected lambdas — no Android provider needed. Production
 * wires DocumentFile lambdas through the one shared storage boundary.
 */
class MangaPhysicalDeleteTest {

    private fun <H> present(handle: H) = MangaPhysicalDelete.Lookup.Present(handle)

    // B1: root resolver null -> Unavailable (no cleanup permit by composition:
    // production returns before evaluate, i.e. before any metadata touch).
    @Test
    fun b1_rootNull_isUnavailable() {
        val root = MangaStorageResolution.resolve { null }
        assertTrue(root is MangaStorageResolution.Root.Unavailable)
    }

    // B2: root resolver throws (SecurityException + representative provider
    // exception) -> Unavailable.
    @Test
    fun b2_rootThrows_isUnavailable() {
        val security = MangaStorageResolution.resolve<Any> {
            throw SecurityException("persisted SAF permission revoked")
        }
        assertTrue(security is MangaStorageResolution.Root.Unavailable)
        val provider = MangaStorageResolution.resolve<Any> {
            throw IllegalStateException("representative provider failure")
        }
        assertTrue(provider is MangaStorageResolution.Root.Unavailable)
    }

    // B3: title lookup throws -> Unavailable, metadata preserved, probe never runs.
    @Test
    fun b3_titleLookupThrows_isUnavailable() {
        val outcome = MangaPhysicalDelete.evaluate<String>(
            lookup = { throw SecurityException("title lookup denied") },
            exists = { error("exists must not run after lookup failure") },
            delete = { error("delete must not run after lookup failure") },
        )
        assertTrue(outcome is MangaPhysicalDelete.Outcome.Unavailable)
        assertFalse(outcome.permitsMetadataCleanup)
    }

    // B4: chapter lookup throws -> Unavailable, metadata preserved.
    @Test
    fun b4_chapterLookupThrows_isUnavailable() {
        val outcome = MangaPhysicalDelete.evaluate<String>(
            lookup = {
                // Title resolved, chapter navigation threw (provider failure).
                throw IllegalArgumentException("chapter lookup failed")
            },
            exists = { error("exists must not run after lookup failure") },
            delete = { error("delete must not run after lookup failure") },
        )
        assertTrue(outcome is MangaPhysicalDelete.Outcome.Unavailable)
        assertFalse(outcome.permitsMetadataCleanup)
    }

    // B5: exists() throws -> Unavailable, metadata preserved.
    @Test
    fun b5_existsThrows_isUnavailable() {
        val outcome = MangaPhysicalDelete.evaluate(
            lookup = { present("chapter-dir") },
            exists = { throw SecurityException("exists verification denied") },
            delete = { error("delete must not run after exists failure") },
        )
        assertTrue(outcome is MangaPhysicalDelete.Outcome.Unavailable)
        assertFalse(outcome.permitsMetadataCleanup)
    }

    // B6: delete throws -> Unavailable, metadata preserved.
    @Test
    fun b6_deleteThrows_isUnavailable() {
        val outcome = MangaPhysicalDelete.evaluate(
            lookup = { present("chapter-dir") },
            exists = { true },
            delete = { throw IllegalStateException("provider delete crashed") },
        )
        assertTrue(outcome is MangaPhysicalDelete.Outcome.Unavailable)
        assertFalse(outcome.permitsMetadataCleanup)
    }

    // B7: delete returns false -> Failed, metadata preserved.
    @Test
    fun b7_deleteFalse_isFailed() {
        val outcome = MangaPhysicalDelete.evaluate(
            lookup = { present("chapter-dir") },
            exists = { true },
            delete = { false },
        )
        assertTrue(outcome is MangaPhysicalDelete.Outcome.Failed)
        assertFalse(outcome.permitsMetadataCleanup)
    }

    // B8: verified absent -> AlreadyAbsent/Deleted path (cleanup permitted):
    // lookup cleanly missing, or existence cleanly false.
    @Test
    fun b8_verifiedAbsent_permitsCleanup() {
        val missing = MangaPhysicalDelete.evaluate<String>(
            lookup = { MangaPhysicalDelete.Lookup.Missing },
            exists = { error("exists must not run when lookup is missing") },
            delete = { error("delete must not run when lookup is missing") },
        )
        assertEquals(MangaPhysicalDelete.Outcome.VerifiedAbsent, missing)
        assertTrue(missing.permitsMetadataCleanup)

        val gone = MangaPhysicalDelete.evaluate(
            lookup = { present("chapter-dir") },
            exists = { false },
            delete = { error("delete must not run when verifiably absent") },
        )
        assertEquals(MangaPhysicalDelete.Outcome.VerifiedAbsent, gone)
        assertTrue(gone.permitsMetadataCleanup)
    }

    // B9: verified present + successful delete -> Deleted, metadata removed.
    @Test
    fun b9_presentDeleteSucceeds_isDeleted() {
        val outcome = MangaPhysicalDelete.evaluate(
            lookup = { present("chapter-dir") },
            exists = { true },
            delete = { true },
        )
        assertEquals(MangaPhysicalDelete.Outcome.Deleted, outcome)
        assertTrue(outcome.permitsMetadataCleanup)
    }

    // B10: reconciliation under an unavailable root remains metadata-preserving:
    // no target is selected and the existence probe never runs.
    @Test
    fun b10_reconcileUnavailableRoot_preservesEverything() {
        var probes = 0
        val selected = MangaDownloadReconciliation.selectReconcileTargets(
            baseAvailable = false,
            downloads = listOf(
                ani.dantotsu.download.DownloadedType(
                    "T", "1", ani.dantotsu.media.MediaType.MANGA, pageCount = 3
                ),
            ),
            isComplete = { probes++; false },
        )
        assertTrue(selected.isEmpty())
        assertEquals("probe must never run when root unresolvable", 0, probes)
    }

    /**
     * Error outcomes can never be mistaken for cleanup permits: every
     * non-success [MangaPhysicalDelete.Outcome] forbids metadata mutation by
     * construction.
     */
    @Test
    fun errorOutcomes_neverPermitMetadataCleanup() {
        val errors = listOf(
            MangaPhysicalDelete.Outcome.Unavailable("root null"),
            MangaPhysicalDelete.Outcome.Unavailable("lookup threw"),
            MangaPhysicalDelete.Outcome.Unavailable("exists threw"),
            MangaPhysicalDelete.Outcome.Unavailable("delete threw"),
            MangaPhysicalDelete.Outcome.Failed("delete false"),
        )
        assertTrue(errors.none { it.permitsMetadataCleanup })
        assertTrue(MangaPhysicalDelete.Outcome.VerifiedAbsent.permitsMetadataCleanup)
        assertTrue(MangaPhysicalDelete.Outcome.Deleted.permitsMetadataCleanup)
    }
}
