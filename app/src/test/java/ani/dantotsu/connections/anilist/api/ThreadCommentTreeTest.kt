package ani.dantotsu.connections.anilist.api

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MAX MERGE M2 correction (Blocker 2): opaque `childComments` payloads
 * become typed, ordered, identity-preserving reply trees. Refresh
 * correctness follows from purity: the same payload always maps to the
 * same items, and the activity rebuilds the list from scratch.
 */
class ThreadCommentTreeTest {

    private fun nodeJson(id: Int, user: String, body: String, children: String = "[]"): String =
        """{"id":$id,"userId":${id + 100},"threadId":7,"comment":${Json.encodeToString(body)},"isLiked":false,"likeCount":2,"createdAt":1700000000,"user":{"id":${id + 100},"name":${Json.encodeToString(user)}} ,"childComments":$children}"""

    @Test
    fun `parent plus one child parses into renderable typed state`() {
        val parent = Json.decodeFromString<ThreadComment>(
            nodeJson(1, "amy", "hello", "[${nodeJson(2, "bob", "hi")}]")
        )
        val children = parent.typedChildren()
        assertEquals(1, children.size)
        assertEquals(2, children.first().id)
        assertEquals("hi", children.first().comment)
        assertEquals("bob", children.first().user?.name)
    }

    @Test
    fun `multiple children preserve served order with identity`() {
        val parent = Json.decodeFromString<ThreadComment>(
            nodeJson(1, "amy", "hello", "[${nodeJson(2, "b", "second")},${nodeJson(3, "c", "third")}]")
        )
        val children = parent.typedChildren()
        assertEquals(listOf(2, 3), children.map { it.id })
        assertEquals(listOf("second", "third"), children.map { it.comment })
        assertEquals(listOf(102, 103), children.map { it.userId })
    }

    @Test
    fun `nested grandchildren recurse`() {
        val grandchild = nodeJson(3, "c", "deep")
        val child = nodeJson(2, "b", "mid", "[$grandchild]")
        val parent = Json.decodeFromString<ThreadComment>(nodeJson(1, "a", "top", "[$child]"))
        val level1 = parent.typedChildren()
        assertEquals(1, level1.size)
        val level2 = level1.first().typedChildren()
        assertEquals(1, level2.size)
        assertEquals(3, level2.first().id)
        assertEquals("deep", level2.first().comment)
    }

    @Test
    fun `asThreadComment keeps reply identity for actions`() {
        val parent = Json.decodeFromString<ThreadComment>(
            nodeJson(1, "amy", "hello", "[${nodeJson(9, "zed", "reply")}]")
        )
        val converted = parent.typedChildren().first().asThreadComment()
        assertEquals(9, converted.id)
        assertEquals(7, converted.threadId)
        assertEquals("reply", converted.comment)
        assertEquals("zed", converted.user?.name)
    }

    @Test
    fun `remap is pure so refresh cannot duplicate`() {
        val payload = nodeJson(1, "amy", "hello", "[${nodeJson(2, "b", "x")}]")
        val first = Json.decodeFromString<ThreadComment>(payload).typedChildren()
        val second = Json.decodeFromString<ThreadComment>(payload).typedChildren()
        assertEquals(first.map { it.id }, second.map { it.id })
        assertEquals(1, first.size)
    }

    @Test
    fun `malformed children degrade to no children without failing parent`() {
        val parent = Json.decodeFromString<ThreadComment>(
            """{"id":1,"threadId":7,"comment":"hi","childComments":{"weird":true}}"""
        )
        assertTrue(parent.typedChildren().isEmpty())
        val scalar = Json.decodeFromString<ThreadComment>(
            """{"id":1,"threadId":7,"comment":"hi","childComments":"nope"}"""
        )
        assertTrue(scalar.typedChildren().isEmpty())
        val missing = Json.decodeFromString<ThreadComment>(
            """{"id":1,"threadId":7,"comment":"hi"}"""
        )
        assertTrue(missing.typedChildren().isEmpty())
    }

    @Test
    fun `wrapped edge payloads unwrap`() {
        val kids = buildJsonArray {
            add(buildJsonObject { put("id", 5); put("comment", "w") })
        }
        val wrapped = buildJsonObject { put("edges", kids) }
        assertEquals(5, parseThreadCommentNodes(wrapped).first().id)
    }
}
