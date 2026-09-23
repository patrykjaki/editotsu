package ani.dantotsu.download.manga

/**
 * Pure physical-state decision for the authoritative manga destructive
 * operations (`removeMangaChapterBlocking`, `removeMangaTitleBlocking`,
 * `purgeMangaBlocking`).
 *
 * Authoritative result semantics — absence is NEVER inferred from a caught
 * exception:
 * - [Outcome.VerifiedAbsent]: absence was actually established without provider
 *   error (lookup cleanly missing, or existence cleanly false). Metadata cleanup
 *   may proceed.
 * - [Outcome.Deleted]: the object existed and deletion succeeded. Metadata
 *   cleanup may proceed.
 * - [Outcome.Unavailable]: root resolution failed/null, child lookup failed,
 *   existence verification failed, the provider threw, or the delete threw.
 *   Metadata MUST be preserved.
 * - [Outcome.Failed]: the object existed but deletion returned false. Metadata
 *   MUST be preserved.
 *
 * [Outcome.permitsMetadataCleanup] encodes the preservation rule so callers —
 * and tests — cannot mistake an error outcome for a cleanup permit.
 *
 * The SAF calls are injected (`lookup` / `exists` / `delete`), so every state
 * is unit-testable without an Android provider; production passes DocumentFile
 * lambdas through the one shared storage boundary. Coroutine cancellation is
 * never swallowed into storage state.
 */
object MangaPhysicalDelete {

    sealed interface Lookup<out H> {
        data object Missing : Lookup<Nothing>
        data class Present<H>(val handle: H) : Lookup<H>
    }

    sealed interface Outcome {
        val permitsMetadataCleanup: Boolean

        data object VerifiedAbsent : Outcome {
            override val permitsMetadataCleanup: Boolean = true
        }

        data object Deleted : Outcome {
            override val permitsMetadataCleanup: Boolean = true
        }

        data class Unavailable(val reason: String) : Outcome {
            override val permitsMetadataCleanup: Boolean = false
        }

        data class Failed(val reason: String) : Outcome {
            override val permitsMetadataCleanup: Boolean = false
        }
    }

    fun <H> evaluate(
        lookup: () -> Lookup<H>,
        exists: (H) -> Boolean,
        delete: (H) -> Boolean,
    ): Outcome {
        val found = try {
            lookup()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            return Outcome.Unavailable(
                "child lookup failed (${e.javaClass.simpleName}); absence unverifiable"
            )
        }
        val handle = when (found) {
            is Lookup.Missing -> return Outcome.VerifiedAbsent
            is Lookup.Present -> found.handle
        }
        val present = try {
            exists(handle)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            return Outcome.Unavailable(
                "existence verification failed (${e.javaClass.simpleName}); absence unverifiable"
            )
        }
        if (!present) return Outcome.VerifiedAbsent
        val removed = try {
            delete(handle)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            return Outcome.Unavailable(
                "physical delete threw (${e.javaClass.simpleName}); metadata preserved"
            )
        }
        return if (removed) {
            Outcome.Deleted
        } else {
            Outcome.Failed("physical delete returned false; metadata preserved")
        }
    }
}
