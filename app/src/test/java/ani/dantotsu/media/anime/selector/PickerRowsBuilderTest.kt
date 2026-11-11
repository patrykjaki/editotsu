package ani.dantotsu.media.anime.selector

import ani.dantotsu.FileUrl
import ani.dantotsu.parsers.Video
import ani.dantotsu.parsers.VideoContainer
import ani.dantotsu.parsers.VideoExtractor
import ani.dantotsu.parsers.VideoServer
import ani.dantotsu.parsers.VideoType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.random.Random

class PickerRowsBuilderTest {

    private val rows: List<String> by lazy {
        val text = javaClass.classLoader!!.getResource("picker/torrentio_rows_2026-11-01.json")!!.readText()
        Json.parseToJsonElement(text).jsonArray.map { it.jsonPrimitive.content }
    }

    private class Ext(override val server: VideoServer, videoList: List<Video>) : VideoExtractor() {
        init { videos = videoList }
        override suspend fun extract(): VideoContainer = throw UnsupportedOperationException()
    }

    private fun ext(i: Int, name: String = rows[i % rows.size]): VideoExtractor {
        val magnet = "magnet:?xt=urn:btih:%040x".format(i + 1)
        return Ext(
            VideoServer(name, FileUrl(magnet), mapOf(StableCandidateBuilder.PROVIDER_EXTRA_KEY to "torrentio")),
            listOf(Video(null, VideoType.CONTAINER, FileUrl(magnet))),
        )
    }

    /** Same order rules as ExtractorAdapter.add(): replace by server name, else append. */
    private fun add(links: MutableList<VideoExtractor>, e: VideoExtractor) {
        val i = links.indexOfFirst { it.server.name == e.server.name }
        if (i >= 0) links[i] = e else links.add(e)
    }

    private fun fullList(b: PickerRowsBuilder, links: List<VideoExtractor>, stored: FamilyPayload?) =
        PickerListBuilder.build(b.build(links, null, stored, "Torrentio"), hasRememberedFamily = stored != null)

    @Test
    fun cached_list_equals_fresh_list_for_random_callback_orders() {
        val stored = FamilyPayload("torrentio", "toonshub", mapOf("resolution" to "1080p"))
        val random = Random(20261101)
        repeat(20) { seq ->
            val cached = PickerRowsBuilder()
            val links = mutableListOf<VideoExtractor>()
            repeat(25) {
                val i = random.nextInt(rows.size + 8)
                add(links, ext(i))
                val withCache = fullList(cached, links, stored)
                val fresh = fullList(PickerRowsBuilder(), links, stored)
                assertEquals("sequence $seq", fresh, withCache)
            }
        }
    }

    @Test
    fun unchanged_row_reuses_its_summary_and_changed_content_parses_again() {
        val b = PickerRowsBuilder()
        val e = ext(0)
        val video = e.videos[0]
        val first = b.viewOf(e, video)
        repeat(20) { org.junit.Assert.assertSame(first, b.viewOf(e, video)) }
        assertEquals(1, b.summarizeCalls)
        b.viewOf(ext(1), ext(1).videos[0])
        assertEquals(2, b.summarizeCalls)
        b.clear()
        b.viewOf(e, video)
        assertEquals(3, b.summarizeCalls)
    }

    @Test
    fun each_callback_parses_only_new_rows() {
        val counting = PickerRowsBuilder()
        val links = mutableListOf<VideoExtractor>()
        rows.indices.forEach { i ->
            add(links, ext(i))
            counting.build(links, null, null, "Torrentio")
            assertEquals("after callback $i", i + 1, counting.summarizeCalls)
        }
        // Moving rows (preferred inserted at the top) costs nothing.
        links.add(0, links.removeAt(links.size - 1))
        counting.build(links, null, null, "Torrentio")
        assertEquals(rows.size, counting.summarizeCalls)
    }

    @Test
    fun a_row_that_fails_to_parse_still_shows_with_its_raw_text() {
        val failing = PickerRowsBuilder(summarize = { e, v ->
            if (e.server.name.startsWith("BAD")) throw IllegalStateException("boom")
            PickerRowsBuilder.defaultSummarize(e, v)
        })
        val links = listOf(ext(1), ext(99, name = "BAD row\nsecond line"), ext(2))
        val built = failing.build(links, null, null, "Torrentio")
        assertEquals(3, built.size)
        assertEquals("BAD row", built[1].header)
        assertEquals("ToonsHub", built[0].header)
    }
}
