package ani.dantotsu.download

import ani.dantotsu.media.MediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MAX MERGE M4 correction (HOLD2 B1): generic index recovery safety.
 * [planIndexRecovery] is the pure seam behind
 * `DownloadsManager.rebuildIndexFromDisk()` (whose SAF/Context walk is not
 * host-testable). The in-transaction re-check is proved through the real
 * [DownloadsMetadataStore].
 */
class IndexRecoveryTest {

    private fun entry(title: String, chapter: String, type: MediaType) =
        DownloadedType(title, chapter, type)

    @Test
    fun `anime recovery works`() {
        val planned = planIndexRecovery(
            indexed = emptyList(),
            scanned = listOf(Triple(MediaType.ANIME, "Frienen", "E1"))
        )
        assertEquals(1, planned.size)
        assertEquals("Frienen", planned[0].titleName)
        assertEquals("E1", planned[0].chapterName)
        assertEquals(MediaType.ANIME, planned[0].type)
    }

    @Test
    fun `novel recovery works`() {
        val planned = planIndexRecovery(
            indexed = emptyList(),
            scanned = listOf(Triple(MediaType.NOVEL, "Overlord", "C3"))
        )
        assertEquals(1, planned.size)
        assertEquals(MediaType.NOVEL, planned[0].type)
    }

    @Test
    fun `already indexed entries are skipped`() {
        val planned = planIndexRecovery(
            indexed = listOf(entry("Frienen", "E1", MediaType.ANIME)),
            scanned = listOf(
                Triple(MediaType.ANIME, "Frienen", "E1"),
                Triple(MediaType.ANIME, "Frienen", "E2")
            )
        )
        assertEquals(listOf("E2"), planned.map { it.chapterName })
    }

    @Test
    fun `manga is never reconstructed by generic rebuild`() {
        val planned = planIndexRecovery(
            indexed = emptyList(),
            scanned = listOf(
                Triple(MediaType.MANGA, "Berserk", "C100"),
                Triple(MediaType.MANGA, "Berserk", "C101")
            )
        )
        assertTrue(planned.isEmpty())
    }

    @Test
    fun `manga skipped even alongside recoverable types`() {
        val planned = planIndexRecovery(
            indexed = emptyList(),
            scanned = listOf(
                Triple(MediaType.ANIME, "Frienen", "E1"),
                Triple(MediaType.MANGA, "Berserk", "C100"),
                Triple(MediaType.NOVEL, "Overlord", "C3")
            )
        )
        assertEquals(
            setOf(MediaType.ANIME, MediaType.NOVEL),
            planned.map { it.type }.toSet()
        )
    }

    @Test
    fun `racing completion does not duplicate metadata`() {
        val store = DownloadsMetadataStore(emptyList()) {}
        val scanned = listOf(Triple(MediaType.ANIME, "Frienen", "E1"))
        // First plan: one candidate.
        val first = planIndexRecovery(store.snapshot(), scanned)
        assertEquals(1, first.size)
        // A normal completion lands before the transaction runs.
        store.transact { it.add(entry("Frienen", "E1", MediaType.ANIME)) }
        // The in-transaction re-check (same predicate production uses) drops it.
        val added = store.transact { live ->
            planIndexRecovery(live.toList(), scanned).filterNot { c ->
                live.any { sameTypedDownload(it, c.titleName, c.chapterName, c.type) }
            }.onEach(live::add)
        }
        assertTrue(added.isEmpty())
        assertEquals(1, store.snapshot().size)
    }
}
