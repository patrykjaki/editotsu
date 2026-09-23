package ani.dantotsu.download.manga

/**
 * Pure construction of the single-item Download Queue Manga cancel command.
 *
 * `MangaDownloaderService`'s cancel receiver requires the EXACT physical-key
 * identity — both chapter and title — before calling
 * `cancelDownload(title, chapter)` (chapter-only would select unrelated titles
 * sharing a chapter label, so the receiver rejects it). The single-item queue
 * cancel path ([DownloadQueueActivity.cancelTask]) must therefore supply both
 * fields from the task; this helper builds that command so the contract is
 * unit-testable without Android.
 */
object MangaQueueCancel {

    /** Cancel identity for one queued manga task: its exact chapter + title. */
    data class Command(val chapter: String, val title: String)

    /** Exact identity carried from [MangaDownloaderService.DownloadTask]. */
    fun commandFor(task: MangaDownloaderService.DownloadTask): Command =
        Command(chapter = task.chapter, title = task.title)
}
