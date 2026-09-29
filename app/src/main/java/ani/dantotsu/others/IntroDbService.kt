package ani.dantotsu.others

import ani.dantotsu.client
import ani.dantotsu.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

object IntroDbService {
    private const val TAG = "IntroDbService"
    private const val INTRODB_APP_URL = "https://api.introdb.app/segments"
    private const val THE_INTRODB_URL = "https://api.theintrodb.org/v3/media"

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    suspend fun getSkipTimes(
        imdbId: String? = null,
        tmdbId: Int? = null,
        season: String = "1",
        episode: String,
        durationMs: Long? = null
    ): List<AniSkip.Stamp>? = withContext(Dispatchers.IO) {
        val epInt = episode.toIntOrNull() ?: return@withContext null

        // 1. Try IntroDB App API via IMDB ID
        if (!imdbId.isNullOrBlank()) {
            val fromImdb = fetchFromIntroDbApp(imdbId, season, episode, epInt, durationMs)
            if (!fromImdb.isNullOrEmpty()) return@withContext fromImdb
        }

        // 2. Try TheIntroDb API via TMDB ID
        if (tmdbId != null && tmdbId > 0) {
            val fromTmdb = fetchFromTheIntroDb(tmdbId, season, episode, epInt, durationMs)
            if (!fromTmdb.isNullOrEmpty()) return@withContext fromTmdb
        }

        null
    }

    /**
     * Pure IntroDB-app payload parser (org.json is JVM-plain, unit-proved).
     * Null intro/recap starts mean 0; a missing end resolves against the
     * known media duration, else the segment is safely omitted.
     */
    internal fun parseIntroDbAppSegments(
        root: JsonObject,
        imdbId: String,
        episodeInt: Int,
        durationMs: Long?
    ): List<AniSkip.Stamp> {
        val durationSec = durationMs?.takeIf { it > 0 }?.div(1000.0)
        val stamps = mutableListOf<AniSkip.Stamp>()
        fun segment(obj: JsonObject, type: String, idKind: String) {
            val start = obj["start_sec"]?.jsonPrimitive?.doubleOrNull ?: 0.0
            var end = obj["end_sec"]?.jsonPrimitive?.doubleOrNull
            if (end == null) {
                end = durationSec
            }
            if (end == null || !start.isFinite() || !end.isFinite() || end <= start || start < 0) return
            stamps.add(
                AniSkip.Stamp(
                    interval = AniSkip.AniSkipInterval(start, end),
                    skipType = type,
                    skipId = "introdb_${idKind}_${imdbId}_$episodeInt",
                    episodeLength = 0.0
                )
            )
        }
        root["intro"]?.jsonObject?.let { segment(it, "op", "op") }
        root["outro"]?.jsonObject?.let { segment(it, "ed", "ed") }
        root["recap"]?.jsonObject?.let { segment(it, "recap", "recap") }
        return stamps
    }

    /**
     * Pure TheIntroDB payload parser. Null intro/recap starts mean 0;
     * credits with a null end run to the known media duration, and are
     * omitted when duration is unknown rather than inventing a bound.
     */
    internal fun parseTheIntroDbSegments(
        root: JsonObject,
        tmdbId: Int,
        episodeInt: Int,
        durationMs: Long?
    ): List<AniSkip.Stamp> {
        val durationSec = durationMs?.takeIf { it > 0 }?.div(1000.0)
        val stamps = mutableListOf<AniSkip.Stamp>()
        fun parseSegments(type: String, key: String) {
            val array = root[key]?.jsonArray ?: return
            for (item in array) {
                val obj = item.jsonObject
                val start = obj["start_ms"]?.jsonPrimitive?.longOrNull?.div(1000.0) ?: 0.0
                var end = obj["end_ms"]?.jsonPrimitive?.longOrNull?.div(1000.0)
                if (end == null) {
                    end = durationSec
                }
                if (end == null || !start.isFinite() || !end.isFinite() || end <= start || start < 0) continue
                stamps.add(
                    AniSkip.Stamp(
                        interval = AniSkip.AniSkipInterval(start, end),
                        skipType = type,
                        skipId = "theintrodb_${type}_${tmdbId}_$episodeInt",
                        episodeLength = 0.0
                    )
                )
            }
        }
        parseSegments("op", "intro")
        parseSegments("ed", "credits")
        parseSegments("recap", "recap")
        return stamps
    }

    internal fun introDbAppUrl(imdbId: String, season: String, episode: String): String =
        "$INTRODB_APP_URL?imdb_id=$imdbId&season=$season&episode=$episode"

    internal fun theIntroDbUrl(tmdbId: Int, season: String, episode: String, durationMs: Long?): String {
        var url = "$THE_INTRODB_URL?tmdb_id=$tmdbId&season=$season&episode=$episode"
        if (durationMs != null && durationMs > 0) {
            url += "&duration_ms=$durationMs"
        }
        return url
    }

    private suspend fun fetchFromIntroDbApp(
        imdbId: String,
        season: String,
        episode: String,
        episodeInt: Int,
        durationMs: Long? = null
    ): List<AniSkip.Stamp>? {
        val url = introDbAppUrl(imdbId, season, episode)
        return try {
            val response = client.get(url)
            val text = response.text
            if (text.isBlank()) return null
            val root = json.parseToJsonElement(text).jsonObject
            parseIntroDbAppSegments(root, imdbId, episodeInt, durationMs)
                .ifEmpty { null }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.log("$TAG: fetchFromIntroDbApp failed for $url: ${e.message}")
            null
        }
    }

    private suspend fun fetchFromTheIntroDb(
        tmdbId: Int,
        season: String,
        episode: String,
        episodeInt: Int,
        durationMs: Long? = null
    ): List<AniSkip.Stamp>? {
        val url = theIntroDbUrl(tmdbId, season, episode, durationMs)
        return try {
            val response = client.get(url)
            val text = response.text
            if (text.isBlank()) return null
            val root = json.parseToJsonElement(text).jsonObject
            parseTheIntroDbSegments(root, tmdbId, episodeInt, durationMs)
                .ifEmpty { null }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.log("$TAG: fetchFromTheIntroDb failed for $url: ${e.message}")
            null
        }
    }
}
