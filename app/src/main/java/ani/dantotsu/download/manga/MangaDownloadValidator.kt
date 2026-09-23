package ani.dantotsu.download.manga

/**
 * Pure, side-effect-free validation for manga chapter page persistence.
 *
 * The authoritative completion decision must be derived from the *actual persisted
 * page set*, not from loop completion or exception absence. This object encapsulates
 * that invariant so it can be unit tested without Android dependencies.
 *
 * Pages are persisted by [MangaDownloaderService] as zero-padded, three-digit,
 * zero-based index files: `000.jpg`, `001.jpg`, ... `(expectedCount - 1).jpg`.
 */
object MangaDownloadValidator {

    /** The on-disk file name for the page at [index] (0-based, 3-digit zero-padded). */
    fun expectedPageName(index: Int): String = index.toString().padStart(3, '0') + ".jpg"

    /**
     * Matches the downloader's page-file contract: a numeric, zero-padded index
     * followed by `.jpg` (e.g. `000.jpg`, `001.jpg`). Stray non-page files such as
     * `media.json` or `cover.jpg` do NOT match this contract.
     */
    private val PAGE_NAME_REGEX = Regex("""^\d+\.jpg$""")

    private fun canonicalPageFiles(pageFiles: List<PageFile>): List<PageFile> =
        pageFiles.filter { it.name.matches(PAGE_NAME_REGEX) }

    /**
     * @return true only when the canonical page-file subset equals the expected set:
     *   - exactly [expectedCount] canonical page files are present;
     *   - every expected index `000.jpg` … `(expectedCount-1).jpg` exists;
     *   - each of those files is observable as non-zero.
     *
     * An extra numbered page (e.g. `005.jpg` when [expectedCount] is 5) is a page-count
     * mismatch and therefore fails. Unrelated non-page files are ignored.
     */
    fun isChapterComplete(pageFiles: List<PageFile>, expectedCount: Int): Boolean {
        if (expectedCount <= 0) return false
        val canonical = canonicalPageFiles(pageFiles)
        if (canonical.size != expectedCount) return false
        for (i in 0 until expectedCount) {
            val name = expectedPageName(i)
            val file = canonical.firstOrNull { it.name == name } ?: return false
            if (file.length <= 0) return false
        }
        return true
    }
}

/**
 * Minimal description of a persisted page file used for validation.
 * Kept as a plain data class so tests can construct fakes without DocumentFile.
 */
data class PageFile(val name: String, val length: Long)
