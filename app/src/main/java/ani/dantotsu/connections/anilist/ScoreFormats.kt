package ani.dantotsu.connections.anilist

import ani.dantotsu.connections.anilist.api.ScoreFormat
import kotlin.math.roundToInt

/**
 * Score conversions between AniList's raw score (POINT_100, 0-100, what the app
 * reads with `score(format:POINT_100)` and writes with `scoreRaw`) and the
 * user's chosen AniList score format.
 *
 * Read rules match what AniList itself returns for `score(format: X)`
 * (sampled from public lists on 2026-10-31, raw 0-100):
 * - POINT_10: floor(raw / 10). Raw 1-9 shows as 0, as on AniList.
 * - POINT_5: round(raw / 20) half-up, at least 1 when scored (raw 1 -> 1).
 * - POINT_3: <=35 -> 1, <=60 -> 2, above -> 3. 30->1, 40->2, 60->2, 61->3
 *   were observed; the 31-39 edge is not (35 is Mihon's cutoff).
 * - 0 means "not scored" in every format.
 * Write values: stars n -> n * 20, smileys 1/2/3 -> 35/60/85.
 *
 * AniList user statistics: `meanScore` is always on the raw 0-100 scale;
 * `scores[].score` buckets are in the user's format, with POINT_10_DECIMAL
 * buckets as tenths (75 = 7.5). See [statsBucketToRaw].
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
            ScoreFormat.POINT_10 -> (r / 10).toDouble()
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
     * Raw AniList score -> MAL 0-10 integer: AniList's own POINT_10 value
     * (floor), but at least 1 when scored, because MAL score 0 clears it.
     */
    fun toMal(raw: Int): Int {
        val r = raw.coerceIn(0, 100)
        if (r == 0) return 0
        return (r / 10).coerceIn(1, 10)
    }

    /**
     * A user-statistics score bucket (`scores[].score`, in that user's
     * format) -> raw 0-100, so charts of users with different formats share
     * one axis. POINT_10_DECIMAL buckets are already tenths.
     */
    fun statsBucketToRaw(bucket: Int, format: ScoreFormat): Int = when (format) {
        ScoreFormat.POINT_100, ScoreFormat.POINT_10_DECIMAL -> bucket.coerceIn(0, 100)
        else -> toRaw(bucket.toDouble(), format)
    }

    /** Index 1-3 = POINT_3 values; index 0 = unscored. */
    val SMILEYS = listOf("", "🙁", "😐", "🙂") // 🙁 😐 🙂

    /** Max characters the editor accepts for [format]. */
    fun inputLength(format: ScoreFormat): Int = when (format) {
        ScoreFormat.POINT_100 -> 3
        ScoreFormat.POINT_10_DECIMAL -> 4
        ScoreFormat.POINT_10 -> 2
        ScoreFormat.POINT_5, ScoreFormat.POINT_3 -> 1
    }

    /** Editor text -> value in [format]; null if it can't be read. Blank -> 0. */
    fun parseInput(text: String, format: ScoreFormat): Double? {
        val t = text.trim().replace(',', '.')
        if (t.isEmpty()) return 0.0
        if (format == ScoreFormat.POINT_3) {
            val i = SMILEYS.indexOf(t)
            if (i > 0) return i.toDouble()
        }
        return t.toDoubleOrNull()?.takeIf { !it.isNaN() && it >= 0.0 }
    }

    /**
     * Raw score to save from the editor, or null to leave the stored score alone.
     * Unchanged text (what [display] showed) returns null, so opening and saving
     * never rewrites a raw score (e.g. raw 77 in POINT_5 stays 77, not 80).
     * Clearing a scored entry returns 0.
     */
    fun editedRaw(initialRaw: Int, text: String, format: ScoreFormat): Int? {
        if (text.trim() == display(initialRaw, format)) return null
        val v = parseInput(text, format) ?: return null
        val raw = toRaw(v, format)
        return if (raw == 0 && initialRaw <= 0) null else raw
    }

    /**
     * Score text on media cards: the user's own score in their format, or the
     * AniList average (0-100, shown /10 as before) when unscored.
     */
    fun cardText(userRaw: Int, meanScore: Int?, format: ScoreFormat): String =
        if (userRaw > 0) display(userRaw, format) else ((meanScore ?: 0) / 10.0).toString()

    // Integer half-up division for non-negative ints: halfUp(75, 20) = 4.
    private fun halfUp(n: Int, d: Int): Int = (n + d / 2) / d

    // Double rounding with a small epsilon so 0.1-step float error never flips a result.
    private fun roundHalfUp(v: Double): Int = (v + 1e-9).roundToInt()
}
