package ani.dantotsu.connections.anilist.api

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * MAX MERGE M2 correction (HOLD2 Blocker 2): the PRODUCTION flatten
 * helper (the exact function ThreadViewActivity uses) must recurse
 * through every child level — grandchildren included — with served
 * order and per-level depths.
 */
class FlattenThreadCommentTreeTest {

    private fun nodeJson(id: Int, body: String, children: String = "[]"): String =
        """{"id":$id,"userId":${id + 100},"threadId":7,"comment":${Json.encodeToString(body)},"user":{"id":${id + 100},"name":"u$id"},"childComments":$children}"""

    @Test
    fun `production flatten keeps grandchildren with depths`() {
        val grandchild = nodeJson(3, "G1")
        val c1 = nodeJson(2, "C1", "[$grandchild]")
        val c2 = nodeJson(4, "C2")
        val parent = Json.decodeFromString<ThreadComment>(nodeJson(1, "P", "[$c1,$c2]"))

        val flat = flattenThreadCommentTree(listOf(parent))

        assertEquals(listOf(1, 2, 3, 4), flat.map { it.comment.id })
        assertEquals(listOf(0, 1, 2, 1), flat.map { it.depth })
    }

    @Test
    fun `sibling order preserved across levels`() {
        val a = nodeJson(2, "a")
        val b = nodeJson(3, "b")
        val parent = Json.decodeFromString<ThreadComment>(nodeJson(1, "P", "[$a,$b]"))

        val flat = flattenThreadCommentTree(listOf(parent))

        assertEquals(listOf(1, 2, 3), flat.map { it.comment.id })
        assertEquals(listOf(0, 1, 1), flat.map { it.depth })
    }

    @Test
    fun `child identity survives for reply actions`() {
        val child = nodeJson(9, "reply")
        val parent = Json.decodeFromString<ThreadComment>(nodeJson(1, "P", "[$child]"))

        val flat = flattenThreadCommentTree(listOf(parent))

        assertEquals(9, flat[1].comment.id)
        assertEquals(7, flat[1].comment.threadId)
        assertEquals("reply", flat[1].comment.comment)
    }

    @Test
    fun `multiple top-level trees flatten independently`() {
        val p1 = Json.decodeFromString<ThreadComment>(nodeJson(1, "P1", "[${nodeJson(2, "C")}]"))
        val p2 = Json.decodeFromString<ThreadComment>(nodeJson(5, "P2"))

        val flat = flattenThreadCommentTree(listOf(p1, p2))

        assertEquals(listOf(1, 2, 5), flat.map { it.comment.id })
        assertEquals(listOf(0, 1, 0), flat.map { it.depth })
    }
}
