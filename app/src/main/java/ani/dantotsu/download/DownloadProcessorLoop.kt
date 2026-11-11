package ani.dantotsu.download

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Queue drain loop for the anime and novel download services (same rules as
 * the manga service's loop).
 *
 * - every polled task is launched once;
 * - tasks queued while others run are picked up without waiting for the
 *   running ones to finish, so the parallel limit stays filled;
 * - the loop ends only when nothing runs and nothing is queued, and then
 *   only through [stopIfIdle], which must refuse the stop when a newer start
 *   command arrived (production: re-check the queue on the main thread, then
 *   `stopSelfResult`). A refused stop keeps the loop going.
 * - when [mayStop] is false (a purge owns the service lifecycle) the loop
 *   ends without stopping; the purge path restarts it if work is left.
 */
class DownloadProcessorLoop<T : Any>(
    private val poll: () -> T?,
    /** Start the task; null when it was not started (dropped or requeued). */
    private val launchTask: suspend (T) -> Job?,
    private val hasQueued: () -> Boolean,
    private val mayStop: () -> Boolean,
    private val stopIfIdle: suspend (startId: Int) -> Boolean,
    private val currentStartId: () -> Int,
    private val idleWait: suspend () -> Unit = { delay(IDLE_WAIT_MS) },
) {
    suspend fun run() {
        val active = mutableListOf<Job>()
        while (true) {
            while (true) {
                val task = poll() ?: break
                launchTask(task)?.let { active += it }
            }
            active.removeAll { it.isCompleted }
            if (active.isNotEmpty() || hasQueued()) {
                idleWait()
                continue
            }
            if (!mayStop()) return
            if (stopIfIdle(currentStartId())) return
            idleWait()
        }
    }

    companion object {
        const val IDLE_WAIT_MS = 250L
    }
}

/**
 * Runs [body] in [scope] with at most one instance at a time. [ensure] while
 * it runs makes it run once more after the current pass, so a kick that
 * arrives as the loop is finishing is never lost.
 */
class SingleProcessor(
    private val scope: CoroutineScope,
    private val body: suspend () -> Unit,
) {
    private val lock = Any()
    private var running = false
    private var rerun = false

    fun ensure() {
        synchronized(lock) {
            if (running) {
                rerun = true
                return
            }
            running = true
        }
        scope.launch {
            var done = false
            try {
                while (!done) {
                    body()
                    synchronized(lock) {
                        if (rerun) {
                            rerun = false
                        } else {
                            running = false
                            done = true
                        }
                    }
                }
            } finally {
                if (!done) synchronized(lock) {
                    running = false
                    rerun = false
                }
            }
        }
    }
}
