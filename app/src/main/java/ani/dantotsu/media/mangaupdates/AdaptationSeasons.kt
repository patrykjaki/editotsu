package ani.dantotsu.media.mangaupdates

/**
 * Turns the adaptation strings ("Vol 1, Chap 1 (S1) / Vol 7, Chap 59 (S2)" for the starts and
 * "Vol 7, Chap 58 (S1) / TBA" for the ends) into one entry per anime season.
 */
object AdaptationSeasons {

    data class Point(val volume: String?, val chapter: String?)

    data class Season(
        /** "1" for "(S1)", the bracket text otherwise, null without one. */
        val label: String?,
        val start: Point?,
        val end: Point?,
        /** The end is "TBA" or missing: the season is still airing or announced. */
        val ongoing: Boolean,
    )

    private val volume = Regex("""\bVol(?:ume)?\.?\s*([\d.]+)""", RegexOption.IGNORE_CASE)
    private val chapter = Regex("""\bCh(?:ap(?:ter)?)?\.?\s*([\d.]+)""", RegexOption.IGNORE_CASE)
    private val bracket = Regex("""\(([^)]+)\)""")
    private val seasonCode = Regex("""^S(\d+)$""", RegexOption.IGNORE_CASE)

    fun parse(start: String?, end: String?): List<Season> {
        val starts = segments(start)
        val ends = segments(end)
        val count = maxOf(starts.size, ends.size)
        return (0 until count).map { i ->
            val s = starts.getOrNull(i)
            val e = ends.getOrNull(i)
            val label = (s?.let(::label) ?: e?.let(::label))
            val endPoint = e?.let(::point)
            Season(label, s?.let(::point), endPoint, ongoing = endPoint == null)
        }
    }

    /** The chapter after the last adapted one, when the last season has finished. */
    fun readFrom(seasons: List<Season>): String? {
        val last = seasons.lastOrNull() ?: return null
        if (last.ongoing) return null
        val ch = last.end?.chapter?.toDoubleOrNull() ?: return null
        return (kotlin.math.floor(ch).toLong() + 1).toString()
    }

    /** True when nothing in the strings could be read as a volume or chapter. */
    fun unreadable(seasons: List<Season>) =
        seasons.none { it.start != null || it.end != null }

    private fun segments(raw: String?): List<String> =
        raw?.split('/')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()

    private fun label(segment: String): String? {
        val text = bracket.find(segment)?.groupValues?.get(1)?.trim() ?: return null
        return seasonCode.find(text)?.groupValues?.get(1) ?: text
    }

    private fun point(segment: String): Point? {
        val v = volume.find(segment)?.groupValues?.get(1)
        val c = chapter.find(segment)?.groupValues?.get(1)
        return if (v == null && c == null) null else Point(v, c)
    }
}
