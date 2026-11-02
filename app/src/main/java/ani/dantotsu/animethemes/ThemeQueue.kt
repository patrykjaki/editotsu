package ani.dantotsu.animethemes

import kotlinx.serialization.Serializable

/**
 * One playable opening or ending, with the video picked for it and the other
 * videos of the same theme to fall back to when the CDN refuses the first one.
 */
@Serializable
data class ThemeTrack(
    val themeId: Int,
    val tag: String,
    val songTitle: String,
    val artist: String,
    val episodes: String?,
    val url: String,
    val fallbackUrls: List<String>,
    val warning: ThemeWarningType,
)

/** Pure helpers that turn [AnimeThemesData] into what the details strip and the player show. */
object ThemeQueue {

    /** Openings first, then endings, each in sequence order; themes without a video are dropped. */
    fun tracks(
        data: AnimeThemesData,
        prefs: ThemePlaybackPreferences = ThemePlaybackPreferences(),
    ): List<ThemeTrack> = (data.openings + data.endings).mapNotNull { track(it, prefs) }

    fun track(theme: ThemeUiModel, prefs: ThemePlaybackPreferences = ThemePlaybackPreferences()): ThemeTrack? {
        val variants = AnimeThemeVideoSelector.extractVariants(theme)
        val chosen = AnimeThemeVideoSelector.selectPreferredVideo(variants, prefs) ?: return null
        val fallbacks = variants
            .filter { it.videoUrl != chosen.videoUrl }
            .sortedWith(
                compareBy<ThemeVideoVariant> { it.isSpoiler || it.isNsfw }
                    .thenByDescending { AnimeThemeVideoSelector.neutralAutoRank(it) }
                    .thenBy { it.videoId }
            )
            .map { it.videoUrl }
            .distinct()
        return ThemeTrack(
            themeId = theme.themeId,
            tag = theme.displayTag,
            songTitle = theme.songTitle,
            artist = theme.artistDisplay,
            episodes = chosen.episodes?.trim()?.takeIf { it.isNotEmpty() }
                ?: theme.alternateVersions.firstNotNullOfOrNull { v -> v.episodes?.trim()?.takeIf { it.isNotEmpty() } },
            url = chosen.videoUrl,
            fallbackUrls = fallbacks,
            warning = chosen.warningType,
        )
    }

    /**
     * First episode a theme plays in, from AnimeThemes' free-form episode field
     * ("1-12", "2-13, 24", "1000", "13-"). Null when the field has no number.
     */
    fun firstEpisode(episodes: String?): Int? {
        if (episodes.isNullOrBlank()) return null
        return Regex("\\d+").find(episodes)?.value?.toIntOrNull()
    }

    /**
     * Spoiler shield for a theme: hidden when the shield is on and the theme first
     * plays after the user's progress. Unknown episodes are never hidden.
     */
    fun shielded(enabled: Boolean, episodes: String?, userProgress: Int?): Boolean {
        if (!enabled) return false
        val first = firstEpisode(episodes) ?: return false
        return first > (userProgress ?: 0) + 1
    }

    /** "Eps 1–47" / "Ep 1000"; null when the field is empty. */
    fun episodesLabel(episodes: String?): String? {
        val text = episodes?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val single = text.all { it.isDigit() }
        return (if (single) "Ep " else "Eps ") + text.replace("-", "–")
    }

    /** What to put in the player queue when [tapped] is opened: visible themes plus the tapped one, in order. */
    fun playQueue(all: List<ThemeTrack>, tapped: ThemeTrack, isShielded: (ThemeTrack) -> Boolean): List<ThemeTrack> =
        all.filter { it.themeId == tapped.themeId || !isShielded(it) }

    /** Player spinner / notification label: "OP 1 · Song". */
    fun label(track: ThemeTrack): String =
        if (track.songTitle.isBlank() || track.songTitle == track.tag) track.tag else "${track.tag} · ${track.songTitle}"

    /**
     * True when playback stopped well before the end: mpv reports a dropped
     * connection as end of file. Unknown duration counts as a normal end.
     */
    fun endedEarly(positionMs: Long, durationMs: Long): Boolean =
        durationMs > 0L && positionMs < durationMs - 3_000L

    data class Attempt(val url: String, val delayMs: Long)

    /**
     * What to load after the [failures]-th failure of [track]: the same video twice
     * more (the CDN answers 503 under load), then each of the theme's other videos.
     * Null when everything was tried.
     */
    fun nextAttempt(track: ThemeTrack, failures: Int): Attempt? = when {
        failures <= 0 -> Attempt(track.url, 0L)
        failures == 1 -> Attempt(track.url, 1_000L)
        failures == 2 -> Attempt(track.url, 3_000L)
        else -> track.fallbackUrls.getOrNull(failures - 3)?.let { Attempt(it, 500L) }
    }
}
