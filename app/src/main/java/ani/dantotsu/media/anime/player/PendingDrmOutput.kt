package ani.dantotsu.media.anime.player

/**
 * MAX MERGE M4 correction (HOLD4 B1/B2): deterministic pending-output
 * state for the DRM engine. Output-affecting commands (surface, size,
 * speed, resize) can arrive before ExoPlayer exists — at bind time — and
 * must survive both initial creation and request-header rebuilds. The
 * engine stores the real Surface separately (Android-bound) and replays
 * everything else from this pure snapshot.
 */
class PendingDrmOutput {

    var hasSurface: Boolean = false
    var surfaceWidth: Int = 0
    var surfaceHeight: Int = 0
    var speed: Float = 1f
    var resize: ResizeMode = ResizeMode.FIT

    data class Snapshot(
        val hasSurface: Boolean,
        val surfaceWidth: Int,
        val surfaceHeight: Int,
        val speed: Float,
        val resize: ResizeMode,
    )

    fun snapshot(): Snapshot = Snapshot(hasSurface, surfaceWidth, surfaceHeight, speed, resize)

    fun restore(snapshot: Snapshot) {
        hasSurface = snapshot.hasSurface
        surfaceWidth = snapshot.surfaceWidth
        surfaceHeight = snapshot.surfaceHeight
        speed = snapshot.speed
        resize = snapshot.resize
    }

    fun clearSurface() {
        hasSurface = false
        surfaceWidth = 0
        surfaceHeight = 0
    }

    companion object {
        const val MIN_SPEED = 0.25f
        const val MAX_SPEED = 4f

        /**
         * Single clamp site: the engine applies AND reports this value, so
         * the reported speed always equals the applied speed.
         */
        fun clampSpeed(requested: Float): Float = requested.coerceIn(MIN_SPEED, MAX_SPEED)
    }
}
