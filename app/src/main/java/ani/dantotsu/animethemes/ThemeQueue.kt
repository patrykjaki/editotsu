package ani.dantotsu.animethemes

import kotlinx.serialization.Serializable

/**
 * One playable version of an opening or ending, with the video picked for it and
 * the other videos to fall back to when the CDN refuses the first one.
 * [version] is AnimeThemes' entry version (v1, v2, …); [versionCount] is how many
 * playable versions the theme has.
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
    val entryId: Int = 0,
    val version: Int = 1,
    val versionCount: Int = 1,
    /** AnimeThemes .ogg of this version; null when AnimeThemes has none (or the cache predates v3). */
    val audioUrl: String? = null,
    val isEnding: Boolean = false,
    /** AniList id and title of the show; set by the screen that builds the queue. */
    val mediaId: Int = 0,
    val showTitle: String = "",
)

/** One theme on the details strip: its playable versions and the one the selector prefers. */
data class ThemeChoice(
    val versions: List<ThemeTrack>,
    val auto: ThemeTrack,
    val isEnding: Boolean = false,
)

/** Pure helpers that turn [AnimeThemesData] into what the details strip and the player show. */
object ThemeQueue {

    /** Openings first, then endings, each in sequence order; themes without a video are dropped. */
    fun tracks(
        data: AnimeThemesData,
        prefs: ThemePlaybackPreferences = ThemePlaybackPreferences(),
    ): List<ThemeTrack> = choices(data, prefs).map { it.auto }

    fun choices(
        data: AnimeThemesData,
        prefs: ThemePlaybackPreferences = ThemePlaybackPreferences(),
    ): List<ThemeChoice> = (data.openings + data.endings).mapNotNull { choice(it, prefs) }

    /** [choice] with every version tagged with its show, for queues that mix shows. */
    fun withShow(choice: ThemeChoice, mediaId: Int, title: String): ThemeChoice = choice.copy(
        versions = choice.versions.map { it.copy(mediaId = mediaId, showTitle = title) },
        auto = choice.auto.copy(mediaId = mediaId, showTitle = title),
    )

    fun track(theme: ThemeUiModel, prefs: ThemePlaybackPreferences = ThemePlaybackPreferences()): ThemeTrack? =
        choice(theme, prefs)?.auto

    /**
     * The theme's versions, one track each, in version order. [ThemeChoice.auto] is the
     * version holding the video the selector prefers across all versions (not flagged
     * spoiler/NSFW when another version isn't).
     */
    fun choice(theme: ThemeUiModel, prefs: ThemePlaybackPreferences = ThemePlaybackPreferences()): ThemeChoice? {
        val entries = theme.alternateVersions
            .map { entry -> entry to AnimeThemeVideoSelector.extractVariants(theme.copy(alternateVersions = listOf(entry))) }
            .filter { it.second.isNotEmpty() }
            .sortedWith(compareBy({ it.first.version }, { it.first.entryId }))
        if (entries.isEmpty()) return null
        val count = entries.size
        val versions = entries.map { (entry, variants) -> versionTrack(theme, entry, variants, count, prefs) }
        val best = AnimeThemeVideoSelector.selectPreferredVideo(entries.flatMap { it.second }, prefs)
        val autoEntry = entries.firstOrNull { (_, v) -> v.any { it.videoUrl == best?.videoUrl } }?.first
        val auto = versions.firstOrNull { it.entryId == autoEntry?.entryId } ?: versions.first()
        return ThemeChoice(versions, auto, isEnding = theme.type.equals("ED", ignoreCase = true))
    }

    private fun versionTrack(
        theme: ThemeUiModel,
        entry: ThemeVersionUiModel,
        variants: List<ThemeVideoVariant>,
        count: Int,
        prefs: ThemePlaybackPreferences,
    ): ThemeTrack {
        val chosen = AnimeThemeVideoSelector.selectPreferredVideo(variants, prefs)!!
        // Fallbacks stay inside this version: other versions have other visuals and may spoil.
        val fallbacks = variants
            .filter { it.videoUrl != chosen.videoUrl }
            .sortedWith(
                compareByDescending<ThemeVideoVariant> { AnimeThemeVideoSelector.neutralAutoRank(it) }
                    .thenBy { it.videoId }
            )
            .map { it.videoUrl }
            .distinct()
        return ThemeTrack(
            themeId = theme.themeId,
            tag = theme.displayTag,
            songTitle = theme.songTitle,
            artist = theme.artistDisplay,
            episodes = entry.episodes?.trim()?.takeIf { it.isNotEmpty() },
            url = chosen.videoUrl,
            fallbackUrls = fallbacks,
            warning = chosen.warningType,
            entryId = entry.entryId,
            version = entry.version,
            versionCount = count,
            audioUrl = chosen.audioUrl ?: variants.firstNotNullOfOrNull { it.audioUrl },
            isEnding = theme.type.equals("ED", ignoreCase = true),
        )
    }

    /**
     * The version the card shows and plays: the selector's pick unless the episode
     * shield hides it, then the first unhidden version without a spoiler/NSFW flag,
     * then the first unhidden one. When every version is hidden, the selector's pick.
     */
    fun preferredVersion(choice: ThemeChoice, isShielded: (ThemeTrack) -> Boolean): ThemeTrack {
        if (!isShielded(choice.auto)) return choice.auto
        val open = choice.versions.filterNot(isShielded)
        return open.firstOrNull { it.warning == ThemeWarningType.NONE } ?: open.firstOrNull() ?: choice.auto
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
        all.mapNotNull { if (it.themeId == tapped.themeId) tapped else it.takeUnless(isShielded) }

    /** Player spinner / notification label: "OP 1 · Song", "OP 1 v2 · Song" when the theme has several versions. */
    fun label(track: ThemeTrack): String {
        val tag = if (track.versionCount > 1) "${track.tag} v${track.version}" else track.tag
        return if (track.songTitle.isBlank() || track.songTitle == track.tag) tag else "$tag · ${track.songTitle}"
    }

    /**
     * True when playback stopped well before the end: mpv reports a dropped
     * connection as end of file. Unknown duration counts as a normal end.
     */
    fun endedEarly(positionMs: Long, durationMs: Long): Boolean =
        durationMs > 0L && positionMs < durationMs - 3_000L

    data class Attempt(val url: String, val delayMs: Long)

    /**
     * Where [track] can be played from, in order. Audio only: saved audio, the .ogg,
     * then saved video and the videos. Otherwise: saved video, the videos, then saved
     * audio. A saved copy of the other kind never goes before the kind asked for,
     * except [offline], when both saved copies go first.
     */
    fun sources(
        track: ThemeTrack,
        audioOnly: Boolean = false,
        savedAudio: String? = null,
        savedVideo: String? = null,
        offline: Boolean = false,
    ): List<String> {
        val videos = listOf(track.url) + track.fallbackUrls
        val list = when {
            offline && audioOnly -> listOfNotNull(savedAudio, savedVideo, track.audioUrl) + videos
            offline -> listOfNotNull(savedVideo, savedAudio) + videos
            audioOnly -> listOfNotNull(savedAudio, track.audioUrl, savedVideo) + videos
            else -> listOfNotNull(savedVideo) + videos + listOfNotNull(savedAudio)
        }
        return list.distinct()
    }

    /** True when audio-only was asked for and the first source of [sources] is not audio. */
    fun audioMissing(audioOnly: Boolean, sources: List<String>, savedAudio: String?): Boolean =
        audioOnly && sources.firstOrNull()?.let { it != savedAudio && !isAudio(it) } ?: false

    /**
     * Every attempt for [sources]: a local copy once, the first network source three
     * times (0 s, 1 s, 3 s), each other source once after 0.5 s.
     */
    fun attempts(sources: List<String>): List<Attempt> {
        val out = mutableListOf<Attempt>()
        var firstNetwork = true
        sources.forEach { url ->
            when {
                !ThemeReach.needsCheck(url) -> out += Attempt(url, if (out.isEmpty()) 0L else 500L)
                firstNetwork -> {
                    firstNetwork = false
                    val start = if (out.isEmpty()) 0L else 500L
                    out += Attempt(url, start)
                    out += Attempt(url, 1_000L)
                    out += Attempt(url, 3_000L)
                }
                else -> out += Attempt(url, 500L)
            }
        }
        return out
    }

    /** Delay before [attempt] after the CDN answered [reach]; a rate limit waits as long as it asks. */
    fun delayFor(attempt: Attempt, reach: ThemeReachResult?): Long = when (reach) {
        is ThemeReachResult.RateLimited -> maxOf(attempt.delayMs, reach.retryAfterS * 1_000L)
        else -> attempt.delayMs
    }

    /** True when [url] is the AnimeThemes audio file rather than a video. */
    fun isAudio(url: String): Boolean = url.substringBefore('?').endsWith(".ogg", ignoreCase = true)

    /**
     * True when AnimeThemes' episode field covers [episode]: "1-12", "13-", "2-5, 8-11",
     * "1000". Null when the field has no number (unknown).
     */
    fun coversEpisode(episodes: String?, episode: Int): Boolean? {
        if (episodes.isNullOrBlank()) return null
        var known = false
        episodes.split(',').forEach { part ->
            val nums = Regex("\\d+").findAll(part).map { it.value.toInt() }.toList()
            if (nums.isEmpty()) return@forEach
            known = true
            val from = nums[0]
            val to = when {
                nums.size >= 2 -> nums[1]
                part.trim().endsWith("-") -> Int.MAX_VALUE
                else -> from
            }
            if (episode in from..to) return true
        }
        return if (known) false else null
    }

    /**
     * The opening ([ending] false) or ending playing in [episode]: the version whose
     * episodes cover it; when no version says, the only theme of that kind. Null when
     * it can't be told.
     */
    fun nowPlaying(choices: List<ThemeChoice>, ending: Boolean, episode: Int): ThemeTrack? {
        val kind = choices.filter { it.isEnding == ending }
        kind.forEach { c -> c.versions.firstOrNull { coversEpisode(it.episodes, episode) == true }?.let { return it } }
        val unknown = kind.filter { c -> c.versions.all { coversEpisode(it.episodes, episode) == null } }
        return if (kind.size == 1 && unknown.size == 1) kind[0].auto else null
    }
}
