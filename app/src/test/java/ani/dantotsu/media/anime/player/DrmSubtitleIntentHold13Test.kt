package ani.dantotsu.media.anime.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MAX MERGE M4 correction (HOLD13): formerly ambiguous selections never
 * resolve through a survivor, and empty snapshots confer no trust.
 *
 * Negative controls (fail on Correction 11 bytes, pass here): Correction 11
 * followed a unique language match even when the selection had been
 * ambiguous at the rebuild boundary (a dropped sibling made the survivor
 * look "confidently unique"), silently retargeting to a different track.
 */
class DrmSubtitleIntentHold13Test {

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
    fun `dropped duplicate never resolves through survivor`() {
        // HOLD13-A: A(en) + B(en, selected); rebuild drops B, A survives
        // (+French external); authoritative. A is a different subtitle.
        val m = DrmSubtitleIntentMachine()
        m.onLoad(1L, null, emptyList())
        val before = listOf(
            ref(0, null, "en", "English", group = 0, index = 0),
            ref(1, null, "en", "English", group = 0, index = 1)
        )
        m.onSelectTrack(1, before[1])
        m.noteSourceRebuilt(pairs(before))
        val after = listOf(
            ref(0, null, "en", "English", group = 0, index = 0),
            ref(1, "https://x/fr.vtt", "fr", group = 1, index = 0)
        )
        val action = m.onTracksAvailable(1L, after, null, null, tracksAuthoritative = true)
        assertTrue(action is DrmSubtitleIntentMachine.Action.DisableText)
        assertTrue(m.selection is SubtitleSelection.Off)
    }

    @Test
    fun `dropped duplicate defers while transitional`() {
        val m = DrmSubtitleIntentMachine()
        m.onLoad(1L, null, emptyList())
        val before = listOf(
            ref(0, null, "en", "English", group = 0, index = 0),
            ref(1, null, "en", "English", group = 0, index = 1)
        )
        m.onSelectTrack(1, before[1])
        m.noteSourceRebuilt(pairs(before))
        val after = listOf(
            ref(0, null, "en", "English", group = 0, index = 0),
            ref(1, "https://x/fr.vtt", "fr", group = 1, index = 0)
        )
        val action = m.onTracksAvailable(1L, after, null, null, tracksAuthoritative = false)
        assertTrue(action is DrmSubtitleIntentMachine.Action.NoOp)
        assertTrue(m.selection is SubtitleSelection.Track)
        assertEquals(1, (m.selection as SubtitleSelection.Track).id)
    }

    @Test
    fun `swap plus append falls back explicitly, never retargets`() {
        // HOLD13-B: identical duplicates swap positions AND a French
        // external appends. Growth prefix differs, so no trust is restored;
        // the identical strings cannot prove continuity either way.
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
        val swappedGrown = listOf(
            ref(0, null, "en", "English", group = 0, index = 1),
            ref(1, null, "en", "English", group = 0, index = 0),
            ref(2, "https://x/fr.vtt", "fr", group = 1, index = 0)
        )
        val action = m.onTracksAvailable(1L, swappedGrown, null, null, tracksAuthoritative = true)
        assertTrue(action is DrmSubtitleIntentMachine.Action.DisableText)
        assertTrue(m.selection is SubtitleSelection.Off)
    }

    @Test
    fun `empty snapshot confers no identity trust`() {
        // HOLD13 acceptance 4: a note with no pre-rebuild tracks cannot
        // verify anything; settled numeric legacy still applies, but no
        // identity is claimed through growth.
        val m = DrmSubtitleIntentMachine()
        m.onLoad(1L, null, emptyList())
        m.onSelectTrack(1)
        m.noteSourceRebuilt(emptyList())
        val refs = listOf(ref(0, null, "en"), ref(1, null, "spa"))
        val settled = m.onTracksAvailable(1L, refs, null, null, tracksAuthoritative = true)
        assertTrue(settled is DrmSubtitleIntentMachine.Action.SelectTrack)
        assertEquals(1, (settled as DrmSubtitleIntentMachine.Action.SelectTrack).id)
        val transitional = m.onTracksAvailable(1L, refs, null, null, tracksAuthoritative = false)
        assertTrue(transitional is DrmSubtitleIntentMachine.Action.NoOp)
    }

    @Test
    fun `stable key language recovery is explicit`() {
        val m = DrmSubtitleIntentMachine()
        assertEquals(
            "emb:en|English",
            m.langKeyOfStableKey("emb:g0:i1:en|English")
        )
        assertEquals(
            "https://x/a.vtt",
            m.langKeyOfStableKey("https://x/a.vtt")
        )
    }

    @Test
    fun `unambiguous boundary still follows confidently`() {
        // Control: genuinely unambiguous selections keep working through
        // rebuilds, with or without a note.
        val m = DrmSubtitleIntentMachine()
        m.onLoad(1L, null, emptyList())
        m.onSelectTrack(1, ref(1, null, "spa", "Full", group = 0, index = 1))
        m.noteSourceRebuilt(
            pairs(
                listOf(
                    ref(0, null, "en", "English", group = 0, index = 0),
                    ref(1, null, "spa", "Full", group = 0, index = 1)
                )
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
}
