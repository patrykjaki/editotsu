package ani.dantotsu.media.comments

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MAX MERGE M6 (upstream aa58a989): timestamp mining contract behind
 * comment click-to-seek.
 */
class CommentTimestampTest {

    @Test
    fun `mm-ss parses to millis`() {
        val hits = findTimestamps("go to 12:34 now")
        assertEquals(1, hits.size)
        assertEquals((12 * 60 + 34) * 1000L, hits[0].timestampMs)
        assertEquals("12:34", "go to 12:34 now".substring(hits[0].start, hits[0].endExclusive))
    }

    @Test
    fun `hh-mm-ss parses to millis`() {
        val hits = findTimestamps("at 1:05:20 roughly")
        assertEquals(1, hits.size)
        assertEquals((3600 + 5 * 60 + 20) * 1000L, hits[0].timestampMs)
    }

    @Test
    fun `multiple timestamps all surface in order`() {
        val hits = findTimestamps("0:10 then 1:00 then 2:03:04")
        assertEquals(3, hits.size)
        assertEquals(10_000L, hits[0].timestampMs)
        assertEquals(60_000L, hits[1].timestampMs)
        assertEquals((2 * 3600 + 3 * 60 + 4) * 1000L, hits[2].timestampMs)
        assertTrue(hits[0].start < hits[1].start && hits[1].start < hits[2].start)
    }

    @Test
    fun `invalid clock values do not match`() {
        assertTrue(findTimestamps("at 99:99 nope").isEmpty())
        assertTrue(findTimestamps("12:60 bad seconds").isEmpty())
    }

    @Test
    fun `episode tag extraction`() {
        assertEquals("12", extractEpisodeTag("ep 12 was great"))
        assertEquals("7", extractEpisodeTag("Episode.7 timestamp"))
        assertEquals("3", extractEpisodeTag("e3 fight scene"))
        assertNull(extractEpisodeTag("no tag here"))
    }

    @Test
    fun `plain text has no hits`() {
        assertTrue(findTimestamps("just a normal comment").isEmpty())
    }
}
