package ani.dantotsu.media.anime.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MAX MERGE M4 correction (HOLD11): benign growth preserves explicit
 * selection; genuine ambiguous rebuilds never silently retarget; the
 * append-only adapter predicate is pinned.
 *
 * Negative controls (fail on Correction 9 bytes, pass here): Correction 9
 * voided positional trust on EVERY source rebuild, so an ambiguous
 * duplicate plus a benign live-attach fell to defer/Off; and its
 * firstOrNull key scan retargeted swapped identical duplicates.
 */
class DrmSubtitleIntentHold11Test {

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
    fun `ambiguous duplicates survive benign append rebuild`() {
        // Blocker A: two identical en/English embedded tracks, second
        // explicitly selected; an unrelated French external appends via
        // live attach (append-only => orderPreserved=true).
        val m = DrmSubtitleIntentMachine()
        m.onLoad(1L, null, emptyList())
        m.onSelectTrack(1, ref(1, null, "en", "English", group = 0, index = 1))
        m.noteSourceRebuilt(orderPreserved = true)
        val grown = listOf(
            ref(0, null, "en", "English", group = 0, index = 0),
            ref(1, null, "en", "English", group = 0, index = 1),
            ref(2, "https://x/fr.vtt", "fr", group = 1, index = 0)
        )
        val action = m.onTracksAvailable(1L, grown, null, null, tracksAuthoritative = true)
        assertTrue(action is DrmSubtitleIntentMachine.Action.SelectTrack)
        assertEquals(1, (action as DrmSubtitleIntentMachine.Action.SelectTrack).id)
        assertEquals(1, (m.selection as SubtitleSelection.Track).id)
    }

    @Test
    fun `swapped indistinguishable duplicates fail safe, never retarget`() {
        // Blocker B: identical duplicates swap positions across a
        // non-order-preserving rebuild. Index 1 still shows the same key
        // string, but it is provably a different physical track.
        val m = DrmSubtitleIntentMachine()
        m.onLoad(1L, null, emptyList())
        m.onSelectTrack(1, ref(1, null, "en", "English", group = 0, index = 1))
        m.noteSourceRebuilt(orderPreserved = false)
        // Same key strings, permuted occupants: index 1 now holds A.
        val swapped = listOf(
            ref(0, null, "en", "English", group = 0, index = 1),
            ref(1, null, "en", "English", group = 0, index = 0)
        )
        val action = m.onTracksAvailable(1L, swapped, null, null, tracksAuthoritative = true)
        // Explicit deterministic fallback (MPV-consistent), NOT SelectTrack.
        assertTrue(action is DrmSubtitleIntentMachine.Action.DisableText)
        assertTrue(m.selection is SubtitleSelection.Off)
    }

    @Test
    fun `unambiguous selection survives non-preserving rebuild by following`() {
        val m = DrmSubtitleIntentMachine()
        m.onLoad(1L, null, emptyList())
        m.onSelectTrack(1, ref(1, null, "spa", "Full", group = 0, index = 1))
        m.noteSourceRebuilt(orderPreserved = false)
        val reordered = listOf(
            ref(0, null, "spa", "Full", group = 0, index = 0),
            ref(1, null, "en", "English", group = 0, index = 1)
        )
        val action = m.onTracksAvailable(1L, reordered, null, null, tracksAuthoritative = true)
        assertTrue(action is DrmSubtitleIntentMachine.Action.SelectTrack)
        assertEquals(0, (action as DrmSubtitleIntentMachine.Action.SelectTrack).id)
    }

    @Test
    fun `steady ambiguous duplicates keep selection without rebuild`() {
        // Control: no rebuild boundary, identical enumeration — the
        // documented steady assumption, unchanged behavior.
        val m = DrmSubtitleIntentMachine()
        m.onLoad(1L, null, emptyList())
        m.onSelectTrack(1, ref(1, null, "en", "English", group = 0, index = 1))
        val refs = listOf(
            ref(0, null, "en", "English", group = 0, index = 0),
            ref(1, null, "en", "English", group = 0, index = 1)
        )
        val action = m.onTracksAvailable(1L, refs, null, null, tracksAuthoritative = true)
        assertTrue(action is DrmSubtitleIntentMachine.Action.SelectTrack)
        assertEquals(1, (action as DrmSubtitleIntentMachine.Action.SelectTrack).id)
    }

    @Test
    fun `fresh explicit selection re-arms trust after void`() {
        val m = DrmSubtitleIntentMachine()
        m.onLoad(1L, null, emptyList())
        m.onSelectTrack(1, ref(1, null, "en", "English", group = 0, index = 1))
        m.noteSourceRebuilt(orderPreserved = false)
        // User re-confirms on the current listing: fresh exact basis.
        val refs = listOf(
            ref(0, null, "en", "English", group = 0, index = 0),
            ref(1, null, "en", "English", group = 0, index = 1)
        )
        m.onSelectTrack(1, refs[1])
        val action = m.onTracksAvailable(1L, refs, null, null, tracksAuthoritative = true)
        assertTrue(action is DrmSubtitleIntentMachine.Action.SelectTrack)
        assertEquals(1, (action as DrmSubtitleIntentMachine.Action.SelectTrack).id)
    }

    @Test
    fun `append-only attach predicate`() {
        val a = ExternalSubtitle(url = "https://x/a.srt")
        val b = ExternalSubtitle(url = "https://x/b.srt")
        val c = ExternalSubtitle(url = "https://x/c.srt")
        assertTrue(DrmTransportPlan.isAppendOnlySubAttach(listOf(a), listOf(a, b)))
        assertTrue(DrmTransportPlan.isAppendOnlySubAttach(emptyList(), listOf(a)))
        assertFalse(DrmTransportPlan.isAppendOnlySubAttach(listOf(a), listOf(a)))
        assertFalse(DrmTransportPlan.isAppendOnlySubAttach(listOf(a), listOf(b, a)))
        assertFalse(DrmTransportPlan.isAppendOnlySubAttach(listOf(a, b), listOf(a)))
        assertFalse(DrmTransportPlan.isAppendOnlySubAttach(listOf(a), listOf(a, b, c)))
    }
}
