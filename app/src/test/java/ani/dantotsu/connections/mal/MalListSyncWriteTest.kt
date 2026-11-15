package ani.dantotsu.connections.mal

import ani.dantotsu.connections.mal.MalListSync.Change
import ani.dantotsu.connections.mal.MalListSync.Entry
import ani.dantotsu.connections.mal.MalListSync.Side
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** What a sync from AniList actually sends to MyAnimeList, captured at the HTTP seam. */
class MalListSyncWriteTest {

    @After
    fun tearDown() {
        MALQueries.testHttpHandler = null
        MAL.token = null
    }

    private fun ok() = com.lagradost.nicehttp.NiceResponse(
        okhttp3.Response.Builder()
            .request(okhttp3.Request.Builder().url("https://api.myanimelist.net/v2").build())
            .protocol(okhttp3.Protocol.HTTP_1_1).code(200).message("OK")
            .body("{}".toResponseBody("application/json".toMediaTypeOrNull()))
            .build(),
        ani.dantotsu.Mapper,
    )

    private fun send(main: Entry, target: Entry?): Map<String, String> = runBlocking {
        var sent: Map<String, String>? = null
        MAL.token = "t"
        MALQueries.testHttpHandler = { _, _, method, data ->
            if (method == "PUT") sent = data
            ok()
        }
        val change = Change(true, main, target, target?.let { MalListSync.diff(main, it) }.orEmpty(), MalListSync.wantedProgress(main, target))
        assertTrue(MalListSync.write(change, Side.ANILIST))
        sent!!
    }

    @Test
    fun `a score set on AniList reaches MyAnimeList`() {
        val main = Entry(idMal = 1, title = "Show", status = "COMPLETED", progress = 12, score = 8)
        assertEquals("8", send(main, main.copy(score = 0))["score"])
    }

    @Test
    fun `a new title carries its score`() {
        val main = Entry(idMal = 1, title = "Show", status = "COMPLETED", progress = 12, score = 7)
        assertEquals("7", send(main, null)["score"])
    }
}
