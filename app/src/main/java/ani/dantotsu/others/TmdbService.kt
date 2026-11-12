package ani.dantotsu.others

import ani.dantotsu.FileUrl
import ani.dantotsu.client
import ani.dantotsu.media.anime.Episode
import ani.dantotsu.util.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

object TmdbService {
    private const val TAG = "TmdbService"
    private const val API_KEY = "926bf284e333aa31eba7658bca87200a"
    private const val BASE_URL = "https://api.themoviedb.org/3"
    private const val IMAGE_BASE_ORIGINAL = "https://image.tmdb.org/t/p/original"

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    suspend fun getClearLogo(tmdbId: Int, isMovie: Boolean = false): String? = withContext(Dispatchers.IO) {
        if (tmdbId <= 0) return@withContext null
        val type = if (isMovie) "movie" else "tv"
        val url = "$BASE_URL/$type/$tmdbId/images?api_key=$API_KEY"

        try {
            val response = client.get(url)
            val text = response.text
            if (text.isBlank()) return@withContext null
            val root = json.parseToJsonElement(text).jsonObject
            val logos = root["logos"]?.jsonArray ?: return@withContext null

            // Prioritize English logo, otherwise first available
            var bestLogoPath: String? = null
            for (item in logos) {
                val obj = item.jsonObject
                val path = obj["file_path"]?.jsonPrimitive?.contentOrNull ?: continue
                val lang = obj["iso_639_1"]?.jsonPrimitive?.contentOrNull
                if (lang.equals("en", ignoreCase = true)) {
                    bestLogoPath = path
                    break
                }
                if (bestLogoPath == null) {
                    bestLogoPath = path
                }
            }

            if (bestLogoPath != null) {
                val fullUrl = "$IMAGE_BASE_ORIGINAL$bestLogoPath"
                "https://wsrv.nl/?url=$fullUrl"
            } else {
                null
            }
        } catch (e: CancellationException) {
            // V4 Blocker 2: cancellation is not a lookup failure.
            throw e
        } catch (e: Exception) {
            Logger.log("$TAG: getClearLogo failed for $url: ${e.message}")
            null
        }
    }

    /**
     * MAX MERGE M5 (upstream 36d57c56): TMDB season fallback for episode
     * titles, synopses and thumbnails when extension/AniZip metadata is
     * missing. Keys are plain episode numbers, matching Anify's map shape.
     */
    suspend fun getEpisodeDetails(tmdbId: Int, season: Int = 1): Map<String, Episode>? = withContext(Dispatchers.IO) {
        if (tmdbId <= 0) return@withContext null
        val url = "$BASE_URL/tv/$tmdbId/season/$season?api_key=$API_KEY"

        try {
            val response = client.get(url)
            val text = response.text
            if (text.isBlank()) return@withContext null
            val root = json.parseToJsonElement(text).jsonObject
            val episodesArray = root["episodes"]?.jsonArray ?: return@withContext null

            val result = mutableMapOf<String, Episode>()
            for (item in episodesArray) {
                val epObj = item.jsonObject
                val epNum = epObj["episode_number"]?.jsonPrimitive?.intOrNull ?: continue
                val name = epObj["name"]?.jsonPrimitive?.contentOrNull
                val overview = epObj["overview"]?.jsonPrimitive?.contentOrNull
                val stillPath = epObj["still_path"]?.jsonPrimitive?.contentOrNull
                val thumbUrl = if (!stillPath.isNullOrBlank()) "$IMAGE_BASE_W500$stillPath" else null

                val key = epNum.toString()
                result[key] = Episode(
                    number = key,
                    title = name,
                    desc = overview,
                    thumb = thumbUrl?.let { FileUrl[it] },
                    extra = buildMap {
                        epObj["air_date"]?.jsonPrimitive?.contentOrNull?.let { put("airDate", it) }
                        put("season", season.toString())
                        put("episode", key)
                    }
                )
            }

            if (result.isNotEmpty()) result else null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.log("$TAG: getEpisodeDetails failed for $url: ${e.message}")
            null
        }
    }

    private const val IMAGE_BASE_W500 = "https://image.tmdb.org/t/p/w500"

    private val seasonMarker = Regex(
        """\s*[:\-]?\s*(?:(?:season|part|cour)\s*\d+|\d+(?:st|nd|rd|th)\s+season|\d+)\s*$""",
        RegexOption.IGNORE_CASE,
    )
    private val romanMarker = Regex("""\s+(?:II|III|IV|V|VI)\s*$""")

    /**
     * Titles to search with: each title as given, then without a trailing
     * season marker ("II", "2", "Season 2", "2nd Season"), since a sequel
     * is a season of the first show's entry there. Order kept, no repeats.
     */
    internal fun searchTitles(titles: List<String?>): List<String> {
        val out = LinkedHashSet<String>()
        for (t in titles) {
            val title = t?.trim().orEmpty()
            if (title.isEmpty()) continue
            out += title
            val base = romanMarker.replace(seasonMarker.replace(title, ""), "").trim()
            if (base.length >= 3) out += base
        }
        return out.toList()
    }

    /**
     * The season (specials excluded) whose first air date is within
     * [toleranceDays] of [start] (yyyy-MM-dd), nearest first. The date is
     * the check that a title hit is the same show and the right season.
     */
    internal fun pickSeasonByAirDate(
        seasons: List<Pair<Int, String?>>,
        start: String,
        toleranceDays: Long = 3,
    ): Int? {
        val target = runCatching { java.time.LocalDate.parse(start) }.getOrNull() ?: return null
        return seasons
            .filter { it.first > 0 }
            .mapNotNull { (number, date) ->
                val aired = runCatching { java.time.LocalDate.parse(date) }.getOrNull() ?: return@mapNotNull null
                number to kotlin.math.abs(java.time.temporal.ChronoUnit.DAYS.between(target, aired))
            }
            .filter { it.second <= toleranceDays }
            .minByOrNull { it.second }
            ?.first
    }

    /**
     * Episodes from the one that aired on [start] (within [toleranceDays])
     * onwards, renumbered from 1. For a show listed as its own entry that
     * the other side keeps inside a longer season (second cour, "part 2").
     * Null when no episode aired on that date.
     */
    internal fun sliceFromAirDate(
        episodes: Map<String, Episode>,
        start: String,
        toleranceDays: Long = 1,
    ): Map<String, Episode>? {
        val target = runCatching { java.time.LocalDate.parse(start) }.getOrNull() ?: return null
        val ordered = episodes.values.sortedBy { it.number.toIntOrNull() ?: Int.MAX_VALUE }
        val first = ordered.indexOfFirst { ep ->
            val aired = runCatching { java.time.LocalDate.parse(ep.extra?.get("airDate")) }.getOrNull()
            aired != null && kotlin.math.abs(java.time.temporal.ChronoUnit.DAYS.between(target, aired)) <= toleranceDays
        }
        if (first < 0) return null
        return ordered.drop(first).mapIndexed { i, ep ->
            val key = (i + 1).toString()
            key to ep.copy(number = key)
        }.toMap()
    }

    /**
     * For shows the id mapping does not know yet (new seasons, mostly):
     * search by title and accept a result only when the show's own start
     * date is found in it, either as a season's first air date or as the
     * air date of an episode inside an earlier-started season. Keys are
     * the show's own episode numbers. Null rather than a guess.
     */
    suspend fun findEpisodesByAirDate(titles: List<String?>, start: String): Map<String, Episode>? =
        withContext(Dispatchers.IO) {
            val seen = HashSet<Int>()
            for (title in searchTitles(titles).take(4)) {
                try {
                    val query = java.net.URLEncoder.encode(title, "utf-8")
                    val text = client.get("$BASE_URL/search/tv?api_key=$API_KEY&query=$query").text
                    val results = json.parseToJsonElement(text).jsonObject["results"]?.jsonArray ?: continue
                    for (item in results.take(3)) {
                        val id = item.jsonObject["id"]?.jsonPrimitive?.intOrNull ?: continue
                        if (!seen.add(id)) continue
                        val show = json.parseToJsonElement(client.get("$BASE_URL/tv/$id?api_key=$API_KEY").text).jsonObject
                        val seasons = show["seasons"]?.jsonArray.orEmpty().mapNotNull { s ->
                            val number = s.jsonObject["season_number"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
                            number to s.jsonObject["air_date"]?.jsonPrimitive?.contentOrNull
                        }
                        val season = pickSeasonByAirDate(seasons, start)
                        if (season != null) {
                            getEpisodeDetails(id, season)?.let { return@withContext it }
                            continue
                        }
                        // no season starts that day: look inside the seasons that began before it
                        val earlier = seasons
                            .filter { it.first > 0 && (it.second ?: "9999") < start }
                            .sortedByDescending { it.second }
                            .take(2)
                        for ((number, _) in earlier) {
                            val sliced = getEpisodeDetails(id, number)?.let { sliceFromAirDate(it, start) }
                            if (sliced != null) return@withContext sliced
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Logger.log("$TAG: findEpisodesByAirDate failed for \"$title\": ${e.message}")
                }
            }
            null
        }

    /**
     * MAX MERGE M5 correction 3: bulk season fetch translated back to
     * LOCAL episode identity. Each local key maps individually through
     * [mapEpisode]; keys group by external season so every required
     * season is fetched at most once; emitted rows carry the LOCAL key
     * both as map key AND as Episode.number (external season/episode
     * preserved in extra). Only explicitly mapped rows are emitted, so
     * wrong-season rows can never populate another local episode.
     * Ordinary mapping failure OMITS that local key (a clean miss beats
     * valid-looking wrong-season metadata); mapping CancellationException
     * escapes. Unit-proved (TmdbMappingTest exercises this seam).
     */
    internal suspend fun resolveTmdbEpisodes(
        localKeys: List<String>,
        mapEpisode: suspend (localKey: String) -> ani.dantotsu.media.EpisodeMapper.SeasonEpisode,
        fetchSeason: suspend (season: Int) -> Map<String, Episode>?
    ): Map<String, Episode> {
        val mapped = mutableMapOf<String, ani.dantotsu.media.EpisodeMapper.SeasonEpisode>()
        for (key in localKeys) {
            val se = try {
                mapEpisode(key)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            } ?: continue
            mapped[key] = se
        }
        val fetched = mutableMapOf<Int, Map<String, Episode>>()
        for (season in mapped.values.map { it.season }.toSet()) {
            fetched[season] = fetchSeason(season) ?: emptyMap()
        }
        val out = mutableMapOf<String, Episode>()
        for ((localKey, se) in mapped) {
            val row = fetched[se.season]?.get(se.episode.toString()) ?: continue
            out[localKey] = row.copy(
                number = localKey,
                extra = (row.extra ?: emptyMap()) + mapOf(
                    "season" to se.season.toString(),
                    "episode" to se.episode.toString()
                )
            )
        }
        return out
    }
}
