package ani.dantotsu.animethemes

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

// ==========================================
// GraphQL Request & Response DTOs
// ==========================================

@Serializable
data class GraphQLRequest(
    val query: String,
    val variables: Map<String, JsonElement> = emptyMap()
)

@Serializable
data class AnimeThemesGraphQLResponse(
    val data: AnimeThemesDataResponse? = null,
    val errors: List<GraphQLErrorDto>? = null
)

@Serializable
data class GraphQLErrorDto(
    val message: String,
    val locations: List<GraphQLLocationDto>? = null
)

@Serializable
data class GraphQLLocationDto(
    val line: Int,
    val column: Int
)

@Serializable
data class AnimeThemesDataResponse(
    val findAnimeByExternalSite: List<AnimeThemesAnimeDto> = emptyList()
)

@Serializable
data class AnimeThemesAnimeDto(
    val id: Int,
    val slug: String,
    val title: AnimeThemesTitleDto? = null,
    val year: Int? = null,
    val season: String? = null,
    val format: String? = null,
    val animethemes: List<AnimeThemesThemeDto> = emptyList()
)

@Serializable
data class AnimeThemesTitleDto(
    val romaji: String? = null
)

@Serializable
data class AnimeThemesThemeDto(
    val id: Int,
    val type: String, // "OP" or "ED"
    val sequence: Int? = null,
    val slug: String, // "OP1", "ED2"
    val song: AnimeThemesSongDto? = null,
    val animethemeentries: List<AnimeThemesEntryDto> = emptyList()
)

@Serializable
data class AnimeThemesSongDto(
    val id: Int,
    val title: AnimeThemesTitleDto? = null,
    val performances: List<AnimeThemesPerformanceDto> = emptyList()
)

@Serializable
data class AnimeThemesPerformanceDto(
    val relevance: Int? = null,
    val alias: String? = null,
    @SerialName("as") val asAlias: String? = null,
    val memberAlias: String? = null,
    val memberAs: String? = null,
    val artist: AnimeThemesArtistDto? = null,
    val member: AnimeThemesArtistDto? = null
)

@Serializable
data class AnimeThemesArtistDto(
    val id: Int,
    val name: AnimeThemesArtistNameDto? = null
)

@Serializable
data class AnimeThemesArtistNameDto(
    val main: String? = null
)

@Serializable
data class AnimeThemesEntryDto(
    val id: Int,
    val version: Int? = 1,
    val episodes: String? = null,
    val nsfw: Boolean = false,
    val spoiler: Boolean = false,
    val videos: AnimeThemesVideoConnectionDto? = null
)

@Serializable
data class AnimeThemesVideoConnectionDto(
    val nodes: List<AnimeThemesVideoDto> = emptyList()
)

@Serializable
data class AnimeThemesVideoDto(
    val id: Int,
    val basename: String,
    val filename: String? = null,
    val resolution: Int? = null,
    val nc: Boolean = false,
    val subbed: Boolean = false,
    val source: String? = null,
    val overlap: String? = null,
    val tags: String? = null,
    val link: String,
    val audio: AnimeThemesAudioDto? = null
)

@Serializable
data class AnimeThemesAudioDto(
    val link: String
)

// ==========================================
// Persistent Disk Cache DTOs (@Serializable)
// ==========================================

// 3: videos carry their audio link (audio-only playback and downloads).
const val CACHE_VERSION = 3

@Serializable
data class AnimeThemesDiskCache(
    val version: Int = CACHE_VERSION,
    val entries: MutableMap<String, CachedAnimeThemesEntryDto> = mutableMapOf()
)

@Serializable
data class CachedAnimeThemesEntryDto(
    val cacheKey: String, // "anilist_123" or "mal_456"
    val mediaId: Int,
    val timestamp: Long,
    val isNegativeMatch: Boolean,
    val payload: AnimeThemesCachePayloadDto? = null
)

@Serializable
data class AnimeThemesCachePayloadDto(
    val animeId: Int,
    val animeSlug: String,
    val openings: List<ThemeCacheDto> = emptyList(),
    val endings: List<ThemeCacheDto> = emptyList()
)

@Serializable
data class ThemeCacheDto(
    val themeId: Int,
    val type: String,
    val sequence: Int? = null,
    val displayTag: String,
    val songTitle: String,
    val artistDisplay: String,
    val alternateVersions: List<ThemeVersionCacheDto> = emptyList()
)

@Serializable
data class ThemeVersionCacheDto(
    val entryId: Int,
    val version: Int = 1,
    val episodes: String? = null,
    val isNsfw: Boolean = false,
    val isSpoiler: Boolean = false,
    val videos: List<ThemeVideoCacheDto> = emptyList()
)

@Serializable
data class ThemeVideoCacheDto(
    val videoId: Int,
    val videoUrl: String,
    val resolution: Int? = null,
    val isCreditless: Boolean = false,
    val source: String? = null,
    val overlap: String? = null,
    val tags: String? = null,
    val isSubbed: Boolean = false,
    val badgeLabel: String,
    val audioUrl: String? = null
)

// ==========================================
// Domain / UI Models
// ==========================================

data class AnimeThemesData(
    val animeId: Int,
    val animeSlug: String,
    val openings: List<ThemeUiModel>,
    val endings: List<ThemeUiModel>
)

data class ThemeUiModel(
    val themeId: Int,
    val type: String, // "OP" or "ED"
    val sequence: Int?,
    val displayTag: String, // "OP 1", "ED 2"
    val songTitle: String,
    val artistDisplay: String,
    val alternateVersions: List<ThemeVersionUiModel>
)

enum class ThemeWarningType {
    NONE,
    SPOILER,
    NSFW,
    SPOILER_AND_NSFW
}

data class ThemeVersionUiModel(
    val entryId: Int,
    val version: Int,
    val episodes: String?,
    val isNsfw: Boolean,
    val isSpoiler: Boolean,
    val videos: List<ThemeVideoUiModel>
)

data class ThemeVideoUiModel(
    val videoId: Int,
    val videoUrl: String,
    val resolution: Int?,
    val isCreditless: Boolean,
    val source: String?,
    val overlap: String?,
    val tags: String?,
    val isSubbed: Boolean,
    val badgeLabel: String, // e.g. "1080p • BD • NC"
    val audioUrl: String? = null
)

data class ThemeVideoVariant(
    val videoId: Int,
    val videoUrl: String,
    val resolution: Int?,
    val isCreditless: Boolean,
    val source: String?,
    val overlap: String?,
    val tags: String?,
    val isSubbed: Boolean,
    val entryVersion: Int,
    val episodes: String?,
    val isSpoiler: Boolean,
    val isNsfw: Boolean,
    val displayLabel: String,
    val warningType: ThemeWarningType,
    val audioUrl: String? = null
)

// ==========================================
// Playback Preferences Models
// ==========================================

enum class ResolutionPref(val value: Int) {
    AUTO(0),
    P1080(1),
    P720(2),
    P480_OR_LOWER(3);

    companion object {
        fun fromInt(value: Int): ResolutionPref = entries.find { it.value == value } ?: AUTO
    }
}

enum class SourcePref(val value: Int) {
    AUTO(0),
    BD(1),
    WEB(2);

    companion object {
        fun fromInt(value: Int): SourcePref = entries.find { it.value == value } ?: AUTO
    }
}

enum class CreditsPref(val value: Int) {
    AUTO(0),
    NC(1),
    WITH_CREDITS(2);

    companion object {
        fun fromInt(value: Int): CreditsPref = entries.find { it.value == value } ?: AUTO
    }
}

enum class SubtitlesPref(val value: Int) {
    AUTO(0),
    NO_SUBTITLES(1),
    SUBTITLED(2);

    companion object {
        fun fromInt(value: Int): SubtitlesPref = entries.find { it.value == value } ?: AUTO
    }
}

data class ThemePlaybackPreferences(
    val resolution: ResolutionPref = ResolutionPref.AUTO,
    val source: SourcePref = SourcePref.AUTO,
    val credits: CreditsPref = CreditsPref.AUTO,
    val subtitles: SubtitlesPref = SubtitlesPref.AUTO
)

// ==========================================
// Mapping Extensions (Domain <-> Cache DTOs)
// ==========================================

fun AnimeThemesData.toCachePayload(): AnimeThemesCachePayloadDto {
    return AnimeThemesCachePayloadDto(
        animeId = animeId,
        animeSlug = animeSlug,
        openings = openings.map { it.toCacheDto() },
        endings = endings.map { it.toCacheDto() }
    )
}

fun AnimeThemesCachePayloadDto.toDomain(): AnimeThemesData {
    return AnimeThemesData(
        animeId = animeId,
        animeSlug = animeSlug,
        openings = openings.map { it.toDomain() },
        endings = endings.map { it.toDomain() }
    )
}

fun ThemeUiModel.toCacheDto(): ThemeCacheDto {
    return ThemeCacheDto(
        themeId = themeId,
        type = type,
        sequence = sequence,
        displayTag = displayTag,
        songTitle = songTitle,
        artistDisplay = artistDisplay,
        alternateVersions = alternateVersions.map { it.toCacheDto() }
    )
}

fun ThemeCacheDto.toDomain(): ThemeUiModel {
    return ThemeUiModel(
        themeId = themeId,
        type = type,
        sequence = sequence,
        displayTag = ThemeTags.normalize(displayTag),
        songTitle = songTitle,
        artistDisplay = artistDisplay,
        alternateVersions = alternateVersions.map { it.toDomain() }
    )
}

fun ThemeVersionUiModel.toCacheDto(): ThemeVersionCacheDto {
    return ThemeVersionCacheDto(
        entryId = entryId,
        version = version,
        episodes = episodes,
        isNsfw = isNsfw,
        isSpoiler = isSpoiler,
        videos = videos.map { it.toCacheDto() }
    )
}

fun ThemeVersionCacheDto.toDomain(): ThemeVersionUiModel {
    return ThemeVersionUiModel(
        entryId = entryId,
        version = version,
        episodes = episodes,
        isNsfw = isNsfw,
        isSpoiler = isSpoiler,
        videos = videos.map { it.toDomain() }
    )
}

fun ThemeVideoUiModel.toCacheDto(): ThemeVideoCacheDto {
    return ThemeVideoCacheDto(
        videoId = videoId,
        videoUrl = videoUrl,
        resolution = resolution,
        isCreditless = isCreditless,
        source = source,
        overlap = overlap,
        tags = tags,
        isSubbed = isSubbed,
        badgeLabel = badgeLabel,
        audioUrl = audioUrl
    )
}

fun ThemeVideoCacheDto.toDomain(): ThemeVideoUiModel {
    return ThemeVideoUiModel(
        videoId = videoId,
        videoUrl = videoUrl,
        resolution = resolution,
        isCreditless = isCreditless,
        source = source,
        overlap = overlap,
        tags = tags,
        isSubbed = isSubbed,
        badgeLabel = badgeLabel,
        audioUrl = audioUrl
    )
}

// ==========================================
// API Result Contracts
// ==========================================

sealed interface AnimeThemesApiResult {
    data class Success(val response: AnimeThemesGraphQLResponse) : AnimeThemesApiResult
    data class Failure(val reason: ApiFailureReason, val message: String? = null) : AnimeThemesApiResult
}

enum class ApiFailureReason {
    INVALID_PARAMETERS,
    NETWORK_ERROR,
    GRAPHQL_ERROR,
    EMPTY_RESPONSE,
    DECODE_ERROR,
    UNKNOWN_ERROR
}
