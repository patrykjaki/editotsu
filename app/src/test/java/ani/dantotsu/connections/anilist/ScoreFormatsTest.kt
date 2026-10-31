package ani.dantotsu.connections.anilist

import ani.dantotsu.connections.anilist.api.ScoreFormat
import ani.dantotsu.connections.anilist.api.ScoreFormat.POINT_10
import ani.dantotsu.connections.anilist.api.ScoreFormat.POINT_100
import ani.dantotsu.connections.anilist.api.ScoreFormat.POINT_10_DECIMAL
import ani.dantotsu.connections.anilist.api.ScoreFormat.POINT_3
import ani.dantotsu.connections.anilist.api.ScoreFormat.POINT_5
import org.junit.Assert.assertEquals
import org.junit.Test

class ScoreFormatsTest {

    private fun read(raw: Int, f: ScoreFormat) = ScoreFormats.fromRaw(raw, f)

    @Test
    fun `parse falls back to POINT_100`() {
        assertEquals(POINT_5, ScoreFormats.parse("POINT_5"))
        assertEquals(POINT_100, ScoreFormats.parse(null))
        assertEquals(POINT_100, ScoreFormats.parse("null"))
        assertEquals(POINT_100, ScoreFormats.parse("BOGUS"))
    }

    @Test
    fun `zero is unscored in every format`() {
        ScoreFormat.entries.forEach {
            assertEquals(0.0, read(0, it), 0.0)
            assertEquals(0, ScoreFormats.toRaw(0.0, it))
            assertEquals("", ScoreFormats.display(0, it))
        }
        assertEquals(0, ScoreFormats.toMal(0))
    }

    @Test
    fun `POINT_100 is identity and clamps`() {
        assertEquals(77.0, read(77, POINT_100), 0.0)
        assertEquals(77, ScoreFormats.toRaw(77.0, POINT_100))
        assertEquals(100, ScoreFormats.toRaw(150.0, POINT_100))
        assertEquals(0, ScoreFormats.toRaw(-3.0, POINT_100))
        assertEquals(100.0, read(120, POINT_100), 0.0)
    }

    @Test
    fun `POINT_10_DECIMAL keeps tenths without float truncation`() {
        assertEquals(7.5, read(75, POINT_10_DECIMAL), 0.0)
        // Old editor did (x * 10).toInt(); these must not lose a point.
        for (tenths in 1..100) {
            assertEquals(tenths, ScoreFormats.toRaw(tenths / 10.0, POINT_10_DECIMAL))
            assertEquals(tenths, ScoreFormats.toRaw("${tenths / 10}.${tenths % 10}".toDouble(), POINT_10_DECIMAL))
        }
        assertEquals(78, ScoreFormats.toRaw(7.75, POINT_10_DECIMAL))
        assertEquals("7.5", ScoreFormats.display(75, POINT_10_DECIMAL))
        assertEquals("10.0", ScoreFormats.display(100, POINT_10_DECIMAL))
    }

    @Test
    fun `POINT_10 rounds half up with a floor of 1`() {
        assertEquals(8.0, read(75, POINT_10), 0.0)
        assertEquals(7.0, read(74, POINT_10), 0.0)
        assertEquals(1.0, read(3, POINT_10), 0.0)
        assertEquals(10.0, read(96, POINT_10), 0.0)
        assertEquals(70, ScoreFormats.toRaw(7.0, POINT_10))
        assertEquals(80, ScoreFormats.toRaw(7.5, POINT_10))
    }

    @Test
    fun `POINT_5 from decimal 7_5 is 4 stars`() {
        // 7.5/10 -> raw 75 -> 3.75 stars -> 4
        assertEquals(4.0, read(ScoreFormats.toRaw(7.5, POINT_10_DECIMAL), POINT_5), 0.0)
        assertEquals(4.0, read(70, POINT_5), 0.0) // 3.5 rounds up
        assertEquals(3.0, read(69, POINT_5), 0.0)
        assertEquals(3.0, read(50, POINT_5), 0.0) // 2.5 rounds up
        assertEquals(1.0, read(9, POINT_5), 0.0) // scored never shows 0 stars
        assertEquals(1.0, read(29, POINT_5), 0.0)
        assertEquals(2.0, read(30, POINT_5), 0.0)
        assertEquals(5.0, read(90, POINT_5), 0.0)
        assertEquals(5.0, read(100, POINT_5), 0.0)
        assertEquals("4", ScoreFormats.display(75, POINT_5))
    }

    @Test
    fun `POINT_5 write and round trip`() {
        for (stars in 1..5) {
            val raw = ScoreFormats.toRaw(stars.toDouble(), POINT_5)
            assertEquals(stars * 20, raw)
            assertEquals(stars.toDouble(), read(raw, POINT_5), 0.0)
        }
        assertEquals(100, ScoreFormats.toRaw(9.0, POINT_5))
    }

    @Test
    fun `POINT_3 thresholds`() {
        assertEquals(1.0, read(1, POINT_3), 0.0)
        assertEquals(1.0, read(35, POINT_3), 0.0)
        assertEquals(2.0, read(36, POINT_3), 0.0)
        assertEquals(2.0, read(60, POINT_3), 0.0)
        assertEquals(3.0, read(61, POINT_3), 0.0)
        assertEquals(3.0, read(100, POINT_3), 0.0)
        // 7.5 decimal -> raw 75 -> smile
        assertEquals(3.0, read(75, POINT_3), 0.0)
    }

    @Test
    fun `POINT_3 write and round trip`() {
        assertEquals(35, ScoreFormats.toRaw(1.0, POINT_3))
        assertEquals(60, ScoreFormats.toRaw(2.0, POINT_3))
        assertEquals(85, ScoreFormats.toRaw(3.0, POINT_3))
        for (s in 1..3) {
            assertEquals(s.toDouble(), read(ScoreFormats.toRaw(s.toDouble(), POINT_3), POINT_3), 0.0)
        }
        assertEquals("🙂", ScoreFormats.display(85, POINT_3))
    }

    @Test
    fun `every format round trips through raw`() {
        ScoreFormat.entries.forEach { f ->
            for (raw in 0..100) {
                val shown = read(raw, f)
                val back = ScoreFormats.toRaw(shown, f)
                // Re-reading what we would write must show the same value.
                assertEquals("$f raw=$raw", shown, read(back, f), 0.0)
            }
        }
    }

    @Test
    fun `MAL mirror rounds half up and never clears a score`() {
        assertEquals(8, ScoreFormats.toMal(75))
        assertEquals(7, ScoreFormats.toMal(74))
        assertEquals(1, ScoreFormats.toMal(3)) // old code sent 0 = cleared on MAL
        assertEquals(10, ScoreFormats.toMal(100))
        assertEquals(10, ScoreFormats.toMal(250))
        assertEquals(8, ScoreFormats.toMal(ScoreFormats.toRaw(4.0, POINT_5)))
        assertEquals(9, ScoreFormats.toMal(ScoreFormats.toRaw(3.0, POINT_3)))
        assertEquals(4, ScoreFormats.toMal(ScoreFormats.toRaw(1.0, POINT_3)))
    }
}
