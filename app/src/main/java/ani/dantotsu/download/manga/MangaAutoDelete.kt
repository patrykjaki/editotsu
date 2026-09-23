package ani.dantotsu.download.manga

import ani.dantotsu.download.DownloadedType

/**
 * Pure construction of the auto-delete (mark-progress) manga chapter command.
 *
 * `updateProgress` auto-delete previously called fire-and-forget
 * `DownloadsManager.removeDownload()` for manga, which deletes outside the
 * ownership/takeover barrier and can race a live or fresh re-download of the same
 * chapter (resurrection via later COMPLETE). Manga auto-delete must instead travel
 * the authoritative chapter delete command ([MangaDownloaderService.ACTION_DELETE_CHAPTER]);
 * this helper builds that command's identity from the stored [DownloadedType] so the
 * contract is unit-testable without Android.
 */
object MangaAutoDelete {

    /** Chapter-delete identity derived from stored download metadata. */
    data class Command(val title: String, val chapter: String, val uniqueNumber: String)

    fun commandFor(downloadedType: DownloadedType): Command =
        Command(
            title = downloadedType.titleName,
            chapter = downloadedType.chapterName,
            uniqueNumber = downloadedType.uniqueName,
        )
}
