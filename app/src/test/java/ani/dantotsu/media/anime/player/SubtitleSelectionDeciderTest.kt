package ani.dantotsu.media.anime.player

import org.junit.Assert.assertEquals
import org.junit.Test

class SubtitleSelectionDeciderTest {

    // --- Unset auto-selection allowed ---

    @Test
    fun `Unset with no selection and no pending allows auto selection`() {
        val d = SubtitleSelectionDecider.decide(
            current = SubtitleSelection.Unset,
            currentSelectedSubId = null,
            pendingTrackId = null,
            autoTrackId = 5,
            explicitTrackExists = false
        )
        assertEquals(5, d.sid)
        assertEquals(SubtitleSelection.Unset, d.selection)
    }

    @Test
    fun `Unset does not auto-select when something is already selected`() {
        val d = SubtitleSelectionDecider.decide(
            current = SubtitleSelection.Unset,
            currentSelectedSubId = 7,
            pendingTrackId = null,
            autoTrackId = 5,
            explicitTrackExists = false
        )
        assertEquals(7, d.sid)
        assertEquals(SubtitleSelection.Unset, d.selection)
    }

    @Test
    fun `Unset defers to a pending explicit selection`() {
        val d = SubtitleSelectionDecider.decide(
            current = SubtitleSelection.Unset,
            currentSelectedSubId = null,
            pendingTrackId = 3,
            autoTrackId = 5,
            explicitTrackExists = false
        )
        // No override while pending; FILE_LOADED applies it.
        assertEquals(null, d.sid)
        assertEquals(SubtitleSelection.Unset, d.selection)
    }

    // --- Explicit Off survives ---

    @Test
    fun `Off survives FILE_LOADED`() {
        val d = SubtitleSelectionDecider.decide(
            current = SubtitleSelection.Off,
            currentSelectedSubId = 9,
            pendingTrackId = null,
            autoTrackId = 5,
            explicitTrackExists = false
        )
        assertEquals(null, d.sid)
        assertEquals(SubtitleSelection.Off, d.selection)
    }

    @Test
    fun `Off survives track refresh`() {
        val d = SubtitleSelectionDecider.decide(
            current = SubtitleSelection.Off,
            currentSelectedSubId = null,
            pendingTrackId = null,
            autoTrackId = 5,
            explicitTrackExists = false
        )
        assertEquals(null, d.sid)
        assertEquals(SubtitleSelection.Off, d.selection)
    }

    @Test
    fun `Off beats automatic selection even when an auto track is available`() {
        // Models the race: auto was computed, but the user explicitly chose Off.
        val d = SubtitleSelectionDecider.decide(
            current = SubtitleSelection.Off,
            currentSelectedSubId = null,
            pendingTrackId = null,
            autoTrackId = 11,
            explicitTrackExists = false
        )
        assertEquals(null, d.sid)
        assertEquals(SubtitleSelection.Off, d.selection)
    }

    // --- Explicit Track survives reload ---

    @Test
    fun `Track survives reload when track still exists`() {
        val d = SubtitleSelectionDecider.decide(
            current = SubtitleSelection.Track(5),
            currentSelectedSubId = null,
            pendingTrackId = null,
            autoTrackId = 2,
            explicitTrackExists = true
        )
        assertEquals(5, d.sid)
        assertEquals(SubtitleSelection.Track(5), d.selection)
    }

    @Test
    fun `Track is re-asserted when player dropped the selection`() {
        val d = SubtitleSelectionDecider.decide(
            current = SubtitleSelection.Track(5),
            currentSelectedSubId = 8,
            pendingTrackId = null,
            autoTrackId = 2,
            explicitTrackExists = true
        )
        assertEquals(5, d.sid)
        assertEquals(SubtitleSelection.Track(5), d.selection)
    }

    // --- Missing explicit track fallback ---

    @Test
    fun `Missing explicit track falls back to Off deterministically`() {
        val d = SubtitleSelectionDecider.decide(
            current = SubtitleSelection.Track(5),
            currentSelectedSubId = null,
            pendingTrackId = null,
            autoTrackId = 2,
            explicitTrackExists = false
        )
        assertEquals(null, d.sid)
        assertEquals(SubtitleSelection.Off, d.selection)
    }

    @Test
    fun `Missing explicit track never reverts to Unset`() {
        val d = SubtitleSelectionDecider.decide(
            current = SubtitleSelection.Track(5),
            currentSelectedSubId = null,
            pendingTrackId = null,
            autoTrackId = 2,
            explicitTrackExists = false
        )
        assertEquals(false, d.selection is SubtitleSelection.Unset)
    }

    // --- Transient / incomplete track list must defer, not downgrade ---

    @Test
    fun `Track missing on non-authoritative list is deferred (not Off)`() {
        val d = SubtitleSelectionDecider.decide(
            current = SubtitleSelection.Track(5),
            currentSelectedSubId = null,
            pendingTrackId = null,
            autoTrackId = 2,
            explicitTrackExists = false,
            tracksAuthoritative = false
        )
        // Deferred: state preserved, no change to the currently selected id.
        assertEquals(SubtitleSelection.Track(5), d.selection)
        assertEquals(null, d.sid)
    }

    @Test
    fun `Track missing on authoritative list falls back to Off`() {
        val d = SubtitleSelectionDecider.decide(
            current = SubtitleSelection.Track(5),
            currentSelectedSubId = null,
            pendingTrackId = null,
            autoTrackId = 2,
            explicitTrackExists = false,
            tracksAuthoritative = true
        )
        assertEquals(SubtitleSelection.Off, d.selection)
        assertEquals(null, d.sid)
    }
}
