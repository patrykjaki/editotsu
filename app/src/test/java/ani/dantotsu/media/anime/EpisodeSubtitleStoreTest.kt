package ani.dantotsu.media.anime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * LANE-B P1 (#72 EpisodeSubtitleStore): pure seam matrix — key/filename
 * shaping and tolerant decode. Android-coupled persistence (prefs,
 * filesDir staging, cleanup) is covered by compile + device validation;
 * ownership/cleanup semantics are asserted here through the helpers.
 */
class EpisodeSubtitleStoreTest {

    @Test
    fun `pref keys isolate media and episode`() {
        val a = subtitlePrefKey(1, "12")
        val b = subtitlePrefKey(1, "13")
        val c = subtitlePrefKey(2, "12")
        assertTrue(setOf(a, b, c).size == 3)
        assertTrue(a.startsWith("saved_ep_online_sub_"))
    }

    @Test
    fun `staged filenames isolate episode and sub id`() {
        val a = stagedSubtitleFileName(1, "12", "wyzie-1", "srt")
        val b = stagedSubtitleFileName(1, "12", "wyzie-2", "srt")
        val c = stagedSubtitleFileName(1, "13", "wyzie-1", "srt")
        assertTrue(setOf(a, b, c).size == 3)
        assertTrue(a.endsWith(".srt"))
        assertTrue(a.contains("1_12_"))
    }

    @Test
    fun `decode round trip preserves record`() {
        val record = EpisodeSubtitleStore.SavedEpisodeSubtitle(
            id = "wyzie-1",
            displayName = "English",
            provider = "Wyzie",
            language = "en",
            filePath = "/cache/saved_online_subs/sub_1_12_x.srt",
            url = null
        )
        val decoded = decodeSavedSubtitle(
            kotlinx.serialization.json.Json.encodeToString(
                EpisodeSubtitleStore.SavedEpisodeSubtitle.serializer(), record
            )
        )
        assertEquals(record, decoded)
    }

    @Test
    fun `decode tolerates unknown keys`() {
        val decoded = decodeSavedSubtitle(
            """{"id":"a","displayName":"b","provider":"c","language":"d","future":"x"}"""
        )
        assertEquals("a", decoded?.id)
    }

    @Test
    fun `decode rejects corrupt payloads`() {
        assertNull(decodeSavedSubtitle("not json"))
        assertNull(decodeSavedSubtitle("""{"id":1}"""))
        assertNull(decodeSavedSubtitle(""))
    }
}
