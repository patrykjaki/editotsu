package ani.dantotsu.download.manga

/**
 * One shared, exception-safe SAF storage resolution boundary for the authoritative
 * manga destructive operations (`removeMangaChapterBlocking`,
 * `removeMangaTitleBlocking`, `purgeMangaBlocking`) and the reconciliation
 * availability check.
 *
 * Contract:
 * - root resolves -> [Root.Available] (proceed);
 * - root is null/unresolvable -> [Root.Unavailable] (no metadata mutation);
 * - root resolution throws `SecurityException`/provider exception ->
 *   [Root.Unavailable] (no metadata mutation, no exception escape).
 *
 * Coroutine cancellation is never swallowed: a `CancellationException` from the
 * resolver propagates instead of being mapped.
 */
object MangaStorageResolution {

    sealed interface Root<out T> {
        data class Available<T>(val directory: T) : Root<T>
        data class Unavailable(val reason: String) : Root<Nothing>
    }

    fun <T> resolve(resolver: () -> T?): Root<T> {
        return try {
            val root = resolver()
            if (root != null) {
                Root.Available(root)
            } else {
                Root.Unavailable(
                    "manga SAF storage root is null/unresolvable; refusing to mutate metadata"
                )
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Root.Unavailable(
                "manga SAF storage root resolution failed " +
                    "(${e.javaClass.simpleName}); refusing to mutate metadata"
            )
        }
    }
}
