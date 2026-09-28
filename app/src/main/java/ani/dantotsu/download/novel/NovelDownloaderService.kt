package ani.dantotsu.download.novel

import android.Manifest
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
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
import ani.dantotsu.download.PurgeCoordinator
import ani.dantotsu.download.PurgeOutcome
import ani.dantotsu.download.PurgeSnapshot
import ani.dantotsu.download.WifiLossMonitor
import ani.dantotsu.download.DownloadsManager
import ani.dantotsu.download.DownloadsManager.Companion.getSubDirectory
import ani.dantotsu.media.Media
import ani.dantotsu.media.MediaType
import ani.dantotsu.media.novel.NovelReadFragment
import ani.dantotsu.parsers.novel.LnReaderNovelParser
import ani.dantotsu.snackString
import ani.dantotsu.util.Logger
import com.anggrayudi.storage.file.forceDelete
import com.anggrayudi.storage.file.openOutputStream
import com.google.gson.GsonBuilder
import com.google.gson.InstanceCreator
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SChapterImpl
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Request
import okio.buffer
import okio.sink
import tachiyomi.core.util.lang.launchIO
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Queue
import java.util.concurrent.ConcurrentLinkedQueue

class NovelDownloaderService : Service() {

    private lateinit var notificationManager: NotificationManagerCompat
    private lateinit var builder: NotificationCompat.Builder
    private val downloadsManager: DownloadsManager = Injekt.get<DownloadsManager>()

    private val downloadJobs = mutableMapOf<String, Job>()
    private val mutex = Mutex()
    private val purgeScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val purgeCoordinator = PurgeCoordinator()
    @Volatile private var lastStartId = 0
    /**
     * Correction-2 Blocker 2: true from the purge command's synchronous
     * entry until its outcome is published. Every service stop decision
     * defers to it — the purge path owns the final stop.
     */
    @Volatile private var purgeActive = false
    /**
     * Keys requeued by the queue loop after hitting a live purge barrier.
     * Guarded by [mutex]. Drained as old scope at the settle check.
     */
    private val purgeGatedKeys = mutableSetOf<String>()
    private var isCurrentlyProcessing = false

    private val networkHelper = Injekt.get<NetworkHelper>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        notificationManager = NotificationManagerCompat.from(this)
        builder = NotificationCompat.Builder(this, Notifications.CHANNEL_DOWNLOADER_PROGRESS).apply {
            setContentTitle("Novel Download Progress")
            setSmallIcon(R.drawable.ic_download_24)
            priority = NotificationCompat.PRIORITY_DEFAULT
            setOnlyAlertOnce(true)
            setProgress(0, 0, false)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, builder.build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, builder.build())
        }
        ContextCompat.registerReceiver(this, cancelReceiver, IntentFilter(ACTION_CANCEL_DOWNLOAD), ContextCompat.RECEIVER_EXPORTED)
        ensureWifiMonitor()
    }

    /**
     * MAX MERGE M3 HOLD (Blocker 6): Wi-Fi → cellular mid-transfer guard.
     * Edge-triggered exactly once per loss; stops active work through the
     * existing safe path. Manual retry only — never auto-resume.
     */
    private var wifiLossMonitor: WifiLossMonitor? = null

    private fun ensureWifiMonitor() {
        val monitor = wifiLossMonitor ?: WifiLossMonitor(
            this,
            onBlocked = {
                cancelAllDownloads()
                snackString(getString(R.string.download_wifi_only_warning))
            }
        ).also { wifiLossMonitor = it }
        monitor.ensure()
    }

    private fun releaseWifiMonitor() {
        wifiLossMonitor?.release()
        wifiLossMonitor = null
    }

    override fun onDestroy() {
        super.onDestroy()
        releaseWifiMonitor()
        purgeScope.cancel()
        purgeCoordinator.shutdown()
        NovelServiceDataSingleton.downloadQueue.clear()
        NovelServiceDataSingleton.currentTasks.clear()
        NovelServiceDataSingleton.progress.clear()
        downloadJobs.clear()
        NovelServiceDataSingleton.isServiceRunning = false
        unregisterReceiver(cancelReceiver)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        snackString("Download started")
        ensureWifiMonitor()
        if (intent?.action == ACTION_CANCEL_ALL_DOWNLOADS) {
            // MAX MERGE M3 (Blocker 5): cancel-all through the full-scope
            // safe path (queue + current + semaphore waiters).
            cancelAllDownloads()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_PURGE_ALL_NOVEL) {
            // MAX MERGE M3 HOLD (Blocker 2): ownership-safe purge
            // transaction — snapshot real jobs, cancel, JOIN, settle,
            // then purge, then report. Stops the service when settled.
            runPurgeAllNovelCommand()
            return START_NOT_STICKY
        }
        ensureProcessor()
        return START_NOT_STICKY
    }

    /** Ordinary download-processor kick (existing semantics, extracted). */
    private fun ensureProcessor() {
        val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        serviceScope.launch {
            mutex.withLock {
                if (!isCurrentlyProcessing) {
                    isCurrentlyProcessing = true
                    processQueue()
                    isCurrentlyProcessing = false
                }
            }
        }
    }

    private fun processQueue() {
        CoroutineScope(Dispatchers.Default).launch {
            val maxParallel = PrefManager.getVal<Int>(PrefName.MaxParallelDownloads).coerceIn(0, 10)
            val concurrency = if (maxParallel > 0) maxParallel else 1
            val semaphore = Semaphore(concurrency)
            val activeJobs = mutableListOf<Job>()

            while (NovelServiceDataSingleton.downloadQueue.isNotEmpty()) {
                // MAX MERGE M3 HOLD (Blocker 2): never launch fresh work
                // into a live purge barrier; queued work survives and
                // drains after the barrier clears.
                if (purgeCoordinator.barrierActive) {
                    delay(500)
                    continue
                }
                val task = NovelServiceDataSingleton.downloadQueue.poll() ?: continue
                // MAX MERGE M3 (upstream 3dbce385): fail-closed Wi-Fi gate.
                if (PrefManager.getVal<Boolean>(PrefName.DownloadWifiOnly) && !ani.dantotsu.isWifiConnected(this@NovelDownloaderService)) {
                    broadcastDownloadFailed(task.originalLink)
                    withContext(Dispatchers.Main) {
                        snackString(getString(R.string.download_wifi_only_warning))
                    }
                    continue
                }
                val taskKey = "${task.title}_${task.chapter}"
                // The record holder breaks the LAZY self-reference: the
                // outer finally must retire by Job identity (see below).
                var jobRef: Job? = null
                val job = launch(start = CoroutineStart.LAZY) {
                    val self = jobRef
                    try {
                        semaphore.withPermit {
                            NovelServiceDataSingleton.currentTasks.add(task)
                            try {
                                download(task)
                            } finally {
                                mutex.withLock {
                                    NovelServiceDataSingleton.currentTasks.remove(task)
                                    NovelServiceDataSingleton.progress.remove(taskKey)
                                }
                            }
                        }
                    } finally {
                        // MAX MERGE M3 HOLD (correction-2 Blocker 1): retire
                        // the record even when cancellation lands BEFORE
                        // permit entry (a waiter never runs the inner
                        // finally). Identity-safe: never removes a fresher
                        // replacement recorded under the same key.
                        mutex.withLock {
                            if (self != null && downloadJobs[taskKey] === self) {
                                downloadJobs.remove(taskKey)
                            }
                        }
                        updateNotification()
                    }
                }
                jobRef = job
                // MAX MERGE M3 HOLD (correction Blocker 1): close the
                // poll→record gap. The job record is published under the
                // same mutex the purge snapshot uses; a task that loses
                // the race to a live barrier is requeued (tagged as old
                // scope) instead of launching into the purge window. The
                // LAZY job never executes unless admitted here.
                var admitted = false
                mutex.withLock {
                    if (!purgeCoordinator.barrierActive) {
                        downloadJobs[taskKey] = job
                        admitted = true
                    } else {
                        purgeGatedKeys.add(taskKey)
                        NovelServiceDataSingleton.downloadQueue.offer(task)
                    }
                }
                if (!admitted) {
                    // Correction-4 Blocker 2: the never-admitted LAZY job
                    // must be explicitly terminated — otherwise an
                    // unstarted, uncancelled child lingers on the processor
                    // after its task was requeued. Cancelling before start
                    // never executes the body.
                    job.cancel()
                    delay(500)
                    continue
                }
                job.start()
                activeJobs.add(job)
            }
            activeJobs.joinAll()
            // MAX MERGE M3 HOLD (correction-2 Blocker 2): the legacy
            // processor must not stop the service while a purge
            // transaction owns the lifecycle. The purge path owns the
            // final stop decision (or keeps draining fresh work).
            if (NovelServiceDataSingleton.downloadQueue.isEmpty() &&
                !purgeActive && !purgeCoordinator.barrierActive
            ) {
                withContext(Dispatchers.Main) { stopSelf() }
            }
        }
    }

    /**
     * MAX MERGE M3 HOLD (Blocker 2): ownership-safe purge transaction.
     * Cancel-all runs first; physical purge happens only after live
     * writers are proven terminated (bounded); completion (or timeout)
     * is broadcast — the UI must never purge directly.
     */
    private fun runPurgeAllNovelCommand() {
        // MAX MERGE M3 HOLD (correction-2 Blocker 2): synchronous ownership
        // claim on the command thread — the legacy processor and every
        // other stop path must defer while the transaction is unfinished.
        purgeActive = true
        purgeScope.launch {
            try {
                val outcome = purgeCoordinator.runPurge(
                    snapshotAndCancel = { snapshotPurgeScope() },
                    settle = { snapshot -> settlePurgeScope(snapshot) },
                    purge = { downloadsManager.purgeDownloads(MediaType.NOVEL) }
                )
                val done = outcome == PurgeOutcome.DONE
                Logger.log("Novel purge ${if (done) "DONE" else "TIMEOUT"}")
                sendBroadcast(Intent(ACTION_PURGE_ALL_NOVEL_DONE).apply {
                    putExtra(EXTRA_PURGE_SUCCESS, done)
                })
            } finally {
                purgeActive = false
            }
            if (isPurgeSettled()) {
                withContext(Dispatchers.Main) { stopSelfResult(lastStartId) }
            } else {
                // Fresh post-purge work survived the cutoff: keep draining.
                ensureProcessor()
            }
        }
    }

    /**
     * Correction Blocker 1: snapshot the ENTIRE live writer set under
     * [mutex] — queue, current, and every [downloadJobs] entry (waiters
     * included) — mark state through the existing safe path, cancel every
     * job, prune old scope from the queue. Records retire in each job's
     * own finally; removal is never the proof.
     */
    private suspend fun snapshotPurgeScope(): PurgeSnapshot {
        return mutex.withLock {
            val queued = NovelServiceDataSingleton.downloadQueue.toList()
            val live = NovelServiceDataSingleton.currentTasks.toList()
            val ids = (queued.map { NovelTaskId(it.title, it.chapter) } +
                live.map { NovelTaskId(it.title, it.chapter) })
            val scopeKeys = novelAllCancelKeys(ids).toSet()
            val match: (NovelDownloaderService.DownloadTask) -> Boolean =
                { novelTaskKey(it.title, it.chapter) in scopeKeys }
            (queued + live).filter(match).forEach { task ->
                broadcastDownloadFailed(task.originalLink)
            }
            NovelServiceDataSingleton.downloadQueue.removeAll(match)
            NovelServiceDataSingleton.progress.keys.removeAll(scopeKeys)
            val jobs = downloadJobs.values.toList()
            jobs.forEach { it.cancel() }
            PurgeSnapshot(jobs, scopeKeys)
        }
    }

    /**
     * Settle check under [mutex]: drop old-scope leftovers (snapshot keys
     * plus barrier-gated requeues) and report whether no writer remains.
     * Fresh keys enqueued after the snapshot survive by construction.
     */
    private suspend fun settlePurgeScope(snapshot: PurgeSnapshot): Boolean {
        return mutex.withLock {
            NovelServiceDataSingleton.downloadQueue.removeAll {
                val key = novelTaskKey(it.title, it.chapter)
                key in snapshot.scopeKeys || key in purgeGatedKeys
            }
            purgeGatedKeys.clear()
            NovelServiceDataSingleton.downloadQueue.isEmpty() &&
                NovelServiceDataSingleton.currentTasks.isEmpty() && downloadJobs.isEmpty()
        }
    }

    /** Truthful settled state for the post-purge stop decision. */
    private suspend fun isPurgeSettled(): Boolean {
        return mutex.withLock {
            NovelServiceDataSingleton.downloadQueue.isEmpty() &&
                NovelServiceDataSingleton.currentTasks.isEmpty() && downloadJobs.isEmpty()
        }
    }

    /**
     * MAX MERGE M3 (Blockers 5+6): cancel every known novel task through
     * EXACT keys (title+chapter). Scope covers queue + current + live jobs
     * (semaphore waiters included). Used by delete-all purge and the
     * Wi-Fi-loss transition.
     */
    fun cancelAllDownloads() {
        purgeScope.launch {
            mutex.withLock {
                val queued = NovelServiceDataSingleton.downloadQueue.toList()
                val live = NovelServiceDataSingleton.currentTasks.toList()
                val ids = (queued.map { NovelTaskId(it.title, it.chapter) } +
                    live.map { NovelTaskId(it.title, it.chapter) })
                val scopeKeys = novelAllCancelKeys(ids).toSet()
                val match: (NovelDownloaderService.DownloadTask) -> Boolean =
                    { novelTaskKey(it.title, it.chapter) in scopeKeys }
                (queued + live).filter(match).forEach { task ->
                    broadcastDownloadFailed(task.originalLink)
                }
                NovelServiceDataSingleton.downloadQueue.removeAll(match)
                NovelServiceDataSingleton.currentTasks.removeAll(match)
                NovelServiceDataSingleton.progress.keys.removeAll(scopeKeys)
                // Cancel EVERY live job, including waiters absent from
                // queue/current. Records retire in each job's finally.
                downloadJobs.values.forEach { it.cancel() }
            }
            updateNotification()
            // Correction-2 Blocker 2: never steal the stop from a live
            // purge transaction (it owns the final stop decision).
            if (isPurgeSettled() && !purgeActive) {
                withContext(Dispatchers.Main) { stopSelfResult(lastStartId) }
            }
        }
    }

    /**
     * Exact-key cancellation: only queue entries, active tasks, job
     * records and progress rows matching this exact task are touched.
     * A started live Job's record retires in its own completion finally —
     * never erased here — so a later purge can still join the unwinding
     * writer. (Correction-4 Blocker 1.)
     */
    fun cancelDownloadByKey(taskKey: String) {
        val match: (NovelDownloaderService.DownloadTask) -> Boolean =
            { novelTaskKey(it.title, it.chapter) == taskKey }
        // Queued tasks never launched: drop directly + broadcast.
        NovelServiceDataSingleton.downloadQueue.filter(match).forEach { task ->
            broadcastDownloadFailed(task.originalLink)
        }
        NovelServiceDataSingleton.downloadQueue.removeAll(match)
        NovelServiceDataSingleton.progress.remove(taskKey)
        CoroutineScope(Dispatchers.Default).launch {
            val victim = mutex.withLock {
                downloadJobs[taskKey]?.also { it.cancel() }
            }
            // Await actual termination (bounded; the record stays
            // discoverable until the job's own finally retires it).
            withTimeoutOrNull(30_000) { victim?.join() }
            updateNotification()
        }
    }

    @Deprecated("Chapter-only identity is ambiguous; send EXTRA_TASK_KEY instead.")
    fun cancelDownload(chapter: String) {
        val tasks = NovelServiceDataSingleton.downloadQueue.filter { it.chapter == chapter }
        tasks.forEach { task ->
            broadcastDownloadFailed(task.originalLink)
        }
        NovelServiceDataSingleton.downloadQueue.removeAll { it.chapter == chapter }
        CoroutineScope(Dispatchers.Default).launch {
            val victim = mutex.withLock {
                downloadJobs[chapter]?.also { it.cancel() }
            }
            withTimeoutOrNull(30_000) { victim?.join() }
            updateNotification()
        }
    }

    private fun updateNotification() {
        val text = if (NovelServiceDataSingleton.downloadQueue.size > 0)
            "Pending downloads: ${NovelServiceDataSingleton.downloadQueue.size}"
        else "All downloads completed"
        builder.setContentText(text)
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) return
        notificationManager.notify(NOTIFICATION_ID, builder.build())
    }

    private suspend fun isEpubFile(urlString: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder().url(urlString).head().build()
                networkHelper.client.newCall(request).execute().use { response ->
                    val contentType = response.header("Content-Type")
                    val contentDisposition = response.header("Content-Disposition")
                    contentType?.contains("application/epub+zip", ignoreCase = true) == true ||
                            contentDisposition?.contains(".epub") == true
                }
            } catch (e: Exception) {
                Logger.log("Error checking file type: ${e.message}")
                false
            }
        }
    }

    private fun isAlreadyDownloaded(urlString: String): Boolean =
        urlString.contains("file://")

    suspend fun download(task: DownloadTask) {
        // MAX MERGE M3 (upstream 3dbce385): fail-closed Wi-Fi gate.
        if (PrefManager.getVal<Boolean>(PrefName.DownloadWifiOnly) && !ani.dantotsu.isWifiConnected(this@NovelDownloaderService)) {
            broadcastDownloadFailed(task.originalLink)
            withContext(Dispatchers.Main) {
                snackString(getString(R.string.download_wifi_only_warning))
            }
            return
        }
        if (task.lnReaderParser != null) {
            downloadHtmlChapter(task)
        } else {
            downloadEpub(task)
        }
    }

    private suspend fun downloadHtmlChapter(task: DownloadTask) {
        val parser = task.lnReaderParser ?: return
        try {
            withContext(Dispatchers.Main) {
                val notifi = hasNotificationPermission()
                broadcastDownloadStarted(task.originalLink)
                if (notifi) {
                    builder.setContentText("Downloading ${task.title} - ${task.chapter}")
                    notificationManager.notify(NOTIFICATION_ID, builder.build())
                }
                
                val html = withContext(Dispatchers.IO) {
                    parser.loadChapterHtml(task.downloadLink)
                }
                
                val directory = getSubDirectory(
                    this@NovelDownloaderService,
                    MediaType.NOVEL, false,
                    task.title, task.chapter
                ) ?: throw Exception("Directory not found")

                directory.findFile("0.epub")?.forceDelete(this@NovelDownloaderService)
                val file = directory.createFile("application/epub+zip", "0.epub")
                    ?: throw Exception("Could not create 0.epub")

                withContext(Dispatchers.IO) {
                    this@NovelDownloaderService.contentResolver.openOutputStream(file.uri)?.use { os ->
                        val epubBytes = HtmlToEpubUtils.createEpub(task.chapter, html)
                        os.write(epubBytes)
                    } ?: throw Exception("Could not open OutputStream")
                }

                task.coverUrl?.let {
                    getSubDirectory(this@NovelDownloaderService, MediaType.NOVEL, false, task.title)
                        ?.let { dir -> downloadImage(it, dir, "cover.jpg") }
                }

                val baseDirectory = getSubDirectory(
                    this@NovelDownloaderService, MediaType.NOVEL, false, task.title
                ) ?: throw Exception("Directory not found")
                saveMediaInfo(task, baseDirectory)

                downloadsManager.addDownload(DownloadedType(task.title, task.chapter, MediaType.NOVEL))

                builder.setContentText("${task.title} - ${task.chapter} Download complete")
                    .setProgress(0, 0, false)
                if (notifi) notificationManager.notify(NOTIFICATION_ID, builder.build())

                broadcastDownloadFinished(task.originalLink)
                snackString("${task.title} - ${task.chapter} Download finished")
            }
        } catch (e: Exception) {
            Logger.log("HTML chapter download failed: ${e.message}")
            snackString("Download failed: ${e.message}")
            Injekt.get<CrashlyticsInterface>().logException(e)
            broadcastDownloadFailed(task.originalLink)
        }
    }

    private suspend fun downloadEpub(task: DownloadTask) {
        try {
            withContext(Dispatchers.Main) {
                val notifi = hasNotificationPermission()
                broadcastDownloadStarted(task.originalLink)
                if (notifi) {
                    builder.setContentText("Downloading ${task.title} - ${task.chapter}")
                    notificationManager.notify(NOTIFICATION_ID, builder.build())
                }

                if (!isEpubFile(task.downloadLink)) {
                    if (isAlreadyDownloaded(task.originalLink)) {
                        Logger.log("Already downloaded")
                        broadcastDownloadFinished(task.originalLink)
                        snackString("Already downloaded")
                        return@withContext
                    }
                    Logger.log("Download link is not an .epub file")
                    broadcastDownloadFailed(task.originalLink)
                    snackString("Download link is not an .epub file")
                    return@withContext
                }

                val baseDirectory = getSubDirectory(
                    this@NovelDownloaderService, MediaType.NOVEL, false, task.title
                ) ?: throw Exception("Directory not found")

                withContext(Dispatchers.IO) {
                    try {
                        val request = Request.Builder().url(task.downloadLink).build()
                        networkHelper.downloadClient.newCall(request).execute().use { response ->
                            if (!response.isSuccessful)
                                throw IOException("Failed to download file: ${response.message}")

                            val directory = getSubDirectory(
                                this@NovelDownloaderService, MediaType.NOVEL, false,
                                task.title, task.chapter
                            ) ?: throw Exception("Directory not found")

                            directory.findFile("0.epub")?.forceDelete(this@NovelDownloaderService)
                            val file = directory.createFile("application/epub+zip", "0.epub")
                                ?: throw Exception("File not created")

                            task.coverUrl?.let {
                                file.parentFile?.let { dir -> downloadImage(it, dir, "cover.jpg") }
                            }

                            val outputStream = this@NovelDownloaderService.contentResolver
                                .openOutputStream(file.uri)
                                ?: throw Exception("Could not open OutputStream")

                            val sink = outputStream.sink().buffer()
                            val body  = response.body
                            val total = body.contentLength()
                            var downloaded = 0L
                            var lastNotif = 0L; var lastBcast = 0L

                            body.source().use { source ->
                                while (true) {
                                    val read = source.read(sink.buffer, 8192)
                                    if (read == -1L) break
                                    downloaded += read
                                    sink.emit()

                                    if (downloaded - lastNotif >= 1024 * 1024) {
                                        val progressPercent = (downloaded * 100 / total).toInt()
                                        NovelServiceDataSingleton.progress["${task.title}_${task.chapter}"] = progressPercent
                                        withContext(Dispatchers.Main) {
                                            builder.setProgress(100, progressPercent, false)
                                            if (notifi) notificationManager.notify(NOTIFICATION_ID, builder.build())
                                        }
                                        lastNotif = downloaded
                                    }
                                    if (downloaded - lastBcast >= 1024 * 256) {
                                        val progressPercent = (downloaded * 100 / total).toInt()
                                        NovelServiceDataSingleton.progress["${task.title}_${task.chapter}"] = progressPercent
                                        withContext(Dispatchers.Main) {
                                            broadcastDownloadProgress(task.originalLink, progressPercent)
                                        }
                                        lastBcast = downloaded
                                    }
                                }
                            }
                            sink.close()
                            if (file.length() < total * 0.95)
                                throw IOException("Failed to download file: ${response.message}")
                        }
                    } catch (e: Exception) {
                        Logger.log("Exception downloading epub: ${e.message}")
                        throw e
                    }
                }

                builder.setContentText("${task.title} - ${task.chapter} Download complete")
                    .setProgress(0, 0, false)
                if (notifi) notificationManager.notify(NOTIFICATION_ID, builder.build())

                saveMediaInfo(task, baseDirectory)
                downloadsManager.addDownload(DownloadedType(task.title, task.chapter, MediaType.NOVEL))
                broadcastDownloadFinished(task.originalLink)
                snackString("${task.title} - ${task.chapter} Download finished")
            }
        } catch (e: Exception) {
            Logger.log("Exception while downloading .epub: ${e.message}")
            snackString("Exception while downloading .epub: ${e.message}")
            Injekt.get<CrashlyticsInterface>().logException(e)
            broadcastDownloadFailed(task.originalLink)
        }
    }

    private fun hasNotificationPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED
        else true

    @OptIn(DelicateCoroutinesApi::class)
    private fun saveMediaInfo(task: DownloadTask, directory: DocumentFile) {
        launchIO {
            directory.findFile("media.json")?.forceDelete(this@NovelDownloaderService)
            val file = directory.createFile("application/json", "media.json")
                ?: throw Exception("File not created")
            val gson = GsonBuilder()
                .registerTypeAdapter(SChapter::class.java, InstanceCreator<SChapter> { SChapterImpl() })
                .create()
            val mediaJson = gson.toJson(task.sourceMedia)
            val media = gson.fromJson(mediaJson, Media::class.java)
            if (media != null) {
                media.cover  = media.cover?.let  { downloadImage(it, directory, "cover.jpg") }
                media.banner = media.banner?.let { downloadImage(it, directory, "banner.jpg") }
                val jsonString = gson.toJson(media)
                withContext(Dispatchers.Main) {
                    try {
                        file.openOutputStream(this@NovelDownloaderService, false).use { output ->
                            if (output == null) throw Exception("Output stream is null")
                            output.write(jsonString.toByteArray())
                        }
                    } catch (e: android.system.ErrnoException) {
                        e.printStackTrace()
                        Toast.makeText(this@NovelDownloaderService, "Error while saving: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private suspend fun downloadImage(url: String, directory: DocumentFile, name: String): String? =
        withContext(Dispatchers.IO) {
            var connection: HttpURLConnection? = null
            try {
                connection = URL(url).openConnection() as HttpURLConnection
                connection.connect()
                if (connection.responseCode != HttpURLConnection.HTTP_OK)
                    throw Exception("Server returned HTTP ${connection.responseCode}")
                directory.findFile(name)?.forceDelete(this@NovelDownloaderService)
                val file = directory.createFile("image/jpeg", name) ?: throw Exception("File not created")
                file.openOutputStream(this@NovelDownloaderService, false).use { output ->
                    if (output == null) throw Exception("Output stream is null")
                    connection.inputStream.use { it.copyTo(output) }
                }
                file.uri.toString()
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@NovelDownloaderService, "Exception saving $name: ${e.message}", Toast.LENGTH_LONG).show()
                }
                null
            } finally {
                connection?.disconnect()
            }
        }

    private fun broadcastDownloadStarted(link: String) =
        sendBroadcast(Intent(NovelReadFragment.ACTION_DOWNLOAD_STARTED).apply { putExtra(NovelReadFragment.EXTRA_NOVEL_LINK, link) })

    private fun broadcastDownloadFinished(link: String) =
        sendBroadcast(Intent(NovelReadFragment.ACTION_DOWNLOAD_FINISHED).apply { putExtra(NovelReadFragment.EXTRA_NOVEL_LINK, link) })

    private fun broadcastDownloadFailed(link: String) =
        sendBroadcast(Intent(NovelReadFragment.ACTION_DOWNLOAD_FAILED).apply { putExtra(NovelReadFragment.EXTRA_NOVEL_LINK, link) })

    private fun broadcastDownloadProgress(link: String, progress: Int) =
        sendBroadcast(Intent(NovelReadFragment.ACTION_DOWNLOAD_PROGRESS).apply {
            putExtra(NovelReadFragment.EXTRA_NOVEL_LINK, link)
            putExtra("progress", progress)
        })

    private val cancelReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ACTION_CANCEL_DOWNLOAD) {
                // Exact key first; legacy chapter-only for old senders.
                intent.getStringExtra(EXTRA_TASK_KEY)?.let { cancelDownloadByKey(it); return }
                intent.getStringExtra(EXTRA_CHAPTER)?.let { cancelDownload(it) }
            }
        }
    }

    data class DownloadTask(
        val title: String,
        val chapter: String,
        val downloadLink: String,
        val originalLink: String,
        val sourceMedia: Media? = null,
        val coverUrl: String? = null,
        val retries: Int = 2,
        val lnReaderParser: ani.dantotsu.parsers.novel.LnReaderNovelParser? = null,
    )

    companion object {
        private const val NOTIFICATION_ID = 1103
        const val ACTION_CANCEL_DOWNLOAD = "action_cancel_download"
        const val EXTRA_CHAPTER = "extra_chapter"
        /** MAX MERGE M3 (Blocker 3): exact `"${title}_${chapter}"` key. */
        const val EXTRA_TASK_KEY = "extra_task_key"
        /** MAX MERGE M3 (Blocker 5): delete-all / Wi-Fi-loss entry point. */
        const val ACTION_CANCEL_ALL_DOWNLOADS = "action_cancel_all_downloads"
        /**
         * MAX MERGE M3 HOLD (Blocker 2): ownership-safe purge entry +
         * completion. Extra [EXTRA_PURGE_SUCCESS] reports DONE vs timeout.
         */
        const val ACTION_PURGE_ALL_NOVEL = "action_purge_all_novel"
        const val ACTION_PURGE_ALL_NOVEL_DONE = "action_purge_all_novel_done"
        const val EXTRA_PURGE_SUCCESS = "extra_purge_success"
    }
}

object NovelServiceDataSingleton {
    var downloadQueue: Queue<NovelDownloaderService.DownloadTask> = ConcurrentLinkedQueue()
    val currentTasks = java.util.Collections.synchronizedList(mutableListOf<NovelDownloaderService.DownloadTask>())
    val progress = java.util.concurrent.ConcurrentHashMap<String, Int>()
    @Volatile var isServiceRunning: Boolean = false
}
