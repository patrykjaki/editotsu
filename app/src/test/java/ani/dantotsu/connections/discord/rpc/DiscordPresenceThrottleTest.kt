package ani.dantotsu.connections.discord.rpc

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class DiscordPresenceThrottleTest {

    /** Test subclass with a controllable monotonic clock. */
    private class TestThrottle : DiscordPresenceThrottle() {
        var nowMs = 0L
        override fun monotonicMs(): Long = nowMs
    }

    private lateinit var throttle: TestThrottle

    @Before
    fun setUp() {
        throttle = TestThrottle()
    }

    // ── Scrub stop ──────────────────────────────────────────────────────

    @Test
    fun `onScrubStop always returns true`() {
        assertTrue(throttle.onScrubStop(10_000L))
    }

    @Test
    fun `onScrubStop suppresses stale pre-seek callback`() {
        // Current position 90s, scrub target 30s.
        throttle.onPlaybackPositionChanged(90_000L, 1.0f)
        throttle.nowMs = 90_500L

        // Scrub to 30s — publishes target.
        assertTrue(throttle.onScrubStop(30_000L))
        throttle.nowMs = 90_800L

        // Stale in-flight callback at 90s arrives — should be suppressed.
        assertFalse("stale pre-seek callback should be suppressed",
            throttle.onPlaybackPositionChanged(90_000L, 1.0f))
    }

    @Test
    fun `scrub settle near target clears pending without duplicate`() {
        throttle.onPlaybackPositionChanged(0L, 1.0f)
        for (sec in 1L..30L) {
            throttle.nowMs = sec * 1000L
            throttle.onPlaybackPositionChanged(sec * 1000L, 1.0f)
        }

        // Scrub from 30s to 60s.
        throttle.nowMs = 30_500L
        assertTrue(throttle.onScrubStop(60_000L))

        // Stale callback at 30s — suppressed.
        throttle.nowMs = 30_800L
        assertFalse(throttle.onPlaybackPositionChanged(30_000L, 1.0f))

        // Near-target callback at 60s — clears pending, no duplicate.
        throttle.nowMs = 31_200L
        assertFalse("near-target should not duplicate",
            throttle.onPlaybackPositionChanged(60_000L, 1.0f))

        // Subsequent normal playback — no false publish.
        for (sec in 1L..5L) {
            throttle.nowMs = (31_200L + sec * 1000L)
            assertFalse(throttle.onPlaybackPositionChanged(60_000L + sec * 1000L, 1.0f))
        }
    }

    @Test
    fun `scrub settle timeout recovers safely`() {
        throttle.onPlaybackPositionChanged(0L, 1.0f)
        for (sec in 1L..10L) {
            throttle.nowMs = sec * 1000L
            throttle.onPlaybackPositionChanged(sec * 1000L, 1.0f)
        }

        // Scrub from 10s to 60s.
        throttle.nowMs = 10_500L
        assertTrue(throttle.onScrubStop(60_000L))

        // Stale callback at 10s — suppressed.
        throttle.nowMs = 10_800L
        assertFalse(throttle.onPlaybackPositionChanged(10_000L, 1.0f))

        // Settle window expires without reaching target. Callback at 15s (not near 60s).
        throttle.nowMs = 14_000L  // > SETTLE_WINDOW_MS from 10_500L
        assertFalse("timeout should recover without publish",
            throttle.onPlaybackPositionChanged(15_000L, 1.0f))

        // Subsequent normal playback — no false publish.
        throttle.nowMs = 15_500L
        assertFalse(throttle.onPlaybackPositionChanged(15_500L, 1.0f))
    }

    @Test
    fun `scrub to off-target keyframe settle clears safely`() {
        throttle.onPlaybackPositionChanged(0L, 1.0f)
        for (sec in 1L..10L) {
            throttle.nowMs = sec * 1000L
            throttle.onPlaybackPositionChanged(sec * 1000L, 1.0f)
        }

        // Scrub from 10s to 30s.
        throttle.nowMs = 10_500L
        assertTrue(throttle.onScrubStop(30_000L))

        // Engine lands slightly off target at 30_300 (keyframe quantization) — near enough.
        throttle.nowMs = 12_000L
        assertFalse("off-target/keyframe should clear pending safely",
            throttle.onPlaybackPositionChanged(30_300L, 1.0f))

        // Subsequent normal playback — no false publish.
        for (sec in 1L..5L) {
            throttle.nowMs = (12_000L + sec * 1000L)
            assertFalse(throttle.onPlaybackPositionChanged(30_300L + sec * 1000L, 1.0f))
        }
    }

    // ── Normal linear playback ──────────────────────────────────────────

    @Test
    fun `60 seconds of normal playback does not emit a SET_ACTIVITY stream`() {
        var emitted = 0
        throttle.onPlaybackPositionChanged(0L, 1.0f)
        for (sec in 1L..60L) {
            throttle.nowMs = sec * 1000L
            if (throttle.onPlaybackPositionChanged(sec * 1000L, 1.0f)) {
                emitted++
            }
        }
        assertEquals("normal playback should not emit any updates", 0, emitted)
    }

    @Test
    fun `slight timing jitter does not look like a seek`() {
        throttle.onPlaybackPositionChanged(10_000L, 1.0f)
        throttle.nowMs = 100L
        assertFalse(throttle.onPlaybackPositionChanged(10_150L, 1.0f))
        throttle.nowMs = 300L
        assertFalse(throttle.onPlaybackPositionChanged(10_380L, 1.0f))
    }

    // ── Large seeks ─────────────────────────────────────────────────────

    @Test
    fun `large forward seek emits one update`() {
        throttle.onPlaybackPositionChanged(10_000L, 1.0f)
        throttle.nowMs = 500L
        assertTrue(throttle.onPlaybackPositionChanged(40_000L, 1.0f))
        assertFalse(throttle.onPlaybackPositionChanged(40_000L, 1.0f))
    }

    @Test
    fun `large backward seek emits one update`() {
        throttle.onPlaybackPositionChanged(60_000L, 1.0f)
        throttle.nowMs = 500L
        assertTrue(throttle.onPlaybackPositionChanged(30_000L, 1.0f))
        assertFalse(throttle.onPlaybackPositionChanged(30_000L, 1.0f))
    }

    @Test
    fun `seek after normal playback is detected`() {
        throttle.onPlaybackPositionChanged(0L, 1.0f)
        for (sec in 1L..5L) {
            throttle.nowMs = sec * 1000L
            assertFalse(throttle.onPlaybackPositionChanged(sec * 1000L, 1.0f))
        }
        throttle.nowMs = 5_500L
        assertTrue(throttle.onPlaybackPositionChanged(25_000L, 1.0f))
    }

    // ── Pause / resume ──────────────────────────────────────────────────

    @Test
    fun `pause then resume emits updates`() {
        throttle.onPlaybackPositionChanged(30_000L, 1.0f)
        throttle.nowMs = 30_500L
        assertTrue(throttle.onPlayPause())
        throttle.nowMs = 40_500L
        assertTrue(throttle.onPlayPause())
    }

    // ── Ownership change ────────────────────────────────────────────────

    @Test
    fun `ownership change resets and returns true`() {
        throttle.onPlaybackPositionChanged(10_000L, 1.0f)
        throttle.nowMs = 10_500L
        assertTrue(throttle.onOwnershipChange())
    }

    @Test
    fun `ownership change prevents stale seek detection`() {
        throttle.onPlaybackPositionChanged(10_000L, 1.0f)
        throttle.nowMs = 10_500L
        throttle.onOwnershipChange()
        throttle.nowMs = 0L
        assertFalse(throttle.onPlaybackPositionChanged(0L, 1.0f))
    }

    // ── Speed-aware discontinuity ───────────────────────────────────────

    @Test
    fun `60 seconds at 2x playback does not emit`() {
        var emitted = 0
        throttle.onPlaybackPositionChanged(0L, 2.0f)
        for (sec in 1L..60L) {
            throttle.nowMs = sec * 1000L
            if (throttle.onPlaybackPositionChanged(sec * 1000L * 2, 2.0f)) {
                emitted++
            }
        }
        assertEquals("2x playback should not emit any updates", 0, emitted)
    }

    @Test
    fun `60 seconds at halfx playback does not emit`() {
        var emitted = 0
        throttle.onPlaybackPositionChanged(0L, 0.5f)
        for (sec in 1L..60L) {
            throttle.nowMs = sec * 1000L
            if (throttle.onPlaybackPositionChanged(sec * 1000L / 2, 0.5f)) {
                emitted++
            }
        }
        assertEquals("0.5x playback should not emit any updates", 0, emitted)
    }

    @Test
    fun `60 seconds at one-point-five x playback does not emit`() {
        var emitted = 0
        throttle.onPlaybackPositionChanged(0L, 1.5f)
        for (sec in 1L..60L) {
            throttle.nowMs = sec * 1000L
            if (throttle.onPlaybackPositionChanged((sec * 1000L * 1.5).toLong(), 1.5f)) {
                emitted++
            }
        }
        assertEquals("1.5x playback should not emit any updates", 0, emitted)
    }

    @Test
    fun `speed change 1x to 2x resets baseline, no false seek`() {
        throttle.onPlaybackPositionChanged(10_000L, 1.0f)
        throttle.nowMs = 5_000L
        // Speed change — resets baseline.
        throttle.onPlaybackSpeedChanged()
        // Now at 2x: 10 seconds later, position should be ~30_000.
        throttle.nowMs = 15_000L
        assertFalse("speed transition should not look like a seek",
            throttle.onPlaybackPositionChanged(30_000L, 2.0f))
    }

    @Test
    fun `real seek while at 2x emits exactly one update`() {
        throttle.onPlaybackPositionChanged(0L, 2.0f)
        for (sec in 1L..5L) {
            throttle.nowMs = sec * 1000L
            assertFalse(throttle.onPlaybackPositionChanged(sec * 1000L * 2, 2.0f))
        }
        throttle.nowMs = 5_500L
        assertTrue(throttle.onPlaybackPositionChanged(40_000L, 2.0f))
        assertFalse(throttle.onPlaybackPositionChanged(40_000L, 2.0f))
    }

    // ── Speed preserved across episode transitions ──────────────────────

    @Test
    fun `2x speed persists across episode A to episode B, zero false seek`() {
        // Episode A at 2x.
        throttle.onPlaybackPositionChanged(0L, 2.0f)
        for (sec in 1L..5L) {
            throttle.nowMs = sec * 1000L
            assertFalse(throttle.onPlaybackPositionChanged(sec * 1000L * 2, 2.0f))
        }

        // Episode change — resets position baseline, but speed is provided per-call.
        throttle.nowMs = 5_500L
        assertTrue(throttle.onOwnershipChange())

        // Episode B starts at 0, still at 2x.
        throttle.nowMs = 6_000L
        assertFalse("first B callback should be baseline",
            throttle.onPlaybackPositionChanged(0L, 2.0f))

        // Normal 2x playback of episode B.
        for (sec in 1L..10L) {
            throttle.nowMs = (6_000L + sec * 1000L)
            assertFalse("2x B playback should not emit",
                throttle.onPlaybackPositionChanged(sec * 1000L * 2, 2.0f))
        }
    }

    @Test
    fun `halfx speed persists across episode transition`() {
        throttle.onPlaybackPositionChanged(0L, 0.5f)
        for (sec in 1L..5L) {
            throttle.nowMs = sec * 1000L
            assertFalse(throttle.onPlaybackPositionChanged(sec * 1000L / 2, 0.5f))
        }
        throttle.nowMs = 5_500L
        assertTrue(throttle.onOwnershipChange())

        // Episode B at 0.5x.
        throttle.nowMs = 6_000L
        assertFalse(throttle.onPlaybackPositionChanged(0L, 0.5f))
        for (sec in 1L..10L) {
            throttle.nowMs = (6_000L + sec * 1000L)
            assertFalse("0.5x B playback should not emit",
                throttle.onPlaybackPositionChanged(sec * 1000L / 2, 0.5f))
        }
    }

    @Test
    fun `speed callback from engine resets baseline`() {
        throttle.onPlaybackPositionChanged(10_000L, 1.0f)
        throttle.nowMs = 5_000L
        // Engine fires speed change (not from dialog).
        throttle.onPlaybackSpeedChanged()
        // Continue at new speed — no false seek.
        throttle.nowMs = 15_000L
        assertFalse(throttle.onPlaybackPositionChanged(30_000L, 2.0f))
    }

    @Test
    fun `real seek after episode transition at 2x emits exactly one`() {
        // Episode A at 2x.
        throttle.onPlaybackPositionChanged(0L, 2.0f)
        for (sec in 1L..3L) {
            throttle.nowMs = sec * 1000L
            assertFalse(throttle.onPlaybackPositionChanged(sec * 1000L * 2, 2.0f))
        }

        // Episode change.
        throttle.nowMs = 3_500L
        assertTrue(throttle.onOwnershipChange())

        // Episode B at 2x, initial baseline.
        throttle.nowMs = 4_000L
        assertFalse(throttle.onPlaybackPositionChanged(0L, 2.0f))

        // Normal 2x for 3 seconds.
        for (sec in 1L..3L) {
            throttle.nowMs = (4_000L + sec * 1000L)
            assertFalse(throttle.onPlaybackPositionChanged(sec * 1000L * 2, 2.0f))
        }

        // Real seek at 2x.
        throttle.nowMs = 7_500L
        assertTrue(throttle.onPlaybackPositionChanged(40_000L, 2.0f))
    }

    // ── Monotonic clock ─────────────────────────────────────────────────

    @Test
    fun `civil clock jump does not create false seek`() {
        throttle.onPlaybackPositionChanged(0L, 1.0f)
        throttle.nowMs = 1_000L
        assertFalse(throttle.onPlaybackPositionChanged(1_000L, 1.0f))
        throttle.nowMs = 2_000L
        assertFalse(throttle.onPlaybackPositionChanged(2_000L, 1.0f))
    }

    // ── Edge cases ──────────────────────────────────────────────────────

    @Test
    fun `reset clears all state`() {
        throttle.onPlaybackPositionChanged(10_000L, 1.0f)
        throttle.nowMs = 10_500L
        throttle.onScrubStop(20_000L)
        throttle.reset()
        throttle.nowMs = 0L
        assertFalse(throttle.onPlaybackPositionChanged(0L, 1.0f))
    }

    @Test
    fun `consecutive scrubs to different positions each emit`() {
        throttle.onScrubStop(10_000L)
        throttle.nowMs = 1_000L
        assertTrue(throttle.onScrubStop(20_000L))
        throttle.nowMs = 2_000L
        assertTrue(throttle.onScrubStop(30_000L))
    }

    @Test
    fun `seek during pause is handled by pause-resume cycle`() {
        throttle.onPlaybackPositionChanged(10_000L, 1.0f)
        throttle.nowMs = 10_500L
        assertTrue(throttle.onPlayPause())
        throttle.nowMs = 20_000L
        assertTrue(throttle.onPlayPause())
        throttle.nowMs = 20_500L
        assertFalse(throttle.onPlaybackPositionChanged(60_000L, 1.0f))
    }

    @Test
    fun `episode A to episode B reset prevents false seek`() {
        throttle.onPlaybackPositionChanged(60_000L, 1.0f)
        throttle.nowMs = 60_500L
        assertTrue(throttle.onOwnershipChange())
        throttle.nowMs = 61_000L
        assertFalse(throttle.onPlaybackPositionChanged(0L, 1.0f))
        for (sec in 1L..5L) {
            throttle.nowMs = (61_000L + sec * 1000L)
            assertFalse(throttle.onPlaybackPositionChanged(sec * 1000L, 1.0f))
        }
    }
}
