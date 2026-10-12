package ani.dantotsu.media.anime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

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

    // ---- HOLD1 item 4: precedence resolver ----

    @Test
    fun `live active choice always wins`() {
        assertEquals(
            SubtitleSourceWin.ACTIVE_ONLINE,
            resolveSubtitleSource("wyzie-1", true, "None", null)
        )
        assertEquals(
            SubtitleSourceWin.ACTIVE_ONLINE,
            resolveSubtitleSource("x", false, "English", 3)
        )
    }

    @Test
    fun `saved record wins without live choice`() {
        assertEquals(
            SubtitleSourceWin.SAVED_RECORD,
            resolveSubtitleSource(null, true, "None", null)
        )
        assertEquals(
            SubtitleSourceWin.SAVED_RECORD,
            resolveSubtitleSource(null, true, "English", 2)
        )
    }

    @Test
    fun `explicit off needs no online and no legacy selection`() {
        assertEquals(SubtitleSourceWin.OFF, resolveSubtitleSource(null, false, "None", 2))
        assertEquals(SubtitleSourceWin.OFF, resolveSubtitleSource(null, false, "None", null))
        assertEquals(SubtitleSourceWin.OFF, resolveSubtitleSource(null, false, null, null))
    }

    @Test
    fun `legacy server selection is not off`() {
        // Previous selectedSubtitle semantics: a chosen server index keeps
        // Off un-highlighted even with no saved language.
        assertEquals(SubtitleSourceWin.LEGACY, resolveSubtitleSource(null, false, null, 3))
        assertEquals(SubtitleSourceWin.LEGACY, resolveSubtitleSource(null, false, "English", null))
    }

    // ---- HOLD1 item 3: stored-file ownership ----

    private fun ownedDir(): java.io.File {
        val base = java.io.File(System.getProperty("java.io.tmpdir"), "owned-subs-test")
        val dir = java.io.File(base, SAVED_SUBS_DIR)
        dir.mkdirs()
        return dir
    }

    @Test
    fun `canonical staged copy is owned`() {
        val dir = ownedDir()
        assertTrue(isOwnedSubtitleFile(dir, File(dir, "sub_1_12_12345.srt").absolutePath))
        assertTrue(isOwnedSubtitleFile(dir, File(dir, "sub_1_12_-99.VTT").absolutePath))
    }

    @Test
    fun `foreign absolute paths are never owned`() {
        val dir = ownedDir()
        assertTrue(!isOwnedSubtitleFile(dir, "/sdcard/evil.srt"))
        assertTrue(!isOwnedSubtitleFile(dir, "/sdcard/fake_saved_online_subs/sub_1_12_1.srt"))
        assertTrue(!isOwnedSubtitleFile(dir, null))
        assertTrue(!isOwnedSubtitleFile(dir, ""))
    }

    @Test
    fun `traversal and shape violations are never owned`() {
        val dir = ownedDir()
        assertTrue(!isOwnedSubtitleFile(dir, File(dir, "../sub_1_12_1.srt").absolutePath))
        assertTrue(!isOwnedSubtitleFile(dir, File(dir, "notes.txt").absolutePath))
        assertTrue(!isOwnedSubtitleFile(dir, File(dir, "sub_1_12_1.mp4").absolutePath))
        assertTrue(!isOwnedSubtitleFile(dir, File(dir, "sub_1_12_1").absolutePath))
        assertTrue(!isOwnedSubtitleFile(dir, File(dir, "other_1_12_1.srt").absolutePath))
    }

    @Test
    fun `replacement retires only differing previous path`() {
        assertEquals("/a/old.srt", staleOwnedFile("/a/old.srt", "/a/new.srt"))
        assertNull(staleOwnedFile("/a/same.srt", "/a/same.srt"))
        assertNull(staleOwnedFile(null, "/a/new.srt"))
        // Null replacement still supersedes: previous retires.
        assertEquals("/a/old.srt", staleOwnedFile("/a/old.srt", null))
    }

    // ---- HOLD1 item 2: async persistence ownership ----

    @Test
    fun `current request on current episode persists and attaches`() {
        assertEquals(
            PersistDecision.PERSIST_AND_ATTACH,
            shouldPersistCompletedRequest(7L, 7L, 1, "12", 1, "12")
        )
    }

    @Test
    fun `superseded generation drops entirely`() {
        // Newer request started (Off/select/another fetch): late A drops,
        // never resurrecting under any episode.
        assertEquals(
            PersistDecision.DROP,
            shouldPersistCompletedRequest(7L, 9L, 1, "12", 1, "12")
        )
        assertEquals(
            PersistDecision.DROP,
            shouldPersistCompletedRequest(7L, 9L, 1, "12", 1, "13")
        )
    }

    @Test
    fun `episode move persists under captured identity without attaching`() {
        // A finishes after switch to B: record belongs to A, player shows B.
        assertEquals(
            PersistDecision.PERSIST_ONLY,
            shouldPersistCompletedRequest(7L, 7L, 1, "12", 1, "13")
        )
        assertEquals(
            PersistDecision.PERSIST_ONLY,
            shouldPersistCompletedRequest(7L, 7L, 1, "12", 2, "12")
        )
    }

    @Test
    fun `dead player persists without attaching`() {
        assertEquals(
            PersistDecision.PERSIST_ONLY,
            shouldPersistCompletedRequest(7L, 7L, 1, "12", null, null)
        )
    }
}
