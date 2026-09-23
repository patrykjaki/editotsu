package ani.dantotsu.download.manga

import android.Manifest
import kotlin.coroutines.coroutineContext
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.os.Build
import android.os.IBinder
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import ani.dantotsu.R
import ani.dantotsu.connections.crashlytics.CrashlyticsInterface
import ani.dantotsu.download.DownloadedType
import ani.dantotsu.download.DownloadsManager
import ani.dantotsu.download.DownloadsManager.Companion.getSubDirectory
import ani.dantotsu.download.MangaChapterDeleteResult
import ani.dantotsu.download.findValidName
import ani.dantotsu.media.Media
import ani.dantotsu.media.MediaType
import ani.dantotsu.media.manga.ImageData
import ani.dantotsu.media.manga.MangaReadFragment.Companion.ACTION_DOWNLOAD_FAILED
import ani.dantotsu.media.manga.MangaReadFragment.Companion.ACTION_DOWNLOAD_FINISHED
import ani.dantotsu.media.manga.MangaReadFragment.Companion.ACTION_DOWNLOAD_PROGRESS
import ani.dantotsu.media.manga.MangaReadFragment.Companion.ACTION_DOWNLOAD_STARTED
import ani.dantotsu.media.manga.MangaReadFragment.Companion.EXTRA_CHAPTER_NUMBER
import ani.dantotsu.media.manga.MangaReadFragment.Companion.EXTRA_DOWNLOADED_BYTES
import ani.dantotsu.media.manga.MangaReadFragment.Companion.EXTRA_ESTIMATED_TOTAL_BYTES
import ani.dantotsu.snackString
import ani.dantotsu.util.Logger
import ani.dantotsu.util.NumberConverter.Companion.ofLength
import ani.dantotsu.util.SizeFormatter
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import com.anggrayudi.storage.file.deleteRecursively
import com.anggrayudi.storage.file.forceDelete
import com.anggrayudi.storage.file.openOutputStream
import com.google.gson.GsonBuilder
import com.google.gson.InstanceCreator
import eu.kanade.tachiyomi.data.notification.Notifications.CHANNEL_DOWNLOADER_PROGRESS
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SChapterImpl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.net.HttpURLConnection
import java.net.URL
import java.util.Queue
import java.util.concurrent.ConcurrentLinkedQueue

class MangaDownloaderService : Service() {

    private lateinit var notificationManager: NotificationManagerCompat
    private lateinit var builder: NotificationCompat.Builder
    private val downloadsManager: DownloadsManager = Injekt.get<DownloadsManager>()

    /**
     * Service job bookkeeping. A [ConcurrentHashMap] so every read — including
     * teardown and idle checks — observes a safe view; every multi-step
     * check-then-act sequence additionally runs under [mutex] (see
     * [isServiceSettled]).
     */
    private val downloadJobs = java.util.concurrent.ConcurrentHashMap<MangaDownloadKey, MangaJobEntry>()
    private val mutex = Mutex()

    /**
     * Authoritative in-flight accounting (see [MangaServiceIdle]): every task
     * handed out by `poll()` is counted until its Job completes, closing the
     * poll→record gap for every stop decision.
     */
    private val flight = MangaServiceIdle()

    /**
     * Authoritative queue-processor lifetime (fresh-retry liveness).
     *
     * `processorMutex` is held across the ENTIRE drain run, so at most one
     * processor exists and every `ensureProcessor()` either waits behind the live
     * run (whose re-loop then observes its tasks) or runs its own pass afterwards —
     * a fresh retry can never be skipped into the void. Combined with the veto-safe
     * stop inside [MangaProcessorLoop] (re-loop when a newer start vetoes the stop)
     * and a destroy that preserves the queue, a post-cutoff retry is always either
     * picked up or covered by a deterministic restart that drains it.
     */
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val processorMutex = Mutex()

    /**
     * Publish-before-start job handoff (see [MangaJobHandoff]): completion
     * accounting is registered and the bookkeeping record published before the
     * child can execute, so completion-before-publication is unobservable.
     * Declared after [serviceScope], which it captures.
     */
    private val jobHandoff = MangaJobHandoff(serviceScope, flight::onTaskDone)
    @Volatile private var lastStartId = 0
    private val activeJobs = mutableListOf<Job>()
    private var downloadSemaphore = Semaphore(1)
    private val processorLoop = MangaProcessorLoop(
        hasWork = { MangaServiceDataSingleton.downloadQueue.isNotEmpty() },
        // Clear-All-stale attempts (id <= the recorded cancel-all cutoff) are
        // dropped at poll time instead of launching doomed jobs for them. Fresh
        // attempts (id > cutoff) are never dropped here; an old attempt that
        // races the cutoff record still fails closed at begin().
        poll = {
            var next = MangaServiceDataSingleton.downloadQueue.poll()
            while (next != null && MangaServiceDataSingleton.downloadOwnership.isClearAllStale(next.attemptId)) {
                next = MangaServiceDataSingleton.downloadQueue.poll()
            }
            // In-flight handoff begins at poll: from here until the launched
            // Job completes, this task is represented in [flight] even before
            // its job-bookkeeping record is published below.
            if (next != null) flight.onPollHandout()
            next
        },
        launchTask = { task -> launchDownloadTask(task) },
        joinLaunched = {
            activeJobs.joinAll()
            activeJobs.clear()
        },
        // Processor stop path: the SAME centralized settled rule (mutex-disciplined
        // map read + in-flight handoff) with the veto-safe stop. On veto the
        // loop itself re-loops onto the fresh work (it already holds the
        // processor lifetime, so no ensureProcessor cue — that would deadlock
        // on processorMutex).
        stopIfIdle = { id ->
            if (!isServiceSettled()) false
            else withContext(Dispatchers.Main) { stopSelfResult(id) }
        },
        currentStartId = { lastStartId },
    )

    override fun onBind(intent: Intent?): IBinder? {
        // This is only required for bound services.
        return null
    }

    override fun onCreate() {
        super.onCreate()
        notificationManager = NotificationManagerCompat.from(this)
        builder = NotificationCompat.Builder(this, CHANNEL_DOWNLOADER_PROGRESS).apply {
            setContentTitle("Manga Download Progress")
            setSmallIcon(R.drawable.ic_download_24)
            priority = NotificationCompat.PRIORITY_DEFAULT
            setOnlyAlertOnce(true)
            setProgress(0, 0, false)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                builder.build(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, builder.build())
        }
        ContextCompat.registerReceiver(
            this,
            cancelReceiver,
            IntentFilter(ACTION_CANCEL_DOWNLOAD),
            // App-internal only: same-app senders (reader UI, queue UI) plus system.
            // Destructive delete is NEVER exposed here — it is an explicit intent to
            // this non-exported service, handled in onStartCommand.
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        // NOTE: the download queue is intentionally NOT cleared here. Teardown runs
        // only when idle (veto-safe stop with an empty queue and no newer starts), so
        // anything still queued is fresh work that must survive for the deterministic
        // restart to drain — clearing it is exactly the "cleared fresh retry" race.
        // Only transient per-run state is reset.
        MangaServiceDataSingleton.currentTasks.clear()
        MangaServiceDataSingleton.progress.clear()
        downloadJobs.clear()
        MangaServiceDataSingleton.isServiceRunning = false
        serviceScope.cancel()
        unregisterReceiver(cancelReceiver)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Every start vetoes a stale stop decision (see MangaProcessorLoop).
        lastStartId = startId
        // Lifecycle-safe authoritative delete: an EXPLICIT intent to this non-exported
        // service, handled here however the service was started — already alive, or
        // freshly created after having stopped (completed downloads are usually
        // deleted long after the queue drained and the service stopped). A dynamic
        // broadcast would silently become a no-op in that state, so delete is never
        // routed through the dynamic receiver.
        if (intent?.action == ACTION_DELETE_CHAPTER) {
            val command = MangaDeleteCommand.parse(
                intent.action,
                intent.getStringExtra(EXTRA_TITLE),
                intent.getStringExtra(EXTRA_CHAPTER),
                intent.getStringExtra(EXTRA_UNIQUE_NUMBER),
            )
            if (command == null) {
                // Malformed delete request: ignore it. Never run a destructive
                // transaction without a validated exact chapter identity. Do not
                // linger either: stop when settled so a malformed start of a stopped
                // service cannot leave the foreground service alive indefinitely.
                // The centralized idle rule (mutex-disciplined map read +
                // in-flight handoff + veto-safe stop) guarantees a polled-but-
                // unrecorded retry is never mistaken for idleness here.
                Logger.log("MangaDownloaderService: ignoring malformed delete command")
                serviceScope.launch { idleStopIfSettled(startId) }
                return START_NOT_STICKY
            }
            runDeleteCommand(command, startId)
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_DELETE_TITLE) {
            val title = intent.getStringExtra(EXTRA_TITLE)
            val scopeCommand = MangaDeleteCommand.parseScope(intent.action, title)
            if (scopeCommand == null) {
                Logger.log("MangaDownloaderService: ignoring malformed title-delete command")
                serviceScope.launch { idleStopIfSettled(startId) }
                return START_NOT_STICKY
            }
            runDeleteTitleCommand((scopeCommand as MangaDeleteCommand.ScopeCommand.Title).title, startId)
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_PURGE_MANGA) {
            runPurgeMangaCommand(startId)
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_CLEAR_MANGA) {
            runClearMangaQueueCommand()
            return START_NOT_STICKY
        }
        snackString("Download started")
        ensureProcessor()
        return START_NOT_STICKY
    }

    /**
     * Idempotent processor cue. Every download-path start calls this; cues serialize
     * behind [processorMutex] and EACH runs a full drain pass via
     * [MangaProcessorLoop.run] (re-looping over newly queued work, veto-safe stop).
     * There is deliberately no skip-if-running flag: skipping is exactly how a fresh
     * retry queued behind a live run was stranded with no processor.
     */
    private fun ensureProcessor() {
        serviceScope.launch {
            processorMutex.withLock {
                val maxParallel = PrefManager.getVal<Int>(PrefName.MaxParallelDownloads).coerceIn(0, 10)
                downloadSemaphore = Semaphore(if (maxParallel > 0) maxParallel else 1)
                processorLoop.run()
            }
        }
    }

    /**
     * Launch one queued task's download. Called by [MangaProcessorLoop] (which owns
     * poll/join/stop decisions); this function owns only per-task launch +
     * bookkeeping, exactly as the old `processQueue` body did per iteration.
     */
    private suspend fun launchDownloadTask(task: DownloadTask): Job {        val taskKey = MangaDownloadKey.fromTask(task.title, task.chapter)
        val progressKey = "${task.title}_${task.chapter}"
        // Publish-before-start handoff ([MangaJobHandoff]): the child is created
        // LAZY so it cannot execute or complete before its bookkeeping record
        // is published; completion-before-publication (false-idle window plus
        // stale already-completed record) is structurally unobservable — even
        // under parent cancellation, where an unstartable child rolls its
        // record back instead of going stale.
        return jobHandoff.launch(
            publish = { job ->
                // Record under the service mutex (same discipline as cancel/cleanup paths).
                mutex.withLock {
                    downloadJobs[taskKey] = MangaJobEntry(task.attemptId, job)
                }
                activeJobs.add(job)
            },
            remove = { job ->
                // Roll back only if the record is still this job's: a fresher
                // retry's record for the key must survive.
                mutex.withLock {
                    val e = downloadJobs[taskKey]
                    if (e != null && e.job === job) {
                        downloadJobs.remove(taskKey)
                    }
                }
            },
        ) {
            downloadSemaphore.withPermit {
                MangaServiceDataSingleton.currentTasks.add(task)
                try {
                    download(task)
                } finally {
                    mutex.withLock {
                        // Remove the service job record only if it still belongs
                        // to THIS attempt; a newer retry's record must survive.
                        val e = downloadJobs[taskKey]
                        if (e != null && e.attemptId == task.attemptId) {
                            downloadJobs.remove(taskKey)
                        }
                        MangaServiceDataSingleton.currentTasks.remove(task)
                        MangaServiceDataSingleton.progress.remove(progressKey)
                    }
                    updateNotification()
                }
            }
        }
    }

    fun cancelDownload(title: String, chapter: String) {
        // Select exactly the queued tasks whose physical directory identity matches
        // (title + chapter). A different title sharing the same chapter label is never
        // selected.
        val tasks = MangaDownloadCancellation.selectTasksToCancel(
            MangaServiceDataSingleton.downloadQueue,
            title,
            chapter
        )
        tasks.forEach { broadcastDownloadFailed(it.uniqueName) }
        // Capture the attempt-id cutoff synchronously at cancellation invocation so the
        // linearization point is "when the user requested cancel", not when the async
        // cancellation coroutine later executes. This is what guarantees an attempt id
        // issued after the user pressed cancel is strictly greater than `cutoff` and
        // therefore survives (see review v10 blocker 1).
        val cutoff = MangaServiceDataSingleton.downloadOwnership.currentCutoff()
        CoroutineScope(Dispatchers.Default).launch {
            // Full linearizable cancellation transaction (Policy A): the ownership
            // coordinator marks the current owner cancelled, cancels AND joins the exact
            // stored owner job (termination barrier), then runs the queue/job-bookkeeping
            // cleanup — all inside the per-key takeover lock. A retry `begin()` for the
            // same physical key cannot publish until this transaction is fully complete,
            // so a fresh retry's queue/job bookkeeping can never be torn down by this
            // cancellation.
            MangaDownloadCancellation.cancelChapterTransaction(
                ownership = MangaServiceDataSingleton.downloadOwnership,
                queue = MangaServiceDataSingleton.downloadQueue,
                title = title,
                chapter = chapter,
                removeJobRecord = { key, cancelledAttemptId, c ->
                    // Cancel+retire the same-key old job record, then JOIN it outside
                    // [mutex] (its `finally` needs that mutex). A swept old job is
                    // therefore terminated — not merely cancelled — before the
                    // cancellation transaction returns.
                    val terminated = mutex.withLock {
                        val e = downloadJobs[key]
                        // Terminate AND remove every same-key job record whose attempt id is
                        // at or below the cancellation cutoff. This covers not only the
                        // current owner but also an old polled-but-not-yet-owner job that is
                        // absent from the queue and was never published via begin().
                        if (e != null && e.attemptId <= c) {
                            e.job.cancel()
                            downloadJobs.remove(key)
                            e.job
                        } else {
                            null
                        }
                    }
                    terminated?.join()
                },
                cutoff = cutoff,
            )
            updateNotification() // Update the notification after cancellation
        }
    }

    /**
     * Authoritative Stop/Delete runner for one validated [MangaDeleteCommand.DeleteCommand].
     *
     * Invocation-time protocol (all synchronous on the calling thread, before any async
     * work): [MangaDownloadOwnership.reserveDelete] installs the delete gate and only
     * then captures the cutoff. A fresh retry issued afterwards suspends in `begin()`
     * instead of publishing into the invocation→takeover gap; attempts already past the
     * gate carry ids at/below the cutoff and the transaction handles them as old.
     *
     * The reservation is released in a `finally`, so waiting retries are freed even if
     * the destructive step fails. A delete-only command stops the service afterwards
     * only when truly idle (empty queue AND no tracked jobs) — live work is never
     * disturbed, and an empty queue is never "downloaded" as a side effect.
     */
    private fun runDeleteCommand(command: MangaDeleteCommand.DeleteCommand, startId: Int) {
        val key = MangaDownloadKey.fromTask(command.title, command.chapter)
        val cutoff = MangaServiceDataSingleton.downloadOwnership.reserveDelete(key)
        // Snapshot the same-key queued names for the ordered post-delete UI signal.
        val queuedNames = MangaDownloadCancellation.selectTasksToCancel(
            MangaServiceDataSingleton.downloadQueue,
            command.title,
            command.chapter
        ).map { it.uniqueName }
        CoroutineScope(Dispatchers.Default).launch {
            try {
                // Authoritative Stop/Delete transaction: cancel+join old owner, retire
                // old queue/job bookkeeping, then run the SYNCHRONOUS metadata +
                // physical deletion — all inside the per-key takeover lock. An
                // unexpected throw (e.g. storage resolution escaping the blocking
                // boundary) must still surface as the normal failure result — never
                // as a lost outcome from an uncaught coroutine exception.
                val outcome = try {
                    MangaDownloadCancellation.cancelDeleteChapterTransaction(
                    ownership = MangaServiceDataSingleton.downloadOwnership,
                    queue = MangaServiceDataSingleton.downloadQueue,
                    title = command.title,
                    chapter = command.chapter,
                    removeJobRecord = { k, _, c ->
                        val terminated = mutex.withLock {
                            val e = downloadJobs[k]
                            if (e != null && e.attemptId <= c) {
                                e.job.cancel()
                                downloadJobs.remove(k)
                                e.job
                            } else {
                                null
                            }
                        }
                        terminated?.join()
                    },
                    cutoff = cutoff,
                    deleteChapter = {
                        downloadsManager.removeMangaChapterBlocking(command.title, command.chapter)
                    },
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Logger.log("MangaDownloaderService: unexpected chapter delete error: ${e.message}")
                    MangaChapterDeleteResult.Failed(
                        "unexpected chapter delete error: ${e.javaClass.simpleName}"
                    )
                }
                // Ordered UI completion: signal ONLY after the destructive transaction
                // finished, and report the REAL outcome — a failed physical delete is
                // never broadcast/purged as success.
                val names = (queuedNames + listOfNotNull(command.uniqueNumber)).distinct()
                val targets = names.ifEmpty { listOf("${command.chapter}-Unknown") }
                when (outcome) {
                    is MangaChapterDeleteResult.Deleted,
                    is MangaChapterDeleteResult.AlreadyAbsent,
                    -> targets.forEach { broadcastDownloadDeleted(it) }
                    is MangaChapterDeleteResult.Failed -> {
                        Logger.log("MangaDownloaderService: chapter delete failed: ${outcome.reason}")
                        targets.forEach { broadcastDownloadDeleteFailed(it) }
                    }
                    is MangaChapterDeleteResult.Unavailable -> {
                        Logger.log("MangaDownloaderService: chapter delete unavailable: ${outcome.reason}")
                        targets.forEach { broadcastDownloadDeleteFailed(it) }
                    }
                }
            } finally {
                MangaServiceDataSingleton.downloadOwnership.finishDeleteReservation(key)
                idleStopIfSettled(startId)
            }
        }
    }

    /**
     * Authoritative whole-title delete runner (explicit `ACTION_DELETE_TITLE`).
     * Same protocol as [runDeleteCommand] at title scope: synchronous scope
     * reservation, cancel+join of every live owner under the title, scoped
     * queue/job retirement, synchronous physical+metadata deletion, honest result
     * broadcast, reservation release in `finally`, idle-stop when appropriate.
     */
    private fun runDeleteTitleCommand(title: String, startId: Int) {
        val ownership = MangaServiceDataSingleton.downloadOwnership
        val cutoff = ownership.reserveMangaScope(title.findValidName())
        CoroutineScope(Dispatchers.Default).launch {
            try {
                val outcome = try {
                    MangaDownloadCancellation.cancelDeleteScopeTransaction(
                        ownership = ownership,
                        queue = MangaServiceDataSingleton.downloadQueue,
                        titlePath = title.findValidName(),
                        removeJobRecords = { scope, c -> removeScopedJobRecords(scope, c) },
                        cutoff = cutoff,
                        deleteScope = { downloadsManager.removeMangaTitleBlocking(title) },
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Logger.log("MangaDownloaderService: unexpected title delete error: ${e.message}")
                    MangaChapterDeleteResult.Failed(
                        "unexpected title delete error: ${e.javaClass.simpleName}"
                    )
                }
                broadcastScopeResult(titlePath = title, outcome = outcome)
            } finally {
                ownership.finishMangaScope(title.findValidName())
                idleStopIfSettled(startId)
            }
        }
    }

    /**
     * Authoritative purge-all-manga runner (explicit `ACTION_PURGE_MANGA`). Whole-type
     * scope: the all-manga gate parks every manga begin for the duration.
     */
    private fun runPurgeMangaCommand(startId: Int) {
        val ownership = MangaServiceDataSingleton.downloadOwnership
        val cutoff = ownership.reserveMangaScope(null)
        CoroutineScope(Dispatchers.Default).launch {
            try {
                val outcome = try {
                    MangaDownloadCancellation.cancelDeleteScopeTransaction(
                        ownership = ownership,
                        queue = MangaServiceDataSingleton.downloadQueue,
                        titlePath = null,
                        removeJobRecords = { scope, c -> removeScopedJobRecords(scope, c) },
                        cutoff = cutoff,
                        deleteScope = { downloadsManager.purgeMangaBlocking() },
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Logger.log("MangaDownloaderService: unexpected purge error: ${e.message}")
                    MangaChapterDeleteResult.Failed(
                        "unexpected purge error: ${e.javaClass.simpleName}"
                    )
                }
                broadcastScopeResult(titlePath = null, outcome = outcome)
            } finally {
                ownership.finishMangaScope(null)
                idleStopIfSettled(startId)
            }
        }
    }

    /**
     * Authoritative clear-all-manga-queues runner (explicit `ACTION_CLEAR_MANGA`).
     * Cancellation ONLY — no files and no metadata are touched.
     *
     * Invocation-time protocol (synchronous on this thread, before any async work):
     * the all-manga scope gate is installed first and only then is the cutoff
     * captured, so a fresh retry issued afterwards waits in `begin()` instead of
     * racing the sweep, while attempts that slipped past carry ids at/below the
     * cutoff and the transaction handles them as old. The SAME cutoff drives the
     * live-owner cancel+join, the in-place queue sweep, and the job-record sweep
     * (see [MangaDownloadCancellation.cancelAllMangaTransaction]) — per-task
     * cancel broadcasts are deliberately NOT used here because each would recapture
     * a later cutoff and could misclassify a fresh retry as old.
     *
     * The gate is released in a `finally`; fresh survivors (id > cutoff) are then
     * drained by [ensureProcessor], whose loop veto-stops itself when idle.
     */
    private fun runClearMangaQueueCommand() {
        val ownership = MangaServiceDataSingleton.downloadOwnership
        val cutoff = ownership.reserveMangaScope(null)
        CoroutineScope(Dispatchers.Default).launch {
            try {
                MangaDownloadCancellation.cancelAllMangaTransaction(
                    ownership = ownership,
                    queue = MangaServiceDataSingleton.downloadQueue,
                    cutoff = cutoff,
                    removeJobRecords = { c -> removeScopedJobRecords(null, c) },
                )
            } finally {
                ownership.finishMangaScope(null)
                ensureProcessor()
            }
        }
    }
    /**
     * Cancel+retire every job-bookkeeping record in scope at/below [cutoff], then
     * JOIN the cancelled jobs. The join runs OUTSIDE [mutex] (a job's `finally`
     * needs that mutex to retire its own record — joining under it would
     * deadlock), and forms the termination barrier the coordinator requires: a
     * swept old job cannot still be active when the scope transaction returns.
     * Fresh records (id > cutoff) are never touched.
     */
    private suspend fun removeScopedJobRecords(titlePath: String?, cutoff: Long) {
        val terminated = mutex.withLock {
            val cancelled = mutableListOf<Job>()
            val it = downloadJobs.iterator()
            while (it.hasNext()) {
                val (key, entry) = it.next()
                if ((titlePath == null || key.titlePath == titlePath) && entry.attemptId <= cutoff) {
                    entry.job.cancel()
                    it.remove()
                    cancelled.add(entry.job)
                }
            }
            cancelled
        }
        terminated.joinAll()
    }

    /** Ordered scope-delete completion: honest success vs failure signal for list UIs. */
    private fun broadcastScopeResult(titlePath: String?, outcome: MangaChapterDeleteResult) {
        val intent = Intent(ACTION_MANGA_SCOPE_DELETED).apply {
            putExtra(EXTRA_SCOPE_TITLE, titlePath)
            when (outcome) {
                is MangaChapterDeleteResult.Deleted,
                is MangaChapterDeleteResult.AlreadyAbsent,
                -> putExtra(EXTRA_MANGA_DELETED, true)
                is MangaChapterDeleteResult.Failed,
                is MangaChapterDeleteResult.Unavailable,
                -> putExtra(EXTRA_MANGA_DELETE_FAILED, true)
            }
        }
        sendBroadcast(intent)
        when (outcome) {
            is MangaChapterDeleteResult.Deleted,
            is MangaChapterDeleteResult.AlreadyAbsent,
            -> snackString("Successfully deleted")
            is MangaChapterDeleteResult.Failed -> {
                Logger.log("MangaDownloaderService: scope delete failed: ${outcome.reason}")
                snackString("Failed to delete")
            }
            is MangaChapterDeleteResult.Unavailable -> {
                Logger.log("MangaDownloaderService: scope delete unavailable: ${outcome.reason}")
                snackString("Failed to delete")
            }
        }
    }

    /**
     * The ONE authoritative settled check shared by every service-stop path
     * (malformed commands, delete finally blocks, processor stop path). The
     * job-map read runs under [mutex] — the same discipline as every map
     * mutation — and the in-flight handoff ([MangaServiceIdle]) covers tasks
     * polled but not yet recorded, so no stop decision can observe a
     * half-published task as absence.
     */
    private suspend fun isServiceSettled(): Boolean = mutex.withLock {
        MangaServiceDataSingleton.downloadQueue.isEmpty() &&
            downloadJobs.isEmpty() &&
            !flight.hasInFlight()
    }

    /**
     * Stop a command's service when truly settled; never disturb live work.
     * The stop itself is veto-safe (`stopSelfResult` with this command's start
     * id: a newer start vetoes it). On veto, fresh work exists, so the
     * processor is cued to drain it — a vetoed stop never strands a retry.
     * (The processor loop path instead re-loops on veto, since it already
     * holds the processor lifetime; see its `stopIfIdle`.)
     */
    private suspend fun idleStopIfSettled(startId: Int) {
        if (isServiceSettled()) {
            val stopped = withContext(Dispatchers.Main) { stopSelfResult(startId) }
            if (!stopped) {
                ensureProcessor()
            }
        } else {
            updateNotification()
        }
    }

    private fun updateNotification() {
        // Update the notification to reflect the current state of the queue
        val pendingDownloads = MangaServiceDataSingleton.downloadQueue.size
        val text = if (pendingDownloads > 0) {
            "Pending downloads: $pendingDownloads"
        } else {
            "All downloads completed"
        }
        builder.setContentText(text)
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        notificationManager.notify(NOTIFICATION_ID, builder.build())
    }

    suspend fun download(task: DownloadTask) {
        // Physical directory identity (title + chapter), matching the on-disk layout
        // .../MANGA/<title.findValidName()>/<chapter.findValidName()>/. Scanlator is NOT
        // part of the path, so two scanlators of the same chapter share this key and
        // serialize through one owner.
        val ownKey = MangaDownloadKey.fromTask(task.title, task.chapter)
        val myJob = coroutineContext[Job]!!
        try {
            withContext(Dispatchers.IO) {
                // Become the sole owner; this cancels+joins any previous attempt for the
                // same directory before publishing us, establishing the termination
                // barrier so a stale attempt cannot touch the shared directory.
                val generation = MangaServiceDataSingleton.downloadOwnership.begin(ownKey, myJob, task.attemptId)

                val notifi = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    ContextCompat.checkSelfPermission(
                        this@MangaDownloaderService,
                        Manifest.permission.POST_NOTIFICATIONS
                    ) == PackageManager.PERMISSION_GRANTED
                } else {
                    true
                }

                val deferredMap = mutableMapOf<Int, Deferred<Bitmap?>>()
                builder.setContentText("Downloading ${task.title} - ${task.chapter}")
                if (notifi) {
                    withContext(Dispatchers.Main) {
                        notificationManager.notify(NOTIFICATION_ID, builder.build())
                    }
                }

                val baseOutputDir = getSubDirectory(
                    this@MangaDownloaderService,
                    MediaType.MANGA,
                    false,
                    task.title
                ) ?: throw Exception("Base output directory not found")
                val outputDir = getSubDirectory(
                    this@MangaDownloaderService,
                    MediaType.MANGA,
                    false,
                    task.title,
                    task.chapter
                ) ?: throw Exception("Output directory not found")

                // Only the current owner may (re)initialize the shared directory. If
                // ownership was lost (cancel/retry) before we reach the write phase,
                // abort this stale attempt rather than wipe/overwrite a newer owner's
                // directory.
                if (!MangaServiceDataSingleton.downloadOwnership.isOwner(ownKey, generation, myJob)) {
                    throw Exception("${task.chapter} - ownership lost before write; aborting stale attempt")
                }
                outputDir.deleteRecursively(this@MangaDownloaderService, true)

                var farthest = 0
                var downloadedBytes = 0L
                for ((index, image) in task.imageData.withIndex()) {
                    if (deferredMap.size >= task.simultaneousDownloads) {
                        deferredMap.values.awaitAll()
                        deferredMap.clear()
                    }

                    deferredMap[index] = async(Dispatchers.IO) {
                        var bitmap: Bitmap? = null
                        var retryCount = 0

                        while (bitmap == null && retryCount < task.retries) {
                            bitmap = image.fetchAndProcessImage(
                                image.page,
                                image.source
                            )
                            if (bitmap == null) {
                                snackString("${task.chapter} - Retrying to download page ${index.ofLength(3)}, attempt ${retryCount + 1}.")
                            }
                            retryCount++
                        }

                        if (bitmap == null) {
                            // Page fetch failed. Do NOT delete the shared directory here;
                            // the failure simply propagates and the download ends without a
                            // COMPLETE marker (reconciliation/retry handles cleanup). A
                            // delete here would be unsafe under a concurrent owner.
                            throw Exception("${task.chapter} - Unable to download all pages after $retryCount attempts. Try again.")
                        }

                        val writtenBytes = saveToDisk("${index.ofLength(3)}.jpg", outputDir, bitmap)
                        downloadedBytes += writtenBytes
                        farthest++

                        builder.setProgress(task.imageData.size, farthest, false)

                        val estimatedTotalBytes = SizeFormatter.estimateTotalBytesByFraction(
                            downloadedBytes,
                            farthest,
                            task.imageData.size
                        )
                        val progressPercent = farthest * 100 / task.imageData.size
                        MangaServiceDataSingleton.progress["${task.title}_${task.chapter}"] = progressPercent
                        broadcastDownloadProgress(
                            task.uniqueName,
                            progressPercent,
                            downloadedBytes,
                            estimatedTotalBytes
                        )
                        if (notifi) {
                            withContext(Dispatchers.Main) {
                                notificationManager.notify(NOTIFICATION_ID, builder.build())
                            }
                        }
                        bitmap
                    }
                }

                deferredMap.values.awaitAll()

                // Validate the persisted page set BEFORE recording completion.
                // The completion metadata (mark COMPLETE) is only committed through
                // this gate, after the expected page set is confirmed present and
                // valid. If the set is incomplete, the chapter is rejected and the
                // partial directory is cleared; no COMPLETE marker is written.
                val expectedPageCount = task.imageData.size
                val pageFiles = outputDir.listFiles()
                    .filter { it.isFile }
                    .map { PageFile(it.name ?: "", it.length()) }

                MangaDownloadCompletionGate.commitIfComplete(
                    pageFiles = pageFiles,
                    expectedCount = expectedPageCount,
                    onIncomplete = { reason ->
                        // Only clear the directory if we still own it. If ownership moved
                        // (cancel/retry), the newer owner owns the directory and we must
                        // not delete it out from under them.
                        if (MangaServiceDataSingleton.downloadOwnership.isOwner(ownKey, generation, myJob)) {
                            outputDir.deleteRecursively(this@MangaDownloaderService, false)
                        }
                        throw Exception("${task.chapter} - Chapter incomplete: $reason")
                    },
                    onComplete = {
                        // Title media (media.json/cover/banner) runs strictly inside
                        // THIS attempt's coroutine lifetime via [MangaMediaWriteGate]
                        // — never detached — so owner cancellation and scope
                        // termination join it before any delete barrier passes.
                        // Best-effort media failure never invalidates the chapter;
                        // the authoritative ownership checks still gate COMPLETE.
                        val mediaOwned = MangaMediaWriteGate.runOwnedMediaWrite(
                            MangaServiceDataSingleton.downloadOwnership,
                            ownKey,
                            generation,
                            myJob,
                            writeTitleMedia = { saveMediaInfo(task, baseOutputDir) },
                            onWriteFailed = { e ->
                                Logger.log("Title media write failed (best-effort): ${e.message}")
                            },
                        )
                        if (!mediaOwned) {
                            throw Exception("${task.chapter} - ownership lost around media write; COMPLETE not written")
                        }
                        // Atomic ownership check: only commit COMPLETE if this task is
                        // still the exact owner of the directory (not cancelled/deleted/
                        // retried, and bound to our job+generation). Holding the per-key
                        // state lock across the commit closes the TOCTOU window between
                        // validating the page snapshot and writing metadata.
                        val committed = MangaServiceDataSingleton.downloadOwnership.commitIfOwner(
                            ownKey,
                            generation,
                            myJob
                        ) {
                            builder.setContentText("${task.title} - ${task.chapter} Download complete")
                                .setProgress(0, 0, false)
                            if (notifi) {
                                notificationManager.notify(NOTIFICATION_ID, builder.build())
                            }

                            downloadsManager.addDownload(
                                DownloadedType(
                                    task.title,
                                    task.chapter,
                                    MediaType.MANGA,
                                    scanlator = task.scanlator,
                                    pageCount = expectedPageCount,
                                )
                            )
                        }
                        if (!committed) {
                            // Ownership was lost (cancel/retry) after validation. We must
                            // NOT delete the directory here: a newer owner now owns it.
                            // Just fail without writing a COMPLETE marker.
                            throw Exception("${task.chapter} - ownership lost before commit; COMPLETE not written")
                        }
                        broadcastDownloadFinished(task.uniqueName)
                        snackString("${task.title} - ${task.chapter} Download finished")
                    }
                )
            }
        } catch (e: Exception) {
            Logger.log("Exception while downloading file: ${e.message}")
            snackString("Exception while downloading file: ${e.message}")
            Injekt.get<CrashlyticsInterface>().logException(e)
            broadcastDownloadFailed(task.uniqueName)
        } finally {
            // Drop our job record so a later attempt can become the owner cleanly.
            MangaServiceDataSingleton.downloadOwnership.clearIfCurrent(ownKey, myJob)
        }
    }


    private fun saveToDisk(
        fileName: String,
        directory: DocumentFile,
        bitmap: Bitmap
    ): Long {
        directory.findFile(fileName)?.forceDelete(this)
        val file =
            directory.createFile("image/jpeg", fileName) ?: throw Exception("File not created")

        // Compress directly to the SAF output stream (no full-page memory copy).
        // A failed compress/write throws and propagates; a zero-byte result is rejected.
        val success = file.openOutputStream(this, false).use { outputStream ->
            if (outputStream == null) throw Exception("Output stream is null for $fileName")
            bitmap.compress(Bitmap.CompressFormat.JPEG, 100, outputStream)
        }
        if (!success) throw Exception("Failed to compress bitmap for $fileName")

        val persisted = file.length()
        if (persisted <= 0) {
            throw Exception("Wrote zero-byte file for $fileName")
        }
        return persisted
    }

    /**
     * Owner-lifetime title media write (media.json/cover/banner). This is a
     * plain suspend function running in the CALLER's (owner's) coroutine —
     * never a detached scope — so owner cancellation and scope termination
     * join it before any delete barrier passes (see [MangaMediaWriteGate]).
     * Best-effort: failures are contained by the caller and never invalidate
     * the chapter result by themselves.
     */
    private suspend fun saveMediaInfo(task: DownloadTask, directory: DocumentFile) {
        directory.findFile("media.json")?.forceDelete(this@MangaDownloaderService)
        val file = directory.createFile("application/json", "media.json")
            ?: throw Exception("File not created")
        val gson = GsonBuilder()
            .registerTypeAdapter(SChapter::class.java, InstanceCreator<SChapter> {
                SChapterImpl() // Provide an instance of SChapterImpl
            })
            .create()
        val mediaJson = gson.toJson(task.sourceMedia)
        val media = gson.fromJson(mediaJson, Media::class.java)
        if (media != null) {
            media.cover = media.cover?.let { downloadImage(it, directory, "cover.jpg") }
            media.banner = media.banner?.let { downloadImage(it, directory, "banner.jpg") }

            val jsonString = gson.toJson(media)
            withContext(Dispatchers.Main) {
                try {
                    file.openOutputStream(this@MangaDownloaderService, false).use { output ->
                        if (output == null) throw Exception("Output stream is null")
                        output.write(jsonString.toByteArray())
                    }
                } catch (e: android.system.ErrnoException) {
                    e.printStackTrace()
                    Toast.makeText(
                        this@MangaDownloaderService,
                        "Error while saving: ${e.localizedMessage}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }


    private suspend fun downloadImage(url: String, directory: DocumentFile, name: String): String? =
        withContext(Dispatchers.IO) {
            var connection: HttpURLConnection? = null
            println("Downloading url $url")
            try {
                connection = URL(url).openConnection() as HttpURLConnection
                connection.connect()
                if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                    throw Exception("Server returned HTTP ${connection.responseCode} ${connection.responseMessage}")
                }
                directory.findFile(name)?.forceDelete(this@MangaDownloaderService)
                val file =
                    directory.createFile("image/jpeg", name) ?: throw Exception("File not created")
                file.openOutputStream(this@MangaDownloaderService, false).use { output ->
                    if (output == null) throw Exception("Output stream is null")
                    connection.inputStream.use { input ->
                        input.copyTo(output)
                    }
                }
                return@withContext file.uri.toString()
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@MangaDownloaderService,
                        "Exception while saving ${name}: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
                null
            } finally {
                connection?.disconnect()
            }
        }

    private fun broadcastDownloadStarted(chapterNumber: String) {
        val intent = Intent(ACTION_DOWNLOAD_STARTED).apply {
            putExtra(EXTRA_CHAPTER_NUMBER, chapterNumber)
        }
        sendBroadcast(intent)
    }

    private fun broadcastDownloadFinished(chapterNumber: String) {
        val intent = Intent(ACTION_DOWNLOAD_FINISHED).apply {
            putExtra(EXTRA_CHAPTER_NUMBER, chapterNumber)
        }
        sendBroadcast(intent)
    }

    private fun broadcastDownloadFailed(chapterNumber: String) {
        val intent = Intent(ACTION_DOWNLOAD_FAILED).apply {
            putExtra(EXTRA_CHAPTER_NUMBER, chapterNumber)
        }
        sendBroadcast(intent)
    }

    /**
     * Ordered delete-completion signal. Sent ONLY after the authoritative
     * cancel/delete transaction (reservation gate + takeover barrier + synchronous
     * deletion) finishes with [MangaChapterDeleteResult.Deleted] or
     * [MangaChapterDeleteResult.AlreadyAbsent].
     * The fragment purges the chapter UI on receipt; [EXTRA_MANGA_DELETED] marks this
     * as a delete completion (as opposed to an ordinary cancel/failure) so the
     * fragment can also refresh an offline source listing.
     */
    private fun broadcastDownloadDeleted(chapterNumber: String) {
        val intent = Intent(ACTION_DOWNLOAD_FAILED).apply {
            putExtra(EXTRA_CHAPTER_NUMBER, chapterNumber)
            putExtra(EXTRA_MANGA_DELETED, true)
        }
        sendBroadcast(intent)
    }

    /**
     * Ordered delete-FAILURE signal. Sent when the destructive transaction finished
     * but the physical deletion did not happen ([MangaChapterDeleteResult.Failed]).
     * The fragment must NOT purge on this signal: files + metadata are intact and the
     * user must be able to retry.
     */
    private fun broadcastDownloadDeleteFailed(chapterNumber: String) {
        val intent = Intent(ACTION_DOWNLOAD_FAILED).apply {
            putExtra(EXTRA_CHAPTER_NUMBER, chapterNumber)
            putExtra(EXTRA_MANGA_DELETE_FAILED, true)
        }
        sendBroadcast(intent)
    }

    private fun broadcastDownloadProgress(
        chapterNumber: String,
        progress: Int,
        downloadedBytes: Long,
        estimatedTotalBytes: Long
    ) {
        val intent = Intent(ACTION_DOWNLOAD_PROGRESS).apply {
            putExtra(EXTRA_CHAPTER_NUMBER, chapterNumber)
            putExtra("progress", progress)
            putExtra(EXTRA_DOWNLOADED_BYTES, downloadedBytes)
            putExtra(EXTRA_ESTIMATED_TOTAL_BYTES, estimatedTotalBytes)
        }
        sendBroadcast(intent)
    }

    private val cancelReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ACTION_CANCEL_DOWNLOAD) {
                val chapter = intent.getStringExtra(EXTRA_CHAPTER)
                val title = intent.getStringExtra(EXTRA_TITLE)
                if (chapter != null && title != null) {
                    cancelDownload(title, chapter)
                }
            }
            // NOTE: destructive delete is intentionally NOT handled here. It arrives
            // only as an explicit intent to this non-exported service (see
            // onStartCommand/ACTION_DELETE_CHAPTER), so no external app can invoke it
            // through this receiver.
        }
    }


    data class DownloadTask(
        val title: String,
        val chapter: String,
        val scanlator: String,
        val imageData: List<ImageData>,
        val sourceMedia: Media? = null,
        val retries: Int = 2,
        val simultaneousDownloads: Int = 2,
        var attemptId: Long = 0L,
    ) {
        val uniqueName: String
            get() = "$chapter-$scanlator"
    }

    /** A recorded in-flight service job, tagged with the attempt id that created it. */
    private data class MangaJobEntry(val attemptId: Long, val job: Job)

    companion object {
        private const val NOTIFICATION_ID = 1103
        const val ACTION_CANCEL_DOWNLOAD = "action_cancel_download"
        const val EXTRA_CHAPTER = "extra_chapter"
        const val EXTRA_TITLE = "extra_title"
        /**
         * Authoritative manga Stop/Delete command. Sent ONLY as an EXPLICIT intent to
         * this non-exported service (`Intent(context, MangaDownloaderService::class.java)`
         * + `startForegroundService`), handled in `onStartCommand` however the service
         * was started. Exactly one serialized transaction per physical key (synchronous
         * invocation-time reservation, old-owner cancel+join, queue/job retirement,
         * synchronous metadata + physical deletion with an honest result), with the UI
         * signal broadcast only after it finishes.
         */
        const val ACTION_DELETE_CHAPTER = "action_delete_chapter"
        const val EXTRA_UNIQUE_NUMBER = "extra_unique_number"
        /** Marker on the ordered delete-completion broadcast (see [broadcastDownloadDeleted]). */
        const val EXTRA_MANGA_DELETED = "extra_manga_deleted"
        /** Marker on the ordered delete-failure broadcast (see [broadcastDownloadDeleteFailed]). */
        const val EXTRA_MANGA_DELETE_FAILED = "extra_manga_delete_failed"
        /**
         * Authoritative whole-title manga delete command (explicit intent only, same
         * non-exported routing as [ACTION_DELETE_CHAPTER]; extra [EXTRA_TITLE]).
         * Handled by [runDeleteTitleCommand] under a title-scope reservation.
         */
        const val ACTION_DELETE_TITLE = "action_delete_title"
        /**
         * Authoritative purge-all-manga command (explicit intent only; no extras).
         * Handled by [runPurgeMangaCommand] under the all-manga scope reservation.
         */
        const val ACTION_PURGE_MANGA = "action_purge_manga"
        /**
         * Authoritative clear-all-manga-queues command (explicit intent only; no
         * extras). Cancellation ONLY — no files or metadata are touched. Handled by
         * [runClearMangaQueueCommand]: one invocation-time attempt-id cutoff drives
         * the queued sweep, the live-owner cancel+join, and the job-record sweep,
         * so a fresh retry can neither be dropped nor misclassified as old.
         */
        const val ACTION_CLEAR_MANGA = "action_clear_manga"
        /**
         * Ordered scope-delete completion broadcast (title or purge). Carries
         * [EXTRA_SCOPE_TITLE] (null = whole-type purge) plus exactly one of
         * [EXTRA_MANGA_DELETED] / [EXTRA_MANGA_DELETE_FAILED]. List UIs refresh on
         * success and must NOT drop entries on failure.
         */
        const val ACTION_MANGA_SCOPE_DELETED = "ani.dantotsu.ACTION_MANGA_SCOPE_DELETED"
        const val EXTRA_SCOPE_TITLE: String = "extra_scope_title"
    }
}

object MangaServiceDataSingleton {
    val downloadOwnership: MangaDownloadOwnership = MangaDownloadOwnership()
    var imageData: List<ImageData> = listOf()
    var sourceMedia: Media? = null
    var downloadQueue: Queue<MangaDownloaderService.DownloadTask> = ConcurrentLinkedQueue()
    val currentTasks = java.util.Collections.synchronizedList(mutableListOf<MangaDownloaderService.DownloadTask>())
    val progress = java.util.concurrent.ConcurrentHashMap<String, Int>()

    @Volatile
    var isServiceRunning: Boolean = false
}
