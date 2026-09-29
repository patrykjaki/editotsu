package ani.dantotsu.others

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MAX MERGE M5 correction (Blocker 1): the public CSV dump holds several
 * provider rows per anime/episode/type. Selection must be deterministic:
 * closest episode length wins, votes break ties, negatives never beat a
 * valid candidate, one stamp per type.
 */
class CsvSelectionTest {

    private fun row(
        provider: String,
        type: String,
        votes: Double,
        start: Double,
        end: Double,
        length: Double
    ) = AniskipCsvFallback.CsvRow(40938, 5, provider, type, votes, start, end, length)

    @Test
    fun `real duplicate shape picks closest duration`() {
        // anime_id=40938 episode=5 type=ed, as seen in the public dump.
        val rows = listOf(
            row("Crunchyroll", "ed", 12.0, 1283.0, 1373.25, 1420.146),
            row("miruro", "ed", 8.0, 1283.0, 1372.0, 1415.0)
        )
        val out = AniskipCsvFallback.selectCsvStamps(rows, 1415L, 40938, 5)
        assertEquals(1, out.size)
        assertEquals("ed", out.first().skipType)
        assertEquals(1372.0, out.first().interval.endTime, 0.001)
        assertTrue(out.first().skipId.contains("miruro"))
    }

    @Test
    fun `equal duration prefers stronger votes`() {
        val rows = listOf(
            row("b", "op", 2.0, 0.0, 90.0, 1400.0),
            row("a", "op", 30.0, 0.0, 90.0, 1400.0)
        )
        val out = AniskipCsvFallback.selectCsvStamps(rows, 1400L, 1, 1)
        assertEquals("a", out.first().skipId.split("_")[1])
    }

    @Test
    fun `negative votes never beat a valid candidate`() {
        val rows = listOf(
            row("neg", "op", -5.0, 0.0, 90.0, 1400.0),
            row("pos", "op", 1.0, 0.0, 89.0, 1200.0)
        )
        val out = AniskipCsvFallback.selectCsvStamps(rows, 1400L, 1, 1)
        assertTrue(out.first().skipId.contains("pos"))
    }

    @Test
    fun `one stamp per type while op and ed both survive`() {
        val rows = listOf(
            row("a", "op", 5.0, 0.0, 90.0, 1400.0),
            row("b", "op", 9.0, 0.0, 91.0, 1400.0),
            row("a", "ed", 5.0, 1200.0, 1300.0, 1400.0)
        )
        val out = AniskipCsvFallback.selectCsvStamps(rows, 1400L, 1, 1)
        assertEquals(2, out.size)
        assertEquals(listOf("op", "ed"), out.map { it.skipType })
    }

    @Test
    fun `invalid ranges discarded before ranking`() {
        val rows = listOf(
            row("bad", "op", 99.0, 90.0, 10.0, 1400.0),
            row("neg", "op", 99.0, -5.0, 90.0, 1400.0)
        )
        assertTrue(AniskipCsvFallback.selectCsvStamps(rows, 1400L, 1, 1).isEmpty())
    }

    @Test
    fun `unknown duration falls back to votes deterministically`() {
        val rows = listOf(
            row("b", "recap", 2.0, 600.0, 660.0, 0.0),
            row("a", "recap", 7.0, 600.0, 660.0, 0.0)
        )
        val out = AniskipCsvFallback.selectCsvStamps(rows, null, 1, 1)
        assertTrue(out.first().skipId.contains("a"))
    }

    @Test
    fun `result independent of input row order`() {
        val rows = listOf(
            row("miruro", "ed", 8.0, 1283.0, 1372.0, 1415.0),
            row("Crunchyroll", "ed", 12.0, 1283.0, 1373.25, 1420.146),
            row("x", "op", 3.0, 0.0, 90.0, 1410.0)
        )
        val forward = AniskipCsvFallback.selectCsvStamps(rows, 1415L, 1, 1)
        val backward = AniskipCsvFallback.selectCsvStamps(rows.reversed(), 1415L, 1, 1)
        assertEquals(forward.map { it.skipId }, backward.map { it.skipId })
        assertEquals(forward.map { it.interval.startTime }, backward.map { it.interval.startTime })
    }

    @Test
    fun `csv line parser keeps provider votes length`() {
        val row = AniskipCsvFallback.parseCsvRow("40938,5,miruro,ed,8,1283,1372,1415,2024-01-01")
        assertEquals("miruro", row?.provider)
        assertEquals(8.0, row?.votes ?: -1.0, 0.001)
        assertEquals(1415.0, row?.episodeLength ?: -1.0, 0.001)
        assertNull(AniskipCsvFallback.parseCsvRow("40938,5,miruro,ed,8,1283"))
        assertNull(AniskipCsvFallback.parseCsvRow("40938,5,miruro,badtype,8,1283,1372,1415,x"))
    }
}
