package ani.dantotsu.connections.anilist

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * MAX MERGE M2 correction: forum search text is interpolated into a
 * GraphQL string literal and must be escaped.
 */
class GraphqlEscapeTest {

    @Test
    fun `quoted search stays one valid literal`() {
        assertEquals(
            "anime \\\"movie\\\"",
            escapeGraphqlString("anime \"movie\"")
        )
    }

    @Test
    fun `backslash and newline are escaped in order`() {
        assertEquals(
            "a\\\\b\\\"c\\nd",
            escapeGraphqlString("a\\b\"c\nd")
        )
    }

    @Test
    fun `plain search passes through`() {
        assertEquals("naruto", escapeGraphqlString("naruto"))
    }
}
