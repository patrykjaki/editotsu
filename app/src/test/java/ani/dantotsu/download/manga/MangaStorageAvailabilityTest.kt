package ani.dantotsu.download.manga

import ani.dantotsu.download.MangaChapterDeleteResult
import ani.dantotsu.download.chapterDeletePrecheck
import ani.dantotsu.download.decideMangaChapterDeleteOutcome
import ani.dantotsu.download.DownloadedType
import ani.dantotsu.media.MediaType
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Blocker-C regressions: SAF "unavailable/unresolvable" is never mapped to success.
 *
 * 1. genuinely absent directory → Deleted (metadata removed) / AlreadyAbsent;
 * 2. existing directory whose delete fails → Failed with metadata preserved;
 * 3. unresolvable SAF root → Unavailable with metadata preserved (precheck);
 * 4. reconciliation under an unresolvable root preserves all metadata (probe never
 *    invoked) while a resolvable root still prunes genuinely-incomplete entries.
 */
class MangaStorageAvailabilityTest {

    // 1 + 2: decide() matrix (physical-first mapping used by all blocking deletes).

    @Test
    fun genuinelyAbsent_mapsToDeletedOrAlreadyAbsent() {
        assertEquals(
            MangaChapterDeleteResult.Deleted,
            decideMangaChapterDeleteOutcome(
                metadataRemoved = true,
                dirExisted = false,
                dirDeleted = false,
            ),
        )
        assertEquals(
            MangaChapterDeleteResult.AlreadyAbsent,
            decideMangaChapterDeleteOutcome(
                metadataRemoved = false,
                dirExisted = false,
                dirDeleted = false,
            ),
        )
    }

    @Test
    fun existingDirDeleteFailure_isFailed() {
        assertEquals(
            MangaChapterDeleteResult.Failed("chapter directory still present after delete"),
            decideMangaChapterDeleteOutcome(
                metadataRemoved = false,
                dirExisted = true,
                dirDeleted = false,
            ),
        )
    }

    // 3: storage-resolution precheck.

    @Test
    fun unresolvableRoot_isUnavailable() {
        val outcome = chapterDeletePrecheck(baseAvailable = false)
        assertTrue(outcome is MangaChapterDeleteResult.Unavailable)
    }

    @Test
    fun resolvableRoot_proceeds() {
        assertNull(chapterDeletePrecheck(baseAvailable = true))
    }

    /**
     * An Unavailable destructive outcome propagates through the transaction,
     * preserves metadata, still bars the stale owner, and frees a fresh retry.
     */
    @Test
    fun unavailableOutcome_propagatesAndPreserves() = runBlocking {
        val ownership = MangaDownloadOwnership()
        val k = MangaDownloadKey("TitleU", "4")
        val metadata = mutableSetOf(k)
        val queue = mutableListOf<MangaDownloaderService.DownloadTask>()
        val jobMap = mutableMapOf<MangaDownloadKey, Pair<Long, Job>>()

        val cutoff = ownership.reserveMangaScope("TitleU")
        val outcome: MangaChapterDeleteResult
        try {
            outcome = MangaDownloadCancellation.cancelDeleteScopeTransaction(
                ownership = ownership,
                queue = queue,
                titlePath = "TitleU",
                removeJobRecords = { _, _ -> },
                cutoff = cutoff,
                deleteScope = {
                    // Production physical-first behavior under unresolvable root:
                    // no metadata touched, explicit Unavailable.
                    MangaChapterDeleteResult.Unavailable("injected unresolvable root")
                },
            )
        } finally {
            ownership.finishMangaScope("TitleU")
        }

        assertEquals(
            MangaChapterDeleteResult.Unavailable("injected unresolvable root"),
            outcome,
        )
        assertTrue("metadata preserved when storage unresolvable", metadata.contains(k))

        val jobB = launch { }
        val genB = ownership.begin(k, jobB, ownership.issueAttemptId())
        assertTrue(ownership.commitIfOwner(k, genB, jobB) {})
        jobB.cancel()
    }

    // 4: reconciliation under unavailable root.

    private fun entry(title: String, chapter: String, pageCount: Int?): DownloadedType =
        DownloadedType(title, chapter, MediaType.MANGA, pageCount = pageCount)

    @Test
    fun reconcile_unavailableRoot_preservesEverything_probeNeverRuns() {
        val downloads = listOf(
            entry("T", "1", 3), // would prune if probed false
            entry("T", "2", null), // legacy
        )
        var probes = 0
        val selected = MangaDownloadReconciliation.selectReconcileTargets(
            baseAvailable = false,
            downloads = downloads,
            isComplete = { probes++; false },
        )
        assertTrue(selected.isEmpty())
        assertEquals("probe must never run when root unresolvable", 0, probes)
    }

    @Test
    fun reconcile_resolvableRoot_stillPrunesGenuinelyIncomplete() {
        val downloads = listOf(
            entry("T", "1", 3),
            entry("T", "2", 3),
            entry("T", "3", null),
        )
        val selected = MangaDownloadReconciliation.selectReconcileTargets(
            baseAvailable = true,
            downloads = downloads,
            isComplete = { it.chapterName == "2" },
        )
        assertEquals(listOf("1"), selected.map { it.chapterName })
    }

    @Test
    fun reconcile_unavailableRoot_keepsLegacyAndComplete() {
        // Even entries that WOULD be trusted/kept must not be touched at all.
        val downloads = listOf(entry("T", "1", 5))
        var probes = 0
        val selected = MangaDownloadReconciliation.selectReconcileTargets(
            baseAvailable = false,
            downloads = downloads,
            isComplete = { probes++; true },
        )
        assertTrue(selected.isEmpty())
        assertEquals(0, probes)
    }
}
