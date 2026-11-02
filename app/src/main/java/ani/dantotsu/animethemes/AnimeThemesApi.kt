package ani.dantotsu.animethemes

import ani.dantotsu.Mapper
import ani.dantotsu.client
import ani.dantotsu.util.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

interface AnimeThemesApiClient {
    suspend fun queryAnimeByExternalSite(site: String, externalId: Int): AnimeThemesApiResult
}

object AnimeThemesApi : AnimeThemesApiClient {

    private const val GRAPHQL_ENDPOINT = "https://graphql.animethemes.moe/"

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

                val response = client.post(
                    url = GRAPHQL_ENDPOINT,
                    headers = mapOf(
                        "Content-Type" to "application/json",
                        "Accept" to "application/json",
                        "User-Agent" to "Editotsu/1.0"
                    ),
                    requestBody = requestBody,
                    cacheTime = 0 // bypass NiceHttp raw cache; repository manages TTL
                )

                val responseText = response.text
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
