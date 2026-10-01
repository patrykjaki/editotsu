package ani.dantotsu.media.anime.player

import android.view.Surface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * MAX MERGE M4B DRM (HOLD2): the episode/engine binding contract is
 * engine-agnostic, so the Media3 DRM backend inherits it. A minimal fake
 * engine proves late callbacks from a retired binding cannot publish into
 * the newly selected episode — the exact cross-episode leak class the
 * post-M5 ownership model closed.
 */
class DrmEngineOwnershipTest {

    private class FakeEngine(var pos: Long = 0L) : PlaybackEngine {
        private val listeners = mutableListOf<PlaybackListener>()
        override val positionMs: Long get() = pos
        override val durationMs: Long get() = 1000L
        override val isPlaying: Boolean get() = false
        override val playWhenReady: Boolean get() = false
        override val playbackSpeed: Float get() = 1f
        override val playbackState: PlaybackState get() = PlaybackState.Ready
        override val currentAudioTrack: PlayerTrack? get() = null
        override val currentSubtitleTrack: PlayerTrack? get() = null
        override val availableTracks: List<PlayerTrack> get() = emptyList()
        override val videoDimensions: VideoDimensions? get() = null
        override val lastError: PlaybackError? get() = null
        override val currentRequest: PlaybackRequest? get() = null
        override fun play() {}
        override fun pause() {}
        override fun togglePlayPause() {}
        override fun seekTo(positionMs: Long) {}
        override fun seekRelative(offsetMs: Long) {}
        override fun setPlaybackSpeed(speed: Float) {}
        override fun setVideoSurface(surface: Surface?) {}
        override fun setVideoSurfaceSize(width: Int, height: Int) {}
        override fun selectAudioTrack(id: Int?) {}
        override fun selectSubtitleTrack(id: Int?) {}
        override fun addExternalSubtitle(uri: String, title: String?, language: String?, select: Boolean, explicitUserAction: Boolean) {}
        override fun applySubtitleStyle(style: SubtitleStyle) {}
        override fun setResizeMode(mode: ResizeMode) {}
        override fun loadMedia(request: PlaybackRequest) {}
        override fun stop() {}
        override fun release() {}
        override fun addListener(listener: PlaybackListener) {
            listeners.add(listener)
        }
        override fun removeListener(listener: PlaybackListener) {
            listeners.remove(listener)
        }

        fun emitPosition(positionMs: Long, durationMs: Long) {
            listeners.toList().forEach { it.onPositionChanged(positionMs, durationMs) }
        }
    }

    @Test
    fun `retired drm binding cannot publish into new episode`() {
        val session = EpisodePlaybackSession()
        val engineA = FakeEngine()
        val bindingA = session.bind("A", engineA)
        var selected: String? = "A"
        val seen = mutableListOf<Long>()
        val gated = session.listener(bindingA, { selected }, object : PlaybackListener {
            override fun onPositionChanged(positionMs: Long, durationMs: Long) {
                seen.add(positionMs)
            }
        })
        engineA.addListener(gated)

        // A publishes while selected.
        engineA.emitPosition(21 * 60 * 1000L, 24 * 60 * 1000L)
        assertEquals(listOf(21 * 60 * 1000L), seen)

        // Switch to B with a fresh binding; late A callbacks must not leak.
        val engineB = FakeEngine()
        session.bind("B", engineB)
        selected = "B"
        engineA.emitPosition(21 * 60 * 1000L + 5000L, 24 * 60 * 1000L)
        assertEquals(listOf(21 * 60 * 1000L), seen)

        // B publishes normally through its own binding.
        val bindingB = session.binding!!
        val seenB = mutableListOf<Long>()
        engineB.addListener(session.listener(bindingB, { selected }, object : PlaybackListener {
            override fun onPositionChanged(positionMs: Long, durationMs: Long) {
                seenB.add(positionMs)
            }
        }))
        engineB.emitPosition(4 * 60 * 1000L + 30 * 1000L, 24 * 60 * 1000L)
        assertEquals(listOf(4 * 60 * 1000L + 30 * 1000L), seenB)
    }

    @Test
    fun `progress seam uses binding key, not mutable selection`() {
        val session = EpisodePlaybackSession()
        val engineA = FakeEngine(pos = 999L)
        session.bind("A", engineA)
        var saved: Pair<String, Long>? = null
        session.savePosition { key, pos -> saved = key to pos }
        assertEquals("A" to 999L, saved)
        assertNull(session.progress("B"))
        assertEquals("A", session.progress("A")?.episodeKey)
    }
}
