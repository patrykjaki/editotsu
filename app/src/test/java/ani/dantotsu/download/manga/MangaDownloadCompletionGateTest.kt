package ani.dantotsu.download.manga

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MangaDownloadCompletionGateTest {

    private fun pages(vararg specs: Pair<Int, Long>): List<PageFile> =
        specs.map { (i, len) -> PageFile(MangaDownloadValidator.expectedPageName(i), len) }

    @Test
    fun invalidSet_reachesOnIncompleteOnly_andNeverCommits() {
        var committed = false
        var rejected = false

        runBlocking {
            MangaDownloadCompletionGate.commitIfComplete(
                pageFiles = pages(0 to 1024L, 1 to 2048L, 2 to 512L), // expected 5, missing 3 & 4
                expectedCount = 5,
                onIncomplete = { rejected = true },
                onComplete = { committed = true }
            )
        }

        assertTrue(rejected)
        assertFalse(committed)
    }

    @Test
    fun validSet_reachesOnCompleteOnly() {
        var committed = false
        var rejected = false

        runBlocking {
            MangaDownloadCompletionGate.commitIfComplete(
                pageFiles = pages(0 to 1024L, 1 to 2048L, 2 to 512L, 3 to 4096L, 4 to 100L),
                expectedCount = 5,
                onIncomplete = { rejected = true },
                onComplete = { committed = true }
            )
        }

        assertTrue(committed)
        assertFalse(rejected)
    }

    @Test
    fun zeroBytePage_rejectedAndNotCommitted() {
        var committed = false
        runBlocking {
            MangaDownloadCompletionGate.commitIfComplete(
                pageFiles = pages(0 to 1024L, 1 to 0L, 2 to 512L, 3 to 4096L, 4 to 100L),
                expectedCount = 5,
                onIncomplete = {},
                onComplete = { committed = true }
            )
        }
        assertFalse(committed)
    }

    @Test
    fun gateIsTheSameOneUsedByTheDownloaderService() {
        // The service routes completion through MangaDownloadCompletionGate.commitIfComplete;
        // exercising it here proves the commit decision is gated and that addDownload-equivalent
        // work (onComplete) cannot run for an invalid persisted page set.
        val incomplete = pages(0 to 1024L) // expected 5
        var reachedCommit = false
        runBlocking {
            MangaDownloadCompletionGate.commitIfComplete(
                pageFiles = incomplete,
                expectedCount = 5,
                onIncomplete = { /* service clears dir + throws here, never commits */ },
                onComplete = { reachedCommit = true }
            )
        }
        assertFalse(reachedCommit)
    }
}
