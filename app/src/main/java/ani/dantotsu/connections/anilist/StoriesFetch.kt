package ani.dantotsu.connections.anilist

import ani.dantotsu.profile.User
import kotlinx.coroutines.CancellationException

/**
 * MAX MERGE M6 HOLD (Blocker 2): lenient Stories fetch seam behind
 * `AnilistHomeViewModel.initUserStatus`.
 *
 * Ordinary failure (or null) resolves to empty so the stories rail never
 * spins forever; cancellation propagates so the caller posts nothing.
 * Framework-free so the contract is unit-proved
 * (StoriesFetchCancellationTest).
 */
internal suspend fun fetchUserStatusLenient(
    fetcher: suspend () -> ArrayList<User>?,
): ArrayList<User> {
    return try {
        fetcher() ?: arrayListOf()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        arrayListOf()
    }
}
