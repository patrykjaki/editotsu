package ani.dantotsu.media

import ani.dantotsu.client
import ani.dantotsu.others.IdMappers
import ani.dantotsu.others.TmdbService
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.util.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.util.concurrent.ConcurrentHashMap

object CarouselLogoResolver {
    private const val NO_LOGO = ""
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
    private val cache = ConcurrentHashMap<String, String>()

    suspend fun resolve(media: Media): String? = withContext(Dispatchers.IO) {
        val cacheKey = "${media.id}:${media.idMAL}"
        val cached = cache[cacheKey]
        if (cached != null) {
            return@withContext if (cached == NO_LOGO) null else cached
        }

        val rescueMode: Boolean = PrefManager.getVal(PrefName.RescueMode)
        val isMalMode = isMalIdentityMode(rescueMode, media.id, media.idMAL)
        // V4 Blocker 1: in MAL/Rescue mode media.id is the MAL identity and
        // must never enter an AniList-ID route (mapper or anilist_id lookup).
        val lookups: List<suspend () -> String?> = if (isMalMode) {
            listOf(
                { malLogoByMalIdentity(media) },
                { tmdbLogo(media, allowAnilistMapper = false) },
            )
        } else {
            listOf(
                { anilistLogo(media) },
                { malLogoByKnownMalId(media) },
                { mappedMalLogo(media) },
                { tmdbLogo(media, allowAnilistMapper = true) },
            )
        }
        // Cancellation propagates out of firstResult: later lookups are
        // skipped and the negative-cache write below is never reached.
        val logo = firstResult(lookups)

        cache[cacheKey] = logo ?: NO_LOGO
        logo
    }

    suspend fun preload(items: List<Media>) = withContext(Dispatchers.IO) {
        coroutineScope {
            items.map { media ->
                async {
                    val logo = resolve(media)
                    media.clearLogo = logo
                }
            }.awaitAll()
        }
    }

    private suspend fun anilistLogo(media: Media): String? {
        // AniList mode only: media.id is a genuine AniList identity here.
        if (media.id <= 0) return null
        return fromAniZip("anilist_id", media.id.toString())
            ?: fromAniZip("anilistId", media.id.toString())
    }

    private suspend fun malLogoByMalIdentity(media: Media): String? {
        // MAL/Rescue mode: media.id itself is the MAL identity.
        val malId = (media.idMAL ?: media.id).toString()
        if (malId.isBlank() || malId == "0") return null
        return fromAniZip("mal_id", malId)
            ?: fromAniZip("malId", malId)
    }

    private suspend fun malLogoByKnownMalId(media: Media): String? {
        // AniList mode: only a separately known MAL id may be used.
        val idMAL = media.idMAL
        if (idMAL == null || idMAL <= 0) return null
        return fromAniZip("mal_id", idMAL.toString())
            ?: fromAniZip("malId", idMAL.toString())
    }

    private suspend fun mappedMalLogo(media: Media): String? {
        // AniList-mode fallback: resolve MAL ID via the AniList-ID mapper.
        // Cancellation-safe: CancellationException propagates, ordinary
        // failure yields null (V4 Blocker 2).
        if (media.id <= 0) return null
        val ids = suppressNonCancellation { IdMappers.getIds(media.id) }
        if (ids?.malId != null && ids.malId != media.idMAL) {
            return fromAniZip("mal_id", ids.malId.toString())
                ?: fromAniZip("malId", ids.malId.toString())
        }
        return null
    }

    private suspend fun tmdbLogo(media: Media, allowAnilistMapper: Boolean): String? {
        if (media.id <= 0) return null
        val tmdbId = resolveTmdbId(
            media.idTMDB,
            allowAnilistMapper,
            media.id,
        ) { anilistId -> IdMappers.getIds(anilistId)?.tmdbId }
        if (tmdbId != null && tmdbId > 0) {
            val isMovie = media.anime?.totalEpisodes == 1 || media.typeMAL?.equals("Movie", ignoreCase = true) == true
            // Cancellation-safe inside getClearLogo (V4 Blocker 2).
            return TmdbService.getClearLogo(tmdbId, isMovie)
        }
        return null
    }
    private suspend fun fromAniZip(key: String, id: String): String? {
        if (id.isBlank() || id == "0") return null
        val endpoints = listOf(
            "https://api.ani.zip/mappings?$key=$id"
        )
        for (url in endpoints) {
            val jsonElement = getJson(url) ?: continue
            val logo = extractLogoUrl(jsonElement)
            if (!logo.isNullOrBlank()) {
                return if (logo.startsWith("http://") || logo.startsWith("https://")) {
                    "https://wsrv.nl/?url=$logo"
                } else {
                    logo
                }
            }
        }
        return null
    }

    private fun extractLogoUrl(element: JsonElement): String? {
        return when (element) {
            is JsonObject -> {
                val logoKeys = listOf("clearlogo", "clearLogo", "logo", "logoImage")
                val direct = logoKeys.firstNotNullOfOrNull { key ->
                    (element[key] as? JsonPrimitive)?.contentOrNull?.takeIf { isLogoUrl(it) }
                }
                if (direct != null) return direct

                val imagesArray = element["images"] as? JsonArray
                imagesArray?.filterIsInstance<JsonObject>()
                    ?.firstOrNull { img ->
                        (img["coverType"] as? JsonPrimitive)?.contentOrNull
                            ?.equals("Clearlogo", ignoreCase = true) == true
                    }
                    ?.let { (it["url"] as? JsonPrimitive)?.contentOrNull?.takeIf { u -> isLogoUrl(u) } }
            }
            is JsonArray -> element.filterIsInstance<JsonObject>().firstNotNullOfOrNull { extractLogoUrl(it) }
            else -> null
        }
    }

    private fun isLogoUrl(value: String): Boolean =
        (value.startsWith("http://") || value.startsWith("https://")) &&
                (value.contains(".png", ignoreCase = true) || value.contains(".svg", ignoreCase = true) ||
                        value.contains(".webp", ignoreCase = true))

    private suspend fun getJson(url: String): JsonElement? = withContext(Dispatchers.IO) {
        try {
            val response = client.get(url)
            val text = response.text
            if (text.isBlank()) return@withContext null
            json.parseToJsonElement(text)
        } catch (e: CancellationException) {
            // V4 Blocker 2: cancellation is not a fetch error.
            throw e
        } catch (e: Exception) {
            Logger.log("CarouselLogoResolver error fetching $url: ${e.message}")
            null
        }
    }
}
