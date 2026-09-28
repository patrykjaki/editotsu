package ani.dantotsu.connections.anilist.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class ForumThreadsResponse(
    @SerialName("data")
    val data: Data? = null
) : java.io.Serializable {
    @Serializable
    data class Data(
        @SerialName("Page")
        val page: ThreadPage? = null,
        @SerialName("Thread")
        val thread: ForumThread? = null
    ) : java.io.Serializable
}

@Serializable
data class ThreadCommentsResponse(
    @SerialName("data")
    val data: Data? = null
) : java.io.Serializable {
    @Serializable
    data class Data(
        @SerialName("Page")
        val page: ThreadCommentsPage? = null
    ) : java.io.Serializable
}

@Serializable
data class ThreadPage(
    @SerialName("pageInfo")
    val pageInfo: PageInfo? = null,
    @SerialName("threads")
    val threads: List<ForumThread> = emptyList()
) : java.io.Serializable

@Serializable
data class ThreadCommentsPage(
    @SerialName("pageInfo")
    val pageInfo: PageInfo? = null,
    @SerialName("threadComments")
    val threadComments: List<ThreadComment> = emptyList()
) : java.io.Serializable

@Serializable
data class ThreadUser(
    @SerialName("id") val id: Int = 0,
    @SerialName("name") val name: String? = null,
    @SerialName("avatar") val avatar: UserAvatar? = null,
    @SerialName("bannerImage") val bannerImage: String? = null,
) : java.io.Serializable

@Serializable
data class ThreadMedia(
    @SerialName("id") val id: Int = 0,
    @SerialName("title") val title: ThreadMediaTitle? = null,
    @SerialName("coverImage") val coverImage: ThreadMediaCoverImage? = null,
) : java.io.Serializable

@Serializable
data class ThreadMediaTitle(
    @SerialName("userPreferred") val userPreferred: String? = null,
    @SerialName("romaji") val romaji: String? = null,
    @SerialName("english") val english: String? = null,
) : java.io.Serializable

@Serializable
data class ThreadMediaCoverImage(
    @SerialName("medium") val medium: String? = null,
    @SerialName("large") val large: String? = null,
) : java.io.Serializable

@Serializable
data class ForumThread(
    @SerialName("id")
    val id: Int = 0,
    @SerialName("title")
    val title: String? = null,
    @SerialName("body")
    val body: String? = null,
    @SerialName("userId")
    val userId: Int? = null,
    @SerialName("replyCount")
    var replyCount: Int? = null,
    @SerialName("viewCount")
    val viewCount: Int? = null,
    @SerialName("isLocked")
    val isLocked: Boolean? = null,
    @SerialName("isSticky")
    val isSticky: Boolean? = null,
    @SerialName("isSubscribed")
    var isSubscribed: Boolean? = null,
    @SerialName("isLiked")
    var isLiked: Boolean? = null,
    @SerialName("likeCount")
    var likeCount: Int? = null,
    @SerialName("createdAt")
    val createdAt: Int? = null,
    @SerialName("updatedAt")
    val updatedAt: Int? = null,
    @SerialName("siteUrl")
    val siteUrl: String? = null,
    @SerialName("user")
    val user: ThreadUser? = null,
    @SerialName("categories")
    val categories: List<ThreadCategory>? = null,
    @SerialName("mediaCategories")
    val mediaCategories: List<ThreadMedia>? = null
) : java.io.Serializable

@Serializable
data class ThreadCategory(
    @SerialName("id")
    val id: Int = 0,
    @SerialName("name")
    val name: String? = null
) : java.io.Serializable

/**
 * MAX MERGE M2 correction (Blocker 2): typed view of one nested forum
 * reply. AniList serves `childComments` as an opaque JSON payload, so
 * parsing is defensive: arrays decode element-wise (bad elements drop,
 * order preserved), common wrapper objects are unwrapped, anything else
 * yields no children rather than failing the parent. Depth is unbounded
 * in the type; rendering caps indentation, never data.
 */
@Serializable
data class ThreadCommentNode(
    @SerialName("id")
    val id: Int = 0,
    @SerialName("userId")
    val userId: Int? = null,
    @SerialName("threadId")
    val threadId: Int? = null,
    @SerialName("comment")
    val comment: String? = null,
    @SerialName("isLocked")
    val isLocked: Boolean? = null,
    @SerialName("isLiked")
    val isLiked: Boolean? = null,
    @SerialName("likeCount")
    val likeCount: Int? = null,
    @SerialName("createdAt")
    val createdAt: Int? = null,
    @SerialName("updatedAt")
    val updatedAt: Int? = null,
    @SerialName("siteUrl")
    val siteUrl: String? = null,
    @SerialName("user")
    val user: ThreadUser? = null,
    @SerialName("childComments")
    val childComments: JsonElement? = null
) : java.io.Serializable {
    /** This node's own nested replies, oldest-first as served. */
    fun typedChildren(): List<ThreadCommentNode> =
        parseThreadCommentNodes(childComments)

    /** Lossless view as a top-level comment (children handled by recursion). */
    fun asThreadComment(): ThreadComment = ThreadComment(
        id = id,
        userId = userId,
        threadId = threadId,
        comment = comment,
        isLocked = isLocked,
        isLiked = isLiked,
        likeCount = likeCount,
        createdAt = createdAt,
        updatedAt = updatedAt,
        siteUrl = siteUrl,
        user = user,
        childComments = null
    )
}

private val threadNodeJson = kotlinx.serialization.json.Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    coerceInputValues = true
}

/** Lenient decode of one nested-reply payload; never throws. */
fun parseThreadCommentNodes(element: JsonElement?): List<ThreadCommentNode> {
    if (element == null || element is kotlinx.serialization.json.JsonNull) return emptyList()
    if (element is kotlinx.serialization.json.JsonArray) {
        return element.mapNotNull { item ->
            runCatching { threadNodeJson.decodeFromJsonElement(ThreadCommentNode.serializer(), item) }.getOrNull()
        }
    }
    if (element is kotlinx.serialization.json.JsonObject) {
        val wrapped = element["edges"] ?: element["nodes"] ?: element["comments"] ?: element["childComments"]
        if (wrapped != null) return parseThreadCommentNodes(wrapped)
        val single = runCatching { threadNodeJson.decodeFromJsonElement(ThreadCommentNode.serializer(), element) }.getOrNull()
        if (single != null && (single.id != 0 || single.comment != null)) return listOf(single)
    }
    return emptyList()
}

/** Top-level comment's nested replies, oldest-first as served. */
fun ThreadComment.typedChildren(): List<ThreadCommentNode> =
    parseThreadCommentNodes(childComments)

/** One rendered row: converted comment plus its nesting depth. */
data class FlatThreadComment(val comment: ThreadComment, val depth: Int)

/**
 * Production flatten used by ThreadViewActivity: parents depth-first,
 * children recurse on the NODE (never on a converted copy whose child
 * payload was dropped), served order preserved at every level.
 */
fun flattenThreadCommentTree(comments: List<ThreadComment>): List<FlatThreadComment> {
    val out = mutableListOf<FlatThreadComment>()
    fun addNode(node: ThreadCommentNode, depth: Int) {
        out.add(FlatThreadComment(node.asThreadComment(), depth))
        node.typedChildren().forEach { addNode(it, depth + 1) }
    }
    fun addComment(comment: ThreadComment, depth: Int) {
        out.add(FlatThreadComment(comment, depth))
        comment.typedChildren().forEach { addNode(it, depth + 1) }
    }
    comments.forEach { addComment(it, 0) }
    return out
}

@Serializable
data class ThreadComment(
    @SerialName("id")
    val id: Int = 0,
    @SerialName("userId")
    val userId: Int? = null,
    @SerialName("threadId")
    val threadId: Int? = null,
    @SerialName("comment")
    val comment: String? = null,
    @SerialName("isLocked")
    val isLocked: Boolean? = null,
    @SerialName("isLiked")
    var isLiked: Boolean? = null,
    @SerialName("likeCount")
    var likeCount: Int? = null,
    @SerialName("createdAt")
    val createdAt: Int? = null,
    @SerialName("updatedAt")
    val updatedAt: Int? = null,
    @SerialName("siteUrl")
    val siteUrl: String? = null,
    @SerialName("user")
    val user: ThreadUser? = null,
    @SerialName("childComments")
    val childComments: JsonElement? = null
) : java.io.Serializable
