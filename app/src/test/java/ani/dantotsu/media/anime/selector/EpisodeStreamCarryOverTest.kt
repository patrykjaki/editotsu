package ani.dantotsu.media.anime.selector

import ani.dantotsu.FileUrl
import ani.dantotsu.media.anime.Episode
import ani.dantotsu.parsers.VideoContainer
import ani.dantotsu.parsers.VideoExtractor
import ani.dantotsu.parsers.VideoServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class EpisodeStreamCarryOverTest {

    private class FakeExtractor(name: String) : VideoExtractor() {
        override val server = VideoServer(name, FileUrl("https://example.invalid/$name"))
        override suspend fun extract(): VideoContainer = throw UnsupportedOperationException()
    }

    @Test
    fun freshEpisodeTakesLoadedStreamsAndSelection() {
        val cached = Episode("1").apply {
            extractors = mutableListOf(FakeExtractor("a"), FakeExtractor("b"))
            allStreams = true
            selectedExtractor = "b"
            selectedVideo = 2
        }
        val fresh = Episode("1")

        EpisodeStreamCarryOver.carry(cached, fresh)

        assertSame(cached.extractors, fresh.extractors)
        assertTrue(fresh.allStreams)
        assertEquals("b", fresh.selectedExtractor)
        assertEquals(2, fresh.selectedVideo)
    }

    @Test
    fun selectionAlreadyOnTargetIsKept() {
        // a row tap writes the selection onto the episode in the current list,
        // while the videos were loaded into the cached one
        val cached = Episode("1").apply {
            extractors = mutableListOf(FakeExtractor("a"), FakeExtractor("b"))
            selectedExtractor = "a"
            selectedVideo = 0
        }
        val fresh = Episode("1").apply {
            selectedExtractor = "b"
            selectedVideo = 1
        }

        EpisodeStreamCarryOver.carry(cached, fresh)

        assertEquals(2, fresh.extractors!!.size)
        assertEquals("b", fresh.selectedExtractor)
        assertEquals(1, fresh.selectedVideo)
    }

    @Test
    fun extractorsAlreadyOnTargetAreKept() {
        val own = mutableListOf<VideoExtractor>(FakeExtractor("c"))
        val cached = Episode("1").apply { extractors = mutableListOf(FakeExtractor("a")); allStreams = true }
        val fresh = Episode("1").apply { extractors = own; allStreams = false }

        EpisodeStreamCarryOver.carry(cached, fresh)

        assertSame(own, fresh.extractors)
        assertFalse(fresh.allStreams)
    }

    @Test
    fun emptyCachedEpisodeChangesNothing() {
        val fresh = Episode("1")
        EpisodeStreamCarryOver.carry(Episode("1"), fresh)
        assertNull(fresh.extractors)
        assertNull(fresh.selectedExtractor)
        assertFalse(fresh.allStreams)
    }

    @Test
    fun carryAllMatchesByKey() {
        val cached1 = Episode("1").apply { extractors = mutableListOf(FakeExtractor("a")); selectedExtractor = "a" }
        val cached2 = Episode("2")
        val old = mapOf("1" to cached1, "2" to cached2)
        val fresh1 = Episode("1")
        val fresh3 = Episode("3")
        val fresh = mapOf("1" to fresh1, "3" to fresh3)

        EpisodeStreamCarryOver.carryAll(old, fresh)

        assertSame(cached1.extractors, fresh1.extractors)
        assertEquals("a", fresh1.selectedExtractor)
        assertNull(fresh3.extractors)
    }

    @Test
    fun carryAllWithoutOldListIsNoOp() {
        val fresh1 = Episode("1")
        EpisodeStreamCarryOver.carryAll(null, mapOf("1" to fresh1))
        assertNull(fresh1.extractors)
    }
}
