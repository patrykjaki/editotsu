package ani.dantotsu.media.anime.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MAX MERGE M4 correction (HOLD15): successive source boundaries must not
 * erase an unresolved ambiguity witness.
 *
 * Negative controls (fail on Correction 13 bytes, pass here): Correction 13
 * overwrote the pending snapshot and recomputed ambiguity on every note,
 * so a second rebuild fired during a transitional partial listing erased
 * the trustworthy pre-boundary state and the next settled evaluation
 * selected the surviving sibling.
 */
class DrmSubtitleIntentHold15Test {

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

    private fun ambiguousPair() = listOf(
        ref(0, null, "en", "English", group = 0, index = 0),
        ref(1, null, "en", "English", group = 0, index = 1)
    )

    @Test
    fun `successive rebuilds preserve unresolved ambiguity to Off`() {
        // Handoff variant 1: B selected from [A, B]; transitional [A];
        // second rebuild while partial; settled [A] must NOT select A.
        val m = DrmSubtitleIntentMachine()
        m.onLoad(1L, null, emptyList())
        m.onSelectTrack(1, ref(1, null, "en", "English", group = 0, index = 1))
        m.noteSourceRebuilt(ambiguousPair())
        val partial = listOf(ref(0, null, "en", "English", group = 0, index = 0))
        val transitional = m.onTracksAvailable(1L, partial, null, null, tracksAuthoritative = false)
        assertTrue(transitional is DrmSubtitleIntentMachine.Action.NoOp)
        assertTrue(m.selection is SubtitleSelection.Track)
        m.noteSourceRebuilt(partial)
        val settled = m.onTracksAvailable(1L, partial, null, null, tracksAuthoritative = true)
        assertTrue(settled is DrmSubtitleIntentMachine.Action.DisableText)
        assertTrue(m.selection is SubtitleSelection.Off)
    }

    @Test
    fun `successive rebuilds preserve ambiguity with appended external`() {
        // Handoff variant 2: same, but a French external appends; the
        // appended URI must not launder the erased ambiguity either.
        val m = DrmSubtitleIntentMachine()
        m.onLoad(1L, null, emptyList())
        m.onSelectTrack(1, ref(1, null, "en", "English", group = 0, index = 1))
        m.noteSourceRebuilt(ambiguousPair())
        val partial = listOf(ref(0, null, "en", "English", group = 0, index = 0))
        m.onTracksAvailable(1L, partial, null, null, tracksAuthoritative = false)
        m.noteSourceRebuilt(partial)
        val grown = listOf(
            ref(0, null, "en", "English", group = 0, index = 0),
            ref(1, "https://x/fr.vtt", "fr", group = 1, index = 0)
        )
        val action = m.onTracksAvailable(1L, grown, null, null, tracksAuthoritative = true)
        assertTrue(action is DrmSubtitleIntentMachine.Action.DisableText)
        assertTrue(m.selection is SubtitleSelection.Off)
    }

    @Test
    fun `later partial notes cannot rebase verification`() {
        // Growth is verified against the oldest unverified boundary, so a
        // later partial note cannot rebase verification onto a subset: the
        // legitimate append below still verifies against the ORIGINAL
        // snapshot and restores trust.
        val m = DrmSubtitleIntentMachine()
        m.onLoad(1L, null, emptyList())
        m.onSelectTrack(1, ref(1, null, "en", "English", group = 0, index = 1))
        m.noteSourceRebuilt(ambiguousPair())
        m.noteSourceRebuilt(listOf(ref(0, null, "en", "English", group = 0, index = 0)))
        val grown = listOf(
            ref(0, null, "en", "English", group = 0, index = 0),
            ref(1, null, "en", "English", group = 0, index = 1),
            ref(2, "https://x/fr.vtt", "fr", group = 1, index = 0)
        )
        val action = m.onTracksAvailable(1L, grown, null, null, tracksAuthoritative = true)
        assertTrue(action is DrmSubtitleIntentMachine.Action.SelectTrack)
        assertEquals(1, (action as DrmSubtitleIntentMachine.Action.SelectTrack).id)
    }
}
