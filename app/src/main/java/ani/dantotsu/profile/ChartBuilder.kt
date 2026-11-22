package ani.dantotsu.profile

import ani.dantotsu.connections.anilist.ScoreFormats
import ani.dantotsu.connections.anilist.api.Query
import ani.dantotsu.connections.anilist.api.ScoreFormat
import java.io.Serializable
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Pure data models, label mappers, bucketing, and formatters for the native profile stats UI.
 * Kept free of Android framework dependencies so every transformation is directly unit-testable.
 */
object ChartBuilder {

    enum class MediaType {
        ANIME, MANGA;

        companion object {
            fun fromOrdinal(ordinal: Int): MediaType = entries.getOrElse(ordinal) { ANIME }
        }
    }

    enum class StatType {
        COUNT, TIME, AVG_SCORE;

        companion object {
            fun fromOrdinal(ordinal: Int): StatType = entries.getOrElse(ordinal) { COUNT }
        }
    }

    enum class ChartKind {
        RANKED_BARS,
        SCORE_HISTOGRAM,
        YEAR_TIMELINE,
    }

    enum class SectionId {
        STATUS,
        FORMAT,
        SCORE,
        GENRES,
        TAGS,
        RELEASE_YEAR,
        START_YEAR,
        LENGTH,
        COUNTRY,
        STUDIOS,
        VOICE_ACTORS,
        STAFF,
    }

    data class StatsSummary(
        val mediaType: MediaType,
        val primaryUser: String,
        val compareUser: String?,
        val count: Int,
        val episodesOrChapters: Int,
        val minutesOrVolumes: Int,
        val meanScore: Float,
        val compareCount: Int? = null,
        val compareEpisodesOrChapters: Int? = null,
        val compareMinutesOrVolumes: Int? = null,
        val compareMeanScore: Float? = null,
    ) : Serializable

    data class ChartEntry(
        val key: String,
        val label: String,
        val value: Double,
        val compareValue: Double? = null,
        val sharePercent: Double? = null,
        val count: Int = 0,
        val timeValue: Double = 0.0,
        val meanScore: Float = 0f,
        val compareCount: Int? = null,
        val compareTimeValue: Double? = null,
        val compareMeanScore: Float? = null,
    ) : Serializable

    data class ChartSection(
        val id: SectionId,
        val title: String,
        val kind: ChartKind,
        val mediaType: MediaType,
        val statType: StatType,
        val primaryUser: String,
        val compareUser: String?,
        val entries: List<ChartEntry>,
        val defaultCollapsedLimit: Int = DEFAULT_BAR_LIMIT,
        val subtitle: String? = null,
    ) : Serializable {
        val isComparing: Boolean get() = compareUser != null
    }

    data class StatsPayload(
        val mediaType: MediaType,
        val statType: StatType,
        val summary: StatsSummary?,
        val sections: List<ChartSection>,
        val hasOtherMediaStats: Boolean,
    ) : Serializable {
        val isEmpty: Boolean get() = summary == null || (summary.count == 0 && sections.isEmpty())
    }

    internal data class RawStatRow(
        val key: String,
        val label: String,
        val count: Int,
        val minutesWatched: Int,
        val chaptersRead: Int,
        val meanScore: Float,
    )

    const val DEFAULT_BAR_LIMIT = 8
    const val MAX_TIMELINE_GAP_FILL_SPAN = 45

    private val numberFormat: NumberFormat
        get() = NumberFormat.getIntegerInstance(Locale.US)

    // ---- Label formatting ----

    fun formatMediaFormat(raw: String): String = when (raw.uppercase(Locale.ROOT)) {
        "TV" -> "TV"
        "TV_SHORT" -> "TV short"
        "MOVIE" -> "Movie"
        "SPECIAL" -> "Special"
        "OVA" -> "OVA"
        "ONA" -> "ONA"
        "MUSIC" -> "Music"
        "MANGA" -> "Manga"
        "NOVEL" -> "Light novel"
        "ONE_SHOT" -> "One-shot"
        else -> toSentenceCase(raw)
    }

    fun formatMediaStatus(raw: String, mediaType: MediaType): String = when (raw.uppercase(Locale.ROOT)) {
        "CURRENT" -> if (mediaType == MediaType.ANIME) "Watching" else "Reading"
        "COMPLETED" -> "Completed"
        "PAUSED" -> "Paused"
        "DROPPED" -> "Dropped"
        "PLANNING" -> "Planning"
        "REPEATING" -> if (mediaType == MediaType.ANIME) "Rewatching" else "Rereading"
        else -> toSentenceCase(raw)
    }

    fun formatCountryCode(raw: String): String {
        val code = raw.trim().uppercase(Locale.ROOT)
        return when (code) {
            "JP" -> "Japan"
            "KR" -> "South Korea"
            "CN" -> "China"
            "TW" -> "Taiwan"
            "HK" -> "Hong Kong"
            "US" -> "United States"
            "GB" -> "United Kingdom"
            "FR" -> "France"
            "DE" -> "Germany"
            "CA" -> "Canada"
            "AU" -> "Australia"
            else -> {
                if (code.length == 2 && code.all { it in 'A'..'Z' }) {
                    val display = Locale("", code).getDisplayCountry(Locale.ENGLISH)
                    if (display.isNotBlank() && !display.equals(code, ignoreCase = true)) display else code
                } else {
                    toSentenceCase(raw)
                }
            }
        }
    }

    fun formatLengthBucket(raw: String?, mediaType: MediaType): String {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty() || trimmed.equals("unknown", ignoreCase = true)) return "Unknown"
        if (trimmed.any { it.isLetter() }) return trimmed.replace('-', '–')
        val pretty = trimmed.replace('-', '–')
        return if (mediaType == MediaType.ANIME) {
            if (pretty == "1") "1 ep" else "$pretty eps"
        } else {
            "$pretty ch"
        }
    }

    fun lengthSortKey(raw: String?): Int {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty() || trimmed.equals("unknown", ignoreCase = true)) return Int.MAX_VALUE
        val leadingDigits = trimmed.takeWhile { it.isDigit() }
        return leadingDigits.toIntOrNull() ?: (Int.MAX_VALUE - 1)
    }

    private fun toSentenceCase(raw: String): String {
        val cleaned = raw.trim().replace('_', ' ').lowercase(Locale.ROOT)
        if (cleaned.isEmpty()) return "Unknown"
        return cleaned.replaceFirstChar { it.uppercaseChar() }
    }

    // ---- Value & summary formatting ----

    fun formatInt(value: Int): String = numberFormat.format(value)

    fun formatDays(minutesWatched: Int): String {
        if (minutesWatched <= 0) return "0"
        val days = minutesWatched / 1440.0
        return when {
            days < 0.1 -> "<0.1"
            days >= 100.0 -> formatInt(days.roundToInt())
            else -> String.format(Locale.US, "%.1f", days)
        }
    }

    fun formatScore(score: Float): String {
        if (score <= 0f) return "—"
        return String.format(Locale.US, "%.1f", score)
    }

    fun formatHours(hours: Double): String {
        if (hours <= 0.0) return "0h"
        if (hours < 1.0) return "<1h"
        return "${formatInt(hours.roundToInt())}h"
    }

    fun formatMetricValue(value: Double, statType: StatType, mediaType: MediaType): String = when (statType) {
        StatType.COUNT -> formatInt(value.roundToInt())
        StatType.TIME -> if (mediaType == MediaType.ANIME) formatHours(value) else formatInt(value.roundToInt())
        StatType.AVG_SCORE -> if (value <= 0.0) "—" else String.format(Locale.US, "%.1f", value)
    }

    fun formatCompactValue(value: Double, statType: StatType, mediaType: MediaType): String = when (statType) {
        StatType.AVG_SCORE -> if (value <= 0.0) "0" else String.format(Locale.US, "%.0f", value)
        StatType.COUNT -> formatCompactNumber(value)
        StatType.TIME -> if (mediaType == MediaType.ANIME) {
            if (value > 0.0 && value < 1.0) "<1h" else "${formatCompactNumber(value)}h"
        } else {
            formatCompactNumber(value)
        }
    }

    fun formatCompactNumber(value: Double): String {
        val rounded = value.roundToInt()
        return when {
            rounded >= 1_000_000 -> String.format(Locale.US, "%.1fM", rounded / 1_000_000.0).removeSuffix(".0M").let {
                if (it.endsWith("M")) it else "${it}M"
            }
            rounded >= 10_000 -> "${rounded / 1_000}k"
            rounded >= 1_000 -> {
                val k = rounded / 1_000.0
                val formatted = String.format(Locale.US, "%.1f", k)
                if (formatted.endsWith(".0")) "${formatted.dropLast(2)}k" else "${formatted}k"
            }
            else -> rounded.toString()
        }
    }

    fun formatSharePercent(percent: Double): String = when {
        percent <= 0.0 -> "0%"
        percent < 1.0 -> "<1%"
        else -> "${percent.roundToInt()}%"
    }

    fun niceAxisMax(rawMax: Double): Double {
        if (rawMax <= 0.0) return 1.0
        val exp = floor(log10(rawMax))
        val magnitude = 10.0.pow(exp)
        val fraction = rawMax / magnitude
        val niceFraction = when {
            fraction <= 1.0 -> 1.0
            fraction <= 2.0 -> 2.0
            fraction <= 5.0 -> 5.0
            else -> 10.0
        }
        val candidate = niceFraction * magnitude
        return if (candidate < rawMax) ceil(rawMax) else candidate
    }

    /** Formats the tap-to-inspect detail string for any chart entry. */
    fun formatEntryDetail(section: ChartSection, entry: ChartEntry): String {
        val unitTime = if (section.mediaType == MediaType.ANIME) {
            formatHours(entry.timeValue)
        } else {
            "${formatInt(entry.timeValue.roundToInt())} ch"
        }
        return if (section.isComparing) {
            val primaryVal = formatMetricValue(entry.value, section.statType, section.mediaType)
            val compareVal = formatMetricValue(entry.compareValue ?: 0.0, section.statType, section.mediaType)
            "${entry.label} · ${section.primaryUser}: $primaryVal · ${section.compareUser}: $compareVal"
        } else {
            val parts = mutableListOf(entry.label)
            val countPart = buildString {
                append(formatInt(entry.count))
                append(if (entry.count == 1) " title" else " titles")
                entry.sharePercent?.let { pct ->
                    if (section.statType == StatType.COUNT && pct > 0.0) {
                        append(" (${String.format(Locale.US, "%.1f", pct)}%)")
                    }
                }
            }
            parts += countPart
            if (entry.timeValue > 0.0) {
                parts += unitTime
            }
            if (entry.meanScore > 0f) {
                parts += "Mean ${formatScore(entry.meanScore)}"
            }
            parts.joinToString(" · ")
        }
    }

    // ---- Metric extraction ----

    private fun extractMetric(row: RawStatRow, statType: StatType, mediaType: MediaType): Double = when (statType) {
        StatType.COUNT -> row.count.toDouble()
        StatType.TIME -> if (mediaType == MediaType.ANIME) row.minutesWatched / 60.0 else row.chaptersRead.toDouble()
        StatType.AVG_SCORE -> row.meanScore.toDouble()
    }

    private fun extractTimeValue(minutesWatched: Int, chaptersRead: Int, mediaType: MediaType): Double =
        if (mediaType == MediaType.ANIME) minutesWatched / 60.0 else chaptersRead.toDouble()

    // ---- Builders ----

    fun buildProfileStats(
        users: List<Query.StatisticsUser?>,
        mediaType: MediaType,
        statType: StatType,
        compareLabelOverride: String? = null,
    ): StatsPayload {
        val cleanUsers = users.filterNotNull()
        val primary = cleanUsers.firstOrNull()
            ?: return StatsPayload(mediaType, statType, summary = null, sections = emptyList(), hasOtherMediaStats = false)
        val compare = cleanUsers.getOrNull(1)
        val compareName = compare?.let { compareLabelOverride ?: it.name }

        val primaryStats = selectMediaStats(primary, mediaType)
        val compareStats = compare?.let { selectMediaStats(it, mediaType) }
        val otherMedia = if (mediaType == MediaType.ANIME) MediaType.MANGA else MediaType.ANIME
        val hasOtherStats = selectMediaStats(primary, otherMedia).count > 0

        val summary = StatsSummary(
            mediaType = mediaType,
            primaryUser = primary.name,
            compareUser = compareName,
            count = primaryStats.count,
            episodesOrChapters = if (mediaType == MediaType.ANIME) primaryStats.episodesWatched else primaryStats.chaptersRead,
            minutesOrVolumes = if (mediaType == MediaType.ANIME) primaryStats.minutesWatched else primaryStats.volumesRead,
            meanScore = primaryStats.meanScore,
            compareCount = compareStats?.count,
            compareEpisodesOrChapters = compareStats?.let {
                if (mediaType == MediaType.ANIME) it.episodesWatched else it.chaptersRead
            },
            compareMinutesOrVolumes = compareStats?.let {
                if (mediaType == MediaType.ANIME) it.minutesWatched else it.volumesRead
            },
            compareMeanScore = compareStats?.meanScore,
        )

        val sections = mutableListOf<ChartSection>()

        // 1. Status
        buildRankedSection(
            id = SectionId.STATUS,
            title = "Status",
            mediaType = mediaType,
            statType = statType,
            primaryUser = primary.name,
            compareUser = compareName,
            primaryRows = primaryStats.statuses.map {
                RawStatRow(
                    key = it.status.uppercase(Locale.ROOT),
                    label = formatMediaStatus(it.status, mediaType),
                    count = it.count,
                    minutesWatched = it.minutesWatched,
                    chaptersRead = it.chaptersRead,
                    meanScore = it.meanScore,
                )
            },
            compareRows = compareStats?.statuses?.map {
                RawStatRow(
                    key = it.status.uppercase(Locale.ROOT),
                    label = formatMediaStatus(it.status, mediaType),
                    count = it.count,
                    minutesWatched = it.minutesWatched,
                    chaptersRead = it.chaptersRead,
                    meanScore = it.meanScore,
                )
            },
            defaultLimit = Int.MAX_VALUE,
        )?.let(sections::add)

        // 2. Format
        buildRankedSection(
            id = SectionId.FORMAT,
            title = "Format",
            mediaType = mediaType,
            statType = statType,
            primaryUser = primary.name,
            compareUser = compareName,
            primaryRows = primaryStats.formats.map {
                RawStatRow(
                    key = it.format.uppercase(Locale.ROOT),
                    label = formatMediaFormat(it.format),
                    count = it.count,
                    minutesWatched = it.minutesWatched,
                    chaptersRead = it.chaptersRead,
                    meanScore = it.meanScore,
                )
            },
            compareRows = compareStats?.formats?.map {
                RawStatRow(
                    key = it.format.uppercase(Locale.ROOT),
                    label = formatMediaFormat(it.format),
                    count = it.count,
                    minutesWatched = it.minutesWatched,
                    chaptersRead = it.chaptersRead,
                    meanScore = it.meanScore,
                )
            },
            defaultLimit = Int.MAX_VALUE,
        )?.let(sections::add)

        // 3. Score distribution
        buildScoreHistogramSection(
            mediaType = mediaType,
            statType = statType,
            primaryUser = primary.name,
            primaryFormat = ScoreFormats.parse(primary.mediaListOptions.scoreFormat?.name),
            primaryScores = primaryStats.scores,
            compareUser = compareName,
            compareFormat = compare?.let { ScoreFormats.parse(it.mediaListOptions.scoreFormat?.name) },
            compareScores = compareStats?.scores,
        )?.let(sections::add)

        // 4. Genres
        buildRankedSection(
            id = SectionId.GENRES,
            title = "Genres",
            mediaType = mediaType,
            statType = statType,
            primaryUser = primary.name,
            compareUser = compareName,
            primaryRows = primaryStats.genres.map {
                RawStatRow(it.genre, it.genre, it.count, it.minutesWatched, it.chaptersRead, it.meanScore)
            },
            compareRows = compareStats?.genres?.map {
                RawStatRow(it.genre, it.genre, it.count, it.minutesWatched, it.chaptersRead, it.meanScore)
            },
            defaultLimit = DEFAULT_BAR_LIMIT,
        )?.let(sections::add)

        // 5. Tags
        buildRankedSection(
            id = SectionId.TAGS,
            title = "Tags",
            mediaType = mediaType,
            statType = statType,
            primaryUser = primary.name,
            compareUser = compareName,
            primaryRows = primaryStats.tags.map {
                RawStatRow(it.tag.name, it.tag.name, it.count, it.minutesWatched, it.chaptersRead, it.meanScore)
            },
            compareRows = compareStats?.tags?.map {
                RawStatRow(it.tag.name, it.tag.name, it.count, it.minutesWatched, it.chaptersRead, it.meanScore)
            },
            defaultLimit = DEFAULT_BAR_LIMIT,
        )?.let(sections::add)

        // 6. Release year
        buildYearTimelineSection(
            id = SectionId.RELEASE_YEAR,
            title = "Release year",
            mediaType = mediaType,
            statType = statType,
            primaryUser = primary.name,
            compareUser = compareName,
            primaryYears = primaryStats.releaseYears.map {
                YearPoint(it.releaseYear, it.count, it.minutesWatched, it.chaptersRead, it.meanScore)
            },
            compareYears = compareStats?.releaseYears?.map {
                YearPoint(it.releaseYear, it.count, it.minutesWatched, it.chaptersRead, it.meanScore)
            },
        )?.let(sections::add)

        // 7. Start year
        buildYearTimelineSection(
            id = SectionId.START_YEAR,
            title = "Start year",
            mediaType = mediaType,
            statType = statType,
            primaryUser = primary.name,
            compareUser = compareName,
            primaryYears = primaryStats.startYears.map {
                YearPoint(it.startYear, it.count, it.minutesWatched, it.chaptersRead, it.meanScore)
            },
            compareYears = compareStats?.startYears?.map {
                YearPoint(it.startYear, it.count, it.minutesWatched, it.chaptersRead, it.meanScore)
            },
        )?.let(sections::add)

        // 8. Length (sorted naturally from shortest to longest)
        buildRankedSection(
            id = SectionId.LENGTH,
            title = "Length",
            mediaType = mediaType,
            statType = statType,
            primaryUser = primary.name,
            compareUser = compareName,
            primaryRows = primaryStats.lengths.map {
                val key = it.length?.trim().orEmpty().ifEmpty { "unknown" }
                RawStatRow(
                    key = key,
                    label = formatLengthBucket(it.length, mediaType),
                    count = it.count,
                    minutesWatched = it.minutesWatched,
                    chaptersRead = it.chaptersRead,
                    meanScore = it.meanScore,
                )
            },
            compareRows = compareStats?.lengths?.map {
                val key = it.length?.trim().orEmpty().ifEmpty { "unknown" }
                RawStatRow(
                    key = key,
                    label = formatLengthBucket(it.length, mediaType),
                    count = it.count,
                    minutesWatched = it.minutesWatched,
                    chaptersRead = it.chaptersRead,
                    meanScore = it.meanScore,
                )
            },
            defaultLimit = Int.MAX_VALUE,
            customComparator = compareBy<ChartEntry> { lengthSortKey(it.key) }.thenBy { it.label },
        )?.let(sections::add)

        // 9. Country
        buildRankedSection(
            id = SectionId.COUNTRY,
            title = "Country",
            mediaType = mediaType,
            statType = statType,
            primaryUser = primary.name,
            compareUser = compareName,
            primaryRows = primaryStats.countries.map {
                val key = it.country.trim().uppercase(Locale.ROOT)
                RawStatRow(
                    key = key,
                    label = formatCountryCode(it.country),
                    count = it.count,
                    minutesWatched = it.minutesWatched,
                    chaptersRead = it.chaptersRead,
                    meanScore = it.meanScore,
                )
            },
            compareRows = compareStats?.countries?.map {
                val key = it.country.trim().uppercase(Locale.ROOT)
                RawStatRow(
                    key = key,
                    label = formatCountryCode(it.country),
                    count = it.count,
                    minutesWatched = it.minutesWatched,
                    chaptersRead = it.chaptersRead,
                    meanScore = it.meanScore,
                )
            },
            defaultLimit = Int.MAX_VALUE,
        )?.let(sections::add)

        // 10. Studios
        buildRankedSection(
            id = SectionId.STUDIOS,
            title = "Studios",
            mediaType = mediaType,
            statType = statType,
            primaryUser = primary.name,
            compareUser = compareName,
            primaryRows = primaryStats.studios.map {
                RawStatRow(it.studio.name, it.studio.name, it.count, it.minutesWatched, it.chaptersRead, it.meanScore)
            },
            compareRows = compareStats?.studios?.map {
                RawStatRow(it.studio.name, it.studio.name, it.count, it.minutesWatched, it.chaptersRead, it.meanScore)
            },
            defaultLimit = DEFAULT_BAR_LIMIT,
        )?.let(sections::add)

        // 11. Voice actors
        buildRankedSection(
            id = SectionId.VOICE_ACTORS,
            title = "Voice actors",
            mediaType = mediaType,
            statType = statType,
            primaryUser = primary.name,
            compareUser = compareName,
            primaryRows = primaryStats.voiceActors.map {
                val name = it.voiceActor.name.userPreferred
                    ?: it.voiceActor.name.full
                    ?: "Unknown"
                RawStatRow(name, name, it.count, it.minutesWatched, it.chaptersRead, it.meanScore)
            },
            compareRows = compareStats?.voiceActors?.map {
                val name = it.voiceActor.name.userPreferred
                    ?: it.voiceActor.name.full
                    ?: "Unknown"
                RawStatRow(name, name, it.count, it.minutesWatched, it.chaptersRead, it.meanScore)
            },
            defaultLimit = DEFAULT_BAR_LIMIT,
        )?.let(sections::add)

        // 12. Staff
        buildRankedSection(
            id = SectionId.STAFF,
            title = "Staff",
            mediaType = mediaType,
            statType = statType,
            primaryUser = primary.name,
            compareUser = compareName,
            primaryRows = primaryStats.staff.map {
                val name = it.staff.name.userPreferred
                    ?: it.staff.name.full
                    ?: "Unknown"
                RawStatRow(name, name, it.count, it.minutesWatched, it.chaptersRead, it.meanScore)
            },
            compareRows = compareStats?.staff?.map {
                val name = it.staff.name.userPreferred
                    ?: it.staff.name.full
                    ?: "Unknown"
                RawStatRow(name, name, it.count, it.minutesWatched, it.chaptersRead, it.meanScore)
            },
            defaultLimit = DEFAULT_BAR_LIMIT,
        )?.let(sections::add)

        return StatsPayload(
            mediaType = mediaType,
            statType = statType,
            summary = summary,
            sections = sections,
            hasOtherMediaStats = hasOtherStats,
        )
    }

    private fun selectMediaStats(user: Query.StatisticsUser, mediaType: MediaType): Query.Statistics =
        if (mediaType == MediaType.ANIME) user.statistics.anime else user.statistics.manga

    internal fun buildRankedSection(
        id: SectionId,
        title: String,
        mediaType: MediaType,
        statType: StatType,
        primaryUser: String,
        compareUser: String?,
        primaryRows: List<RawStatRow>,
        compareRows: List<RawStatRow>?,
        defaultLimit: Int = DEFAULT_BAR_LIMIT,
        customComparator: Comparator<ChartEntry>? = null,
    ): ChartSection? {
        if (primaryRows.isEmpty() && compareRows.isNullOrEmpty()) return null

        val primaryByKey = primaryRows.associateBy { it.key }
        val compareByKey = compareRows?.associateBy { it.key }.orEmpty()
        val allKeys = linkedSetOf<String>()
        primaryRows.forEach { allKeys.add(it.key) }
        compareRows?.forEach { allKeys.add(it.key) }

        val totalPrimary = if (statType != StatType.AVG_SCORE) {
            primaryRows.sumOf { extractMetric(it, statType, mediaType) }
        } else 0.0

        val entries = allKeys.mapNotNull { key ->
            val p = primaryByKey[key]
            val c = compareByKey[key]
            val label = p?.label ?: c?.label ?: return@mapNotNull null
            val pVal = p?.let { extractMetric(it, statType, mediaType) } ?: 0.0
            val cVal = if (compareUser != null) c?.let { extractMetric(it, statType, mediaType) } ?: 0.0 else null
            if (pVal <= 0.0 && (cVal == null || cVal <= 0.0)) return@mapNotNull null
            val share = if (compareUser == null && statType != StatType.AVG_SCORE && totalPrimary > 0.0) {
                (pVal / totalPrimary) * 100.0
            } else null
            ChartEntry(
                key = key,
                label = label,
                value = pVal,
                compareValue = cVal,
                sharePercent = share,
                count = p?.count ?: 0,
                timeValue = p?.let { extractTimeValue(it.minutesWatched, it.chaptersRead, mediaType) } ?: 0.0,
                meanScore = p?.meanScore ?: 0f,
                compareCount = if (compareUser != null) c?.count ?: 0 else null,
                compareTimeValue = if (compareUser != null) {
                    c?.let { extractTimeValue(it.minutesWatched, it.chaptersRead, mediaType) } ?: 0.0
                } else null,
                compareMeanScore = if (compareUser != null) c?.meanScore ?: 0f else null,
            )
        }

        if (entries.isEmpty()) return null

        val comparator = customComparator ?: compareByDescending<ChartEntry> { it.value }
            .thenByDescending { it.compareValue ?: 0.0 }
            .thenBy { it.label.lowercase(Locale.ROOT) }

        val sorted = entries.sortedWith(comparator)
        val subtitle = if (sorted.size > defaultLimit) {
            "Top $defaultLimit of ${sorted.size}"
        } else null

        return ChartSection(
            id = id,
            title = title,
            kind = ChartKind.RANKED_BARS,
            mediaType = mediaType,
            statType = statType,
            primaryUser = primaryUser,
            compareUser = compareUser,
            entries = sorted,
            defaultCollapsedLimit = defaultLimit,
            subtitle = subtitle,
        )
    }

    // ---- Score histogram bucketing ----

    private data class BucketAccum(
        var count: Int = 0,
        var minutesWatched: Int = 0,
        var chaptersRead: Int = 0,
        var weightedScoreSum: Double = 0.0,
        var weightCount: Int = 0,
    ) {
        fun add(s: Query.StatisticsScore) {
            count += s.count
            minutesWatched += s.minutesWatched
            chaptersRead += s.chaptersRead
            val w = s.count.coerceAtLeast(1)
            weightedScoreSum += s.meanScore * w
            weightCount += w
        }

        val meanScore: Float
            get() = if (weightCount > 0) (weightedScoreSum / weightCount).toFloat() else 0f
    }

    /** Maps a raw 0..100 score into a 10-point histogram bin (10, 20, ..., 100); 0 if unscored. */
    fun rawScoreToDecileBin(raw: Int): Int {
        if (raw <= 0) return 0
        val clamped = raw.coerceIn(1, 100)
        return (((clamped - 1) / 10) + 1) * 10
    }

    internal fun buildScoreHistogramSection(
        mediaType: MediaType,
        statType: StatType,
        primaryUser: String,
        primaryFormat: ScoreFormat,
        primaryScores: List<Query.StatisticsScore>,
        compareUser: String?,
        compareFormat: ScoreFormat?,
        compareScores: List<Query.StatisticsScore>?,
    ): ChartSection? {
        if (primaryScores.isEmpty() && compareScores.isNullOrEmpty()) return null

        // Single-user profiles in native small-bucket formats keep their native axis;
        // 100-point, decimal, or 2-user comparisons normalize onto the shared 10..100 scale.
        val useNativeFormat = compareUser == null && primaryFormat in setOf(
            ScoreFormat.POINT_3,
            ScoreFormat.POINT_5,
            ScoreFormat.POINT_10,
        )

        val binSpecs: List<Pair<Int, String>> = if (useNativeFormat) {
            when (primaryFormat) {
                ScoreFormat.POINT_3 -> listOf(1 to ScoreFormats.SMILEYS[1], 2 to ScoreFormats.SMILEYS[2], 3 to ScoreFormats.SMILEYS[3])
                ScoreFormat.POINT_5 -> (1..5).map { it to "$it★" }
                ScoreFormat.POINT_10 -> (1..10).map { it to it.toString() }
                else -> (1..10).map { it * 10 to (it * 10).toString() }
            }
        } else {
            (1..10).map { it * 10 to (it * 10).toString() }
        }

        fun accumulate(scores: List<Query.StatisticsScore>, format: ScoreFormat): Map<Int, BucketAccum> {
            val map = linkedMapOf<Int, BucketAccum>()
            for (s in scores) {
                if (s.score <= 0) continue
                val bin = if (useNativeFormat) {
                    s.score
                } else {
                    val raw = ScoreFormats.statsBucketToRaw(s.score, format)
                    rawScoreToDecileBin(raw)
                }
                if (bin <= 0) continue
                map.getOrPut(bin) { BucketAccum() }.add(s)
            }
            return map
        }

        val pMap = accumulate(primaryScores, primaryFormat)
        val cMap = if (compareUser != null && compareScores != null && compareFormat != null) {
            accumulate(compareScores, compareFormat)
        } else emptyMap()

        if (pMap.isEmpty() && cMap.isEmpty()) return null

        val totalPrimary = if (statType != StatType.AVG_SCORE) {
            pMap.values.sumOf {
                when (statType) {
                    StatType.COUNT -> it.count.toDouble()
                    StatType.TIME -> extractTimeValue(it.minutesWatched, it.chaptersRead, mediaType)
                    StatType.AVG_SCORE -> 0.0
                }
            }
        } else 0.0

        val entries = binSpecs.map { (bin, label) ->
            val p = pMap[bin]
            val c = cMap[bin]
            val pVal = when (statType) {
                StatType.COUNT -> (p?.count ?: 0).toDouble()
                StatType.TIME -> p?.let { extractTimeValue(it.minutesWatched, it.chaptersRead, mediaType) } ?: 0.0
                StatType.AVG_SCORE -> (p?.meanScore ?: 0f).toDouble()
            }
            val cVal = if (compareUser != null) {
                when (statType) {
                    StatType.COUNT -> (c?.count ?: 0).toDouble()
                    StatType.TIME -> c?.let { extractTimeValue(it.minutesWatched, it.chaptersRead, mediaType) } ?: 0.0
                    StatType.AVG_SCORE -> (c?.meanScore ?: 0f).toDouble()
                }
            } else null
            val share = if (compareUser == null && statType != StatType.AVG_SCORE && totalPrimary > 0.0) {
                (pVal / totalPrimary) * 100.0
            } else null
            ChartEntry(
                key = bin.toString(),
                label = label,
                value = pVal,
                compareValue = cVal,
                sharePercent = share,
                count = p?.count ?: 0,
                timeValue = p?.let { extractTimeValue(it.minutesWatched, it.chaptersRead, mediaType) } ?: 0.0,
                meanScore = p?.meanScore ?: 0f,
                compareCount = if (compareUser != null) c?.count ?: 0 else null,
                compareTimeValue = if (compareUser != null) {
                    c?.let { extractTimeValue(it.minutesWatched, it.chaptersRead, mediaType) } ?: 0.0
                } else null,
                compareMeanScore = if (compareUser != null) c?.meanScore ?: 0f else null,
            )
        }

        val peak = entries.maxByOrNull { it.value }
        val subtitle = peak?.takeIf { it.value > 0.0 }?.let { "Peak ${it.label}" }

        return ChartSection(
            id = SectionId.SCORE,
            title = "Score distribution",
            kind = ChartKind.SCORE_HISTOGRAM,
            mediaType = mediaType,
            statType = statType,
            primaryUser = primaryUser,
            compareUser = compareUser,
            entries = entries,
            defaultCollapsedLimit = entries.size,
            subtitle = subtitle,
        )
    }

    // ---- Year timeline ----

    internal data class YearPoint(
        val year: Int,
        val count: Int,
        val minutesWatched: Int,
        val chaptersRead: Int,
        val meanScore: Float,
    )

    internal fun buildYearTimelineSection(
        id: SectionId,
        title: String,
        mediaType: MediaType,
        statType: StatType,
        primaryUser: String,
        compareUser: String?,
        primaryYears: List<YearPoint>,
        compareYears: List<YearPoint>?,
    ): ChartSection? {
        val validPrimary = primaryYears.filter { it.year > 0 }
        val validCompare = compareYears?.filter { it.year > 0 }.orEmpty()
        if (validPrimary.isEmpty() && validCompare.isEmpty()) return null

        val pByYear = validPrimary.associateBy { it.year }
        val cByYear = validCompare.associateBy { it.year }
        val presentYears = (pByYear.keys + cByYear.keys).sorted()
        val minYear = presentYears.first()
        val maxYear = presentYears.last()

        // Fill intermediate zero years when the span is reasonable so the timeline keeps a uniform scale.
        val timelineYears: List<Int> = if (maxYear - minYear in 1..MAX_TIMELINE_GAP_FILL_SPAN) {
            (minYear..maxYear).toList()
        } else {
            presentYears
        }

        val totalPrimary = if (statType != StatType.AVG_SCORE) {
            validPrimary.sumOf {
                when (statType) {
                    StatType.COUNT -> it.count.toDouble()
                    StatType.TIME -> extractTimeValue(it.minutesWatched, it.chaptersRead, mediaType)
                    StatType.AVG_SCORE -> 0.0
                }
            }
        } else 0.0

        val entries = timelineYears.map { year ->
            val p = pByYear[year]
            val c = cByYear[year]
            val pVal = when (statType) {
                StatType.COUNT -> (p?.count ?: 0).toDouble()
                StatType.TIME -> p?.let { extractTimeValue(it.minutesWatched, it.chaptersRead, mediaType) } ?: 0.0
                StatType.AVG_SCORE -> (p?.meanScore ?: 0f).toDouble()
            }
            val cVal = if (compareUser != null) {
                when (statType) {
                    StatType.COUNT -> (c?.count ?: 0).toDouble()
                    StatType.TIME -> c?.let { extractTimeValue(it.minutesWatched, it.chaptersRead, mediaType) } ?: 0.0
                    StatType.AVG_SCORE -> (c?.meanScore ?: 0f).toDouble()
                }
            } else null
            val share = if (compareUser == null && statType != StatType.AVG_SCORE && totalPrimary > 0.0) {
                (pVal / totalPrimary) * 100.0
            } else null
            ChartEntry(
                key = year.toString(),
                label = year.toString(),
                value = pVal,
                compareValue = cVal,
                sharePercent = share,
                count = p?.count ?: 0,
                timeValue = p?.let { extractTimeValue(it.minutesWatched, it.chaptersRead, mediaType) } ?: 0.0,
                meanScore = p?.meanScore ?: 0f,
                compareCount = if (compareUser != null) c?.count ?: 0 else null,
                compareTimeValue = if (compareUser != null) {
                    c?.let { extractTimeValue(it.minutesWatched, it.chaptersRead, mediaType) } ?: 0.0
                } else null,
                compareMeanScore = if (compareUser != null) c?.meanScore ?: 0f else null,
            )
        }

        val subtitle = if (minYear == maxYear) minYear.toString() else "$minYear–$maxYear"

        return ChartSection(
            id = id,
            title = title,
            kind = ChartKind.YEAR_TIMELINE,
            mediaType = mediaType,
            statType = statType,
            primaryUser = primaryUser,
            compareUser = compareUser,
            entries = entries,
            defaultCollapsedLimit = entries.size,
            subtitle = subtitle,
        )
    }
}
