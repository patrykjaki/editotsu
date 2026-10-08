package ani.dantotsu.media.anime.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MAX MERGE M4 correction (HOLD8): persisted preference vs Media3
 * auto-selection, and transitional vs authoritative enumerations.
 *
 * Reproduction evidence (Correction 6 code): Unset required
 * `currentSelectedId == null`, so a Media3 auto-selected English suppressed
 * a saved Spanish choice (NoOp); and the engine never passed
 * `tracksAuthoritative`, so a transient empty list converted explicit
 * Track to Off. Both are closed below.
 */
class DrmSubtitleIntentHold8Test {

    private fun ref(
        id: Int,
        externalId: String? = null,
        lang: String? = null,
        name: String? = null,
    ) = DrmSubtitleIntentMachine.TrackRef(id, externalId, lang, name)

    @Test
    fun `saved embedded spa beats media3 auto-selected english`() {
        val m = DrmSubtitleIntentMachine()
        m.onLoad(1L, "Embedded:spa", emptyList())
        val tracks = listOf(ref(0, null, "en"), ref(1, null, "spa"))
        val auto = resolveDrmAutoSubtitleId(tracks, "Embedded:spa")
        assertEquals(1, auto)
        val action = m.onTracksAvailable(
            1L, tracks, autoId = auto, currentSelectedId = 0,
            preferExplicit = true
        )
        assertTrue(action is DrmSubtitleIntentMachine.Action.SelectTrack)
        assertEquals(1, (action as DrmSubtitleIntentMachine.Action.SelectTrack).id)
    }

    @Test
    fun `saved language spa beats media3 auto-selected english`() {
        val m = DrmSubtitleIntentMachine()
        m.onLoad(1L, "spa", emptyList())
        val tracks = listOf(ref(0, null, "en"), ref(1, null, "spa"))
        val auto = resolveDrmAutoSubtitleId(tracks, "spa")
        assertEquals(1, auto)
        val action = m.onTracksAvailable(
            1L, tracks, autoId = auto, currentSelectedId = 0,
            preferExplicit = true
        )
        assertEquals(1, (action as DrmSubtitleIntentMachine.Action.SelectTrack).id)
    }

    @Test
    fun `honored preference plus callback is idempotent`() {
        val m = DrmSubtitleIntentMachine()
        m.onLoad(1L, "spa", emptyList())
        val tracks = listOf(ref(0, null, "en"), ref(1, null, "spa"))
        val first = m.onTracksAvailable(1L, tracks, autoId = 1, currentSelectedId = 0, preferExplicit = true)
        assertTrue(first is DrmSubtitleIntentMachine.Action.SelectTrack)
        // Preference now applied (player reports spa selected): no override loop.
        val second = m.onTracksAvailable(1L, tracks, autoId = 1, currentSelectedId = 1, preferExplicit = true)
        assertTrue(second is DrmSubtitleIntentMachine.Action.NoOp)
    }

    @Test
    fun `newer explicit selection is not replaced by saved pref`() {
        val m = DrmSubtitleIntentMachine()
        m.onLoad(1L, "spa", emptyList())
        val tracks = listOf(ref(0, null, "en"), ref(1, null, "spa"))
        m.onSelectTrack(0, tracks[0])
        // Refresh with the saved preference still present: explicit Track(0)
        // stands, and since it is already correctly selected the evaluation
        // is an idempotent NoOp (HOLD10 §5: no selector feedback loop).
        val action = m.onTracksAvailable(1L, tracks, autoId = 1, currentSelectedId = 0, preferExplicit = true)
        assertTrue(action is DrmSubtitleIntentMachine.Action.NoOp)
        assertTrue(m.selection is SubtitleSelection.Track)
        assertEquals(0, (m.selection as SubtitleSelection.Track).id)
    }

    @Test
    fun `explicit track survives transient empty list`() {
        val m = DrmSubtitleIntentMachine()
        m.onLoad(1L, null, emptyList())
        m.onSelectTrack(1, DrmSubtitleIntentMachine.TrackRef(1, null, "spa", "x"))
        val action = m.onTracksAvailable(
            1L, emptyList(), autoId = null, currentSelectedId = null,
            tracksAuthoritative = false
        )
        assertTrue(action is DrmSubtitleIntentMachine.Action.NoOp)
        assertTrue(m.selection is SubtitleSelection.Track)
    }

    @Test
    fun `transient empty then same track restores`() {
        val m = DrmSubtitleIntentMachine()
        m.onLoad(1L, null, emptyList())
        m.onSelectTrack(1, DrmSubtitleIntentMachine.TrackRef(1, null, "spa", "x"))
        m.onTracksAvailable(1L, emptyList(), autoId = null, currentSelectedId = null, tracksAuthoritative = false)
        val tracks = listOf(ref(0, null, "en", "y"), ref(1, null, "spa", "x"))
        val action = m.onTracksAvailable(1L, tracks, autoId = null, currentSelectedId = null)
        assertTrue(action is DrmSubtitleIntentMachine.Action.SelectTrack)
        assertEquals(1, (action as DrmSubtitleIntentMachine.Action.SelectTrack).id)
    }

    @Test
    fun `final authoritative missing track falls back explicitly`() {
        val m = DrmSubtitleIntentMachine()
        m.onLoad(1L, null, emptyList())
        m.onSelectTrack(1, DrmSubtitleIntentMachine.TrackRef(1, null, "spa", "x"))
        // Authoritative text-bearing list without the intended track:
        // documented MPV-consistent fallback is Off, never an unrelated id.
        val tracks = listOf(ref(0, null, "en"), ref(2, null, "fr"))
        val action = m.onTracksAvailable(1L, tracks, autoId = null, currentSelectedId = null)
        assertTrue(action is DrmSubtitleIntentMachine.Action.DisableText)
        assertTrue(m.selection is SubtitleSelection.Off)
    }

    @Test
    fun `pending A survives empty then wins on arrival`() {
        val m = DrmSubtitleIntentMachine()
        m.onLoad(1L, null, listOf("https://x/a.vtt"))
        m.onTracksAvailable(1L, emptyList(), autoId = null, currentSelectedId = null, tracksAuthoritative = false)
        val tracks = listOf(ref(0, "https://x/a.vtt", "en"))
        val action = m.onTracksAvailable(1L, tracks, autoId = null, currentSelectedId = null)
        assertTrue(action is DrmSubtitleIntentMachine.Action.SelectTrack)
        assertEquals(0, (action as DrmSubtitleIntentMachine.Action.SelectTrack).id)
    }

    @Test
    fun `off after pending keeps off when A arrives`() {
        val m = DrmSubtitleIntentMachine()
        m.onLoad(1L, null, listOf("https://x/a.vtt"))
        m.onSelectTrack(null)
        val tracks = listOf(ref(0, "https://x/a.vtt", "en"))
        val action = m.onTracksAvailable(1L, tracks, autoId = null, currentSelectedId = null)
        assertTrue(action is DrmSubtitleIntentMachine.Action.DisableText)
        assertTrue(m.selection is SubtitleSelection.Off)
    }

    @Test
    fun `rebuild reorder keeps stable identity`() {
        val m = DrmSubtitleIntentMachine()
        m.onLoad(1L, null, emptyList())
        // User selects the Spanish track while enumeration is [en(0), spa(1)].
        m.onSelectTrack(1, DrmSubtitleIntentMachine.TrackRef(1, null, "spa", "x"))
        // Rebuild reassigns numeric ids: [spa(0), en(1)]. The recycled
        // number 1 (now English) must NOT be silently selected.
        val reordered = listOf(ref(0, null, "spa", "x"), ref(1, null, "en", "y"))
        val action = m.onTracksAvailable(1L, reordered, autoId = null, currentSelectedId = null)
        assertTrue(action is DrmSubtitleIntentMachine.Action.SelectTrack)
        assertEquals(0, (action as DrmSubtitleIntentMachine.Action.SelectTrack).id)
        assertEquals(0, (m.selection as SubtitleSelection.Track).id)
    }

    @Test
    fun `stale generation callback is ignored`() {
        val m = DrmSubtitleIntentMachine()
        m.onLoad(2L, "None", emptyList())
        val tracks = listOf(ref(0, "https://x/en.vtt", "en"))
        val action = m.onTracksAvailable(1L, tracks, autoId = 0, currentSelectedId = null, preferExplicit = true)
        assertTrue(action is DrmSubtitleIntentMachine.Action.NoOp)
        assertTrue(m.selection is SubtitleSelection.Off)
    }

    @Test
    fun `repeated identical evaluations stay quiet`() {
        // HOLD10 §5: steady states must not generate selector writes on
        // every callback — the engine additionally skips identical
        // consecutive actions, but the machine itself goes quiet.
        val m = DrmSubtitleIntentMachine()
        m.onLoad(1L, "spa", emptyList())
        val tracks = listOf(ref(0, null, "en"), ref(1, null, "spa"))
        m.onSelectTrack(0, tracks[0])
        repeat(3) {
            val action = m.onTracksAvailable(1L, tracks, autoId = 1, currentSelectedId = 0, preferExplicit = true)
            assertTrue(action is DrmSubtitleIntentMachine.Action.NoOp)
        }
        assertTrue(m.selection is SubtitleSelection.Track)
    }

    @Test
    fun `absent explicit preference selects nothing unrelated`() {
        // Preferred Spanish is absent: no fabrication, Media3 choice stands.
        assertEquals(null, resolveDrmAutoSubtitleId(listOf(ref(0, null, "en")), "spa"))
        assertEquals(null, resolveDrmAutoSubtitleId(listOf(ref(0, null, "en")), "Embedded:spa"))
        assertEquals(null, resolveDrmAutoSubtitleId(listOf(ref(0, null, "en")), "Embedded:7"))
    }
}
