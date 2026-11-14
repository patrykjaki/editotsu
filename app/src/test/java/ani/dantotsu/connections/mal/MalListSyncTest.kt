package ani.dantotsu.connections.mal

import ani.dantotsu.connections.mal.MalListSync.Local
import ani.dantotsu.connections.mal.MalListSync.Remote
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MalListSyncTest {
    private val watching = Local(idMal = 1, status = "CURRENT", progress = 5, score = 80)

    @Test
    fun `an entry is written when MyAnimeList lacks it or holds other values`() {
        assertTrue(MalListSync.differs(watching, null, true))
        assertFalse(MalListSync.differs(watching, Remote("watching", 5, 8, false), true))
        assertTrue(MalListSync.differs(watching, Remote("watching", 4, 8, false), true))
        assertTrue(MalListSync.differs(watching, Remote("watching", 5, 7, false), true))
        assertTrue(MalListSync.differs(watching, Remote("on_hold", 5, 8, false), true))
        // the same AniList status has another name for manga
        assertFalse(MalListSync.differs(watching, Remote("reading", 5, 8, false), false))
        assertTrue(MalListSync.differs(watching, Remote("watching", 5, 8, false), false))
    }

    @Test
    fun `a score of 85 and MyAnimeList's 8 are the same score`() {
        assertFalse(MalListSync.differs(watching.copy(score = 85), Remote("watching", 5, 8, false), true))
        assertFalse(MalListSync.differs(watching.copy(score = 0), Remote("watching", 5, 0, false), true))
    }

    @Test
    fun `rewatching is a flag on MyAnimeList, not a status`() {
        val again = watching.copy(status = "REPEATING")
        assertFalse(MalListSync.differs(again, Remote("completed", 5, 8, true), true))
        assertTrue(MalListSync.differs(again, Remote("completed", 5, 8, false), true))
        assertTrue(MalListSync.differs(watching, Remote("watching", 5, 8, true), true))
    }

    @Test
    fun `only differing entries are planned, each title once, MyAnimeList-only entries untouched`() {
        val locals = listOf(
            watching,
            watching, // also in a custom list
            Local(2, "COMPLETED", 12, 0),
            Local(3, "PLANNING", 0, 0),
        )
        val remote = mapOf(
            1 to Remote("watching", 5, 8, false),
            2 to Remote("watching", 11, 0, false),
            99 to Remote("completed", 24, 9, false),
        )
        assertEquals(listOf(2, 3), MalListSync.plan(locals, remote, true).map { it.idMal })
    }
}
