package ani.dantotsu.connections.anilist

import ani.dantotsu.connections.anilist.api.ScoreFormat
import kotlin.math.roundToInt

/**
 * Score conversions between AniList's raw score (POINT_100, 0-100, what the app
 * reads with `score(format:POINT_100)` and writes with `scoreRaw`) and the
 * user's chosen AniList score format.
 *
 * Rules:
 * - 0 always means "not scored", in every format.
 * - Any raw score above 0 shows as at least the lowest non-zero step, so a
 *   scored entry never looks unscored.
 * - Read rounding is half-up. POINT_5 and POINT_3 read rules match Mihon's
 *   AniList tracker (stars = round(raw / 20); smileys: <=35, <=60, above).
 * - Write values: stars n -> n * 20, smileys 1/2/3 -> 35/60/85.
 */
object ScoreFormats {

    /** Parses the string kept in [Anilist.scoreFormat]; unknown or null -> POINT_100. */
    fun parse(name: String?): ScoreFormat =
        ScoreFormat.entries.firstOrNull { it.name == name } ?: ScoreFormat.POINT_100

    /** Largest value the user can enter in [format]. */
    fun max(format: ScoreFormat): Double = when (format) {
        ScoreFormat.POINT_100 -> 100.0
        ScoreFormat.POINT_10_DECIMAL, ScoreFormat.POINT_10 -> 10.0
        ScoreFormat.POINT_5 -> 5.0
        ScoreFormat.POINT_3 -> 3.0
    }

    /** True when [format] accepts one decimal place. */
    fun allowsDecimal(format: ScoreFormat): Boolean = format == ScoreFormat.POINT_10_DECIMAL

    /**
     * Raw 0-100 score -> value in [format]. POINT_10_DECIMAL keeps one decimal;
     * every other format returns a whole number.
     */
    fun fromRaw(raw: Int, format: ScoreFormat): Double {
        val r = raw.coerceIn(0, 100)
        if (r == 0) return 0.0
        return when (format) {
            ScoreFormat.POINT_100 -> r.toDouble()
            ScoreFormat.POINT_10_DECIMAL -> r / 10.0
            ScoreFormat.POINT_10 -> halfUp(r, 10).coerceAtLeast(1).toDouble()
            ScoreFormat.POINT_5 -> halfUp(r, 20).coerceAtLeast(1).toDouble()
            ScoreFormat.POINT_3 -> when {
                r <= 35 -> 1.0
                r <= 60 -> 2.0
                else -> 3.0
            }
        }
    }

    /**
     * Value in [format] -> raw 0-100 for `scoreRaw`. Out-of-range input is
     * clamped; values between steps round half-up to the nearest step.
     */
    fun toRaw(value: Double, format: ScoreFormat): Int {
        if (value.isNaN() || value <= 0.0) return 0
        val v = value.coerceAtMost(max(format))
        return when (format) {
            ScoreFormat.POINT_100 -> roundHalfUp(v)
            // Round on tenths first so 7.7 -> 77, not 76 from 76.999...
            ScoreFormat.POINT_10_DECIMAL -> roundHalfUp(v * 10.0)
            ScoreFormat.POINT_10 -> roundHalfUp(v) * 10
            ScoreFormat.POINT_5 -> roundHalfUp(v) * 20
            ScoreFormat.POINT_3 -> when (roundHalfUp(v)) {
                1 -> 35
                2 -> 60
                else -> 85
            }
        }.coerceIn(0, 100)
    }

    /** Text for the user's own score in [format]; "" when unscored. */
    fun display(raw: Int, format: ScoreFormat): String {
        if (raw <= 0) return ""
        val v = fromRaw(raw, format)
        return when (format) {
            ScoreFormat.POINT_10_DECIMAL -> "%.1f".format(java.util.Locale.ROOT, v)
            ScoreFormat.POINT_3 -> SMILEYS[v.toInt()]
            else -> v.toInt().toString()
        }
    }

    /**
     * Raw AniList score -> MAL 0-10 integer. Half-up rounding; any scored
     * entry maps to at least 1, because MAL score 0 clears the score.
     */
    fun toMal(raw: Int): Int {
        val r = raw.coerceIn(0, 100)
        if (r == 0) return 0
        return halfUp(r, 10).coerceIn(1, 10)
    }

    private val SMILEYS = listOf("", "🙁", "😐", "🙂") // 🙁 😐 🙂

    // Integer half-up division for non-negative ints: halfUp(75, 20) = 4.
    private fun halfUp(n: Int, d: Int): Int = (n + d / 2) / d

    // Double rounding with a small epsilon so 0.1-step float error never flips a result.
    private fun roundHalfUp(v: Double): Int = (v + 1e-9).roundToInt()
}
