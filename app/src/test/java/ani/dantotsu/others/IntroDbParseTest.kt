package ani.dantotsu.others

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

/**
 * MAX MERGE M5 correction (Blockers 3+4): TheIntroDB null-bound contract,
 * duration matching, URL seams, and cancellation propagation through the
 * provider chain.
 */
class IntroDbParseTest {

    @Test
    fun `intro with null start begins at zero`() {
        val root = Json.parseToJsonElement("{\"intro\":{\"start_sec\":null,\"end_sec\":90.0}}").jsonObject
        val out = IntroDbService.parseIntroDbAppSegments(root, "tt1", 1, 1_400_000L)
        assertEquals(1, out.size)
        assertEquals(0.0, out.first().interval.startTime, 0.001)
        assertEquals(90.0, out.first().interval.endTime, 0.001)
    }

    @Test
    fun `recap with null start begins at zero`() {
        val root = Json.parseToJsonElement("{\"recap\":{\"start_sec\":null,\"end_sec\":60.0}}").jsonObject
        val out = IntroDbService.parseIntroDbAppSegments(root, "tt1", 1, null)
        assertEquals(1, out.size)
        assertEquals("recap", out.first().skipType)
        assertEquals(0.0, out.first().interval.startTime, 0.001)
    }

    @Test
    fun `credits with null end use known duration`() {
        val root = Json.parseToJsonElement("{\"credits\":[{\"start_ms\":1200000,\"end_ms\":null}]}").jsonObject
        val out = IntroDbService.parseTheIntroDbSegments(root, 5, 2, 1_400_000L)
        assertEquals(1, out.size)
        assertEquals("ed", out.first().skipType)
        assertEquals(1400.0, out.first().interval.endTime, 0.001)
    }

    @Test
    fun `credits with null end and unknown duration are omitted`() {
        val root = Json.parseToJsonElement("{\"credits\":[{\"start_ms\":1200000,\"end_ms\":null}]}").jsonObject
        assertTrue(IntroDbService.parseTheIntroDbSegments(root, 5, 2, null).isEmpty())
        assertTrue(IntroDbService.parseTheIntroDbSegments(root, 5, 2, 0L).isEmpty())
    }

    @Test
    fun `bounded credits preserved with ms conversion`() {
        val root = Json.parseToJsonElement("{\"credits\":[{\"start_ms\":1000,\"end_ms\":90000}]}").jsonObject
        val out = IntroDbService.parseTheIntroDbSegments(root, 5, 2, null)
        assertEquals(1, out.size)
        assertEquals(1.0, out.first().interval.startTime, 0.001)
        assertEquals(90.0, out.first().interval.endTime, 0.001)
    }

    @Test
    fun `request carries mapped season episode and duration`() {
        val url = IntroDbService.theIntroDbUrl(42, "2", "1", 1_400_000L)
        assertTrue(url.contains("tmdb_id=42"))
        assertTrue(url.contains("season=2"))
        assertTrue(url.contains("episode=1"))
        assertTrue(url.contains("duration_ms=1400000"))
        val noDur = IntroDbService.theIntroDbUrl(42, "1", "13", null)
        assertTrue(noDur.contains("episode=13"))
        assertFalse(noDur.contains("duration_ms"))
        val app = IntroDbService.introDbAppUrl("tt123", "2", "1")
        assertTrue(app.contains("season=2") && app.contains("episode=1"))
    }
}

/**
 * Blocker 4: cancellation escapes, ordinary failures fall through, and
 * the live provider is duration-gated.
 */
class SkipFallbackChainTest {

    private fun stamp(type: String) = AniSkip.Stamp(
        interval = AniSkip.AniSkipInterval(0.0, 90.0),
        skipType = type,
        skipId = "t",
        episodeLength = 0.0
    )

    @Test
    fun `first non-empty provider wins in order`() = runBlocking {
        var second = false
        val out = firstNonEmptyProvider(
            { listOf(stamp("op")) },
            { second = true; listOf(stamp("ed")) }
        )
        assertEquals(listOf("op"), out?.map { it.skipType })
        assertFalse(second)
    }

    @Test
    fun `ordinary miss falls through to next provider`() = runBlocking {
        val out = firstNonEmptyProvider(
            { null },
            { emptyList() },
            { listOf(stamp("ed")) }
        )
        assertEquals(listOf("ed"), out?.map { it.skipType })
    }

    @Test
    fun `cancelled live provider aborts chain without calling next`() = runBlocking {
        var nextCalled = false
        try {
            firstNonEmptyProvider(
                { throw CancellationException("gone") },
                { nextCalled = true; listOf(stamp("ed")) }
            )
            fail("cancellation must escape")
        } catch (_: CancellationException) {
        }
        assertFalse(nextCalled)
    }

    @Test
    fun `ordinary provider failure propagates so providers own conversion`() = runBlocking {
        // Providers convert their own ordinary failures to null
        // (AniSkip/CSV/IntroDB all catch Exception internally); the chain
        // stays strict so unexpected errors stay visible.
        try {
            firstNonEmptyProvider(
                { throw IOException("down") },
                { listOf(stamp("recap")) }
            )
            fail("must propagate")
        } catch (e: IOException) {
            assertEquals("down", e.message)
        }
    }

    @Test
    fun `suspendNullOnError rethrows cancellation but nulls ordinary errors`() = runBlocking {
        try {
            suspendNullOnError<Int> { throw CancellationException("x") }
            fail("must rethrow")
        } catch (_: CancellationException) {
        }
        assertNull(suspendNullOnError<Int> { throw IOException("y") })
        assertEquals(3, suspendNullOnError { 3 })
    }

    @Test
    fun `live skip requires known duration`() {
        assertTrue(shouldQueryLiveSkip(10, 1400L))
        assertFalse(shouldQueryLiveSkip(10, 0L))
        assertFalse(shouldQueryLiveSkip(10, null))
        assertFalse(shouldQueryLiveSkip(null, 1400L))
    }
}
