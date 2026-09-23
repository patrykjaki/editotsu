package ani.dantotsu.download.manga

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job

/**
 * Lifecycle gate for title-level media writes (`media.json` / `cover.jpg` /
 * `banner.jpg`) of a Manga download attempt.
 *
 * The v9 defect: `saveMediaInfo` launched a detached `GlobalScope.launchIO`
 * writer inside `commitIfOwner`'s commit lambda. That writer was joined by
 * nothing — not the owner Job, not `cancelAndJoin`, not scope termination —
 * so title/purge physical deletion could run while it was still executing,
 * and its delayed writes resurrected title media under a deleted/purged title
 * after a `Deleted` result.
 *
 * This gate makes the media write part of the owning coroutine lifetime:
 * [writeTitleMedia] runs INLINE in the caller's (owner's) coroutine — no
 * detached scope is created anywhere on this path — so the work
 * completes-or-aborts strictly inside the owner Job's lifetime and is
 * therefore joined by owner cancellation, scope termination, and service
 * teardown before any delete/scope barrier passes.
 *
 * Protocol (all cheap ownership checks; no mutex held across media work):
 * 1. pre-check [MangaDownloadOwnership.isOwner]: never start media work for
 *    an already-stale attempt (returns false);
 * 2. run [writeTitleMedia] inline (cancellation aborts, as with any owner
 *    work); failure is best-effort — reported via [onWriteFailed] but never
 *    invalidates the chapter result by itself;
 * 3. post-check `isOwner`: only a still-current owner may proceed to the
 *    authoritative COMPLETE commit (which the caller performs AFTER this
 *    returns, keeping the final metadata check authoritative).
 */
object MangaMediaWriteGate {

    suspend fun runOwnedMediaWrite(
        ownership: MangaDownloadOwnership,
        key: MangaDownloadKey,
        generation: Long,
        job: Job,
        writeTitleMedia: suspend () -> Unit,
        onWriteFailed: (Throwable) -> Unit = {},
    ): Boolean {
        if (!ownership.isOwner(key, generation, job)) return false
        try {
            writeTitleMedia()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            onWriteFailed(e)
        }
        return ownership.isOwner(key, generation, job)
    }
}
