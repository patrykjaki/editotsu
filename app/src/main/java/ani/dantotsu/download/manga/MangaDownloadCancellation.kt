package ani.dantotsu.download.manga

import ani.dantotsu.download.MangaChapterDeleteResult

/**
 * Pure cancellation selection + orchestration used by [MangaDownloaderService.cancelDownload].
 *
 * Given a global download queue, [selectTasksToCancel] selects exactly the tasks whose
 * physical directory identity (sanitized title + chapter) matches the intended cancel
 * target. This is the exact-physical-key counterpart to the raw `it.chapter == chapter`
 * predicate that previously removed unrelated titles sharing a chapter label.
 */
object MangaDownloadCancellation {

    fun selectTasksToCancel(
        queue: Collection<MangaDownloaderService.DownloadTask>,
        title: String,
        chapter: String,
    ): List<MangaDownloaderService.DownloadTask> {
        val targetKey = MangaDownloadKey.fromTask(title, chapter)
        return queue.filter { MangaDownloadKey.fromTask(it.title, it.chapter) == targetKey }
    }

    /**
     * The atomic attempt-classification predicate for queue cleanup.
     *
     * An attempt is "old" (to be cancelled) iff it targets the same physical directory
     * [targetKey] AND its monotonic [MangaDownloaderService.DownloadTask.attemptId] is at
     * or below [cutoff]. The cutoff is captured by [cancelChapterTransaction] at the
     * moment cancellation begins (see [MangaDownloadOwnership.currentCutoff]). Because the
     * decision depends only on the attempt id relative to a single captured cutoff — not
     * on `ConcurrentLinkedQueue` iterator visibility — it is a real linearization point
     * even when an enqueue overlaps the cancellation snapshot. A fresh retry issued after
     * cancellation began has a strictly larger attempt id and is preserved.
     */
    fun isCancelledQueuedAttempt(
        entry: MangaDownloaderService.DownloadTask,
        targetKey: MangaDownloadKey,
        cutoff: Long,
    ): Boolean =
        MangaDownloadKey.fromTask(entry.title, entry.chapter) == targetKey &&
            entry.attemptId <= cutoff

    /**
     * Performs the full, linearizable cancellation transaction for one physical chapter
     * key (Policy A):
     *   1. [MangaDownloadOwnership.cancelAndJoin] marks the current owner cancelled,
     *      cancels AND joins the exact stored owner job (termination barrier), and records
     *      this key's `lastCancelledCutoff` so any later `begin()` carrying an attempt id
     *      at or below it is failed-closed (covers an attempt whose id was issued before
     *      cancellation but whose queue publication arrived after the cleanup sweep);
     *   2. queued attempts are removed by attempt-id cutoff via [isCancelledQueuedAttempt]
     *      (id <= captured cutoff => old => removed; id > cutoff => fresh => survives),
     *      a real linearization point independent of `ConcurrentLinkedQueue` iteration;
     *   3. [removeJobRecord] is invoked with the key, the cancelled attempt id, and the
     *      cutoff so the service CAN cancel AND remove every same-key job-bookkeeping
     *      record whose attempt id is at or below the cutoff — including an old
     *      polled-but-not-yet-owner job;
     *   all executed *inside* the ownership coordinator's per-key takeover lock, so a
     *   retry `begin()` cannot publish a new owner until this entire transaction is
     *   finished.
     *
     * This makes the linearization boundary cover enqueue + queue removal + owner
     * publication + job bookkeeping: a stale attempt (id issued at/before the cancellation
     * cutoff) can never publish or commit, whether it is still queued, already polled, or
     * merely delayed in its queue publication; and a fresh retry (id > cutoff) survives,
     * and cannot publish until the transaction completes.
     *
     * [removeJobRecord] is invoked with the [MangaDownloadKey], the cancelled attempt id,
     * and the captured cutoff, so the caller can cancel AND retire every same-key
     * job-bookkeeping record whose attempt id is at or below the cutoff.
     */
    suspend fun cancelChapterTransaction(
        ownership: MangaDownloadOwnership,
        queue: MutableCollection<MangaDownloaderService.DownloadTask>,
        title: String,
        chapter: String,
        removeJobRecord: suspend (MangaDownloadKey, Long, Long) -> Unit,
        cutoff: Long,
    ) {
        val targetKey = MangaDownloadKey.fromTask(title, chapter)
        // Serialize a plain cancel behind any pending destructive delete for the same
        // key: otherwise this cancel's own cutoff could retire a fresh retry that the
        // delete reservation is protecting. Holds no lock while waiting.
        ownership.awaitDeleteReservation(targetKey)
        // The cutoff is captured at CANCELLATION INVOCATION (the caller passes
        // ownership.currentCutoff() taken synchronously when cancelDownload() is called),
        // not when this coroutine later executes — so an attempt id issued after the user
        // requested cancellation is strictly greater than `cutoff` and survives, while an
        // id issued before is old and is removed. The decision depends only on the monotonic
        // attempt id vs the captured cutoff, never on ConcurrentLinkedQueue iteration order.
        ownership.cancelAndJoin(targetKey, cutoff) { cancelledAttemptId, cutoff ->
            queue.removeAll { isCancelledQueuedAttempt(it, targetKey, cutoff) }
            // Cancel AND remove every same-key job-bookkeeping record whose attempt id is at
            // or below the cutoff — including an old polled-but-not-yet-owner job — so a
            // stale attempt is terminated in every lifecycle state it may occupy.
            removeJobRecord(targetKey, cancelledAttemptId, cutoff)
        }
    }

    /**
     * The atomic attempt-classification predicate for scope-wide (title / all-manga)
     * queue cleanup. [titlePath] null means whole-type scope. Same cutoff rule as
     * [isCancelledQueuedAttempt]: id <= cutoff is old, id > cutoff survives.
     */
    fun isCancelledScopedAttempt(
        entry: MangaDownloaderService.DownloadTask,
        titlePath: String?,
        cutoff: Long,
    ): Boolean =
        (titlePath == null || MangaDownloadKey.fromTask(entry.title, entry.chapter).titlePath == titlePath) &&
            entry.attemptId <= cutoff

    /**
     * Authoritative scope-wide destructive transaction for one manga title
     * ([titlePath]) or the whole manga type ([titlePath] null: purge-all).
     *
     * Ordering (the caller announced the scope synchronously via
     * [MangaDownloadOwnership.reserveMangaScope] beforehand and releases it in a
     * `finally` afterwards):
     *   1. under the scope admission lock ([MangaDownloadOwnership.withScopeAdmission]):
     *      snapshot every live owner in scope AND record scope destruction
     *      ([MangaDownloadOwnership.noteMangaScopeDestroyed]) atomically — an old
     *      attempt either publishes before this point (then it is observed and
     *      cancel+joined below) or checks after it (then `begin()` rejects it
     *      before publication); there is no snapshot->note window in which it
     *      could become an unobserved writer during [deleteScope];
     *   2. cancel AND join every snapshotted live owner, each through its own
     *      per-key takeover barrier (publishes per-key cutoffs, so delayed stale
     *      begins fail closed). [deleteScope] runs only after every old owner in
     *      scope has terminated — no stale writer can be active while the
     *      physical deletion runs. New same-scope begins cannot publish meanwhile:
     *      they wait on the scope gate installed at invocation;
     *   3. sweep scoped queued attempts (id <= cutoff) + scoped job bookkeeping;
     *   4. run [deleteScope] — the synchronous title/type metadata + physical
     *      deletion, completed before return. The scope gate (not per-key takeover)
     *      is what holds fresh attempts back across this step; it stays installed
     *      until the runner's `finally`.
     *
     * @return the destructive [deleteScope] outcome for honest success/failure reporting.
     */
    suspend fun cancelDeleteScopeTransaction(
        ownership: MangaDownloadOwnership,
        queue: MutableCollection<MangaDownloaderService.DownloadTask>,
        titlePath: String?,
        removeJobRecords: suspend (String?, Long) -> Unit,
        cutoff: Long,
        deleteScope: suspend () -> MangaChapterDeleteResult,
    ): MangaChapterDeleteResult {
        // Never await our OWN scope gate here (it completes when our runner finishes).
        // Snapshot + destruction-note are atomic under admission: no old attempt can
        // publish between them unseen. The snapshot covers every UNFINISHED owner
        // (including cancelling-but-incomplete jobs), so deleteScope cannot start
        // while an old writer is still executing.
        val liveKeys = ownership.withScopeAdmission {
            val keys = ownership.unfinishedMangaKeys(titlePath)
            ownership.noteMangaScopeDestroyed(titlePath, cutoff)
            keys
        }
        for (key in liveKeys) {
            ownership.cancelAndJoin(key, cutoff) { _, _ -> }
        }
        queue.removeAll { isCancelledScopedAttempt(it, titlePath, cutoff) }
        removeJobRecords(titlePath, cutoff)
        return deleteScope()
    }

    /**
     * The atomic attempt-classification predicate for all-manga Clear All queue
     * cleanup. Any attempt at/below [cutoff] is old; anything above survives. The
     * decision depends only on the monotonic attempt id vs the single
     * invocation-time cutoff — never on queue iteration — so a fresh offer racing
     * the sweep is never removed, whenever it lands.
     */
    fun isClearAllCancelledAttempt(
        entry: MangaDownloaderService.DownloadTask,
        cutoff: Long,
    ): Boolean = entry.attemptId <= cutoff

    /**
     * Authoritative all-manga Clear All CANCELLATION transaction (cancellation only:
     * no files, no metadata are touched here).
     *
     * One invocation-time [cutoff] drives every state with the SAME linearization
     * point (the caller captures `currentCutoff()` synchronously at invocation and
     * installs the all-manga scope gate first, via
     * [MangaDownloadOwnership.reserveMangaScope], releasing it in a `finally`
     * afterwards — so fresh attempts may wait, then process normally):
     *   1. under the scope admission lock ([MangaDownloadOwnership.withScopeAdmission]):
     *      snapshot every live owner AND record the cancel-all cutoff
     *      ([MangaDownloadOwnership.noteMangaCancelAll]) atomically — an old
     *      attempt either publishes before this point (then it is observed and
     *      cancel+joined below) or checks after it (then `begin()` rejects it
     *      before publication); no unobserved owner can survive this point;
     *   2. cancel AND join every snapshotted live owner, each through its own
     *      per-key takeover barrier (publishes per-key cutoffs, so delayed stale
     *      begins fail closed). When this transaction returns, no attempt at/below
     *      [cutoff] can be a live owner or commit later;
     *   3. sweep queued attempts in place (`removeAll` by cutoff — never
     *      snapshot/clear/re-add, so a fresh concurrent offer is never lost);
     *   4. [removeJobRecords] cancels+retires job bookkeeping at/below [cutoff].
     *
     * Never awaits its OWN scope gate here (it completes when the runner finishes).
     */
    suspend fun cancelAllMangaTransaction(
        ownership: MangaDownloadOwnership,
        queue: MutableCollection<MangaDownloaderService.DownloadTask>,
        cutoff: Long,
        removeJobRecords: suspend (Long) -> Unit,
    ) {
        // Never await our OWN scope gate here (it completes when our runner finishes).
        // Snapshot + cutoff-note are atomic under admission (see above). The
        // snapshot covers every UNFINISHED owner (including
        // cancelling-but-incomplete jobs), so this transaction cannot return
        // while an old owner is still executing.
        val liveKeys = ownership.withScopeAdmission {
            val keys = ownership.unfinishedMangaKeys(null)
            ownership.noteMangaCancelAll(cutoff)
            keys
        }
        for (key in liveKeys) {
            ownership.cancelAndJoin(key, cutoff) { _, _ -> }
        }
        queue.removeAll { isClearAllCancelledAttempt(it, cutoff) }
        removeJobRecords(cutoff)
    }

    /**
     * Authoritative Stop/Delete transaction for one physical chapter key.
     *
     * This is the ONLY path that may destructively delete a manga chapter that could
     * have a live downloader. It reuses the exact same per-key `takeover` barrier as
     * [cancelChapterTransaction] ([MangaDownloadOwnership.cancelAndJoin]), extended with
     * the destructive step:
     *   0. BEFORE this coroutine runs, the caller announced the delete synchronously
     *      at invocation ([MangaDownloadOwnership.reserveDelete]), which both captures
     *      the attempt-id cutoff and installs a reservation gate. Any fresh attempt
     *      (id > cutoff) issued afterwards suspends in `begin()` until the gate
     *      completes, so it can neither publish in the invocation→takeover gap nor be
     *      deleted underneath. Attempts that slipped past before the gate became
     *      visible carry ids at/below the cutoff and are handled as old.
     *   1. inside the takeover lock: mark the current old owner cancelled (iff its
     *      attempt id is at/below the cutoff), cancel AND join it (termination barrier),
     *      publish the cutoff so delayed stale attempts fail closed at `begin()`;
     *   2. still inside the same lock: remove old queued attempts (id <= cutoff),
     *      cancel+retire old job-bookkeeping records (id <= cutoff);
     *   3. still inside the same lock: run [deleteChapter] — the synchronous metadata +
     *      physical chapter-directory deletion, which MUST have fully completed when it
     *      returns (no fire-and-forget IO);
     *   4. the caller completes the reservation ([MangaDownloadOwnership
     *      .finishDeleteReservation]) in a `finally`, releasing waiting fresh attempts;
     *      only then may a fresh attempt (id > cutoff) publish via `begin()` and write
     *      new files.
     *
     * Why this closes both races:
     *  - Stale A cannot commit after delete: A is cancelled+joined before deletion, the
     *    cutoff is published, and `commitIfOwner` fails for a cancelled owner — so a
     *    cached valid page snapshot can never become COMPLETE metadata afterwards.
     *  - Old delete cannot erase fresh B: B's `begin()` blocks on the reservation gate
     *    (installed synchronously at invocation, before any async lock acquisition)
     *    until the whole transaction (including the completed physical deletion) is
     *    done, so B writes its files strictly after the old directory is gone, and no
     *    late cleanup runs against B afterwards.
     *
     * @return the destructive [deleteChapter] outcome, so the caller can report success
     *   vs failure honestly instead of purging UI on a failed physical delete.
     */
    suspend fun cancelDeleteChapterTransaction(
        ownership: MangaDownloadOwnership,
        queue: MutableCollection<MangaDownloaderService.DownloadTask>,
        title: String,
        chapter: String,
        removeJobRecord: suspend (MangaDownloadKey, Long, Long) -> Unit,
        cutoff: Long,
        deleteChapter: suspend () -> MangaChapterDeleteResult,
    ): MangaChapterDeleteResult {
        val targetKey = MangaDownloadKey.fromTask(title, chapter)
        // This transaction never awaits its OWN reservation gate here (it would
        // deadlock: the gate completes only when this transaction's runner finishes).
        // Fresh attempts wait on the gate inside begin(); old attempts are handled below.
        var outcome: MangaChapterDeleteResult = MangaChapterDeleteResult.Failed(
            "destructive step did not run"
        )
        ownership.cancelAndJoin(targetKey, cutoff) { cancelledAttemptId, c ->
            queue.removeAll { isCancelledQueuedAttempt(it, targetKey, c) }
            removeJobRecord(targetKey, cancelledAttemptId, c)
            // Destructive phase, still under the same takeover lock: metadata +
            // physical deletion complete before any fresh same-key begin() publishes.
            outcome = deleteChapter()
        }
        return outcome
    }
}
