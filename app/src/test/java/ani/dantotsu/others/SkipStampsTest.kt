package ani.dantotsu.others

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MAX MERGE M5: skip-provider results are untrusted input. Ranges must be
 * validated (no reversed/negative/non-finite intervals) and ordered
 * deterministically; type labels must map to display names.
 */
class SkipStampsTest {

    private fun stamp(type: String, start: Double, end: Double) = AniSkip.Stamp(
        interval = AniSkip.AniSkipInterval(start, end),
        skipType = type,
        skipId = "test",
        episodeLength = 0.0
    )

    @Test
    fun `valid ranges pass through ordered by start`() {
        val out = listOf(
            stamp("ed", 1200.0, 1290.0),
            stamp("op", 0.0, 90.0),
            stamp("recap", 600.0, 660.0)
        ).sanitizedStamps()
        assertEquals(listOf("op", "recap", "ed"), out.map { it.skipType })
    }

    @Test
    fun `reversed range is dropped`() {
        assertTrue(listOf(stamp("op", 90.0, 10.0)).sanitizedStamps().isEmpty())
    }

    @Test
    fun `zero-length range is dropped`() {
        assertTrue(listOf(stamp("ed", 100.0, 100.0)).sanitizedStamps().isEmpty())
    }

    @Test
    fun `negative start is dropped`() {
        assertTrue(listOf(stamp("op", -5.0, 90.0)).sanitizedStamps().isEmpty())
    }

    @Test
    fun `non-finite bounds are dropped`() {
        assertTrue(
            listOf(
                stamp("op", Double.NaN, 90.0),
                stamp("ed", 0.0, Double.POSITIVE_INFINITY)
            ).sanitizedStamps().isEmpty()
        )
    }

    @Test
    fun `mixed valid and invalid keeps only valid in order`() {
        val out = listOf(
            stamp("ed", 1200.0, 1290.0),
            stamp("op", 90.0, 10.0),
            stamp("recap", 600.0, 660.0)
        ).sanitizedStamps()
        assertEquals(listOf("recap", "ed"), out.map { it.skipType })
    }

    @Test
    fun `skip types map to display names`() {
        with(AniSkip) {
            assertEquals("Opening", "op".getType())
            assertEquals("Ending", "ed".getType())
            assertEquals("Recap", "recap".getType())
            assertEquals("Mixed Ending", "mixed-ed".getType())
            assertEquals("Mixed Opening", "mixed-op".getType())
            assertEquals("preview", "preview".getType())
        }
    }
}
