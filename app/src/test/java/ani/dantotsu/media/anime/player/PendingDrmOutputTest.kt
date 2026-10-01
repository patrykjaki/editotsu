package ani.dantotsu.media.anime.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MAX MERGE M4 correction (HOLD4 B1/B2): pending output survives player
 * creation and rebuilds; reported speed equals applied speed.
 */
class PendingDrmOutputTest {

    @Test
    fun `pending surface survives player creation`() {
        val pending = PendingDrmOutput()
        // Bind-time commands land before ExoPlayer exists.
        pending.hasSurface = true
        pending.surfaceWidth = 1280
        pending.surfaceHeight = 720
        pending.speed = 1.5f
        // Creation replays the snapshot.
        val replayed = PendingDrmOutput().also { it.restore(pending.snapshot()) }
        assertTrue(replayed.hasSurface)
        assertEquals(1280, replayed.surfaceWidth)
        assertEquals(720, replayed.surfaceHeight)
        assertEquals(1.5f, replayed.speed, 0f)
    }

    @Test
    fun `pending state survives request-header rebuild`() {
        val pending = PendingDrmOutput()
        pending.hasSurface = true
        pending.speed = 2f
        pending.resize = ResizeMode.ZOOM
        // A rebuild replays the same snapshot onto the new player.
        val afterRebuild = PendingDrmOutput().also { it.restore(pending.snapshot()) }
        assertEquals(pending.snapshot(), afterRebuild.snapshot())
    }

    @Test
    fun `clearing the surface drops size with it`() {
        val pending = PendingDrmOutput()
        pending.hasSurface = true
        pending.surfaceWidth = 640
        pending.clearSurface()
        assertFalse(pending.hasSurface)
        assertEquals(0, pending.surfaceWidth)
        assertEquals(0, pending.surfaceHeight)
    }

    @Test
    fun `speed clamp is the single applied and reported value`() {
        assertEquals(1f, PendingDrmOutput.clampSpeed(1f), 0f)
        assertEquals(0.25f, PendingDrmOutput.clampSpeed(0.1f), 0f)
        assertEquals(4f, PendingDrmOutput.clampSpeed(9f), 0f)
        assertEquals(0.25f, PendingDrmOutput.clampSpeed(-2f), 0f)
    }
}
