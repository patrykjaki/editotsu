package ani.dantotsu.media.anime.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MAX MERGE M4 correction (HOLD9): incomplete-nonempty authority and
 * discriminating track identity.
 *
 * Negative controls (fail on Correction 7 bytes, pass here): Correction 7
 * passed bare `refs.isNotEmpty()` as authority — the partial-enumeration
 * case below produced `DisableText`+`Off`; and `firstOrNull` on
 * `language|name` keys retargeted duplicate embedded tracks. The reviewer
 * executed both counterexamples against Correction 7 sources.
 */
class DrmSubtitleIntentHold9Test {

    private fun ref(
        id: Int,
        externalId: String? = null,
        lang: String? = null,
        name: String? = null,
        group: Int = 0,
        index: Int = 0,
    ) = DrmSubtitleIntentMachine.TrackRef(id, externalId, lang, name, groupIndex = group, indexInGroup = index)

    private fun authority(
        refs: List<DrmSubtitleIntentMachine.TrackRef>,
        attached: Set<String>,
    ) = computeTracksAuthoritative(refs, attached)

    @Test
    fun `partial nonempty enumeration defers explicit track`() {
        // Reviewer B1 counterexample: explicit Spanish choice, only English
        // enumerated so far, player mid-prepare (unsettled). The adapter
        // reports tracksAuthoritative=false for anything but a settled,
        // text-bearing enumeration with all attached externals present.
        val m = DrmSubtitleIntentMachine()
        m.onLoad(1L, null, emptyList())
        m.onSelectTrack(
            1,
            DrmSubtitleIntentMachine.TrackRef(1, null, "spa", "Spanish")
        )
        val partial = listOf(ref(0, null, "en", "English"))
        val action = m.onTracksAvailable(1L, partial, null, 0, tracksAuthoritative = false)
        assertTrue(action is DrmSubtitleIntentMachine.Action.NoOp)
        assertTrue(m.selection is SubtitleSelection.Track)
        // Settled full enumeration arrives: original selection restored.
        val full = listOf(ref(0, null, "en", "English"), ref(1, null, "spa", "Spanish"))
        val settled = computeTracksAuthoritative(full, emptySet())
        assertTrue(settled)
        val restored = m.onTracksAvailable(1L, full, null, 0, tracksAuthoritative = settled)
        assertTrue(restored is DrmSubtitleIntentMachine.Action.SelectTrack)
        assertEquals(1, (restored as DrmSubtitleIntentMachine.Action.SelectTrack).id)
    }

    @Test
    fun `attached external missing from enumeration defers`() {
        // The intended external is attached (expected) but not yet parsed:
        // unsettled by definition, whatever the text content.
        val m = DrmSubtitleIntentMachine()
        m.onLoad(1L, null, emptyList())
        m.onLiveExternal(
            "https://x/spa.vtt", select = true, explicit = true, knownTracks = emptyList()
        )
        val partial = listOf(ref(0, null, "en", "English"))
        assertTrue(!authority(partial, attached = setOf("https://x/spa.vtt")))
        val deferred = m.onTracksAvailable(1L, partial, null, 0, tracksAuthoritative = false)
        assertTrue(deferred is DrmSubtitleIntentMachine.Action.NoOp)
        // Settled full enumeration adopts the pending explicit external.
        val full = listOf(ref(0, null, "en", "English"), ref(1, "https://x/spa.vtt", "spa"))
        assertTrue(authority(full, attached = setOf("https://x/spa.vtt")))
        val adopted = m.onTracksAvailable(1L, full, null, 0, tracksAuthoritative = true)
        assertTrue(adopted is DrmSubtitleIntentMachine.Action.SelectTrack)
        assertEquals(1, (adopted as DrmSubtitleIntentMachine.Action.SelectTrack).id)
    }

    @Test
    fun `authority requires attached externals present`() {
        val refs = listOf(ref(0, null, "en", "English"))
        assertTrue(!authority(refs, setOf("https://x/spa.vtt")))
        assertTrue(authority(refs, emptySet()))
        assertTrue(!authority(emptyList(), emptySet()))
        assertTrue(
            authority(
                listOf(ref(0, "https://x/spa.vtt", "spa")),
                setOf("https://x/spa.vtt")
            )
        )
    }

    @Test
    fun `duplicate embedded display key does not retarget`() {
        // Reviewer B2 counterexample: two distinct embedded English tracks.
        // The user explicitly chose the SECOND; the first must never win,
        // with or without a reorder.
        val m = DrmSubtitleIntentMachine()
        m.onLoad(1L, null, emptyList())
        m.onSelectTrack(
            1,
            ref(1, null, "en", "English", group = 0, index = 1)
        )
        val refs = listOf(
            ref(0, null, "en", "English", group = 0, index = 0),
            ref(1, null, "en", "English", group = 0, index = 1)
        )
        val action = m.onTracksAvailable(1L, refs, null, null)
        assertTrue(action is DrmSubtitleIntentMachine.Action.SelectTrack)
        assertEquals(1, (action as DrmSubtitleIntentMachine.Action.SelectTrack).id)
        assertEquals(1, (m.selection as SubtitleSelection.Track).id)
    }

    @Test
    fun `duplicate embedded with reorder follows confidently`() {
        val m = DrmSubtitleIntentMachine()
        m.onLoad(1L, null, emptyList())
        // Spanish full-dialogue vs Spanish signs share nothing but the
        // assertions below use unique names; duplicates covered above.
        m.onSelectTrack(
            1,
            ref(1, null, "spa", "Full", group = 0, index = 1)
        )
        // Rebuild swaps positions but keeps group membership.
        val reordered = listOf(
            ref(0, null, "spa", "Full", group = 0, index = 0),
            ref(1, null, "en", "English", group = 0, index = 1)
        )
        val action = m.onTracksAvailable(1L, reordered, null, null)
        assertTrue(action is DrmSubtitleIntentMachine.Action.SelectTrack)
        // Unique language identity followed to its new index.
        assertEquals(0, (action as DrmSubtitleIntentMachine.Action.SelectTrack).id)
    }

    @Test
    fun `ambiguous rebuild defers instead of switching`() {
        val m = DrmSubtitleIntentMachine()
        m.onLoad(1L, null, emptyList())
        m.onSelectTrack(
            1,
            ref(1, null, "en", "English", group = 0, index = 1)
        )
        // Both candidates share language AND name; neither may win silently.
        // A transitional list defers with intent preserved.
        val ambiguous = listOf(
            ref(0, null, "en", "English", group = 1, index = 0),
            ref(1, null, "en", "English", group = 1, index = 1)
        )
        val deferred = m.onTracksAvailable(1L, ambiguous, null, null, tracksAuthoritative = false)
        assertTrue(deferred is DrmSubtitleIntentMachine.Action.NoOp)
        assertTrue(m.selection is SubtitleSelection.Track)
        // A genuinely authoritative yet unresolvable list falls back to
        // explicit Off (documented MPV-consistent fallback) — still never
        // a silent switch to one of the ambiguous tracks.
        val fallback = m.onTracksAvailable(1L, ambiguous, null, null, tracksAuthoritative = true)
        assertTrue(fallback is DrmSubtitleIntentMachine.Action.DisableText)
        assertTrue(m.selection is SubtitleSelection.Off)
    }

    @Test
    fun `external URI identity is exact across reorder`() {
        val m = DrmSubtitleIntentMachine()
        m.onLoad(1L, null, emptyList())
        m.onLiveExternal(
            "https://x/b.vtt", select = true, explicit = true,
            knownTracks = listOf(ref(1, "https://x/b.vtt", "fr"))
        )
        // Rebuild moves it; the URI still resolves exactly.
        val reordered = listOf(
            ref(0, "https://x/b.vtt", "fr"),
            ref(1, null, "en", "English")
        )
        val action = m.onTracksAvailable(1L, reordered, null, null)
        assertTrue(action is DrmSubtitleIntentMachine.Action.SelectTrack)
        assertEquals(0, (action as DrmSubtitleIntentMachine.Action.SelectTrack).id)
    }
}
