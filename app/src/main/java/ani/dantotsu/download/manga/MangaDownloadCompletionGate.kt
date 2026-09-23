package ani.dantotsu.download.manga

/**
 * Production completion gate shared by [MangaDownloaderService].
 *
 * This is the single place that decides whether a chapter's COMPLETE marker may be
 * committed. It does NOT touch the filesystem or Android APIs, so it can be unit
 * tested directly. [MangaDownloaderService.download] routes its completion through
 * [commitIfComplete] and only performs the actual `addDownload(...)` inside
 * [onComplete]; on an incomplete set it performs [onIncomplete] (which clears the
 * partial directory and fails the download) and never reaches the commit path.
 *
 * [onComplete] is a suspend lambda so the service can perform the atomic ownership
 * check (and the `addDownload` commit) under the ownership lock before completing.
 */
object MangaDownloadCompletionGate {

    suspend fun commitIfComplete(
        pageFiles: List<PageFile>,
        expectedCount: Int,
        onIncomplete: suspend (reason: String) -> Unit,
        onComplete: suspend () -> Unit,
    ) {
        if (!MangaDownloadValidator.isChapterComplete(pageFiles, expectedCount)) {
            onIncomplete("persisted pages do not satisfy the expected set of $expectedCount pages")
        } else {
            onComplete()
        }
    }
}
