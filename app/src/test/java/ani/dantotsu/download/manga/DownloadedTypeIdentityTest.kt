package ani.dantotsu.download.manga

import ani.dantotsu.download.DownloadedType
import ani.dantotsu.media.MediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadedTypeIdentityTest {

    @Test
    fun sameLogicalDownload_equalRegardlessOfSizeOrPageCount() {
        val a = DownloadedType("Title", "Ch1", MediaType.MANGA, size = 1.5, pageCount = 5)
        val b = DownloadedType("Title", "Ch1", MediaType.MANGA, size = null, pageCount = null)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun differentChapter_notEqual() {
        val a = DownloadedType("Title", "Ch1", MediaType.MANGA, pageCount = 5)
        val b = DownloadedType("Title", "Ch2", MediaType.MANGA, pageCount = 5)
        assertFalse(a == b)
    }

    @Test
    fun differentScanlator_notEqual() {
        val a = DownloadedType("Title", "Ch1", MediaType.MANGA, scanlator = "GroupA", pageCount = 5)
        val b = DownloadedType("Title", "Ch1", MediaType.MANGA, scanlator = "GroupB", pageCount = 5)
        assertFalse(a == b)
    }

    @Test
    fun differentMediaType_notEqual() {
        val a = DownloadedType("Title", "Ch1", MediaType.MANGA, pageCount = 5)
        val b = DownloadedType("Title", "Ch1", MediaType.NOVEL, pageCount = 5)
        assertFalse(a == b)
    }

    @Test
    fun equalEntriesHaveEqualHashCode() {
        val a = DownloadedType("Title", "Ch1", MediaType.MANGA, scanlator = "G", pageCount = 5)
        val b = DownloadedType("Title", "Ch1", MediaType.MANGA, scanlator = "G", pageCount = 9)
        assertTrue(a == b)
        assertEquals(a.hashCode(), b.hashCode())
    }
}
