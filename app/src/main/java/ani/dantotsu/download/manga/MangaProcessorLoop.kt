package ani.dantotsu.download.manga

import kotlinx.coroutines.Job

/**
 * Authoritative manga queue drain loop: the single processor lifetime for
 * [MangaDownloaderService]'s download queue.
 *
 * Production defect it replaces: `processQueue()` was a detached single-pass loop
 * (`while(queue.isNotEmpty()){poll;launch}` → `joinAll` → unconditional `stopSelf`
 * with no re-loop), guarded by an `isCurrentlyProcessing` flag that flipped false
 * while work still ran. A fresh retry B queued after the drain (or around the
 * post-join empty-check) was therefore stranded with no processor, or wiped by
 * `stopSelf` → `onDestroy` queue-clear.
 *
 * Guarantees of [run] (all effects injected, JVM-testable):
 * - every polled task is launched exactly once;
 * - tasks queued while launched work is joined are picked up by re-looping —
 *   the loop only stops on a queue that is STILL empty after the join;
 * - the stop decision is veto-safe: [stopIfIdle] receives the latest start id and
 *   must stop the service only if no newer start arrived (production:
 *   `stopSelfResult`). On veto (false) the loop re-verifies instead of exiting, so
 *   a newer start's work is always covered by a live loop iteration.
 */
class MangaProcessorLoop(
    private val hasWork: () -> Boolean,
    private val poll: () -> MangaDownloaderService.DownloadTask?,
    private val launchTask: suspend (MangaDownloaderService.DownloadTask) -> Job,
    private val joinLaunched: suspend () -> Unit,
    private val stopIfIdle: suspend (Int) -> Boolean,
    private val currentStartId: () -> Int,
) {
    suspend fun run() {
        while (true) {
            while (hasWork()) {
                val task = poll() ?: break
                launchTask(task)
            }
            joinLaunched()
            // Stop only on a queue that survived the join empty AND a stop the
            // service layer honors (no newer start vetoed it). Otherwise re-loop:
            // the work that vetoed/arrived is covered by this live iteration.
            if (!hasWork() && stopIfIdle(currentStartId())) return
        }
    }
}
