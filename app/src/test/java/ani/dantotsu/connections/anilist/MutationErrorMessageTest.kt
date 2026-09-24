package ani.dantotsu.connections.anilist

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Upstream e70fdc56 subset: mutation error payloads must surface
 * field-level validation messages instead of raw JSON.
 */
class MutationErrorMessageTest {

    private val mutations = AnilistMutations()

    private fun errors(json: String) =
        with(mutations) {
            Json.parseToJsonElement(json).jsonObject.extractErrorMessage()
        }

    @Test
    fun `plain message is extracted`() {
        assertEquals(
            "Invalid token",
            errors("""{"errors":[{"message":"Invalid token"}]}""")
        )
    }

    @Test
    fun `validation messages are joined`() {
        assertEquals(
            "Title is required\nBody is too short",
            errors("""{"errors":[{"message":"Validation failed","validation":{"title":["Title is required"],"body":["Body is too short"]}}]}""")
        )
    }

    @Test
    fun `message fallback when validation is empty`() {
        assertEquals(
            "Something went wrong",
            errors("""{"errors":[{"message":"Something went wrong","validation":{}}]}""")
        )
    }

    @Test
    fun `missing errors returns null`() {
        assertNull(errors("""{"data":{"x":1}}"""))
    }

    @Test
    fun `multiple errors are joined`() {
        assertEquals(
            "First\nSecond",
            errors("""{"errors":[{"message":"First"},{"message":"Second"}]}""")
        )
    }
}
