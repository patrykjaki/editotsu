package ani.dantotsu.media.manga.mangareader

import kotlin.math.roundToInt

/**
 * Longest side a decoded reader page may have. 8192 px is the smallest
 * maximum texture size still common on Android GPUs, so a page within it can
 * always be drawn; a 1080 x 8192 strip is about 35 MB in ARGB_8888, well
 * under the 100 MB per-bitmap draw limit. Ordinary pages (1-3k px) never
 * reach it, so their quality is unchanged; only long webtoon strips and very
 * large scans are scaled down.
 */
const val MAX_PAGE_DIMENSION = 8192

/** The size to scale a page to, or null when it already fits. */
fun cappedPageSize(width: Int, height: Int, maxDimension: Int = MAX_PAGE_DIMENSION): Pair<Int, Int>? {
    if (width <= 0 || height <= 0) return null
    val longest = maxOf(width, height)
    if (longest <= maxDimension) return null
    val scale = maxDimension.toDouble() / longest
    val w = if (width >= height) maxDimension else (width * scale).roundToInt().coerceAtLeast(1)
    val h = if (height >= width) maxDimension else (height * scale).roundToInt().coerceAtLeast(1)
    return w to h
}

/**
 * Largest power-of-two decode sample size that keeps the longest side at
 * least [maxDimension], so decoding never allocates the full-size strip and
 * an exact scale finishes the job without losing more detail than needed.
 */
fun pageInSampleSize(width: Int, height: Int, maxDimension: Int = MAX_PAGE_DIMENSION): Int {
    val longest = maxOf(width, height)
    if (longest <= 0) return 1
    var sample = 1
    while (longest / (sample * 2) >= maxDimension) sample *= 2
    return sample
}
