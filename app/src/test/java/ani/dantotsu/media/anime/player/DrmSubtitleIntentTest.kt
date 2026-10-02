package ani.dantotsu.media.anime.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MAX MERGE M4 correction (HOLD7): DRM subtitle-selection intent parity.
 * Every behavior runs through the pure [DrmSubtitleIntentMachine]; the
 * engine only executes returned actions.
 */
class DrmSubtitleIntentTest {

    private fun ref(
        id: Int,
        externalId: String? = null,
        lang: String? = null,
        name: String? = null,
    ) = DrmSubtitleIntentMachine.TrackRef(id, externalId, lang, name)

    @Test
    fun `preferred None starts Off`() {
        val m = DrmSubtitleIntentMachine()
        val action = m.onLoad(1L, "None", listOf("https://x/en.vtt"))
        assertTrue(action is DrmSubtitleIntentMachine.Action.DisableText)
        assertTrue(m.selection is SubtitleSelection.Off)
        // Request-carried external cannot override Off.
        val tracks = listOf(ref(0, "https://x/en.vtt", "en"))
        val later = m.onTracksAvailable(1L, tracks, autoId = 0, currentSelectedId = null)
        assertTrue(later is DrmSubtitleIntentMachine.Action.DisableText)
        assertTrue(m.selection is SubtitleSelection.Off)
    }

    @Test
    fun `persisted embedded preference restores intended track`() {
        val m = DrmSubtitleIntentMachine()
        m.onLoad(1L, "Embedded:spa", emptyList())
        val tracks = listOf(ref(0, null, "en"), ref(1, null, "spa"))
        val auto = resolveDrmAutoSubtitleId(tracks, "Embedded:spa")
        assertEquals(1, auto)
        val action = m.onTracksAvailable(1L, tracks, autoId = auto, currentSelectedId = null)
        assertTrue(action is DrmSubtitleIntentMachine.Action.SelectTrack)
        assertEquals(1, (action as DrmSubtitleIntentMachine.Action.SelectTrack).id)
    }

    @Test
    fun `request-carried external cannot override Off`() {
        val m = DrmSubtitleIntentMachine()
        m.onLoad(1L, "None", listOf("https://x/en.vtt"))
        val tracks = listOf(ref(0, "https://x/en.vtt", "en"))
        val action = m.onTracksAvailable(1L, tracks, autoId = null, currentSelectedId = null)
        assertTrue(action is DrmSubtitleIntentMachine.Action.DisableText)
    }

    @Test
    fun `live explicit external overrides Off`() {
        val m = DrmSubtitleIntentMachine()
        m.onLoad(1L, "None", emptyList())
        val tracks = listOf(ref(0, "https://x/en.vtt", "en"))
        val action = m.onLiveExternal("https://x/en.vtt", select = true, explicit = true, knownTracks = tracks)
        assertTrue(action is DrmSubtitleIntentMachine.Action.SelectTrack)
        assertEquals(0, (action as DrmSubtitleIntentMachine.Action.SelectTrack).id)
        assertTrue(m.selection is SubtitleSelection.Track)
    }

    @Test
    fun `live explicit external overrides prior embedded override`() {
        val m = DrmSubtitleIntentMachine()
        m.onLoad(1L, "Embedded:en", emptyList())
        val tracks = listOf(ref(0, null, "en"), ref(1, "https://x/fr.vtt", "fr"))
        val auto = m.onTracksAvailable(1L, tracks, autoId = 0, currentSelectedId = null)
        assertTrue(auto is DrmSubtitleIntentMachine.Action.SelectTrack)
        // Automatic resolution applies without taking explicit ownership.
        assertTrue(m.selection is SubtitleSelection.Unset)
        val action = m.onLiveExternal("https://x/fr.vtt", select = true, explicit = true, knownTracks = tracks)
        assertTrue(action is DrmSubtitleIntentMachine.Action.SelectTrack)
        assertEquals(1, (action as DrmSubtitleIntentMachine.Action.SelectTrack).id)
        assertTrue(m.selection is SubtitleSelection.Track)
    }

    @Test
    fun `already-attached external can be selected again`() {
        val m = DrmSubtitleIntentMachine()
        m.onLoad(1L, null, emptyList())
        val tracks = listOf(ref(0, "https://x/a.vtt", "en"), ref(1, "https://x/b.vtt", "fr"))
        // Select B, then A, then A again: every explicit select re-asserts.
        m.onLiveExternal("https://x/b.vtt", select = true, explicit = true, knownTracks = tracks)
        m.onLiveExternal("https://x/a.vtt", select = true, explicit = true, knownTracks = tracks)
        val again = m.onLiveExternal("https://x/a.vtt", select = true, explicit = true, knownTracks = tracks)
        assertTrue(again is DrmSubtitleIntentMachine.Action.SelectTrack)
        assertEquals(0, (again as DrmSubtitleIntentMachine.Action.SelectTrack).id)
    }

    @Test
    fun `pending external then explicit embedded keeps B winner`() {
        val m = DrmSubtitleIntentMachine()
        m.onLoad(1L, null, listOf("https://x/a.vtt"))
        // User explicitly selects embedded B before A materializes.
        m.onSelectTrack(5)
        // A materializes late alongside embedded tracks: B must still win.
        val tracks = listOf(ref(4, null, "en"), ref(5, null, "spa"), ref(6, "https://x/a.vtt", "fr"))
        val action = m.onTracksAvailable(1L, tracks, autoId = null, currentSelectedId = null)
        assertTrue(action is DrmSubtitleIntentMachine.Action.SelectTrack)
        assertEquals(5, (action as DrmSubtitleIntentMachine.Action.SelectTrack).id)
    }

    @Test
    fun `pending external then explicit external keeps B winner`() {
        val m = DrmSubtitleIntentMachine()
        m.onLoad(1L, null, listOf("https://x/a.vtt"))
        // Live-explicit B arrives before either track materializes.
        val pending = m.onLiveExternal("https://x/b.vtt", select = true, explicit = true, knownTracks = emptyList())
        assertTrue(pending is DrmSubtitleIntentMachine.Action.EnableTextAuto)
        // Both materialize: B wins over request-carried A.
        val tracks = listOf(ref(0, "https://x/a.vtt", "en"), ref(1, "https://x/b.vtt", "fr"))
        val action = m.onTracksAvailable(1L, tracks, autoId = null, currentSelectedId = null)
        assertTrue(action is DrmSubtitleIntentMachine.Action.SelectTrack)
        assertEquals(1, (action as DrmSubtitleIntentMachine.Action.SelectTrack).id)
    }

    @Test
    fun `stale generation cannot resurrect older intent`() {
        val m = DrmSubtitleIntentMachine()
        m.onLoad(2L, "None", emptyList())
        // Late enumeration from generation 1 is ignored entirely.
        val tracks = listOf(ref(0, "https://x/en.vtt", "en"))
        val action = m.onTracksAvailable(1L, tracks, autoId = 0, currentSelectedId = null)
        assertTrue(action is DrmSubtitleIntentMachine.Action.NoOp)
        assertTrue(m.selection is SubtitleSelection.Off)
    }

    @Test
    fun `automatic attach never overrides explicit state`() {
        val m = DrmSubtitleIntentMachine()
        m.onLoad(1L, null, emptyList())
        m.onSelectTrack(null)
        // A later automatic (non-explicit) attach registers nothing.
        val action = m.onLiveExternal("https://x/auto.vtt", select = true, explicit = false, knownTracks = emptyList())
        assertTrue(action is DrmSubtitleIntentMachine.Action.NoOp)
        assertTrue(m.selection is SubtitleSelection.Off)
    }
}
