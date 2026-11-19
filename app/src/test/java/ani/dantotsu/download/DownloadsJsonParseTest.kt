package ani.dantotsu.download

import ani.dantotsu.media.MediaType
import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadsJsonParseTest {

    @Test
    fun nullBlankAndJsonNull_returnEmptyWithoutError() {
        var errored = false
        for (input in listOf(null, "", "   ", "\n\t", "null")) {
            val parsed = parseDownloadsJson(input) { errored = true }
            assertEquals(emptyList<DownloadedType>(), parsed)
        }
        assertFalse(errored)
    }

    @Test
    fun truncatedOrMalformedJson_returnsEmptyAndReportsError() {
        val malformed = listOf(
            """[{"pTitle":"Series""",
            """not valid json""",
            """{}""",
        )
        for (input in malformed) {
            var errored = false
            val parsed = parseDownloadsJson(input) { errored = true }
            assertEquals(emptyList<DownloadedType>(), parsed)
            assertTrue("Expected onError for input=$input", errored)
        }
    }

    @Test
    fun validJsonList_roundTripsCleanly() {
        val gson = Gson()
        val original = listOf(
            DownloadedType("Re:Zero", "Chapter 1", MediaType.MANGA, scanlator = "ScanA", pageCount = 12),
            DownloadedType("Sample Novel", "Vol 1", MediaType.NOVEL),
        )
        val json = gson.toJson(original)

        var errored = false
        val parsed = parseDownloadsJson(json, gson) { errored = true }

        assertFalse(errored)
        assertEquals(original, parsed)
    }

    @Test
    fun arrayContainingNullElements_filtersOutNulls() {
        var errored = false
        val parsed = parseDownloadsJson("[null, null]") { errored = true }
        assertFalse(errored)
        assertEquals(emptyList<DownloadedType>(), parsed)
    }
}
