package ani.dantotsu.connections.discord.rpc

/**
 * Pure policy class that decides when a Discord Rich Presence update is warranted
 * based on player position and elapsed-time progression.
 *
 * The goal: publish on seeks/discontinuities, pause/resume, and scrub stops,
 * but NOT during ordinary linear playback. Discord rate-limits clients to
 * approximately 5 updates per 20 seconds; a naive 2-second periodic re-publish
 * can exhaust that budget.
 *
 * Speed-aware: accepts playback speed on each position sample so that
 * fast-forward/rewind does not produce false seek detections. The caller
 * provides the authoritative engine speed; this class never stores or
 * assumes a speed value across calls.
 *
 * Scrub-settlement: after an explicit scrub, a bounded settle window
 * suppresses stale pre-seek callbacks that arrive before the engine
 * reaches the target position.
 *
 * Clock: uses a monotonic clock source for elapsed-time measurement so that
 * civil-clock jumps (NTP, manual) cannot create false seeks.
 *
 * Thread-safety: all state is mutable and single-threaded (main/UI thread only).
 */
open class DiscordPresenceThrottle {

    companion object {
        /** Discontinuity threshold: if the player position deviates from expected by more
         *  than this, treat it as a seek. 5 seconds is generous enough to absorb normal
         *  buffering/jitter but small enough to catch real seeks. */
        const val DISCONTINUITY_THRESHOLD_MS = 5000L

        /** Settle window: after a scrub, stale callbacks within this time window are
         *  suppressed. Must be long enough for async seekTo to complete but short enough
         *  to not block normal playback flow. */
        const val SETTLE_WINDOW_MS = 3000L

        /** Near-target threshold: a callback is considered "near target" if it is within
         *  this distance of the scrub target (accounts for keyframe quantization). */
        const val NEAR_TARGET_THRESHOLD_MS = 500L
    }

    // Position/monotonic-clock at last sample (used to compute expected progression).
    private var lastSamplePositionMs = -1L
    private var lastSampleMonoMs = -1L

    // Position/monotonic-clock of last successful publish (used to suppress duplicates).
    private var lastPublishedPositionMs = -1L
    private var lastPublishedMonoMs = -1L

    // Pending scrub/settle state.
    private var pendingScrubTargetMs = -1L
    private var pendingScrubMonoMs = -1L

    /**
     * Called when the user finishes a timeline scrub (onScrubStop).
     * Always returns true — a scrub stop should always publish.
     * Enters a settle window to suppress stale pre-seek callbacks.
     */
    fun onScrubStop(targetMs: Long): Boolean {
        pendingScrubTargetMs = targetMs
        pendingScrubMonoMs = monotonicMs()
        // Don't markPublished here — we need the settle logic to handle
        // the real post-seek callback and establish the final baseline.
        return true
    }

    /**
     * Called from the player's periodic position callback during playback.
     * [currentSpeed] is the engine's effective playback speed at this sample.
     * Returns true only if a discontinuity (seek) is detected.
     *
     * During a pending scrub settle window:
     *  - stale callbacks far from target → suppressed (return false)
     *  - near-target callbacks → clear pending, rebaseline (return false, no duplicate)
     *  - settle timeout → clear pending, rebaseline (return false, recovery)
     *
     * Normal linear playback should NOT cause a publish.
     */
    fun onPlaybackPositionChanged(positionMs: Long, currentSpeed: Float): Boolean {
        val now = monotonicMs()

        // If position matches what we already published, skip.
        if (positionMs == lastPublishedPositionMs) return false

        // ── Pending scrub settle logic ──────────────────────────────────
        if (pendingScrubTargetMs >= 0) {
            val settleElapsed = now - pendingScrubMonoMs
            val nearTarget = kotlin.math.abs(positionMs - pendingScrubTargetMs) <= NEAR_TARGET_THRESHOLD_MS
            val settled = settleElapsed >= SETTLE_WINDOW_MS

            if (nearTarget || settled) {
                // Settle: clear pending state, rebaseline using actual settled position.
                clearPending()
                lastSamplePositionMs = positionMs
                lastSampleMonoMs = now
                return false
            }
            // Stale pre-seek callback: ignore entirely (don't publish, don't rebaseline).
            return false
        }

        // First call after reset — just record the sample.
        if (lastSamplePositionMs < 0) {
            lastSamplePositionMs = positionMs
            lastSampleMonoMs = now
            return false
        }

        // Calculate expected position based on monotonic elapsed time and playback speed.
        val elapsed = now - lastSampleMonoMs
        val expected = lastSamplePositionMs + (elapsed * currentSpeed).toLong()

        // Check for discontinuity.
        val deviation = positionMs - expected
        val isSeek = deviation > DISCONTINUITY_THRESHOLD_MS || -deviation > DISCONTINUITY_THRESHOLD_MS

        // Always update the sample.
        lastSamplePositionMs = positionMs
        lastSampleMonoMs = now

        if (isSeek) {
            markPublished(positionMs)
            return true
        }

        return false
    }

    /**
     * Called when playback state changes (play/pause).
     * Always returns true — a play/pause transition should always publish.
     * Resets the sample baseline since timing changes.
     */
    fun onPlayPause(): Boolean {
        clearPending()
        lastSamplePositionMs = -1L
        lastSampleMonoMs = -1L
        return true
    }

    /**
     * Called when playback speed changes. Resets the sampling baseline so the
     * speed transition itself is not misdetected as a seek. Does NOT publish —
     * the caller is responsible for publishing if desired.
     *
     * Speed is NOT stored by this class. The caller provides the authoritative
     * speed on each [onPlaybackPositionChanged] call.
     */
    fun onPlaybackSpeedChanged() {
        lastSamplePositionMs = -1L
        lastSampleMonoMs = -1L
    }

    /**
     * Called when a new episode/chapter/source is loaded.
     * Always returns true — an ownership transition should always publish.
     * Does NOT reset playback speed — the engine may continue at the same speed.
     */
    fun onOwnershipChange(): Boolean {
        lastSamplePositionMs = -1L
        lastSampleMonoMs = -1L
        clearPending()
        return true
    }

    fun reset() {
        lastSamplePositionMs = -1L
        lastSampleMonoMs = -1L
        lastPublishedPositionMs = -1L
        lastPublishedMonoMs = -1L
        clearPending()
    }

    private fun clearPending() {
        pendingScrubTargetMs = -1L
        pendingScrubMonoMs = -1L
    }

    private fun markPublished(positionMs: Long) {
        val now = monotonicMs()
        lastPublishedPositionMs = positionMs
        lastPublishedMonoMs = now
        lastSamplePositionMs = positionMs
        lastSampleMonoMs = now
    }

    /** Monotonic clock source — immune to civil-clock adjustments. Override in tests. */
    internal open fun monotonicMs(): Long = System.nanoTime() / 1_000_000
}
