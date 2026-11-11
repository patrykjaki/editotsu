package ani.dantotsu.media.manga.mangareader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PageSizeTest {

    @Test
    fun `ordinary pages keep their size`() {
        assertNull(cappedPageSize(1400, 2000))
        assertNull(cappedPageSize(2880, 2048)) // double-page spread
        assertNull(cappedPageSize(1080, MAX_PAGE_DIMENSION))
        assertEquals(1, pageInSampleSize(1400, 2000))
    }

    @Test
    fun `long strip is scaled to the maximum height keeping the aspect ratio`() {
        assertEquals(540 to MAX_PAGE_DIMENSION, cappedPageSize(1080, 16384))
        assertEquals(354 to MAX_PAGE_DIMENSION, cappedPageSize(1080, 25000))
    }

    @Test
    fun `very wide page is capped on its width`() {
        assertEquals(MAX_PAGE_DIMENSION to 2048, cappedPageSize(16384, 4096))
    }

    @Test
    fun `sample size never drops below the limit`() {
        assertEquals(2, pageInSampleSize(1080, 16384)) // decodes at exactly 8192
        assertEquals(2, pageInSampleSize(1080, 25000)) // 12500, then scaled to 8192
        assertEquals(4, pageInSampleSize(800, 40000))  // 10000, then scaled
        assertEquals(1, pageInSampleSize(1080, 12000))
    }

    @Test
    fun `invalid sizes are left alone`() {
        assertNull(cappedPageSize(0, 100))
        assertEquals(1, pageInSampleSize(0, 0))
    }
}
