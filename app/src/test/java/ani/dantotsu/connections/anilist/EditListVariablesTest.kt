package ani.dantotsu.connections.anilist

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class EditListVariablesTest {

    private fun roundTrip(notes: String?, lists: List<String>?) =
        Json.parseToJsonElement(
            editListVariables(mediaID = 21, progress = 3, notes = notes, status = "CURRENT", customList = lists).toString()
        ).jsonObject

    @Test
    fun `notes with quotes, backslashes and newlines stay valid json`() {
        val notes = "he said \"hi\"\\ok\nline 2"
        val obj = roundTrip(notes, null)
        assertEquals(notes, obj["notes"]!!.jsonPrimitive.content)
        assertEquals(21, obj["mediaID"]!!.jsonPrimitive.int)
        assertEquals(3, obj["progress"]!!.jsonPrimitive.int)
    }

    @Test
    fun `custom list names with quotes survive`() {
        val obj = roundTrip(null, listOf("My \"best\"", "a\\b"))
        val names = obj["customLists"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("My \"best\"", "a\\b"), names)
    }

    @Test
    fun `unset fields are left out`() {
        val obj = editListVariables(mediaID = 1)
        assertEquals(setOf("mediaID"), obj.keys)
        assertFalse(obj.containsKey("notes"))
    }

    @Test
    fun `score goes to scoreRaw`() {
        val obj = editListVariables(mediaID = 1, score = 85)
        assertEquals(85, obj["scoreRaw"]!!.jsonPrimitive.int)
    }
}
