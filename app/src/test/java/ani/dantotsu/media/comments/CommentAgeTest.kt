package ani.dantotsu.media.comments

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Date
import java.util.Locale

class CommentAgeTest {
    private val minute = 60_000L
    private val hour = 60 * minute
    private val day = 24 * hour

    @Test
    fun `short ages stay relative`() {
        val posted = Date(0)
        assertEquals("now", commentAge(20_000, posted))
        assertEquals("5m", commentAge(5 * minute, posted))
        assertEquals("3h", commentAge(3 * hour + 10 * minute, posted))
        assertEquals("29d", commentAge(29 * day + hour, posted))
    }

    @Test
    fun `a month old comment shows its date`() {
        val posted = Date(1_700_000_000_000L) // 14 Nov 2023 UTC
        val shown = commentAge(231 * day, posted, Locale.US)
        assertEquals(true, shown.contains("2023"))
    }
}
