package ani.dantotsu.connections.mal

import ani.dantotsu.connections.anilist.api.FuzzyDate
import ani.dantotsu.connections.mal.MalListSync.Entry
import ani.dantotsu.connections.mal.MalListSync.What
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MalListSyncTest {
    private val watching = Entry(idMal = 1, title = "Show", status = "CURRENT", progress = 5, score = 8)

    @Test
    fun `each field that differs is listed with what it is and what it becomes`() {
        assertTrue(MalListSync.diff(watching, watching.copy(title = "Other spelling")).isEmpty())
        val fields = MalListSync.diff(watching, watching.copy(status = "PAUSED", progress = 3, score = 0))
        assertEquals(listOf(What.STATUS, What.PROGRESS, What.SCORE), fields.map { it.what })
        assertEquals("PAUSED" to "CURRENT", fields[0].from to fields[0].to)
        assertEquals("3" to "5", fields[1].from to fields[1].to)
    }

    @Test
    fun `a completed title is fully seen on the other list even when the episode counts differ`() {
        val done = watching.copy(status = "COMPLETED", progress = 12)
        assertEquals(13, MalListSync.wantedProgress(done, done.copy(progress = 13, total = 13)))
        assertTrue(MalListSync.diff(done, done.copy(progress = 13, total = 13)).isEmpty())
        assertEquals(12, MalListSync.wantedProgress(done, null))
        assertEquals(5, MalListSync.wantedProgress(watching, watching.copy(total = 13)))
    }

    @Test
    fun `a date is copied but an empty one never erases the other list's date`() {
        val started = FuzzyDate(2026, 10, 3)
        assertEquals(listOf(What.START), MalListSync.diff(watching.copy(start = started), watching).map { it.what })
        assertTrue(MalListSync.diff(watching, watching.copy(start = started)).isEmpty())
    }

    @Test
    fun `only entries that are missing or differ are written, each title once, the rest untouched`() {
        val main = listOf(watching, watching, Entry(2, "B", "COMPLETED", 12, 0), Entry(3, "C", "PLANNING", 0, 0))
        val other = listOf(watching, Entry(2, "B", "CURRENT", 11, 0), Entry(99, "only there", "COMPLETED", 24, 9))
        val changes = MalListSync.changes(true, main, other)
        assertEquals(listOf(2, 3), changes.map { it.main.idMal })
        assertEquals(null, changes[1].target)
        assertTrue(changes[1].fields.isEmpty())
    }

    @Test
    fun `MyAnimeList statuses and dates are read into the shared words`() {
        assertEquals("CURRENT", MalListSync.fromMalStatus("watching", false))
        assertEquals("CURRENT", MalListSync.fromMalStatus("reading", false))
        assertEquals("PLANNING", MalListSync.fromMalStatus("plan_to_read", false))
        assertEquals("PAUSED", MalListSync.fromMalStatus("on_hold", false))
        assertEquals("REPEATING", MalListSync.fromMalStatus("completed", true))
        assertEquals(FuzzyDate(2026, 11, 14), MalListSync.parseDate("2026-11-14"))
        assertEquals(FuzzyDate(2026, 11, null), MalListSync.parseDate("2026-11"))
        assertTrue(MalListSync.parseDate(null).isEmpty())
    }
}
