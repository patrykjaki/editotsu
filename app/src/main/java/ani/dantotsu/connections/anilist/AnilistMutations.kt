package ani.dantotsu.connections.anilist

import ani.dantotsu.connections.anilist.Anilist.executeQuery
import ani.dantotsu.connections.anilist.api.FuzzyDate
import ani.dantotsu.connections.anilist.api.Query
import ani.dantotsu.connections.anilist.api.ToggleLike
import ani.dantotsu.currContext
import com.google.gson.Gson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

class AnilistMutations {

    suspend fun updateSettings(
        timezone: String? = null,
        titleLanguage: String? = null,
        staffNameLanguage: String? = null,
        activityMergeTime: Int? = null,
        airingNotifications: Boolean? = null,
        displayAdultContent: Boolean? = null,
        restrictMessagesToFollowing: Boolean? = null,
        scoreFormat: String? = null,
        rowOrder: String? = null,
    ) {
        val query = """
            mutation (
                ${"$"}timezone: String,
                ${"$"}titleLanguage: UserTitleLanguage,
                ${"$"}staffNameLanguage: UserStaffNameLanguage,
                ${"$"}activityMergeTime: Int,
                ${"$"}airingNotifications: Boolean,
                ${"$"}displayAdultContent: Boolean,
                ${"$"}restrictMessagesToFollowing: Boolean,
                ${"$"}scoreFormat: ScoreFormat,
                ${"$"}rowOrder: String
            ) {
                UpdateUser(
                    timezone: ${"$"}timezone,
                    titleLanguage: ${"$"}titleLanguage,
                    staffNameLanguage: ${"$"}staffNameLanguage,
                    activityMergeTime: ${"$"}activityMergeTime,
                    airingNotifications: ${"$"}airingNotifications,
                    displayAdultContent: ${"$"}displayAdultContent,
                    restrictMessagesToFollowing: ${"$"}restrictMessagesToFollowing,
                    scoreFormat: ${"$"}scoreFormat,
                    rowOrder: ${"$"}rowOrder,
                ) {
                    id
                    options {
                        timezone
                        titleLanguage
                        staffNameLanguage
                        activityMergeTime
                        airingNotifications
                        displayAdultContent
                        restrictMessagesToFollowing
                    }
                    mediaListOptions {
                      scoreFormat
                      rowOrder
                    }
                }
            }
        """.trimIndent()

        val variables = """
            {
                ${timezone?.let { """"timezone":"$it"""" } ?: ""}
                ${titleLanguage?.let { """"titleLanguage":"$it"""" } ?: ""}
                ${staffNameLanguage?.let { """"staffNameLanguage":"$it"""" } ?: ""}
                ${activityMergeTime?.let { """"activityMergeTime":$it""" } ?: ""}
                ${airingNotifications?.let { """"airingNotifications":$it""" } ?: ""}
                ${displayAdultContent?.let { """"displayAdultContent":$it""" } ?: ""}
                ${restrictMessagesToFollowing?.let { """"restrictMessagesToFollowing":$it""" } ?: ""}
                ${scoreFormat?.let { """"scoreFormat":"$it"""" } ?: ""}
                ${rowOrder?.let { """"rowOrder":"$it"""" } ?: ""}
            }
        """.trimIndent().replace("\n", "").replace("""    """, "").replace(",}", "}")

        executeQuery<JsonObject>(query, variables)
    }

    suspend fun toggleFav(anime: Boolean = true, id: Int) {
        val query = """
            mutation (${"$"}animeId: Int, ${"$"}mangaId: Int) {
                ToggleFavourite(animeId: ${"$"}animeId, mangaId: ${"$"}mangaId) {
                    anime {
                        edges {
                            id
                        }
                    }
                    manga {
                        edges {
                            id
                        }
                    }
                }
            }
        """.trimIndent()
        val variables = if (anime) """{"animeId":"$id"}""" else """{"mangaId":"$id"}"""
        executeQuery<JsonObject>(query, variables)
    }

    suspend fun toggleFav(type: FavType, id: Int): Boolean {
        val filter = when (type) {
            FavType.ANIME -> "animeId"
            FavType.MANGA -> "mangaId"
            FavType.CHARACTER -> "characterId"
            FavType.STAFF -> "staffId"
            FavType.STUDIO -> "studioId"
        }
        val query = """
            mutation {
                ToggleFavourite($filter: $id) {
                    anime {
                        pageInfo {
                            total
                        }
                    }
                }
            }
        """.trimIndent()
        val result = executeQuery<JsonObject>(query)
        return result?.get("errors") == null && result != null
    }

    enum class FavType {
        ANIME, MANGA, CHARACTER, STAFF, STUDIO
    }

    suspend fun deleteCustomList(name: String, type: String): Boolean {
        val query = """
            mutation (${"$"}name: String, ${"$"}type: MediaType) {
                DeleteCustomList(customList: ${"$"}name, type: ${"$"}type) {
                    deleted
                }
            }
        """.trimIndent()
        val variables = """
            {
                "name": "$name",
                "type": "$type"
            }
        """.trimIndent()
        val result = executeQuery<JsonObject>(query, variables)
        return result?.get("errors") == null
    }

    suspend fun updateCustomLists(
        animeCustomLists: List<String>?,
        mangaCustomLists: List<String>?
    ): Boolean {
        val query = """
            mutation (${"$"}animeListOptions: MediaListOptionsInput, ${"$"}mangaListOptions: MediaListOptionsInput) {
                UpdateUser(animeListOptions: ${"$"}animeListOptions, mangaListOptions: ${"$"}mangaListOptions) {
                    mediaListOptions {
                        animeList {
                            customLists
                        }
                        mangaList {
                            customLists
                        }
                    }
                }
            }
        """.trimIndent()
        val variables = """
            {
                ${animeCustomLists?.let { """"animeListOptions": {"customLists": ${Gson().toJson(it)}}""" } ?: ""}
                ${if (animeCustomLists != null && mangaCustomLists != null) "," else ""}
                ${mangaCustomLists?.let { """"mangaListOptions": {"customLists": ${Gson().toJson(it)}}""" } ?: ""}
            }
        """.trimIndent().replace("\n", "").replace("""    """, "").replace(",}", "}")

        val result = executeQuery<JsonObject>(query, variables)
        return result?.get("errors") == null
    }

    suspend fun editList(
        mediaID: Int,
        progress: Int? = null,
        progressVolumes: Int? = null,
        score: Int? = null,
        repeat: Int? = null,
        notes: String? = null,
        status: String? = null,
        private: Boolean? = null,
        priority: Int? = null,
        hiddenFromStatusLists: Boolean? = null,
        startedAt: FuzzyDate? = null,
        completedAt: FuzzyDate? = null,
        customList: List<String>? = null,
        advancedScores: List<Double>? = null
    ): Boolean {
        val headerParams = mutableListOf(
            "${"$"}mediaID: Int",
            "${"$"}progress: Int",
            "${"$"}progressVolumes: Int",
            "${"$"}private: Boolean",
            "${"$"}priority: Int",
            "${"$"}hiddenFromStatusLists: Boolean",
            "${"$"}repeat: Int",
            "${"$"}notes: String",
            "${"$"}customLists: [String]",
            "${"$"}scoreRaw: Int",
            "${"$"}status: MediaListStatus"
        )
        val entryArgs = mutableListOf(
            "mediaId: ${"$"}mediaID",
            "progress: ${"$"}progress",
            "progressVolumes: ${"$"}progressVolumes",
            "repeat: ${"$"}repeat",
            "notes: ${"$"}notes",
            "private: ${"$"}private",
            "priority: ${"$"}priority",
            "hiddenFromStatusLists: ${"$"}hiddenFromStatusLists",
            "scoreRaw: ${"$"}scoreRaw",
            "status: ${"$"}status",
            "customLists: ${"$"}customLists"
        )

        if (startedAt != null) {
            headerParams.add("${"$"}start: FuzzyDateInput = ${startedAt.toVariableString()}")
            entryArgs.add("startedAt: ${"$"}start")
        }
        if (completedAt != null) {
            headerParams.add("${"$"}completed: FuzzyDateInput = ${completedAt.toVariableString()}")
            entryArgs.add("completedAt: ${"$"}completed")
        }
        if (advancedScores != null) {
            headerParams.add("${"$"}advancedScores: [Float]")
            entryArgs.add("advancedScores: ${"$"}advancedScores")
        }

        val query = """
            mutation (
                ${headerParams.joinToString(",\n                ")}
            ) {
                SaveMediaListEntry(
                    ${entryArgs.joinToString(",\n                    ")}
                ) {
                    score(format: POINT_10_DECIMAL)
                    startedAt {
                        year
                        month
                        day
                    }
                    completedAt {
                        year
                        month
                        day
                    }
                }
            }
        """.trimIndent()

        val variables = editListVariables(
            mediaID = mediaID,
            private = private,
            priority = priority,
            hiddenFromStatusLists = hiddenFromStatusLists,
            progress = progress,
            progressVolumes = progressVolumes,
            score = score,
            repeat = repeat,
            notes = notes,
            status = status,
            customList = customList,
            advancedScores = advancedScores,
        ).toString()
        val result = executeQuery<JsonObject>(query, variables, show = true)
        // Upstream 5ffa682b: list edits must drop stale home/stories caches.
        Anilist.query.invalidateUserStatusCache()
        Anilist.query.invalidateHomePageCache()
        // null = network error, timeout, 429 window or HTTP error.
        return result != null && result["errors"] == null
    }

    suspend fun deleteList(listId: Int) {
        val query = """
            mutation(${"$"}id: Int) {
                DeleteMediaListEntry(id: ${"$"}id) {
                    deleted
                }
            }
        """.trimIndent()
        val variables = """{"id":"$listId"}"""
        executeQuery<JsonObject>(query, variables)
        // Upstream 5ffa682b: deletions must drop stale home/stories caches.
        Anilist.query.invalidateUserStatusCache()
        Anilist.query.invalidateHomePageCache()
    }

    suspend fun rateReview(reviewId: Int, rating: String): Query.RateReviewResponse? {
        val query = """
            mutation {
                RateReview(reviewId: $reviewId, rating: $rating) {
                    id
                    mediaId
                    mediaType
                    summary
                    body(asHtml: true)
                    rating
                    ratingAmount
                    userRating
                    score
                    private
                    siteUrl
                    createdAt
                    updatedAt
                    user {
                        id
                        name
                        bannerImage
                        avatar {
                            medium
                            large
                        }
                    }
                }
            }
        """.trimIndent()
        return executeQuery<Query.RateReviewResponse>(query)
    }

    suspend fun toggleFollow(id: Int): Query.ToggleFollow? {
        return executeQuery<Query.ToggleFollow>(
            """
            mutation {
                ToggleFollow(userId: $id) {
                    id
                    isFollowing
                    isFollower
                }
            }
        """.trimIndent()
        )
    }

    suspend fun toggleLike(id: Int, type: String): ToggleLike? {
        return executeQuery<ToggleLike>(
            """
            mutation Like {
                ToggleLikeV2(id: $id, type: $type) {
                    __typename
                }
            }
        """.trimIndent()
        )
    }

    suspend fun toggleActivitySubscription(activityId: Int, subscribe: Boolean): Boolean {
        val result = executeQuery<JsonObject>(
            """
            mutation {
                ToggleActivitySubscription(activityId: $activityId, subscribe: $subscribe) {
                    __typename
                }
            }
        """.trimIndent()
        )
        val errors = result?.get("errors") as? JsonArray

        if (result != null && errors.isNullOrEmpty()) {
            Anilist.query.invalidateUserStatusCache()
            return true
        }
        return false
    }

    suspend fun toggleActivityPin(activityId: Int, pinned: Boolean): Boolean {
        val result = executeQuery<JsonObject>(
            """
            mutation {
                ToggleActivityPin(id: $activityId, pinned: $pinned) {
                    __typename
                }
            }
        """.trimIndent()
        )
        val errors = result?.get("errors") as? JsonArray
        if (result != null && errors.isNullOrEmpty()) {
            Anilist.query.invalidateUserStatusCache()
            return true
        }
        return false
    }

    suspend fun toggleBlock(userId: Int): Boolean {
        val query = """
            mutation {
                ToggleBlock(userId: $userId) {
                    id
                    isBlocked
                }
            }
        """.trimIndent()
        val result = executeQuery<JsonObject>(query)
        return result?.get("errors") == null && result != null
    }

    // Upstream e70fdc56: extract human-readable (incl. field-validation)
    // messages from mutation error payloads instead of raw JSON.
    // Internal (not private) so the extraction contract is unit-tested.
    internal fun JsonObject.extractErrorMessage(): String? {
        val errors = this["errors"]?.let { it as? JsonArray } ?: return null
        val messages = errors.mapNotNull { elem ->
            val obj = elem.jsonObject
            val validationObj = obj["validation"]?.let { it as? JsonObject }
            if (validationObj != null) {
                val validationMessages = validationObj.values.flatMap { v ->
                    (v as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
                }
                if (validationMessages.isNotEmpty()) {
                    validationMessages.joinToString("\n")
                } else {
                    obj["message"]?.jsonPrimitive?.contentOrNull
                }
            } else {
                obj["message"]?.jsonPrimitive?.contentOrNull
            }
        }
        return if (messages.isNotEmpty()) messages.joinToString("\n") else null
    }

    /** Result of a post-style mutation; [message] is shown to the user either way. */
    data class PostResult(val ok: Boolean, val message: String)

    /**
     * A null [result] means executeQuery failed (network, timeout, 429, 400/403),
     * so it is a failure too, reported with the query's last error.
     */
    internal fun postOutcome(
        result: JsonObject?,
        lastError: String? = Anilist.lastError,
        successText: String = currContext()?.getString(ani.dantotsu.R.string.success) ?: "Success",
        fallbackError: String = currContext()?.getString(ani.dantotsu.R.string.post_failed) ?: "Could not post to AniList"
    ): PostResult {
        if (result == null) return PostResult(false, lastError?.takeIf { it.isNotBlank() } ?: fallbackError)
        if (result["errors"] != null) {
            return PostResult(false, result.extractErrorMessage() ?: lastError ?: fallbackError)
        }
        return PostResult(true, successText)
    }

    suspend fun postActivity(text: String, edit: Int? = null): PostResult {
        val encodedText = text.stringSanitizer()
        val query = """
            mutation {
                SaveTextActivity(${if (edit != null) "id: $edit," else ""} text: $encodedText) {
                    siteUrl
                }
            }
        """.trimIndent()
        val result = executeQuery<JsonObject>(query)
        val outcome = postOutcome(result)
        // Upstream 5ffa682b: successful posts must drop the stories cache.
        if (outcome.ok) {
            Anilist.query.invalidateUserStatusCache()
        }
        return outcome
    }

    suspend fun postMessage(
        userId: Int,
        text: String,
        edit: Int? = null,
        isPrivate: Boolean = false
    ): PostResult {
        val encodedText = text.replace("", "").stringSanitizer()
        val query = """
            mutation {
                SaveMessageActivity(
                    ${if (edit != null) "id: $edit," else ""}
                    recipientId: $userId,
                    message: $encodedText,
                    private: $isPrivate
                ) {
                    id
                }
            }
        """.trimIndent()
        val result = executeQuery<JsonObject>(query)
        return postOutcome(result)
    }

    suspend fun postReply(activityId: Int, text: String, edit: Int? = null): PostResult {
        val encodedText = text.stringSanitizer()
        val query = """
            mutation {
                SaveActivityReply(
                    ${if (edit != null) "id: $edit," else ""}
                    activityId: $activityId,
                    text: $encodedText
                ) {
                    id
                }
            }
        """.trimIndent()
        val result = executeQuery<JsonObject>(query)
        return postOutcome(result)
    }

    suspend fun postReview(
        summary: String,
        body: String,
        mediaId: Int,
        score: Int,
        edit: Int? = null,
        isPrivate: Boolean = false
    ): PostResult {
        val encodedSummary = summary.stringSanitizer()
        val encodedBody = body.stringSanitizer()
        val query = """
            mutation {
                SaveReview(
                    ${if (edit != null) "id: $edit," else ""}
                    mediaId: $mediaId,
                    summary: $encodedSummary,
                    body: $encodedBody,
                    score: $score,
                    private: $isPrivate
                ) {
                    siteUrl
                    id
                }
            }
        """.trimIndent()
        val result = executeQuery<JsonObject>(query)
        return postOutcome(result)
    }

    suspend fun deleteReview(reviewId: Int): Boolean {
        val query = """
            mutation {
                DeleteReview(id: $reviewId) {
                    deleted
                }
            }
        """.trimIndent()
        val result = executeQuery<JsonObject>(query)
        val errors = result?.get("errors")
        return errors == null && result != null
    }

    suspend fun saveRecommendation(
        mediaId: Int,
        mediaRecommendationId: Int,
        rating: String? = null
    ): Boolean {
        val ratingArg = if (rating != null) ", rating: $rating" else ""
        val query = """
            mutation {
                SaveRecommendation(
                    mediaId: $mediaId,
                    mediaRecommendationId: $mediaRecommendationId
                    $ratingArg
                ) {
                    id
                    rating
                    userRating
                }
            }
        """.trimIndent()
        val result = executeQuery<JsonObject>(query)
        val errors = result?.get("errors")
        return errors == null && result != null
    }

    suspend fun saveThread(
        title: String,
        body: String,
        categories: List<Int>? = null,
        mediaCategories: List<Int>? = null,
        edit: Int? = null
    ): PostResult {
        val encodedTitle = title.stringSanitizer()
        val encodedBody = body.stringSanitizer()
        val categoriesArg = if (!categories.isNullOrEmpty()) ", categories: [${categories.joinToString(",")}]" else ""
        val mediaCategoriesArg = if (!mediaCategories.isNullOrEmpty()) ", mediaCategories: [${mediaCategories.joinToString(",")}]" else ""
        val query = """
            mutation {
                SaveThread(
                    ${if (edit != null) "id: $edit," else ""}
                    title: $encodedTitle,
                    body: $encodedBody
                    $categoriesArg
                    $mediaCategoriesArg
                ) {
                    id
                    siteUrl
                }
            }
        """.trimIndent()
        val result = executeQuery<JsonObject>(query)
        return postOutcome(result)
    }

    suspend fun deleteThread(threadId: Int): Boolean {
        val query = """
            mutation {
                DeleteThread(id: $threadId) {
                    deleted
                }
            }
        """.trimIndent()
        val result = executeQuery<JsonObject>(query)
        val errors = result?.get("errors")
        return errors == null && result != null
    }

    suspend fun saveThreadComment(
        threadId: Int,
        comment: String,
        parentCommentId: Int? = null,
        edit: Int? = null
    ): PostResult {
        val encodedComment = comment.stringSanitizer()
        val parentArg = if (parentCommentId != null) ", parentCommentId: $parentCommentId" else ""
        val query = """
            mutation {
                SaveThreadComment(
                    ${if (edit != null) "id: $edit," else ""}
                    threadId: $threadId,
                    comment: $encodedComment
                    $parentArg
                ) {
                    id
                    siteUrl
                }
            }
        """.trimIndent()
        val result = executeQuery<JsonObject>(query)
        return postOutcome(result)
    }

    suspend fun deleteThreadComment(commentId: Int): Boolean {
        val query = """
            mutation {
                DeleteThreadComment(id: $commentId) {
                    deleted
                }
            }
        """.trimIndent()
        val result = executeQuery<JsonObject>(query)
        val errors = result?.get("errors")
        return errors == null && result != null
    }

    suspend fun toggleThreadSubscription(threadId: Int, subscribe: Boolean): Boolean {
        val query = """
            mutation {
                ToggleThreadSubscription(threadId: $threadId, subscribe: $subscribe) {
                    id
                    isSubscribed
                }
            }
        """.trimIndent()
        val result = executeQuery<JsonObject>(query)
        val errors = result?.get("errors")
        return errors == null && result != null
    }

    suspend fun updateUserBio(about: String): Boolean {
        val encodedAbout = about.stringSanitizer()
        val query = """
            mutation {
                UpdateUser(about: $encodedAbout) {
                    id
                    about
                }
            }
        """.trimIndent()
        val result = executeQuery<JsonObject>(query)
        val errors = result?.get("errors")
        return errors == null && result != null
    }

    suspend fun deleteActivityReply(activityId: Int): Boolean {
        val query = """
            mutation {
                DeleteActivityReply(id: $activityId) {
                    deleted
                }
            }
        """.trimIndent()
        val result = executeQuery<JsonObject>(query)
        val errors = result?.get("errors")
        return errors == null
    }

    suspend fun deleteActivity(activityId: Int): Boolean {
        val query = """
            mutation {
                DeleteActivity(id: $activityId) {
                    deleted
                }
            }
        """.trimIndent()
        val result = executeQuery<JsonObject>(query)
        val errors = result?.get("errors")
        // Upstream 5ffa682b: successful deletions must drop the stories cache.
        if (errors == null && result != null) {
            Anilist.query.invalidateUserStatusCache()
        }
        return errors == null
    }

    private fun String.stringSanitizer(): String {
        val sb = StringBuilder()
        var i = 0
        while (i < this.length) {
            val codePoint = this.codePointAt(i)
            if (codePoint > 0xFFFF) {
                sb.append("&#").append(codePoint).append(";")
                i += 2
            } else {
                sb.append(this[i])
                i++
            }
        }
        return Gson().toJson(sb.toString())
    }
}

/**
 * GraphQL variables for SaveMediaListEntry. Built as a JSON object so notes
 * and custom list names with quotes, backslashes or newlines stay valid.
 */
internal fun editListVariables(
    mediaID: Int,
    private: Boolean? = null,
    priority: Int? = null,
    hiddenFromStatusLists: Boolean? = null,
    progress: Int? = null,
    progressVolumes: Int? = null,
    score: Int? = null,
    repeat: Int? = null,
    notes: String? = null,
    status: String? = null,
    customList: List<String>? = null,
    advancedScores: List<Double>? = null,
): JsonObject = buildJsonObject {
    put("mediaID", mediaID)
    private?.let { put("private", it) }
    priority?.let { put("priority", it) }
    hiddenFromStatusLists?.let { put("hiddenFromStatusLists", it) }
    progress?.let { put("progress", it) }
    progressVolumes?.let { put("progressVolumes", it) }
    score?.let { put("scoreRaw", it) }
    repeat?.let { put("repeat", it) }
    notes?.let { put("notes", it) }
    status?.let { put("status", it) }
    customList?.let { list -> putJsonArray("customLists") { list.forEach { add(it) } } }
    advancedScores?.let { list -> putJsonArray("advancedScores") { list.forEach { add(it) } } }
}
