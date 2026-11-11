package ani.dantotsu.connections

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingProgressRulesTest {

    private fun entry(id: Int = 1, progress: Int = 5) = PendingProgressUpdate(
        mediaId = id, idMAL = 100 + id, isAnime = true, progress = progress, status = "CURRENT",
    )

    @Test
    fun `already ahead or equal is not sent`() {
        assertFalse(PendingProgressRules.shouldSend(4, 5))
        assertFalse(PendingProgressRules.shouldSend(5, 5))
        assertTrue(PendingProgressRules.shouldSend(6, 5))
        assertTrue(PendingProgressRules.shouldSend(0, null))
    }

    @Test
    fun `success on both trackers advances and queues nothing`() {
        val d = PendingProgressRules.decide(entry(), rescueMode = false, anilistOk = true, malOk = true)
        assertTrue(d.advanceLocal)
        assertNull(d.queue)
    }

    @Test
    fun `anilist failure does not advance and queues both parts that failed`() {
        val d = PendingProgressRules.decide(entry(), rescueMode = false, anilistOk = false, malOk = false)
        assertFalse(d.advanceLocal)
        val q = d.queue!!
        assertFalse(q.anilistDone)
        assertTrue(q.malPending)
    }

    @Test
    fun `mal failure alone advances and queues only the mal part`() {
        val d = PendingProgressRules.decide(entry(), rescueMode = false, anilistOk = true, malOk = false)
        assertTrue(d.advanceLocal)
        assertTrue(d.queue!!.anilistDone)
        assertTrue(d.queue!!.malPending)
    }

    @Test
    fun `rescue mode always queues anilist and advances on mal success`() {
        val ok = PendingProgressRules.decide(entry(), rescueMode = true, anilistOk = false, malOk = true)
        assertTrue(ok.advanceLocal)
        assertFalse(ok.queue!!.anilistDone)
        assertFalse(ok.queue!!.malPending)

        val fail = PendingProgressRules.decide(entry(), rescueMode = true, anilistOk = false, malOk = false)
        assertFalse(fail.advanceLocal)
        assertTrue(fail.queue!!.malPending)
    }

    @Test
    fun `enqueue keeps one entry per media, keeps editor fields and never lowers progress`() {
        val old = entry(progress = 7).copy(notes = "note", score = 80, anilistDone = true, malPending = true)
        val other = entry(id = 2)
        val list = PendingProgressRules.enqueue(listOf(old, other), entry(progress = 6))
        assertEquals(2, list.size)
        val merged = list.single { it.mediaId == 1 }
        assertEquals(7, merged.progress)
        assertEquals("note", merged.notes)
        assertEquals(80, merged.score)
        assertFalse(merged.anilistDone)
        assertTrue(merged.malPending)
    }

    @Test
    fun `a direct success raises a lower queued progress`() {
        val list = PendingProgressRules.raiseQueuedProgress(listOf(entry(progress = 3), entry(id = 2, progress = 1)), 1, 5)
        assertEquals(5, list.single { it.mediaId == 1 }.progress)
        assertEquals(1, list.single { it.mediaId == 2 }.progress)
    }

    @Test
    fun `sync run removes finished entries and counts failures`() {
        val e = entry().copy(malPending = true)
        assertNull(PendingProgressRules.afterSyncRun(e, anilistOk = true, malOk = true))
        val left = PendingProgressRules.afterSyncRun(e, anilistOk = true, malOk = false)!!
        assertTrue(left.anilistDone)
        assertEquals(1, left.failedAttempts)
        // Rescue mode: AniList not tried, MAL done; AniList part stays.
        val rescue = PendingProgressRules.afterSyncRun(e, anilistOk = null, malOk = true)!!
        assertFalse(rescue.anilistDone)
        assertEquals(0, rescue.failedAttempts)
    }

    @Test
    fun `entry is dropped after too many failed runs`() {
        val e = entry().copy(failedAttempts = PendingProgressRules.MAX_FAILED_ATTEMPTS - 1)
        assertNull(PendingProgressRules.afterSyncRun(e, anilistOk = false, malOk = null))
    }

    @Test
    fun `sync results keep entries queued during the run`() {
        val a = entry(id = 1)
        val b = entry(id = 2)
        val c = entry(id = 3)
        val newerA = entry(id = 1, progress = 9)
        // Run worked on a and b; meanwhile a was replaced and c was added.
        val current = listOf(newerA, b, c)
        val out = PendingProgressRules.applySyncResults(current, mapOf(a to null, b to null))
        assertEquals(listOf(newerA, c), out)
    }
}
