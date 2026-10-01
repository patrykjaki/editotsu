package ani.dantotsu.settings

import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.settings.saving.internal.Location
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * MAX MERGE M4C (upstream aa98472a): reader-settings surface contracts.
 * Covers only pure-JVM seams — enum ordinal mapping used by persisted
 * settings, and the new PrefName reader defaults. Android-coupled reader
 * behavior (adapters, activity, dialogs, OCR) is not host-testable and is
 * covered by compile + device validation instead. Never instantiates
 * [CurrentReaderSettings] (its constructor reads PrefManager).
 */
class ReaderPrefsTest {

    @Test
    fun `image quality ordinals map and unknown falls back to null`() {
        assertEquals(CurrentReaderSettings.ImageQuality.FAST, CurrentReaderSettings.ImageQuality[0])
        assertEquals(CurrentReaderSettings.ImageQuality.BALANCED, CurrentReaderSettings.ImageQuality[1])
        assertEquals(CurrentReaderSettings.ImageQuality.LANCZOS, CurrentReaderSettings.ImageQuality[2])
        assertNull(CurrentReaderSettings.ImageQuality[3])
        assertNull(CurrentReaderSettings.ImageQuality[-1])
        assertNull(CurrentReaderSettings.ImageQuality[99])
    }

    @Test
    fun `layout ordinals map and unknown is null`() {
        assertEquals(CurrentReaderSettings.Layouts.PAGED, CurrentReaderSettings.Layouts[0])
        assertEquals(CurrentReaderSettings.Layouts.CONTINUOUS_PAGED, CurrentReaderSettings.Layouts[1])
        assertEquals(CurrentReaderSettings.Layouts.CONTINUOUS, CurrentReaderSettings.Layouts[2])
        assertNull(CurrentReaderSettings.Layouts[3])
        assertNull(CurrentReaderSettings.Layouts[-1])
    }

    @Test
    fun `dual page mode ordinals map and unknown is null`() {
        assertEquals(CurrentReaderSettings.DualPageModes.No, CurrentReaderSettings.DualPageModes[0])
        assertEquals(CurrentReaderSettings.DualPageModes.Automatic, CurrentReaderSettings.DualPageModes[1])
        assertEquals(CurrentReaderSettings.DualPageModes.Force, CurrentReaderSettings.DualPageModes[2])
        assertNull(CurrentReaderSettings.DualPageModes[3])
    }

    @Test
    fun `direction ordinals map and unknown is null`() {
        assertEquals(CurrentReaderSettings.Directions.TOP_TO_BOTTOM, CurrentReaderSettings.Directions[0])
        assertEquals(CurrentReaderSettings.Directions.RIGHT_TO_LEFT, CurrentReaderSettings.Directions[1])
        assertEquals(CurrentReaderSettings.Directions.BOTTOM_TO_TOP, CurrentReaderSettings.Directions[2])
        assertEquals(CurrentReaderSettings.Directions.LEFT_TO_RIGHT, CurrentReaderSettings.Directions[3])
        assertNull(CurrentReaderSettings.Directions[4])
        assertNull(CurrentReaderSettings.Directions[-1])
    }

    @Test
    fun `new reader prefs live in Reader location with upstream defaults`() {
        val expected = mapOf(
            PrefName.OneHandZoom to true,
            PrefName.AutoScroll to false,
            PrefName.AutoScrollSpeed to 3.0f,
            PrefName.ReaderBackgroundColor to 0,
            PrefName.DefaultRotation to 0,
            PrefName.ContinuousSidePadding to 0,
            PrefName.EInkFlashPageChange to false,
            PrefName.ImageQuality to 0,
            PrefName.PagePreloadAmount to 3,
            PrefName.AlwaysShowChapterTransition to false,
        )
        assertEquals(10, expected.size)
        for ((pref, default) in expected) {
            assertEquals("$pref location", Location.Reader, pref.data.prefLocation)
            assertEquals("$pref default", default, pref.data.default)
        }
    }
}
