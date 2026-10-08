package ani.dantotsu.media.anime.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MAX MERGE M4 correction (HOLD14): formerly ambiguous selections stay
 * guarded through every reassertion path, and identity is never recovered
 * by reparsing key strings.
 *
 * Negative controls (fail on Correction 12 bytes, pass here): Correction 12
 * reasserted same-index matches without consulting the pre-boundary
 * ambiguity (HOLD14-A repro selects wrong track A), and derived boundary
 * ambiguity by splitting stable-key strings on ':' (HOLD14-B repro with
 * "English: Commentary" labels computes boundaryAmbiguous=false).
 */
class DrmSubtitleIntentHold14Test {

    private fun ref(
        id: Int,
        externalId: String? = null,
        lang: String? = null,
        name: String? = null,
        group: Int = 0,
        index: Int = 0,
    ) = DrmSubtitleIntentMachine.TrackRef(
        id, externalId, lang, name, groupIndex = group, indexInGroup = index
    )

    @Test
    fun `formerly ambiguous same-index match does not reassert`() {
        // HOLD14-A repro: A(en) + B(en, selected at id 1); rebuild drops B
        // while A moves into the old B slot and French takes slot zero.
        // Index 1 shows an identical key string, but it denotes A.
        val m = DrmSubtitleIntentMachine()
        m.onLoad(1L, null, emptyList())
        val before = listOf(
            ref(0, null, "en", "English", group = 0, index = 0),
            ref(1, null, "en", "English", group = 0, index = 1)
        )
        m.onSelectTrack(1, before[1])
        m.noteSourceRebuilt(before)
        val after = listOf(
            ref(0, null, "fr", "French", group = 0, index = 0),
            ref(1, null, "en", "English", group = 0, index = 1)
        )
        val action = m.onTracksAvailable(1L, after, null, null, tracksAuthoritative = true)
        assertTrue(action is DrmSubtitleIntentMachine.Action.DisableText)
        assertTrue(m.selection is SubtitleSelection.Off)
    }

    @Test
    fun `formerly ambiguous same-index defers while transitional`() {
        val m = DrmSubtitleIntentMachine()
        m.onLoad(1L, null, emptyList())
        val before = listOf(
            ref(0, null, "en", "English", group = 0, index = 0),
            ref(1, null, "en", "English", group = 0, index = 1)
        )
        m.onSelectTrack(1, before[1])
        m.noteSourceRebuilt(before)
        val after = listOf(
            ref(0, null, "fr", "French", group = 0, index = 0),
            ref(1, null, "en", "English", group = 0, index = 1)
        )
        val action = m.onTracksAvailable(1L, after, null, null, tracksAuthoritative = false)
        assertTrue(action is DrmSubtitleIntentMachine.Action.NoOp)
        assertTrue(m.selection is SubtitleSelection.Track)
        assertEquals(1, (m.selection as SubtitleSelection.Track).id)
    }

    @Test
    fun `benign append with formerly ambiguous selection still preserves`() {
        // Control: growth-verified appends restore trust even when the
        // pre-boundary selection was ambiguous.
        val m = DrmSubtitleIntentMachine()
        m.onLoad(1L, null, emptyList())
        val before = listOf(
            ref(0, null, "en", "English", group = 0, index = 0),
            ref(1, null, "en", "English", group = 0, index = 1)
        )
        m.onSelectTrack(1, before[1])
        m.noteSourceRebuilt(before)
        val grown = before + ref(2, "https://x/fr.vtt", "fr", group = 1, index = 0)
        val action = m.onTracksAvailable(1L, grown, null, null, tracksAuthoritative = true)
        assertTrue(action is DrmSubtitleIntentMachine.Action.SelectTrack)
        assertEquals(1, (action as DrmSubtitleIntentMachine.Action.SelectTrack).id)
    }

    @Test
    fun `unambiguous reorder follow ignores boundary`() {
        // Control: genuinely unambiguous selections keep following across
        // rebuilds; the boundary guard only constrains ambiguous ones.
        val m = DrmSubtitleIntentMachine()
        m.onLoad(1L, null, emptyList())
        val before = listOf(
            ref(0, null, "en", "English", group = 0, index = 0),
            ref(1, null, "spa", "Spanish", group = 0, index = 1)
        )
        m.onSelectTrack(1, before[1])
        m.noteSourceRebuilt(before)
        val reordered = listOf(
            ref(0, null, "spa", "Spanish", group = 0, index = 0),
            ref(1, null, "en", "English", group = 0, index = 1)
        )
        val action = m.onTracksAvailable(1L, reordered, null, null, tracksAuthoritative = true)
        assertTrue(action is DrmSubtitleIntentMachine.Action.SelectTrack)
        assertEquals(0, (action as DrmSubtitleIntentMachine.Action.SelectTrack).id)
    }

    @Test
    fun `explicit fresh intent re-arms after boundary guard`() {
        // Control (e): after a guarded fallback, a newer explicit user
        // selection works normally.
        val m = DrmSubtitleIntentMachine()
        m.onLoad(1L, null, emptyList())
        val before = listOf(
            ref(0, null, "en", "English", group = 0, index = 0),
            ref(1, null, "en", "English", group = 0, index = 1)
        )
        m.onSelectTrack(1, before[1])
        m.noteSourceRebuilt(before)
        val swapped = listOf(
            ref(0, null, "en", "English", group = 0, index = 1),
            ref(1, null, "en", "English", group = 0, index = 0)
        )
        val fallback = m.onTracksAvailable(1L, swapped, null, null, tracksAuthoritative = true)
        assertTrue(fallback is DrmSubtitleIntentMachine.Action.DisableText)
        m.onSelectTrack(0, swapped[0])
        val action = m.onTracksAvailable(1L, swapped, null, null, tracksAuthoritative = true)
        assertTrue(action is DrmSubtitleIntentMachine.Action.SelectTrack)
        assertEquals(0, (action as DrmSubtitleIntentMachine.Action.SelectTrack).id)
    }
}
