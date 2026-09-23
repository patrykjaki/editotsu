package ani.dantotsu.download.manga

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MangaDownloadValidatorTest {

    private fun pages(vararg specs: Pair<Int, Long>): List<PageFile> {
        return specs.map { (index, length) -> PageFile(MangaDownloadValidator.expectedPageName(index), length) }
    }

    @Test
    fun expectedPageNameZeroPadsToThreeDigits() {
        assertTrue(MangaDownloadValidator.expectedPageName(0) == "000.jpg")
        assertTrue(MangaDownloadValidator.expectedPageName(7) == "007.jpg")
        assertTrue(MangaDownloadValidator.expectedPageName(12) == "012.jpg")
        assertTrue(MangaDownloadValidator.expectedPageName(123) == "123.jpg")
    }

    // Test 1: fully successful chapter.
    @Test
    fun allExpectedPagesPresentAndValid_isComplete() {
        val files = pages(0 to 1024L, 1 to 2048L, 2 to 512L, 3 to 4096L, 4 to 100L)
        assertTrue(MangaDownloadValidator.isChapterComplete(files, 5))
    }

    // Test 2: one expected page absent.
    @Test
    fun oneExpectedPageMissing_isNotComplete() {
        val files = pages(0 to 1024L, 1 to 2048L, 2 to 512L, 3 to 4096L) // missing index 4
        assertFalse(MangaDownloadValidator.isChapterComplete(files, 5))
    }

    // Test 3: one expected page exists but size == 0.
    @Test
    fun oneExpectedPageZeroBytes_isNotComplete() {
        val files = pages(0 to 1024L, 1 to 2048L, 2 to 0L, 3 to 4096L, 4 to 100L)
        assertFalse(MangaDownloadValidator.isChapterComplete(files, 5))
    }

    // Extra numbered page (page-count mismatch) => NOT COMPLETE.
    @Test
    fun extraNumberedPage_isNotComplete() {
        // expected 5, but 000..005 are present (6 canonical page files).
        val files = pages(0 to 1024L, 1 to 2048L, 2 to 512L, 3 to 4096L, 4 to 100L, 5 to 100L)
        assertFalse(MangaDownloadValidator.isChapterComplete(files, 5))
    }

    // Test 4: page write failure left only a subset persisted.
    @Test
    fun pageWriteFailureLeavesSubset_isNotComplete() {
        val files = pages(0 to 1024L, 1 to 2048L, 2 to 512L) // only 3 of 5 written
        assertFalse(MangaDownloadValidator.isChapterComplete(files, 5))
    }

    // Test 5: partial / interrupted chapter.
    @Test
    fun partialInterruptedChapter_isNotComplete() {
        val files = pages(0 to 1024L, 1 to 2048L, 2 to 512L, 3 to 4096L,
            4 to 100L, 5 to 100L, 6 to 100L, 7 to 100L, 8 to 100L, 9 to 100L)
        assertFalse(MangaDownloadValidator.isChapterComplete(files, 25))
    }

    // Test 6: stored COMPLETE metadata + missing/invalid page => not trusted.
    @Test
    fun staleCompleteMetadataWithMissingPage_isNotComplete() {
        // Stored pageCount claims 5 but the filesystem is incomplete.
        val files = pages(0 to 1024L, 1 to 2048L, 2 to 512L, 3 to 4096L)
        assertFalse(MangaDownloadValidator.isChapterComplete(files, 5))
    }

    // Test 7: retry to completion.
    @Test
    fun retryToCompletion_eventuallyComplete() {
        val firstAttempt = pages(0 to 1024L, 1 to 2048L, 2 to 512L, 3 to 4096L) // missing 4
        assertFalse(MangaDownloadValidator.isChapterComplete(firstAttempt, 5))

        val retried = pages(0 to 1024L, 1 to 2048L, 2 to 512L, 3 to 4096L, 4 to 777L)
        assertTrue(MangaDownloadValidator.isChapterComplete(retried, 5))
    }

    // Test 8: ordering — completion is not granted before the final page is durable+valid.
    @Test
    fun completionNotGrantedUntilFinalPageValid() {
        // All but the last expected page are present and valid.
        val files = pages(0 to 1024L, 1 to 2048L, 2 to 512L, 3 to 4096L) // index 4 missing
        assertFalse(MangaDownloadValidator.isChapterComplete(files, 5))

        // Once the final page is persisted and valid, the set validates.
        val complete = pages(0 to 1024L, 1 to 2048L, 2 to 512L, 3 to 4096L, 4 to 999L)
        assertTrue(MangaDownloadValidator.isChapterComplete(complete, 5))
    }

    @Test
    fun strayNonPageFilesDoNotAffectCompletion() {
        val files = pages(0 to 1024L, 1 to 2048L, 2 to 512L) +
                listOf(PageFile("media.json", 200L), PageFile("cover.jpg", 500L))
        assertTrue(MangaDownloadValidator.isChapterComplete(files, 3))
    }

    // An unrelated non-page file alongside the exact expected set => COMPLETE.
    @Test
    fun expectedSetPlusUnrelatedCover_isComplete() {
        // expected 5, exactly 000..004 valid, plus an unrelated cover.jpg.
        val files = pages(0 to 1024L, 1 to 2048L, 2 to 512L, 3 to 4096L, 4 to 100L) +
                listOf(PageFile("cover.jpg", 500L))
        assertTrue(MangaDownloadValidator.isChapterComplete(files, 5))
    }

    // Out-of-range numbered page while still hitting the expected count is rejected.
    @Test
    fun expectedCountMetButWrongIndices_isNotComplete() {
        // expected 5, but files are 000,001,002,003,005 (missing 004, has 005).
        val files = pages(0 to 1024L, 1 to 2048L, 2 to 512L, 3 to 4096L, 5 to 100L)
        assertFalse(MangaDownloadValidator.isChapterComplete(files, 5))
    }

    @Test
    fun noFilesAndPositiveExpected_isNotComplete() {
        assertFalse(MangaDownloadValidator.isChapterComplete(emptyList(), 5))
    }

    @Test
    fun nonPositiveExpected_isNotComplete() {
        assertFalse(MangaDownloadValidator.isChapterComplete(pages(0 to 100L), 0))
        assertFalse(MangaDownloadValidator.isChapterComplete(pages(0 to 100L), -1))
    }
}
