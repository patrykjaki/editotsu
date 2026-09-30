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
