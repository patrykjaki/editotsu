package ani.dantotsu.settings.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * MAX MERGE M3: human-readable storage formatting contract.
 */
class StorageFormatTest {

    @Test
    fun `tiny values read as less-than-a-kilobyte`() {
        assertEquals("<1 KB", formatStorageSize(0))
        assertEquals("<1 KB", formatStorageSize(-5))
        assertEquals("<1 KB", formatStorageSize(26))
        assertEquals("<1 KB", formatStorageSize(1023))
    }

    @Test
    fun `kilobytes megabytes gigabytes`() {
        assertEquals("1.0 KB", formatStorageSize(1024))
        assertEquals("12.4 MB", formatStorageSize((12.4 * 1024 * 1024).toLong()))
        assertEquals("2.1 GB", formatStorageSize((2.1 * 1024 * 1024 * 1024).toLong()))
    }
}
