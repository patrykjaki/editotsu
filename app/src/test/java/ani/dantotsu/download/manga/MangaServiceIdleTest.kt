package ani.dantotsu.download.manga

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * Handoff-B regressions: every service-stop decision consults one
 * authoritative settled truth ([MangaServiceIdle]) — queue empty AND job map
 * empty AND no polled-but-unfinished handoff — with mutex discipline on the
 * map and a veto-safe stop (never plain `stopSelf`).
 *
 * The v9 defect: `poll()` removed a task that was represented nowhere until
 * `launchDownloadTask` published its record, so a concurrent idle check
 * (malformed commands, delete finally blocks) observed an empty service and
 * stopped it from under the unaccounted task; the plain map was also read
 * outside its mutex.
 */
class MangaServiceIdleTest {

    // B1+B3: polled handoff with empty queue/map is NOT settled; completion settles.
    @Test
    fun b_pollGap_notSettledUntilTaskDone() {
        val idle = MangaServiceIdle()
        assertTrue(idle.isSettled(queueEmpty = true, jobsEmpty = true))
        idle.onPollHandout() // B is polled; queue empty; nothing recorded yet
        assertFalse("polled-but-unrecorded task forbids stopping", idle.isSettled(true, true))
        idle.onTaskDone()
        assertTrue("completed handoff settles", idle.isSettled(true, true))
    }

    // Truth table: any represented work forbids stopping.
    @Test
    fun b_unsettledOnAnyRepresentedWork() {
        val idle = MangaServiceIdle()
        assertFalse(idle.isSettled(queueEmpty = false, jobsEmpty = true))
        assertFalse(idle.isSettled(queueEmpty = true, jobsEmpty = false))
        idle.onPollHandout()
        assertFalse(idle.isSettled(queueEmpty = false, jobsEmpty = false))
        idle.onTaskDone()
        assertTrue(idle.isSettled(queueEmpty = true, jobsEmpty = true))
    }

    // B7: after the final task really finishes with no queue/commands, stopping allowed.
    @Test
    fun b_drainedService_mayStop() {
        val idle = MangaServiceIdle()
        idle.onPollHandout()
        idle.onTaskDone()
        assertTrue(idle.isSettled(queueEmpty = true, jobsEmpty = true))
    }

    // B8: a fresh handoff after settle re-arms the guard (no stop with
    // unaccounted work); completion settles again. Loop re-loop liveness itself
    // is covered by MangaProcessorLoopTest; this pins the helper half.
    @Test
    fun b_freshHandoffRearmsGuard() {
        val idle = MangaServiceIdle()
        assertTrue(idle.isSettled(true, true))
        idle.onPollHandout()
        assertFalse(idle.isSettled(true, true))
        idle.onTaskDone()
        assertTrue(idle.isSettled(true, true))
    }

    /**
     * B2+B4+B5+B6: production wiring — every idle path shares the centralized
     * rule. The malformed chapter/title branches plus the chapter/title/purge
     * delete finally blocks call `idleStopIfSettled` (which consults
     * `isServiceSettled`); the processor stop path consults it directly and
     * re-loops on veto. No bare `stopSelf(` remains; the map is a
     * ConcurrentHashMap and its emptiness is read only inside the settled
     * blocks.
     */
    @Test
    fun b_allIdlePaths_shareCentralizedRule() {
        val text = File(
            repoRoot(),
            "app/src/main/java/ani/dantotsu/download/manga/MangaDownloaderService.kt"
        ).readText()
        val code = stripComments(text)
        // Five non-loop stop paths share idleStopIfSettled (2 malformed
        // branches + 3 delete finally blocks); the definition line reads
        // `idleStopIfSettled(startId: Int)` and does not match this count.
        assertEquals(
            "all five command idle paths share idleStopIfSettled",
            5,
            code.split("idleStopIfSettled(startId)").size - 1,
        )
        // isServiceSettled: 1 definition + 1 use in idleStopIfSettled + 1 use
        // in the processor stop path.
        assertEquals(
            "settled check defined once, consulted by both stop kinds",
            3,
            code.split("isServiceSettled()").size - 1,
        )
        // No plain stopSelf bypass of the veto-safe stop ("stopSelfResult("
        // does not contain the substring "stopSelf(").
        assertFalse("no bare stopSelf may remain", code.contains("stopSelf("))
        // Map discipline: concurrent reads/writes are safe by construction…
        assertTrue(code.contains("ConcurrentHashMap<MangaDownloadKey, MangaJobEntry>"))
        // …and emptiness is consulted only inside the single settled definition
        // (the processor stop path delegates to it rather than reading the map).
        assertEquals(
            "isEmpty only inside isServiceSettled",
            1,
            code.split("downloadJobs.isEmpty()").size - 1,
        )
    }

    private fun repoRoot(): File {
        var dir: File? = File(System.getProperty("user.dir"))
        while (dir != null) {
            if (File(dir, "app/src/main/java/ani/dantotsu/download/manga/MangaDownloaderService.kt").exists()) {
                return dir
            }
            dir = dir.parentFile
        }
        throw AssertionError("repo root not found from user.dir")
    }

    private fun stripComments(code: String): String =
        code.replace(Regex("/\\*[\\s\\S]*?\\*/"), "")
            .lines()
            .joinToString("\n") { it.substringBefore("//") }
}
