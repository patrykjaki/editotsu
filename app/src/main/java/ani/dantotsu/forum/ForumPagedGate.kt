package ani.dantotsu.forum

/**
 * MAX MERGE M2 correction (Blockers 1+4): framework-free pagination +
 * latest-wins gate shared by ForumActivity and ThreadViewActivity.
 *
 * - `startRefresh()` begins a filter/search/swipe/initial load: bumps the
 *   generation so any in-flight older request is discarded on arrival,
 *   resets page state, and returns the generation tag for the request.
 * - `requestNext()` answers a near-bottom event: null while a load is in
 *   flight or no pages remain (no duplicate same-page requests), else the
 *   (generation, page) to fetch.
 * - `finish()` applies a result only when its generation is current;
 *   stale results touch nothing (no page/hasNext/adapter/empty changes).
 *   The page advances even on an empty current-generation page so a
 *   pathological empty-but-hasNext server cannot pin the paginator.
 * - `fail()` releases the loading flag for the current generation only.
 *
 * Unit-proved (ForumPagedGateTest).
 */
data class PagedRequest(val generation: Int, val page: Int)

class ForumPagedGate {
    private var generation = 0
    var currentPage = 1
        private set
    var hasNextPage = true
        private set
    var loading = false
        private set

    fun startRefresh(): Int {
        generation += 1
        currentPage = 1
        hasNextPage = true
        loading = true
        return generation
    }

    fun requestNext(): PagedRequest? {
        if (loading || !hasNextPage) return null
        loading = true
        return PagedRequest(generation, currentPage)
    }

    fun finish(generation: Int, loadedPage: Int, hasNext: Boolean): Boolean {
        if (generation != this.generation) return false
        currentPage = loadedPage + 1
        hasNextPage = hasNext
        loading = false
        return true
    }

    fun fail(generation: Int) {
        if (generation == this.generation) loading = false
    }
}
