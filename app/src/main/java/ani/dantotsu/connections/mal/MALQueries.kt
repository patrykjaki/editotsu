package ani.dantotsu.connections.mal

import ani.dantotsu.client
import ani.dantotsu.connections.anilist.api.FuzzyDate
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.tryWithSuspend
import ani.dantotsu.util.Logger
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.Serializable
import java.net.URLEncoder

class MALQueries {
    private val apiUrl = "https://api.myanimelist.net/v2"

    companion object {
        internal var testHttpHandler: (suspend (url: String, headers: Map<String, String>, method: String, data: Map<String, String>?) -> com.lagradost.nicehttp.NiceResponse)? = null
    }

    private suspend fun httpGet(url: String, headers: Map<String, String>): com.lagradost.nicehttp.NiceResponse {
        val handler = testHttpHandler
        if (handler != null) {
            return handler(url, headers, "GET", null)
        }
        return client.get(url, headers)
    }

    private suspend fun httpPut(url: String, headers: Map<String, String>, data: Map<String, String>): com.lagradost.nicehttp.NiceResponse {
        val handler = testHttpHandler
        if (handler != null) {
            return handler(url, headers, "PUT", data)
        }
        return client.put(url, headers, data = data)
    }

    private suspend fun httpDelete(url: String, headers: Map<String, String>): com.lagradost.nicehttp.NiceResponse {
        val handler = testHttpHandler
        if (handler != null) {
            return handler(url, headers, "DELETE", null)
        }
        return client.delete(url, headers)
    }

    private val authHeader: Map<String, String>?
        get() {
            return mapOf("Authorization" to "Bearer ${MAL.token ?: return null}")
        }
    private val clientIdHeader: Map<String, String>
        get() = mapOf("X-MAL-CLIENT-ID" to MAL.clientId)

    private fun preferredHeader(): Map<String, String> = authHeader ?: clientIdHeader

    @Serializable
    data class MalUser(
        val id: Int,
        val name: String,
        val picture: String? = null,
        @kotlinx.serialization.SerialName("anime_statistics") val animeStatistics: MalAnimeStatistics? = null,
        @kotlinx.serialization.SerialName("manga_statistics") val mangaStatistics: MalMangaStatistics? = null,
    )

    internal suspend fun executeRequest(
        requestBlock: suspend () -> com.lagradost.nicehttp.NiceResponse
    ): com.lagradost.nicehttp.NiceResponse {
        var lastResponse: com.lagradost.nicehttp.NiceResponse? = null
        var lastException: Exception? = null
        var delayMs = 1000L
        val maxAttempts = 3
        var refreshedFor401 = false

        for (attempt in 1..maxAttempts) {
            val tokenBeforeRequest = MAL.token
            try {
                val response = requestBlock()
                lastResponse = response
                if (response.code == 401 && !refreshedFor401 && tokenBeforeRequest != null) {
                    refreshedFor401 = true
                    val refreshed = MAL.refreshToken(force = true, failedAccessToken = tokenBeforeRequest)
                    if (refreshed != null) {
                        continue
                    }
                }
                if (response.code != 429) {
                    return response
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                lastException = e
            }

            if (attempt < maxAttempts) {
                val jitter = (0..200).random().toLong()
                kotlinx.coroutines.delay(delayMs + jitter)
                delayMs *= 2
            }
        }

        return lastResponse ?: throw (lastException ?: Exception("Request failed after $maxAttempts attempts"))
    }

    suspend fun getUserData(): Boolean {
        val generationAtStart = MAL.currentAuthGeneration
        if (authHeader == null) return false
        val res = try {
            val response = executeRequest {
                httpGet(
                    "$apiUrl/users/@me?fields=picture,anime_statistics,manga_statistics",
                    authHeader ?: emptyMap()
                )
            }
            if (!response.isSuccessful) {
                Logger.log("MAL: getUserData failed with HTTP status ${response.code}")
                return false
            }
            response.parsed<MalUser>()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.log("MAL: getUserData network or parse error (${e.javaClass.simpleName})")
            return false
        }

        if (MAL.currentAuthGeneration != generationAtStart) return false
        kotlin.coroutines.coroutineContext.ensureActive()

        var avatarUrl = res.picture
        if (avatarUrl.isNullOrBlank() && res.name.isNotBlank()) {
            try {
                val jikanProfile = MAL.jikan.getUserProfile(res.name)
                avatarUrl = jikanProfile?.images?.jpg?.imageUrl ?: jikanProfile?.images?.webp?.imageUrl
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // Non-fatal Jikan profile fallback error
            }
        }

        if (MAL.currentAuthGeneration != generationAtStart) return false
        kotlin.coroutines.coroutineContext.ensureActive()

        val episodes = res.animeStatistics?.numEpisodes ?: estimateEpisodesWatched()
        if (MAL.currentAuthGeneration != generationAtStart) return false
        kotlin.coroutines.coroutineContext.ensureActive()

        val chapters = res.mangaStatistics?.numChaptersRead ?: estimateChaptersRead()
        if (MAL.currentAuthGeneration != generationAtStart) return false
        kotlin.coroutines.coroutineContext.ensureActive()

        return MAL.commitProfile(
            userId = res.id,
            username = res.name,
            avatar = avatarUrl,
            episodesWatched = episodes,
            chaptersRead = chapters,
            generation = generationAtStart
        )
    }

    private suspend fun estimateEpisodesWatched(): Int? {
        val statuses = listOf("watching", "completed", "on_hold", "dropped", "plan_to_watch")
        var total = 0
        var loaded = false
        for (status in statuses) {
            kotlin.coroutines.coroutineContext.ensureActive()
            getUserAnimeList(status = status, limit = 100)?.data?.let { entries ->
                loaded = true
                total += entries.sumOf { it.listStatus?.numEpisodesWatched ?: 0 }
            }
        }
        return if (loaded) total else null
    }

    private suspend fun estimateChaptersRead(): Int? {
        val statuses = listOf("reading", "completed", "on_hold", "dropped", "plan_to_read")
        var total = 0
        var loaded = false
        for (status in statuses) {
            kotlin.coroutines.coroutineContext.ensureActive()
            getUserMangaList(status = status, limit = 100)?.data?.let { entries ->
                loaded = true
                total += entries.sumOf { it.listStatus?.numChaptersRead ?: 0 }
            }
        }
        return if (loaded) total else null
    }

    suspend fun editList(
        idMAL: Int?,
        isAnime: Boolean,
        progress: Int?,
        score: Int?,
        status: String,
        rewatch: Int? = null,
        start: FuzzyDate? = null,
        end: FuzzyDate? = null
    ) {
        if (idMAL == null || authHeader == null) return
        val data = mutableMapOf("status" to convertStatus(isAnime, status))
        if (progress != null)
            data[if (isAnime) "num_watched_episodes" else "num_chapters_read"] = progress.toString()
        data[if (isAnime) "is_rewatching" else "is_rereading"] = (status == "REPEATING").toString()
        if (score != null)
            data["score"] = Math.round(score / 10.0).coerceIn(0L, 10L).toString()
        if (rewatch != null)
            data[if (isAnime) "num_times_rewatched" else "num_times_reread"] = rewatch.toString()
        if (start != null && !start.isEmpty())
            data["start_date"] = start.toMALString()
        if (end != null && !end.isEmpty())
            data["finish_date"] = end.toMALString()
        tryWithSuspend {
            executeRequest {
                httpPut(
                    "$apiUrl/${if (isAnime) "anime" else "manga"}/$idMAL/my_list_status",
                    authHeader ?: emptyMap(),
                    data = data,
                )
            }
        }
    }

    suspend fun deleteList(isAnime: Boolean, idMAL: Int?) {
        if (idMAL == null || authHeader == null) return
        tryWithSuspend {
            executeRequest {
                httpDelete(
                    "$apiUrl/${if (isAnime) "anime" else "manga"}/$idMAL/my_list_status",
                    authHeader ?: emptyMap()
                )
            }
        }
    }

    private val listFields = "list_status,num_episodes,num_chapters,main_picture,mean,media_type,status,genres,start_date,start_season"

    suspend fun getUserAnimeList(
        status: String? = null,
        sort: String = "list_updated_at",
        limit: Int = 100,
        offset: Int = 0,
    ): MalListResponse? {
        if (authHeader == null) return null
        val statusParam = status?.let { "&status=$it" } ?: ""
        val offsetParam = if (offset > 0) "&offset=$offset" else ""
        return tryWithSuspend {
            executeRequest {
                httpGet(
                    "$apiUrl/users/@me/animelist?fields=$listFields&sort=$sort&limit=$limit$offsetParam&nsfw=1$statusParam",
                    authHeader ?: emptyMap()
                )
            }.parsed<MalListResponse>()
        }
    }

    suspend fun getUserMangaList(
        status: String? = null,
        sort: String = "list_updated_at",
        limit: Int = 100,
        offset: Int = 0,
    ): MalListResponse? {
        if (authHeader == null) return null
        val statusParam = status?.let { "&status=$it" } ?: ""
        val offsetParam = if (offset > 0) "&offset=$offset" else ""
        return tryWithSuspend {
            executeRequest {
                httpGet(
                    "$apiUrl/users/@me/mangalist?fields=$listFields&sort=$sort&limit=$limit$offsetParam&nsfw=1$statusParam",
                    authHeader ?: emptyMap()
                )
            }.parsed<MalListResponse>()
        }
    }

    private val rankingFields = "mean,status,media_type,num_episodes,num_chapters,main_picture,genres,my_list_status,start_date,start_season"

    suspend fun getAnimeRanking(
        rankingType: String = "all",
        limit: Int = 15,
        offset: Int = 0,
    ): MalRankingResponse? {
        return tryWithSuspend {
            executeRequest {
                httpGet(
                    "$apiUrl/anime/ranking?ranking_type=$rankingType&limit=$limit&offset=$offset&fields=$rankingFields",
                    preferredHeader()
                )
            }.parsed<MalRankingResponse>()
        }
    }

    suspend fun getMangaRanking(
        rankingType: String = "all",
        limit: Int = 15,
        offset: Int = 0,
    ): MalRankingResponse? {
        return tryWithSuspend {
            executeRequest {
                httpGet(
                    "$apiUrl/manga/ranking?ranking_type=$rankingType&limit=$limit&offset=$offset&fields=$rankingFields",
                    preferredHeader()
                )
            }.parsed<MalRankingResponse>()
        }
    }

    suspend fun searchAnime(
        query: String,
        limit: Int = 25,
        offset: Int = 0,
    ): MalRankingResponse? {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        return tryWithSuspend {
            executeRequest {
                httpGet(
                    "$apiUrl/anime?q=$encodedQuery&limit=$limit&offset=$offset&fields=$rankingFields",
                    preferredHeader()
                )
            }.parsed<MalRankingResponse>()
        }
    }

    suspend fun searchManga(
        query: String,
        limit: Int = 25,
        offset: Int = 0,
    ): MalRankingResponse? {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        return tryWithSuspend {
            executeRequest {
                httpGet(
                    "$apiUrl/manga?q=$encodedQuery&limit=$limit&offset=$offset&fields=$rankingFields",
                    preferredHeader()
                )
            }.parsed<MalRankingResponse>()
        }
    }

    private val recRelFields = "%7Bnode%7Bid,title,main_picture,num_episodes,num_chapters,mean,media_type,status,alternative_titles,rating,popularity%7D%7D"
    private val detailFields = "mean,status,media_type,synopsis,genres,num_episodes,num_chapters," +
        "main_picture,alternative_titles,title_synonyms,start_date,end_date,start_season,source,rating," +
        "average_episode_duration,studios,authors,rank,popularity,my_list_status," +
        "recommendations$recRelFields,related_anime$recRelFields,related_manga$recRelFields"

    suspend fun getAnimeDetails(malId: Int): MalAnimeNode? {
        return tryWithSuspend {
            executeRequest {
                httpGet(
                    "$apiUrl/anime/$malId?fields=$detailFields",
                    preferredHeader()
                )
            }.parsed<MalAnimeNode>()
        }
    }

    suspend fun getMangaDetails(malId: Int): MalAnimeNode? {
        return tryWithSuspend {
            executeRequest {
                httpGet(
                    "$apiUrl/manga/$malId?fields=$detailFields",
                    preferredHeader()
                )
            }.parsed<MalAnimeNode>()
        }
    }

    suspend fun getSeasonalAnime(
        year: Int,
        season: String,
        sort: String = "anime_num_list_users",
        limit: Int = 15,
    ): MalRankingResponse? {
        return tryWithSuspend {
            executeRequest {
                httpGet(
                    "$apiUrl/anime/season/$year/$season?sort=$sort&limit=$limit&fields=$rankingFields",
                    preferredHeader()
                )
            }.parsed<MalRankingResponse>()
        }
    }

    private fun convertStatus(isAnime: Boolean, status: String): String {
        return when (status) {
            "PLANNING"   -> if (isAnime) "plan_to_watch" else "plan_to_read"
            "COMPLETED"  -> "completed"
            "PAUSED"     -> "on_hold"
            "DROPPED"    -> "dropped"
            "REPEATING"  -> if (isAnime) "rewatching" else "rereading"
            "CURRENT"    -> if (isAnime) "watching" else "reading"
            else         -> if (isAnime) "watching" else "reading"
        }
    }

    suspend fun getAnimeSuggestions(limit: Int = 15): MalRankingResponse? {
        if (authHeader == null) return null
        return tryWithSuspend {
            executeRequest {
                httpGet(
                    "$apiUrl/anime/suggestions?limit=$limit&fields=$rankingFields",
                    authHeader ?: emptyMap()
                )
            }.parsed<MalRankingResponse>()
        }
    }

    private val sequelListFields = "list_status,num_episodes,main_picture,mean,media_type,status,related_anime${recRelFields}"

    suspend fun getCompletedAnimeWithRelations(limit: Int = 100): MalListResponse? {
        if (authHeader == null) return null
        return tryWithSuspend {
            executeRequest {
                httpGet(
                    "$apiUrl/users/@me/animelist?fields=$sequelListFields&status=completed&sort=list_updated_at&limit=$limit&nsfw=1",
                    authHeader ?: emptyMap()
                )
            }.parsed<MalListResponse>()
        }
    }

    suspend fun getAllUserAnimeIds(): Set<Int> {
        val allIds = mutableSetOf<Int>()
        var offset = 0
        val batchSize = 100
        if (authHeader == null) return emptySet()
        while (true) {
            val response = tryWithSuspend {
                executeRequest {
                    httpGet(
                        "$apiUrl/users/@me/animelist?fields=&sort=list_updated_at&limit=$batchSize&offset=$offset&nsfw=1",
                        authHeader ?: emptyMap()
                    )
                }.parsed<MalListResponse>()
            } ?: break
            response.data.forEach { allIds.add(it.node.id) }
            if (response.data.size < batchSize || response.paging?.next == null) break
            offset += batchSize
        }
        return allIds
    }
}
