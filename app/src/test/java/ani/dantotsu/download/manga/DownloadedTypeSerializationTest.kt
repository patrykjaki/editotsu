package ani.dantotsu.download.manga

import ani.dantotsu.download.DownloadedType
import ani.dantotsu.media.MediaType
import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadedTypeSerializationTest {

    private val gson = Gson()

    @Test
    fun newEntry_roundTripsPageCount() {
        val original = DownloadedType(
            "Title", "Ch1", MediaType.MANGA,
            scanlator = "Group", pageCount = 12
        )
        val json = gson.toJson(original)
        assertTrue("pageCount should be serialized", json.contains("pageCount"))

        val back = gson.fromJson(json, DownloadedType::class.java)
        // Logical identity is preserved (pageCount is not part of equality).
        assertEquals(original, back)
        // The persisted expected-page-count metadata survives the round trip.
        assertEquals(12, back.pageCount)
    }

    @Test
    fun legacyEntryWithoutPageCount_deserializesAsNull() {
        val json = """{"pTitle":"Title","pChapter":"Ch1","type":"MANGA","scanlator":"Group"}"""
        val back = gson.fromJson(json, DownloadedType::class.java)
        assertNull(back.pageCount)
        assertEquals("Title", back.titleName)
        assertEquals("Ch1", back.chapterName)
    }

    @Test
    fun pageCountIsNotPartOfIdentity_afterRoundTrip() {
        val withCount = DownloadedType("Title", "Ch1", MediaType.MANGA, pageCount = 5)
        val json = gson.toJson(withCount)
        val fromJson = gson.fromJson(json, DownloadedType::class.java)
        assertEquals(withCount, fromJson)
    }
}
