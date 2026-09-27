package ani.dantotsu.media

import kotlinx.coroutines.CancellationException

/**
 * M1 FINAL CLOSE (V4 Blocker 2): cancellation-safe lookup primitives.
 *
 * `runCatching` / `catch (e: Exception)` swallow [CancellationException],
 * turning a lifecycle cancellation into ordinary failure — extra fallbacks
 * may then run and a negative-cache entry may persist. These helpers keep
 * cancellation loud while still tolerating network errors.
 *
 * Framework-free so the contract is unit-proved
 * (LogoLookupCancellationTest).
 */

/** Returns null on ordinary failure; [CancellationException] propagates. */
internal suspend fun <T> suppressNonCancellation(block: suspend () -> T?): T? {
    try {
        return block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        return null
    }
}

/**
 * Runs [lookups] in order, returning the first non-null result.
 * A [CancellationException] propagates immediately: later lookups never
 * run, so callers that write negative cache only after this returns
 * structurally cannot persist a cancelled lookup.
 */
internal suspend fun firstResult(lookups: List<suspend () -> String?>): String? {
    for (lookup in lookups) {
        val result = lookup()
        if (result != null) return result
    }
    return null
}

/**
 * TMDB identity gate (V4 Blocker 1): a known `idTMDB` is used directly in
 * either mode; the AniList-ID mapper route runs only when
 * [allowAnilistMapper] is true (AniList mode). In MAL/Rescue mode there is
 * no MAL-aware TMDB mapping route in the codebase, so the fallback is
 * safely skipped instead of misinterpreting the MAL id as AniList.
 */
internal suspend fun resolveTmdbId(
    idTMDB: String?,
    allowAnilistMapper: Boolean,
    anilistId: Int,
    lookupAnilistMapper: suspend (Int) -> Int?,
): Int? {
    idTMDB?.toIntOrNull()?.let { return it }
    if (!allowAnilistMapper) return null
    if (anilistId <= 0) return null
    return suppressNonCancellation { lookupAnilistMapper(anilistId) }
}
