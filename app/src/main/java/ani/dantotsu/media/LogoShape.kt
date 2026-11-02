package ani.dantotsu.media

/**
 * Whether an image fetched as a title logo looks like one (pure; JVM-tested).
 * Title logos are transparent PNG/WebP art: a good part of the image is see-through.
 * Logo sites sometimes return a scene image instead (opaque, often square), which
 * looks wrong in the carousel; those fall back to the text title.
 */
object LogoShape {
    /** Size the image is decoded at for the check. */
    const val CHECK_PX = 256

    /** Taller than this (width / height) is not a title logo. */
    const val MIN_ASPECT = 0.8

    /** Share of sampled pixels that must be (nearly) transparent. */
    const val MIN_TRANSPARENT = 0.15

    private const val GRID = 24
    private const val TRANSPARENT_ALPHA = 40

    fun looksLikeLogo(width: Int, height: Int, hasAlpha: Boolean, alphaAt: (x: Int, y: Int) -> Int): Boolean {
        if (width <= 0 || height <= 0) return false
        if (width.toDouble() / height < MIN_ASPECT) return false
        if (!hasAlpha) return false
        var transparent = 0
        var total = 0
        for (gy in 0 until GRID) {
            val y = ((gy + 0.5) * height / GRID).toInt().coerceIn(0, height - 1)
            for (gx in 0 until GRID) {
                val x = ((gx + 0.5) * width / GRID).toInt().coerceIn(0, width - 1)
                total++
                if (alphaAt(x, y) < TRANSPARENT_ALPHA) transparent++
            }
        }
        return transparent.toDouble() / total >= MIN_TRANSPARENT
    }
}
