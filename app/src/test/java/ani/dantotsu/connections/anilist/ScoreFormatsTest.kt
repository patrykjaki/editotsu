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
    fun `POINT_10 floors like AniList`() {
        assertEquals(7.0, read(75, POINT_10), 0.0)
        assertEquals(7.0, read(79, POINT_10), 0.0)
        assertEquals(0.0, read(3, POINT_10), 0.0) // AniList returns 0 for raw 1-9
        assertEquals(9.0, read(96, POINT_10), 0.0)
        assertEquals(10.0, read(100, POINT_10), 0.0)
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
    fun `MAL mirror uses AniList 10-point value and never clears a score`() {
        assertEquals(7, ScoreFormats.toMal(75))
        assertEquals(7, ScoreFormats.toMal(79))
        assertEquals(1, ScoreFormats.toMal(3)) // MAL 0 would clear the score
        assertEquals(10, ScoreFormats.toMal(100))
        assertEquals(10, ScoreFormats.toMal(250))
        assertEquals(8, ScoreFormats.toMal(ScoreFormats.toRaw(4.0, POINT_5)))
        assertEquals(8, ScoreFormats.toMal(ScoreFormats.toRaw(3.0, POINT_3)))
        assertEquals(3, ScoreFormats.toMal(ScoreFormats.toRaw(1.0, POINT_3)))
    }

    @Test
    fun `stats buckets map to raw`() {
        assertEquals(75, ScoreFormats.statsBucketToRaw(75, POINT_10_DECIMAL))
        assertEquals(80, ScoreFormats.statsBucketToRaw(80, POINT_100))
        assertEquals(70, ScoreFormats.statsBucketToRaw(7, POINT_10))
        assertEquals(80, ScoreFormats.statsBucketToRaw(4, POINT_5))
        assertEquals(60, ScoreFormats.statsBucketToRaw(2, POINT_3))
        assertEquals(0, ScoreFormats.statsBucketToRaw(0, POINT_5))
    }

    /** raw, POINT_10, POINT_5, POINT_3 as returned by AniList's API (public lists, 2026-10-31). */
    private val aniListObserved = listOf(
        intArrayOf(0, 0, 0, 0),
        intArrayOf(1, 0, 1, 1),
        intArrayOf(10, 1, 1, 1),
        intArrayOf(20, 2, 1, 1),
        intArrayOf(25, 2, 1, 1),
        intArrayOf(30, 3, 2, 1),
        intArrayOf(40, 4, 2, 2),
        intArrayOf(44, 4, 2, 2),
        intArrayOf(46, 4, 2, 2),
        intArrayOf(50, 5, 3, 2),
        intArrayOf(54, 5, 3, 2),
        intArrayOf(55, 5, 3, 2),
        intArrayOf(57, 5, 3, 2),
        intArrayOf(58, 5, 3, 2),
        intArrayOf(60, 6, 3, 2),
        intArrayOf(61, 6, 3, 3),
        intArrayOf(62, 6, 3, 3),
        intArrayOf(63, 6, 3, 3),
        intArrayOf(64, 6, 3, 3),
        intArrayOf(65, 6, 3, 3),
        intArrayOf(66, 6, 3, 3),
        intArrayOf(67, 6, 3, 3),
        intArrayOf(68, 6, 3, 3),
        intArrayOf(69, 6, 3, 3),
        intArrayOf(70, 7, 4, 3),
        intArrayOf(71, 7, 4, 3),
        intArrayOf(72, 7, 4, 3),
        intArrayOf(73, 7, 4, 3),
        intArrayOf(74, 7, 4, 3),
        intArrayOf(75, 7, 4, 3),
        intArrayOf(76, 7, 4, 3),
        intArrayOf(77, 7, 4, 3),
        intArrayOf(78, 7, 4, 3),
        intArrayOf(79, 7, 4, 3),
        intArrayOf(80, 8, 4, 3),
        intArrayOf(81, 8, 4, 3),
        intArrayOf(82, 8, 4, 3),
        intArrayOf(83, 8, 4, 3),
        intArrayOf(84, 8, 4, 3),
        intArrayOf(85, 8, 4, 3),
        intArrayOf(86, 8, 4, 3),
        intArrayOf(87, 8, 4, 3),
        intArrayOf(88, 8, 4, 3),
        intArrayOf(89, 8, 4, 3),
        intArrayOf(90, 9, 5, 3),
        intArrayOf(91, 9, 5, 3),
        intArrayOf(92, 9, 5, 3),
        intArrayOf(93, 9, 5, 3),
        intArrayOf(94, 9, 5, 3),
        intArrayOf(95, 9, 5, 3),
        intArrayOf(96, 9, 5, 3),
        intArrayOf(97, 9, 5, 3),
        intArrayOf(98, 9, 5, 3),
        intArrayOf(99, 9, 5, 3),
        intArrayOf(100, 10, 5, 3)
    )

    @Test
    fun `matches AniList observed conversions`() {
        aniListObserved.forEach { (raw, p10, p5, p3) ->
            assertEquals("POINT_10 raw=$raw", p10.toDouble(), read(raw, POINT_10), 0.0)
            assertEquals("POINT_5 raw=$raw", p5.toDouble(), read(raw, POINT_5), 0.0)
            assertEquals("POINT_3 raw=$raw", p3.toDouble(), read(raw, POINT_3), 0.0)
            assertEquals("DECIMAL raw=$raw", raw / 10.0, read(raw, POINT_10_DECIMAL), 0.0)
        }
    }
}
