package ani.dantotsu.connections.anilist

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class DeleteCustomListVariablesTest {
    @Test
    fun `quotes and backslashes in the list name round-trip`() {
        val name = "My \"best\" list \\ 2"
        val json = deleteCustomListVariables(name, "ANIME").toString()
        val parsed = Json.parseToJsonElement(json).jsonObject
        assertEquals(name, parsed["name"]!!.jsonPrimitive.content)
        assertEquals("ANIME", parsed["type"]!!.jsonPrimitive.content)
    }
}
