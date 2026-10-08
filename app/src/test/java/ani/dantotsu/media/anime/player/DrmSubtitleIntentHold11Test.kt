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
        val before = listOf(
            ref(0, null, "en", "English", group = 0, index = 0),
            ref(1, null, "en", "English", group = 0, index = 1)
        )
        m.noteSourceRebuilt(before)
        val grown = before + ref(2, "https://x/fr.vtt", "fr", group = 1, index = 0)
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
        m.noteSourceRebuilt(
            listOf(
                    ref(0, null, "en", "English", group = 0, index = 0),
                    ref(1, null, "en", "English", group = 0, index = 1)
                )
        )
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
        m.noteSourceRebuilt(
            listOf(
                    ref(0, null, "en", "English", group = 0, index = 0),
                    ref(1, null, "spa", "Full", group = 0, index = 1)
                )
        )
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
        m.noteSourceRebuilt(
            listOf(
                    ref(0, null, "en", "English", group = 0, index = 0),
                    ref(1, null, "en", "English", group = 0, index = 1)
                )
        )
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
    fun `append-growth verification matrix`() {
        val m = DrmSubtitleIntentMachine()
        val k = { id: Int, s: String -> id to s }
        // Strict prefix-preserved superset only; an empty previous
        // snapshot proves nothing (HOLD13 acceptance 4) and never counts
        // as growth.
        assertTrue(m.isAppendGrowth(listOf(k(0, "a")), listOf(k(0, "a"), k(1, "b"))))
        assertFalse(m.isAppendGrowth(emptyList(), listOf(k(0, "a"))))
        assertFalse(m.isAppendGrowth(listOf(k(0, "a")), listOf(k(0, "a"))))
        assertFalse(m.isAppendGrowth(listOf(k(0, "a")), listOf(k(1, "b"), k(0, "a"))))
        assertFalse(m.isAppendGrowth(listOf(k(0, "a"), k(1, "b")), listOf(k(0, "a"))))
        assertTrue(m.isAppendGrowth(listOf(k(0, "a")), listOf(k(0, "a"), k(1, "b"), k(2, "c"))))
        // Same-size permutation of identical keys is NOT growth.
        assertFalse(m.isAppendGrowth(listOf(k(0, "x"), k(1, "x")), listOf(k(0, "x"), k(1, "x"))))
        // Renumbered identical keys are NOT growth.
        assertFalse(m.isAppendGrowth(listOf(k(0, "x"), k(1, "x")), listOf(k(0, "x"))))
    }
}
