package ani.dantotsu.media.anime.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MAX MERGE M4 correction (HOLD12): transitional unique-follow defers,
 * observed-enumeration verification replaces URL prediction, and selector
 * idempotence depends on observed state.
 *
 * Negative controls (fail on Correction 10 bytes, pass here): Correction 10
 * followed a unique language match even on transitional lists (a duplicate
 * still unparsed looks falsely unique); derived rebuild order from URL
 * lists instead of observed enumerations; and suppressed reapplication from
 * the last requested action alone.
 */
class DrmSubtitleIntentHold12Test {

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

    private fun pairs(
        refs: List<DrmSubtitleIntentMachine.TrackRef>
    ): List<Pair<Int, String>> = refs.map { it.id to it.stableKey() }

    @Test
    fun `unique follow on transitional list defers`() {
        // HOLD12-A repro: explicit spa choice; only an unrelated English
        // track enumerated so far (spa still unparsed, transitional).
        // Unique-in-partial-list must NOT retarget.
        val m = DrmSubtitleIntentMachine()
        m.onLoad(1L, null, emptyList())
        m.onSelectTrack(1, ref(1, null, "spa", "Spanish"))
        val partial = listOf(ref(0, null, "en", "English"))
        val action = m.onTracksAvailable(1L, partial, null, null, tracksAuthoritative = false)
        assertTrue(action is DrmSubtitleIntentMachine.Action.NoOp)
        assertTrue(m.selection is SubtitleSelection.Track)
        assertEquals(1, (m.selection as SubtitleSelection.Track).id)
        // Settled full list with the intended track absent: explicit Off.
        val settled = listOf(ref(0, null, "en", "English"), ref(1, null, "fr", "French"))
        val fallback = m.onTracksAvailable(1L, settled, null, null, tracksAuthoritative = true)
        assertTrue(fallback is DrmSubtitleIntentMachine.Action.DisableText)
        assertTrue(m.selection is SubtitleSelection.Off)
    }

    @Test
    fun `unique follow on settled list still migrates`() {
        val m = DrmSubtitleIntentMachine()
        m.onLoad(1L, null, emptyList())
        m.onSelectTrack(1, ref(1, null, "spa", "Spanish"))
        val reordered = listOf(
            ref(0, null, "spa", "Spanish"),
            ref(1, null, "en", "English")
        )
        val action = m.onTracksAvailable(1L, reordered, null, null, tracksAuthoritative = true)
        assertTrue(action is DrmSubtitleIntentMachine.Action.SelectTrack)
        assertEquals(0, (action as DrmSubtitleIntentMachine.Action.SelectTrack).id)
    }

    @Test
    fun `verified append growth restores trust`() {
        // HOLD12-B: the engine snapshots pre-rebuild pairs; growth clears
        // the pending verification and positional trust resumes.
        val m = DrmSubtitleIntentMachine()
        m.onLoad(1L, null, emptyList())
        m.onSelectTrack(1, ref(1, null, "en", "English", group = 0, index = 1))
        val before = listOf(
            ref(0, null, "en", "English", group = 0, index = 0),
            ref(1, null, "en", "English", group = 0, index = 1)
        )
        m.noteSourceRebuilt(pairs(before))
        val grown = before + ref(2, "https://x/fr.vtt", "fr", group = 1, index = 0)
        val action = m.onTracksAvailable(1L, grown, null, null, tracksAuthoritative = true)
        assertTrue(action is DrmSubtitleIntentMachine.Action.SelectTrack)
        assertEquals(1, (action as DrmSubtitleIntentMachine.Action.SelectTrack).id)
    }

    @Test
    fun `non-growth rebuild keeps ambiguous selection guarded`() {
        // Same-size permutation of identical keys is NOT growth: the
        // pending verification persists and positional trust stays void.
        val m = DrmSubtitleIntentMachine()
        m.onLoad(1L, null, emptyList())
        m.onSelectTrack(1, ref(1, null, "en", "English", group = 0, index = 1))
        m.noteSourceRebuilt(
            pairs(
                listOf(
                    ref(0, null, "en", "English", group = 0, index = 0),
                    ref(1, null, "en", "English", group = 0, index = 1)
                )
            )
        )
        // Identical strings, permuted occupants, authoritative: explicit
        // fallback, never a silent retarget to the other duplicate.
        val swapped = listOf(
            ref(0, null, "en", "English", group = 0, index = 1),
            ref(1, null, "en", "English", group = 0, index = 0)
        )
        val action = m.onTracksAvailable(1L, swapped, null, null, tracksAuthoritative = true)
        assertTrue(action is DrmSubtitleIntentMachine.Action.DisableText)
        assertTrue(m.selection is SubtitleSelection.Off)
    }

    @Test
    fun `skip write respects observed drift`() {
        // HOLD12-C: last=SelectTrack(1) but Media3 reports track 0 -> write.
        val drifted = ObservedSubtitleSelectorState(
            textDisabled = false,
            selectedSubtitleId = 0
        )
        assertFalse(
            shouldSkipSubtitleWrite(
                DrmSubtitleIntentMachine.Action.SelectTrack(1),
                DrmSubtitleIntentMachine.Action.SelectTrack(1),
                drifted,
                textOverrideActive = true
            )
        )
        // Last=SelectTrack(1) and Media3 reports 1 -> skip.
        val steady = ObservedSubtitleSelectorState(
            textDisabled = false,
            selectedSubtitleId = 1
        )
        assertTrue(
            shouldSkipSubtitleWrite(
                DrmSubtitleIntentMachine.Action.SelectTrack(1),
                DrmSubtitleIntentMachine.Action.SelectTrack(1),
                steady,
                textOverrideActive = true
            )
        )
        // Last=DisableText but a track is selected -> write.
        assertFalse(
            shouldSkipSubtitleWrite(
                DrmSubtitleIntentMachine.Action.DisableText,
                DrmSubtitleIntentMachine.Action.DisableText,
                steady,
                textOverrideActive = false
            )
        )
        // Last=DisableText and nothing selected -> skip.
        assertTrue(
            shouldSkipSubtitleWrite(
                DrmSubtitleIntentMachine.Action.DisableText,
                DrmSubtitleIntentMachine.Action.DisableText,
                ObservedSubtitleSelectorState(textDisabled = true, selectedSubtitleId = null),
                textOverrideActive = false
            )
        )
        // Different actions always write; NoOp never writes.
        assertFalse(
            shouldSkipSubtitleWrite(
                DrmSubtitleIntentMachine.Action.DisableText,
                DrmSubtitleIntentMachine.Action.SelectTrack(1),
                steady,
                textOverrideActive = false
            )
        )
        assertFalse(
            shouldSkipSubtitleWrite(
                null,
                DrmSubtitleIntentMachine.Action.SelectTrack(1),
                steady,
                textOverrideActive = false
            )
        )
        assertTrue(
            shouldSkipSubtitleWrite(
                DrmSubtitleIntentMachine.Action.SelectTrack(1),
                DrmSubtitleIntentMachine.Action.NoOp,
                steady,
                textOverrideActive = true
            )
        )
        // EnableTextAuto repeats skip only when enabled with no live override.
        assertTrue(
            shouldSkipSubtitleWrite(
                DrmSubtitleIntentMachine.Action.EnableTextAuto,
                DrmSubtitleIntentMachine.Action.EnableTextAuto,
                ObservedSubtitleSelectorState(textDisabled = false, selectedSubtitleId = null),
                textOverrideActive = false
            )
        )
        assertFalse(
            shouldSkipSubtitleWrite(
                DrmSubtitleIntentMachine.Action.EnableTextAuto,
                DrmSubtitleIntentMachine.Action.EnableTextAuto,
                ObservedSubtitleSelectorState(textDisabled = true, selectedSubtitleId = null),
                textOverrideActive = false
            )
        )
    }
}
