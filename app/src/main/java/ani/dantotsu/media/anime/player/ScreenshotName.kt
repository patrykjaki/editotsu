package ani.dantotsu.media.anime.player

/**
 * File names for player screenshots, so the gallery sorts them by show and moment:
 *  - episode: "Kusuriya no Hitorigoto 2nd Season E5 12m34s.jpg"
 *  - theme:   "Frieren OP1 · Yuusha 0m45s.jpg" -> "Frieren OP1 Yuusha 0m45s.jpg"
 * AniList has no season number field; the title already carries it ("2nd Season").
 */
object ScreenshotName {
    private const val MAX_BASE = 120

    fun episode(title: String?, episode: String?, positionMs: Long): String {
        val ep = episode?.trim()?.takeIf { it.isNotEmpty() }?.let { "E$it" }
        return build(listOfNotNull(title, ep, time(positionMs)))
    }

    fun theme(anime: String?, label: String?, positionMs: Long): String =
        build(listOfNotNull(anime, label, time(positionMs)))

    /** Playback position as "1h02m03s" / "12m34s"; colons are not allowed in file names. */
    fun time(positionMs: Long): String {
        val total = (positionMs.coerceAtLeast(0L) / 1000L)
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) String.format(java.util.Locale.US, "%dh%02dm%02ds", h, m, s)
        else String.format(java.util.Locale.US, "%dm%02ds", m, s)
    }

    private fun build(parts: List<String>): String {
        val base = parts.joinToString(" ") { clean(it) }
            .replace(Regex("\\s+"), " ").trim()
            .take(MAX_BASE).trim()
        return (base.ifEmpty { "Editotsu" }) + ".jpg"
    }

    // Keeps non-Latin titles; drops only characters file systems reject, plus the "·" separator.
    private fun clean(s: String): String =
        s.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}·]"), " ")
}
