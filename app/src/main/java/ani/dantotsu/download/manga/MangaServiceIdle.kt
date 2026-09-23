package ani.dantotsu.download.manga

import java.util.concurrent.atomic.AtomicInteger

/**
 * Authoritative in-flight + idle truth for [MangaDownloaderService].
 *
 * The v9 defect: `poll()` removed a task from the queue, but the task was
 * represented nowhere until `launchDownloadTask` published its `downloadJobs`
 * record (and `activeJobs` entry) afterwards. A concurrent idle check in that
 * poll→record gap observed `queue.isEmpty() && downloadJobs.isEmpty()` and
 * stopped the service from under the unaccounted task; the plain mutable map
 * was also read outside its mutex.
 *
 * Protocol: [onPollHandout] is called synchronously for every task handed out
 * by `poll()`; [onTaskDone] runs when that task's Job completes (registered
 * via `invokeOnCompletion` immediately at launch, so it fires however the job
 * ends). From successful poll until Job completion the task is therefore
 * ALWAYS represented here. [isSettled] is the ONE idle rule consulted by every
 * service-stop decision (malformed commands, delete finally blocks, processor
 * stop path): queue empty AND job map empty AND no in-flight handoff — with
 * the stop itself veto-safe (`stopSelfResult`), never a plain `stopSelf`.
 */
class MangaServiceIdle {
    private val inFlight = AtomicInteger(0)

    /** A polled task was handed to a Job (which will complete exactly once). */
    fun onPollHandout() {
        inFlight.incrementAndGet()
    }

    /** A handed-out task's Job completed (any outcome, including cancelled). */
    fun onTaskDone() {
        inFlight.decrementAndGet()
    }

    fun hasInFlight(): Boolean = inFlight.get() != 0

    /**
     * Authoritative settled truth: no queued work, no tracked jobs, and no
     * polled-but-unfinished handoff. Callers still apply the veto-safe stop
     * (`stopSelfResult`) and re-verify/drain on veto — this predicate alone
     * never stops anything.
     */
    fun isSettled(queueEmpty: Boolean, jobsEmpty: Boolean): Boolean =
        queueEmpty && jobsEmpty && !hasInFlight()
}
