package ani.dantotsu.media.anime.player

import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerSyncSessionTest {
    @Test
    fun `starts from the preference`() {
        val o = SyncDelayOverride()
        assertEquals(300L, o.effective(300L))
    }

    @Test
    fun `plus 100 on a 300 ms preference gives 400 ms`() {
        val o = SyncDelayOverride()
        val shown = o.effective(300L)
        o.set(shown + 100L, prefMs = 300L)
        assertEquals(400L, o.effective(300L))
    }

    @Test
    fun `session value survives a pipeline re-apply with the same preference`() {
        val o = SyncDelayOverride()
        o.set(-500L, prefMs = 0L)
        // engine rebuild or settings refresh reads the preference again
        assertEquals(-500L, o.effective(0L))
        assertEquals(-500L, o.effective(0L))
    }

    @Test
    fun `changing the preference drops the session value`() {
        val o = SyncDelayOverride()
        o.set(700L, prefMs = 0L)
        assertEquals(250L, o.effective(250L))
    }

    @Test
    fun `reset clears both`() {
        PlayerSyncSession.subtitle.set(100L, 0L)
        PlayerSyncSession.audio.set(200L, 0L)
        PlayerSyncSession.reset()
        assertEquals(0L, PlayerSyncSession.subtitle.effective(0L))
        assertEquals(0L, PlayerSyncSession.audio.effective(0L))
    }
}
