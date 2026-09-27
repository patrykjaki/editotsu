package ani.dantotsu.others

import ani.dantotsu.client
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
}
