package ani.dantotsu.download.manga

import ani.dantotsu.download.findValidName
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Physical identity of a manga chapter's page directory on disk.
 *
 * The on-disk layout (see [ani.dantotsu.download.DownloadsManager.getSubDirectory] /
 * [ani.dantotsu.download.DownloadsManager.findExistingDirectory]) is:
 *   .../MANGA/<title.findValidName()>/<chapter.findValidName()>/
 * There is NO scanlator component in the path, so different scanlators of the same
 * title+chapter mutate the SAME directory and MUST serialize through the same key.
 *
 * Ownership is therefore keyed by the sanitized title+chapter, exactly matching the
 * mutable filesystem resource — not by metadata identity such as scanlator.
 */
data class MangaDownloadKey(
    val titlePath: String,
    val chapterPath: String,
) {
    companion object {
        fun fromTask(title: String, chapter: String): MangaDownloadKey =
            MangaDownloadKey(title.findValidName(), chapter.findValidName())
    }
}

/**
 * Exclusive-ownership coordinator for the shared manga chapter directory.
 *
 * Guarantees (linearizable takeover):
 *  - At most one owner per [MangaDownloadKey] may exist.
 *  - A new owner is published only AFTER the previous owner's job has been cancelled
 *    and fully joined (termination barrier), so a stale attempt can never write/delete
 *    the directory once a newer attempt owns it.
 *  - The generation returned by [begin] is the one assigned to the calling job, and
 *    ownership checks bind BOTH the generation and the job identity.
 *  - Cancellation targets the exact key only (never a global chapter-label scan).
 *
 * Attempt identity: every enqueued download attempt is assigned a monotonic attempt id
 * by [issueAttemptId] at enqueue time. This gives the cancellation transaction a real,
 * atomic linearization point (see [currentCutoff]): an attempt whose id was issued at or
 * before cancellation-start is "old" and is removed; an attempt issued afterwards is
 * "fresh" and survives — independent of `ConcurrentLinkedQueue` iterator timing.
 */
class MangaDownloadOwnership {
    /**
     * Immutable owner record. All mutations replace the whole object in [state]
     * (copy-on-write) instead of mutating fields, so a snapshot that reads
     * `state[key]` once observes a coherent view that no concurrent mutation can
     * tear — this is what makes [unfinishedMangaKeys] a safe lock-free snapshot
     * for termination decisions.
     */
    private data class KeyState(
        val generation: Long,
        val currentJob: Job?,
        val cancelled: Boolean,
        val attemptId: Long,
    )

    // Monotonic attempt-id source. Incrementing it is the single linearization point
    // that decides whether a given enqueue is "old" (<= cutoff) or "fresh" (> cutoff)
    // relative to a cancellation transaction.
    private val attemptCounter = AtomicLong(0)

    // Per physical-directory coordination. `takeover` serializes takeover attempts so
    // only one is in flight; `state` guards the owner records ([KeyState] itself is
    // immutable and replaced whole on mutation). The two are never
    // held simultaneously across a `join()` (see begin), so a previous owner's
    // finally/cleanup cannot deadlock against a new takeover.
    private val takeoverLocks = ConcurrentHashMap<MangaDownloadKey, Mutex>()
    private val stateLocks = ConcurrentHashMap<MangaDownloadKey, Mutex>()
    private val state = ConcurrentHashMap<MangaDownloadKey, KeyState>()

    // Per physical-directory "last cancelled cutoff". When a cancellation transaction
    // completes for [key], it records the highest attempt id that was issued at that
    // point. Any later `begin()` carrying an attempt id at or below this cutoff is a
    // STALE attempt — its id was issued before (or during) an already-completed
    // cancellation — and is rejected/failed-closed, even if its queue publication was
    // delayed and arrived after the cancellation's one-time cleanup sweep. This is the
    // final barrier that closes the split between attempt-id issuance and queue offer.
    private val lastCancelledCutoff = ConcurrentHashMap<MangaDownloadKey, Long>()

    /**
     * Invocation-time destructive-delete reservation for [MangaDownloadKey].
     *
     * A destructive delete is announced synchronously at invocation (UI tap /
     * `onStartCommand`, both non-suspending) via [reserveDelete], potentially long
     * before the asynchronous delete coroutine acquires the per-key `takeover` lock.
     * Without this, a fresh retry B issued after the captured cutoff could publish via
     * [begin] in that gap; the later delete transaction would then correctly spare B
     * (it is fresh) yet still run its physical deletion underneath the live B.
     *
     * The reservation closes the gap: [begin] suspends on the reservation gate before
     * touching `takeover`, so no fresh attempt can publish between invocation and the
     * completed destructive cleanup. Attempts that slipped past the gate check before
     * the reservation became visible provably carry an id at or below the recorded
     * cutoff (their id was issued before [reserveDelete]'s counter read, because the
     * gate install strictly precedes that read) and are therefore handled as old by
     * the transaction itself (cancelled if owning, removed if queued, fail-closed if
     * delayed) — never as fresh owners under a deletion.
     *
     * `holders` reference-counts concurrent reservations of the same key (e.g. a
     * double-tapped Delete): the gate completes only when the last destructive
     * transaction finishes, so a fresh retry can never slip between two back-to-back
     * deletes either. `cutoff` only ever widens (max) while a gate is shared.
     */
    private data class DeleteGuard(
        val cutoff: Long,
        val gate: CompletableDeferred<Unit>,
        val holders: Int,
    )

    private val deleteGuards = ConcurrentHashMap<MangaDownloadKey, DeleteGuard>()

    /**
     * Scope-wide (title / all-manga) destructive reservation, mirroring the per-key
     * delete gate for the title-wide and purge-all surfaces (`removeMedia`,
     * `purgeDownloads`). A whole-title or whole-type delete cannot serialize on one
     * chapter key, so it announces a scope gate instead: every same-scope [begin]
     * suspends until the scope transaction finishes, while other titles proceed.
     *
     * Map key is the sanitized title path, or [ALL_MANGA_SCOPE] for a whole-type
     * purge. [begin] always waits on the all-manga gate plus its own title gate.
     * Semantics mirror [DeleteGuard]: refcounted shared gates, widen-only cutoff,
     * gate install strictly before the cutoff read (so pre-install slips are old).
     */
    companion object {
        const val ALL_MANGA_SCOPE = "*"
    }

    private data class ScopeGuard(
        val gate: CompletableDeferred<Unit>,
        var holders: Int,
        var cutoff: Long,
    )

    private val scopeGuards = ConcurrentHashMap<String, ScopeGuard>()

    /**
     * Scope admission mutex: the single linearization point between an old
     * attempt's owner publication and a scope transaction's owner snapshot +
     * cutoff publication.
     *
     * [begin] holds it across its cutoff check + owner publication; every scope
     * transaction ([MangaDownloadCancellation.cancelAllMangaTransaction] /
     * `cancelDeleteScopeTransaction`) holds it across its live-owner snapshot +
     * cutoff note. Therefore an old attempt (id <= C) either publishes BEFORE the
     * snapshot (then it is observed and cancel+joined) or checks AFTER the note
     * (then it is rejected before publication) — it can never slip from a
     * pre-reservation gate admission into an unobserved owner. Fresh attempts
     * (id > C) still wait on the scope gate first and are unaffected.
     *
     * Lock order is admission -> takeover -> state everywhere; the admitted
     * section never waits on a scope gate and never outlives a bounded
     * prev-owner join, so no deadlock and no wedging.
     */
    private val scopeAdmission = Mutex()

    /** Run [block] inside the scope admission critical section (see [scopeAdmission]). */
    suspend fun <T> withScopeAdmission(block: suspend () -> T): T =
        scopeAdmission.withLock { block() }

    /**
     * Per-title destruction cutoffs for the commit guard. When a title scope is
     * destroyed, every attempt at/below the recorded cutoff must never commit
     * COMPLETE afterwards — even for keys that had no live owner (hence no per-key
     * cutoff published) when the scope transaction ran.
     */
    private val titleDestroyedCutoffs = ConcurrentHashMap<String, Long>()

    /** Whole-type destruction cutoff; same commit-guard role for purge-all. */
    private val mangaDestroyedCutoff = AtomicLong(0)

    /**
     * Whole-type cancel-all cutoff (cancellation only, never destructive). Recorded by
     * the all-manga Clear All transaction ([noteMangaCancelAll]) with the same
     * invocation-time cutoff that drives its queue sweep and live-owner
     * cancellation. Attempts at/below it are stale everywhere: they are swept from
     * the queue, dropped by the poll filter, their live owners are cancelled+joined,
     * and — as the final barrier — [begin] rejects them even when their queue
     * offer/begin was delayed until after the one-time sweep.
     */
    private val mangaCancelAllCutoff = AtomicLong(0)

    /** Assign a globally-unique, monotonically-increasing attempt id at enqueue time. */
    fun issueAttemptId(): Long = attemptCounter.incrementAndGet()

    /**
     * The highest attempt id issued so far. A cancellation transaction captures this as
     * its cutoff at invocation: any attempt id <= cutoff was enqueued on or before the
     * cancellation began and is eligible for removal; any id > cutoff is a fresh retry
     * and must survive.
     */
    fun currentCutoff(): Long = attemptCounter.get()

    private fun takeover(key: MangaDownloadKey): Mutex =
        takeoverLocks.computeIfAbsent(key) { Mutex() }

    private fun stateLock(key: MangaDownloadKey): Mutex =
        stateLocks.computeIfAbsent(key) { Mutex() }

    /**
     * Synchronously announce a destructive delete for [key] and capture its cutoff.
     *
     * Safe to call from non-suspending invocation paths (UI thread, service
     * `onStartCommand`). The gate install strictly precedes the cutoff read, so any
     * attempt whose [begin] gate check slips past before the install becomes visible
     * was issued before the cutoff and is therefore old — the transaction cancels it
     * rather than deleting underneath it as if it were fresh.
     *
     * @return the captured cutoff; attempt ids at or below it are old for this delete.
     */
    fun reserveDelete(key: MangaDownloadKey): Long {
        // Step 1: install (or join) the gate FIRST, so it is visible to any later begin().
        deleteGuards.compute(key) { _, old ->
            old?.copy(holders = old.holders + 1)
                ?: DeleteGuard(0L, CompletableDeferred(), 1)
        }
        // Step 2: capture the cutoff only after the gate is installed.
        val cutoff = attemptCounter.get()
        // Step 3: record the cutoff (widen-only while the gate is shared).
        deleteGuards.computeIfPresent(key) { _, guard ->
            if (cutoff > guard.cutoff) guard.copy(cutoff = cutoff) else guard
        }
        return cutoff
    }

    /**
     * Suspend until no destructive-delete reservation covers [key]. Holds no lock, so
     * a delete transaction blocked on `takeover` can always complete independently —
     * waiting here can never deadlock the delete it waits for.
     */
    suspend fun awaitDeleteReservation(key: MangaDownloadKey) {
        deleteGuards[key]?.gate?.await()
    }

    /**
     * Release one destructive-delete reservation for [key], completing the shared gate
     * only when the last holder finishes. MUST be called in a `finally` block by the
     * delete runner — a leaked gate would park every future same-key [begin] forever.
     */
    fun finishDeleteReservation(key: MangaDownloadKey) {
        deleteGuards.computeIfPresent(key) { _, guard ->
            val remaining = guard.holders - 1
            if (remaining <= 0) {
                guard.gate.complete(Unit)
                null
            } else {
                guard.copy(holders = remaining)
            }
        }
    }

    /**
     * Synchronously announce a scope-wide destructive delete and capture its cutoff.
     * [titlePath] is a sanitized title, or null for whole-type (all-manga) scope.
     * Same install-before-cutoff protocol as [reserveDelete]; same slip-past proof:
     * attempts past the gate check before install carry ids at/below the cutoff.
     */
    fun reserveMangaScope(titlePath: String?): Long {
        val scopeKey = titlePath ?: ALL_MANGA_SCOPE
        scopeGuards.compute(scopeKey) { _, old ->
            old?.copy(holders = old.holders + 1)
                ?: ScopeGuard(CompletableDeferred(), 1, 0L)
        }
        val cutoff = attemptCounter.get()
        scopeGuards.computeIfPresent(scopeKey) { _, guard ->
            if (cutoff > guard.cutoff) guard.copy(cutoff = cutoff) else guard
        }
        return cutoff
    }

    /**
     * Suspend until no scope-wide destructive reservation covers [titlePath]
     * (its own title gate plus the all-manga gate). Holds no lock.
     */
    suspend fun awaitMangaScope(titlePath: String) {
        scopeGuards[ALL_MANGA_SCOPE]?.gate?.await()
        if (titlePath != ALL_MANGA_SCOPE) {
            scopeGuards[titlePath]?.gate?.await()
        }
    }

    /**
     * Release one scope reservation. Completes the shared gate only for the last
     * holder. MUST run in a `finally` on the scope-delete runner path.
     */
    fun finishMangaScope(titlePath: String?) {
        val scopeKey = titlePath ?: ALL_MANGA_SCOPE
        scopeGuards.computeIfPresent(scopeKey) { _, guard ->
            val remaining = guard.holders - 1
            if (remaining <= 0) {
                guard.gate.complete(Unit)
                null
            } else {
                guard.copy(holders = remaining)
            }
        }
    }

    /**
     * Record scope destruction for the commit guard, synchronously. After this,
     * [commitIfOwner] (and [isOwner]) refuse every owner in scope whose attempt id is
     * at/below [cutoff] — closing stale COMPLETE resurrection for keys that had no
     * live owner (hence no per-key cutoff) during the scope transaction.
     */
    fun noteMangaScopeDestroyed(titlePath: String?, cutoff: Long) {
        if (titlePath == null) {
            mangaDestroyedCutoff.updateAndGet { prev -> if (cutoff > prev) cutoff else prev }
        } else {
            titleDestroyedCutoffs.merge(titlePath, cutoff) { a, b -> if (a > b) a else b }
        }
    }

    /**
     * Coherent synchronous snapshot of UNFINISHED owner keys in scope: every
     * published owner whose job has not completed — active, cancelling
     * (`isActive == false` but still executing synchronous/non-cancellable
     * work), or parked — regardless of the `cancelled` flag or `isActive`.
     * A termination barrier must wait for all of these, not only for
     * still-active non-cancelled owners.
     *
     * Each record is read once from [state]; because [KeyState] is immutable
     * and replaced whole on mutation, the view cannot tear. A job that
     * completes concurrently after being observed is harmless (its join is a
     * no-op); an owner that publishes concurrently after the encompassing
     * snapshot+note section is fresh by admission atomicity (see
     * [withScopeAdmission]).
     */
    fun unfinishedMangaKeys(titlePath: String?): List<MangaDownloadKey> =
        state.entries
            .filter { (key, s) ->
                (titlePath == null || key.titlePath == titlePath) &&
                    s.currentJob?.isCompleted == false
            }
            .map { it.key }

    /** Synchronous check fencing maintenance deletion off titles with unfinished owners. */
    fun hasLiveMangaOwner(titlePath: String): Boolean = unfinishedMangaKeys(titlePath).isNotEmpty()

    /**
     * Record an all-manga Clear All cancellation cutoff, synchronously. After this,
     * [begin] rejects every attempt at/below [cutoff] before publication — the final
     * barrier for an old attempt whose queue offer arrived after the transaction's
     * one-time sweep. Widen-only; fresh attempts (id > cutoff) are unaffected, then
     * and forever (later ids only grow).
     */
    fun noteMangaCancelAll(cutoff: Long) {
        mangaCancelAllCutoff.updateAndGet { prev -> if (cutoff > prev) cutoff else prev }
    }

    /**
     * Non-suspending stale check for the queue poll filter: an attempt at/below the
     * recorded Clear All cutoff can never legitimately publish and is dropped at
     * poll time instead of launching a doomed job.
     */
    fun isClearAllStale(attemptId: Long): Boolean = attemptId <= mangaCancelAllCutoff.get()

    private fun isScopeDestroyed(key: MangaDownloadKey, attemptId: Long): Boolean {
        val titleCutoff = titleDestroyedCutoffs[key.titlePath] ?: 0L
        return attemptId <= titleCutoff || attemptId <= mangaDestroyedCutoff.get()
    }

    /**
     * Become the sole owner of [key]'s directory. Cancels and joins any previous
     * owner for the same key before publishing this attempt, returning the generation
     * assigned to [myJob]. [attemptId] is the id assigned at enqueue time and is bound
     * to the owner record so cancellation can identify exactly which attempt it cancels.
     *
     * Fail-closed against already-cancelled attempts: if [attemptId] is at or below this
     * key's [lastCancelledCutoff], this attempt's id was issued at/before an already
     * completed cancellation and can never legitimately own the directory — even when its
     * queue publication was delayed until after that cancellation's cleanup sweep. In that
     * case the calling [myJob] is cancelled and [begin] throws, so the attempt can neither
     * publish nor commit COMPLETE.
     *
     * The same fail-closed rejection applies before publication to:
     * - the all-manga Clear All cutoff ([mangaCancelAllCutoff]): an old attempt whose
     *   queue offer arrived after the Clear All sweep;
     * - the title / whole-type destruction cutoffs ([titleDestroyedCutoffs] /
     *   [mangaDestroyedCutoff]): a delayed old attempt that slipped the scope-gate
     *   check before the gate was installed. Rejecting here — instead of publishing
     *   and waiting until `isOwner()/commitIfOwner()` — prevents it from recreating
     *   directories after a title/purge delete.
     *
     * Fresh attempts (id above every recorded cutoff) are unaffected.
     */
    suspend fun begin(key: MangaDownloadKey, myJob: Job, attemptId: Long): Long {
        // Destructive-delete reservation barrier (invocation-time). A delete announced
        // via reserveDelete() installs its gate synchronously at invocation — possibly
        // long before its async coroutine acquires `takeover`. Waiting here first makes
        // it impossible for a fresh attempt (id > delete cutoff) to publish in that
        // gap and then be deleted underneath. Attempts that checked before the gate
        // became visible carry ids at/below the recorded cutoff and are handled as old
        // below / by the transaction, never as fresh owners under a deletion.
        awaitDeleteReservation(key)
        // Scope-wide destructive barriers (whole-title / purge-all). Bounded: only the
        // all-manga gate plus this key's own title gate can park this attempt.
        awaitMangaScope(key.titlePath)
        // Admission protocol (scope linearizability): the cutoff check + owner
        // publication below is serialized with every scope transaction's
        // snapshot+cutoff-note via [withScopeAdmission]. An old attempt that
        // passed the gate lookup before the reservation was installed either
        // publishes before the transaction's snapshot (then it is observed and
        // cancel+joined) or checks after the cutoff note (then it is rejected
        // below) — never an unobserved owner.
        return withScopeAdmission {
            takeover(key).withLock {
                val cutoff = lastCancelledCutoff[key] ?: 0L
                val cancelAllCutoff = mangaCancelAllCutoff.get()
                val titleCutoff = titleDestroyedCutoffs[key.titlePath] ?: 0L
                val mangaCutoff = mangaDestroyedCutoff.get()
                val staleReason = when {
                    attemptId <= cutoff -> "last cancelled cutoff $cutoff"
                    attemptId <= cancelAllCutoff -> "cancel-all cutoff $cancelAllCutoff"
                    attemptId <= titleCutoff -> "title destroyed cutoff $titleCutoff"
                    attemptId <= mangaCutoff -> "manga destroyed cutoff $mangaCutoff"
                    else -> null
                }
                if (staleReason != null) {
                    // Stale attempt: fail closed. Cancel our own job so the download coroutine
                    // aborts; it must never publish or commit.
                    myJob.cancel()
                    throw kotlinx.coroutines.CancellationException(
                        "attempt id $attemptId <= $staleReason for $key; rejecting stale attempt"
                    )
                }
                // Capture previous owner WITHOUT holding the state lock, so its
                // finally-cleanup (which needs the state lock) cannot deadlock us.
                val prev = stateLock(key).withLock { state[key]?.currentJob }
                prev?.cancel()
                prev?.join() // termination barrier: old attempt fully stopped
                stateLock(key).withLock {
                    val next = (state[key]?.generation ?: 0L) + 1
                    state[key] = KeyState(next, myJob, false, attemptId)
                    next
                }
            }
        }
    }

    /** Invalidate the exact key so its current owner can no longer commit. */
    suspend fun invalidate(key: MangaDownloadKey) {
        takeover(key).withLock {
            stateLock(key).withLock {
                val s = state[key] ?: return@withLock
                state[key] = s.copy(cancelled = true)
            }
            // Record the cancellation cutoff so a delayed/stale attempt cannot later publish.
            val c = currentCutoff()
            val prev = lastCancelledCutoff[key] ?: 0L
            lastCancelledCutoff[key] = if (c > prev) c else prev
        }
    }

    /**
     * Authoritative cancel/delete termination barrier for [key].
     *
     * The entire cancellation transaction is performed while holding the per-key
     * `takeover` lock:
     *   1. mark the current owner cancelled and capture the exact stored `currentJob`;
     *   2. cancel AND join that job (returns only after the in-flight attempt —
     *      including any synchronous page writes — has fully terminated);
     *   3. run [cleanup] (service queue/job bookkeeping removal).
     *
     * Because the `takeover` lock is held across the whole transaction, a retry's
     * `begin()` (which also takes `takeover`) cannot publish a new owner until the
     * cancellation — including its bookkeeping cleanup — is fully complete. This makes
     * cancel-vs-retry linearizable (Policy A): a fresh retry's queue/job bookkeeping
     * can never be torn down by an older cancellation transaction.
     *
     * On completion the transaction records this key's [lastCancelledCutoff] so that any
     * later `begin()` carrying an attempt id at or below it is failed-closed (covers an
     * attempt whose id was issued before cancellation but whose queue publication arrived
     * after the one-time cleanup sweep). [cleanup] receives both the exact
     * `cancelledAttemptId` of the owner this transaction cancelled and the captured
     * `cutoff`, so the service can cancel AND remove every same-key job-bookkeeping
     * record whose attempt id is at or below the cutoff — including an old polled-but-not
     * -yet-owner job.
     *
     * The `state` lock is NOT held across `join()` (the job's `finally` needs it), so
     * there is no deadlock; only the `takeover` lock spans the join.
     */
    suspend fun cancelAndJoin(
        key: MangaDownloadKey,
        cutoff: Long,
        cleanup: suspend (Long, Long) -> Unit = { _, _ -> },
    ) {
        takeover(key).withLock {
            // Read the current owner while holding the takeover lock. Only the attempt that
            // this cancellation actually targets — i.e. the current owner whose attempt id is
            // at or below the captured cutoff — is marked cancelled and terminated. A fresh
            // owner created AFTER cancellation invocation (attemptId > cutoff) must survive:
            // it is left untouched, while the cancellation's cutoff is still published so any
            // delayed stale attempt fails closed at begin().
            val (job, cancelledAttemptId) = stateLock(key).withLock {
                val s = state[key]
                if (s != null && s.attemptId <= cutoff) {
                    state[key] = s.copy(cancelled = true)
                    (s.currentJob to s.attemptId)
                } else {
                    (null to 0L)
                }
            }
            // Publish the cancellation cutoff regardless: any attempt id <= it is now stale.
            val prev = lastCancelledCutoff[key] ?: 0L
            lastCancelledCutoff[key] = if (cutoff > prev) cutoff else prev
            job?.cancel()
            job?.join()
            cleanup(cancelledAttemptId, cutoff)
        }
    }

    /** True only when (key, generation, myJob) is exactly the current, non-cancelled owner
     * whose scope was not destroyed after it published (see [noteMangaScopeDestroyed])
     * and which is not stale for a completed Clear All (see [noteMangaCancelAll]). */
    suspend fun isOwner(key: MangaDownloadKey, generation: Long, myJob: Job): Boolean {
        return stateLock(key).withLock {
            val s = state[key]
            s != null && s.generation == generation && s.currentJob == myJob && !s.cancelled &&
                !isScopeDestroyed(key, s.attemptId) && !isClearAllStale(s.attemptId)
        }
    }

    /**
     * Atomically verify that (key, generation, myJob) is still the current,
     * non-cancelled owner, and if so run [commit] while the state lock is held so no
     * concurrent takeover can interleave a commit for this directory. Additionally
     * refuses owners whose title/all-manga scope was destroyed after they published,
     * so a whole-title/purge delete can never be followed by stale COMPLETE
     * resurrection — even for keys that had no live owner during that delete.
     * Likewise refuses owners stale for a completed Clear All, so an old attempt
     * that somehow published can never commit afterwards.
     */
    suspend fun commitIfOwner(
        key: MangaDownloadKey,
        generation: Long,
        myJob: Job,
        commit: () -> Unit,
    ): Boolean {
        return stateLock(key).withLock {
            val s = state[key]
            val ok = s != null
                && s.generation == generation
                && s.currentJob == myJob
                && !s.cancelled
                && !isScopeDestroyed(key, s.attemptId)
                && !isClearAllStale(s.attemptId)
            if (ok) commit()
            ok
        }
    }

    /** Remove our record only if we are still the current owner (never a newer one). */
    suspend fun clearIfCurrent(key: MangaDownloadKey, myJob: Job) {
        stateLock(key).withLock {
            val s = state[key]
            if (s != null && s.currentJob == myJob) {
                state.remove(key)
            }
        }
    }
}
