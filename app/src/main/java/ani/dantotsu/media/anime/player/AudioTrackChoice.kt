package ani.dantotsu.media.anime.player

/**
 * The audio track picked for a show, saved as `<language>|<title>` and restored on the
 * next episode, server or reopen (dual-audio releases: Japanese vs English dub).
 */
object AudioTrackChoice {

    fun encode(track: PlayerTrack): String =
        "${track.language.orEmpty().replace("|", " ")}|${track.name.orEmpty()}"

    private fun decode(saved: String): Pair<String?, String?> {
        val parts = saved.split('|', limit = 2)
        return parts.getOrNull(0)?.ifBlank { null } to parts.getOrNull(1)?.ifBlank { null }
    }

    /**
     * The audio track to select for [saved], or null when none fits or the saved one is
     * already selected. Same language and title first, then the same language (by code or
     * name, see [SubtitleLanguageMatch.matches]), then the same title.
     */
    fun restore(tracks: List<PlayerTrack>, saved: String?): PlayerTrack? {
        if (saved.isNullOrBlank()) return null
        val audio = tracks.filter { it.type == TrackType.AUDIO }
        if (audio.size < 2) return null
        val (lang, title) = decode(saved)
        val match = audio.firstOrNull { lang != null && title != null && it.name == title && SubtitleLanguageMatch.matches(it.language, null, lang) }
            ?: lang?.let { l -> audio.firstOrNull { SubtitleLanguageMatch.matches(it.language, null, l) } }
            ?: title?.let { t -> audio.firstOrNull { it.name == t } }
            ?: return null
        return if (match.selected) null else match
    }
}
