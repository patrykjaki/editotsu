package ani.dantotsu.animethemes

import ani.dantotsu.Mapper
import ani.dantotsu.okHttpClient
import ani.dantotsu.util.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

interface AnimeThemesApiClient {
    suspend fun queryAnimeByExternalSite(site: String, externalId: Int): AnimeThemesApiResult
}

object AnimeThemesApi : AnimeThemesApiClient {

    private const val GRAPHQL_ENDPOINT = "https://graphql.animethemes.moe/"

    // The API often takes 20-40 s per lookup; the app-wide client gives up after
    // 30 s without data, which dropped most shows to the AniList text list.
    private val httpClient by lazy {
        okHttpClient.newBuilder()
            .readTimeout(90, TimeUnit.SECONDS)
            .callTimeout(120, TimeUnit.SECONDS)
            .build()
    }

    const val QUERY_GET_ANIME_THEMES = """
query GetAnimeThemesByExternalId(${'$'}site: ResourceSite!, ${'$'}id: [Int!]) {
    findAnimeByExternalSite(site: ${'$'}site, id: ${'$'}id) {
        id
        slug
        title {
            romaji
        }
        year
        season
        format
        animethemes {
            id
            type
            sequence
            slug
            song {
                id
                title {
                    romaji
                }
                performances {
                    relevance
                    alias
                    as
                    memberAlias
                    memberAs
                    artist {
                        id
                        name {
                            main
                        }
                    }
                    member {
                        id
                        name {
                            main
                        }
                    }
                }
            }
            animethemeentries {
                id
                version
                episodes
                nsfw
                spoiler
                videos {
                    nodes {
                        id
                        basename
                        filename
                        resolution
                        nc
                        subbed
                        source
                        overlap
                        tags
                        link
                    }
                }
            }
        }
    }
}
"""

    /**
     * Executes the GraphQL findAnimeByExternalSite query for site and externalId.
     * [site] must be either "ANILIST" or "MAL".
     */
    override suspend fun queryAnimeByExternalSite(site: String, externalId: Int): AnimeThemesApiResult {
        if (externalId <= 0) {
            return AnimeThemesApiResult.Failure(
                reason = ApiFailureReason.INVALID_PARAMETERS,
                message = "Invalid external ID: $externalId"
            )
        }
        return withContext(Dispatchers.IO) {
            try {
                val variables = mapOf(
                    "site" to JsonPrimitive(site),
                    "id" to JsonArray(listOf(JsonPrimitive(externalId)))
                )
                val requestPayload = GraphQLRequest(
                    query = QUERY_GET_ANIME_THEMES,
                    variables = variables
                )

                val jsonBody = Mapper.json.encodeToString(GraphQLRequest.serializer(), requestPayload)
                val requestBody = jsonBody.toRequestBody("application/json; charset=utf-8".toMediaType())

                val request = Request.Builder()
                    .url(GRAPHQL_ENDPOINT)
                    .header("Accept", "application/json")
                    .header("User-Agent", "Editotsu/1.0")
                    .post(requestBody)
                    .build()
                // Not cached by OkHttp; the repository keeps its own cache.
                val responseText = httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Logger.log("AnimeThemesApi: HTTP ${response.code} for site=$site id=$externalId")
                        return@withContext AnimeThemesApiResult.Failure(
                            reason = ApiFailureReason.NETWORK_ERROR,
                            message = "HTTP ${response.code}"
                        )
                    }
                    response.body?.string().orEmpty()
                }
                if (responseText.isBlank()) {
                    Logger.log("AnimeThemesApi: empty response for site=$site id=$externalId")
                    return@withContext AnimeThemesApiResult.Failure(
                        reason = ApiFailureReason.EMPTY_RESPONSE,
                        message = "Empty HTTP response body"
                    )
                }

                val graphQLResponse = try {
                    Mapper.json.decodeFromString(AnimeThemesGraphQLResponse.serializer(), responseText)
                } catch (decodeEx: Exception) {
                    Logger.log(decodeEx)
                    return@withContext AnimeThemesApiResult.Failure(
                        reason = ApiFailureReason.DECODE_ERROR,
                        message = decodeEx.message
                    )
                }

                if (!graphQLResponse.errors.isNullOrEmpty()) {
                    val errMsg = graphQLResponse.errors.joinToString { it.message }
                    Logger.log("AnimeThemesApi GraphQL errors: $errMsg")
                    return@withContext AnimeThemesApiResult.Failure(
                        reason = ApiFailureReason.GRAPHQL_ERROR,
                        message = errMsg
                    )
                }

                if (graphQLResponse.data == null) {
                    return@withContext AnimeThemesApiResult.Failure(
                        reason = ApiFailureReason.EMPTY_RESPONSE,
                        message = "GraphQL response missing data object"
                    )
                }

                AnimeThemesApiResult.Success(graphQLResponse)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.log(e)
                AnimeThemesApiResult.Failure(
                    reason = ApiFailureReason.NETWORK_ERROR,
                    message = e.message ?: "Unknown network exception"
                )
            }
        }
    }
}
