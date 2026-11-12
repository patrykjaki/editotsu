package ani.dantotsu.connections.anilist

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PostOutcomeTest {

    private val mutations = AnilistMutations()

    private fun outcome(json: String?, lastError: String?) = mutations.postOutcome(
        result = json?.let { Json.parseToJsonElement(it).jsonObject },
        lastError = lastError,
        successText = "Success",
        fallbackError = "Could not post"
    )

    @Test
    fun `null result is a failure with the last error`() {
        val r = outcome(null, "Rate limited. Try after 30 seconds")
        assertFalse(r.ok)
        assertEquals("Rate limited. Try after 30 seconds", r.message)
    }

    @Test
    fun `null result without last error uses the fallback`() {
        val r = outcome(null, null)
        assertFalse(r.ok)
        assertEquals("Could not post", r.message)
    }

    @Test
    fun `errors in the payload are a failure`() {
        val r = outcome("""{"errors":[{"message":"Text too short"}]}""", null)
        assertFalse(r.ok)
        assertEquals("Text too short", r.message)
    }

    @Test
    fun `data without errors is a success`() {
        val r = outcome("""{"data":{"SaveTextActivity":{"siteUrl":"x"}}}""", "stale")
        assertTrue(r.ok)
        assertEquals("Success", r.message)
    }
}
