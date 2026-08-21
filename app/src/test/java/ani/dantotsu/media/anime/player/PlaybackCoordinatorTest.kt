package ani.dantotsu.media.anime.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PlaybackCoordinatorTest {

    private class FakeAudioFocusController : AudioFocusController {
        var focusResult: FocusResult = FocusResult.Granted
        var requestCount = 0
        var abandonCount = 0
        private var focusListener: AudioFocusChangeListener? = null

        override fun requestFocus(): FocusResult {
            requestCount++
            return focusResult
        }

        override fun abandonFocus() {
            abandonCount++
        }

        override fun setListener(listener: AudioFocusChangeListener?) {
            this.focusListener = listener
        }

        fun emitChange(change: FocusChangeType) {
            focusListener?.onAudioFocusChange(change)
        }
    }

    private class FakeEngine : PlaybackEngine {
        var playCalls = 0
        var pauseCalls = 0
        var isPlayingInternal = false

        override val positionMs: Long = 0L
        override val durationMs: Long = 0L
        override val isPlaying: Boolean get() = isPlayingInternal
        override val playWhenReady: Boolean = true
        override val playbackSpeed: Float = 1.0f
        override val playbackState: PlaybackState = PlaybackState.Idle
        override val currentAudioTrack: PlayerTrack? = null
        override val currentSubtitleTrack: PlayerTrack? = null
        override val availableTracks: List<PlayerTrack> = emptyList()
        override val videoDimensions: VideoDimensions? = null
        override val lastError: PlaybackError? = null
        override val currentRequest: PlaybackRequest? = null

        override fun play() {
            playCalls++
            isPlayingInternal = true
        }

        override fun pause() {
            pauseCalls++
            isPlayingInternal = false
        }

        override fun togglePlayPause() {}
        override fun seekTo(positionMs: Long) {}
        override fun seekRelative(offsetMs: Long) {}
        override fun setPlaybackSpeed(speed: Float) {}
        override fun setVideoSurface(surface: android.view.Surface?) {}
        override fun setVideoSurfaceSize(width: Int, height: Int) {}
        override fun selectAudioTrack(id: Int?) {}
        override fun selectSubtitleTrack(id: Int?) {}
        override fun addExternalSubtitle(uri: String, title: String?, language: String?, select: Boolean) {}
        override fun applySubtitleStyle(style: SubtitleStyle) {}
        override fun setResizeMode(mode: ResizeMode) {}
        override fun loadMedia(request: PlaybackRequest) {}
        override fun stop() {}
        override fun release() {}
        override fun addListener(listener: PlaybackListener) {}
        override fun removeListener(listener: PlaybackListener) {}
    }

    private lateinit var fakeFocus: FakeAudioFocusController
    private lateinit var fakeEngine: FakeEngine
    private lateinit var coordinator: PlaybackCoordinator

    @Before
    fun setup() {
        fakeFocus = FakeAudioFocusController()
        fakeEngine = FakeEngine()
        coordinator = PlaybackCoordinator(
            getEngine = { fakeEngine },
            audioFocusController = fakeFocus
        )
    }

    @Test
    fun testFocusGrantedStartsPlayback() {
        fakeFocus.focusResult = FocusResult.Granted
        coordinator.play()

        assertTrue(coordinator.userPlayIntent)
        assertEquals(PlaybackCoordinator.FocusState.GRANTED, coordinator.audioFocusState)
        assertEquals(1, fakeEngine.playCalls)
        assertTrue(coordinator.shouldPlayLocally)
    }

    @Test
    fun testFocusFailedDoesNotStartPlaybackAndCanRetry() {
        fakeFocus.focusResult = FocusResult.Failed
        coordinator.play()

        assertTrue(coordinator.userPlayIntent)
        assertEquals(PlaybackCoordinator.FocusState.FAILED, coordinator.audioFocusState)
        assertEquals(0, fakeEngine.playCalls)
        assertEquals(1, fakeEngine.pauseCalls)
        assertFalse(coordinator.shouldPlayLocally)

        // Retry on second user play
        fakeFocus.focusResult = FocusResult.Granted
        coordinator.play()

        assertEquals(2, fakeFocus.requestCount)
        assertEquals(PlaybackCoordinator.FocusState.GRANTED, coordinator.audioFocusState)
        assertEquals(1, fakeEngine.playCalls)
        assertTrue(coordinator.shouldPlayLocally)
    }

    @Test
    fun testFocusDelayedWaitsForGain() {
        fakeFocus.focusResult = FocusResult.Delayed
        coordinator.play()

        assertTrue(coordinator.userPlayIntent)
        assertEquals(PlaybackCoordinator.FocusState.DELAYED, coordinator.audioFocusState)
        assertEquals(0, fakeEngine.playCalls)
        assertFalse(coordinator.shouldPlayLocally)

        // Delayed gain callback received
        fakeFocus.emitChange(FocusChangeType.GAIN)

        assertEquals(PlaybackCoordinator.FocusState.GRANTED, coordinator.audioFocusState)
        assertEquals(1, fakeEngine.playCalls)
        assertTrue(coordinator.shouldPlayLocally)
    }

    @Test
    fun testTransientLossPausesAndResumesOnGain() {
        fakeFocus.focusResult = FocusResult.Granted
        coordinator.play()
        assertEquals(1, fakeEngine.playCalls)

        // Transient loss
        fakeFocus.emitChange(FocusChangeType.LOSS_TRANSIENT)

        assertEquals(PlaybackCoordinator.FocusState.TRANSIENT_LOSS, coordinator.audioFocusState)
        assertTrue(coordinator.userPlayIntent) // user still wants play
        assertEquals(1, fakeEngine.pauseCalls)
        assertFalse(coordinator.shouldPlayLocally)

        // Gain back
        fakeFocus.emitChange(FocusChangeType.GAIN)

        assertEquals(PlaybackCoordinator.FocusState.GRANTED, coordinator.audioFocusState)
        assertEquals(2, fakeEngine.playCalls)
        assertTrue(coordinator.shouldPlayLocally)
    }

    @Test
    fun testManualPauseDuringTransientLossPreventsAutoResume() {
        fakeFocus.focusResult = FocusResult.Granted
        coordinator.play()

        // Transient loss
        fakeFocus.emitChange(FocusChangeType.LOSS_TRANSIENT)
        assertEquals(PlaybackCoordinator.FocusState.TRANSIENT_LOSS, coordinator.audioFocusState)

        // User explicitly taps Pause during phone call / navigation announcement
        coordinator.pause()
        assertFalse(coordinator.userPlayIntent)

        // Focus restored
        fakeFocus.emitChange(FocusChangeType.GAIN)

        assertEquals(PlaybackCoordinator.FocusState.GRANTED, coordinator.audioFocusState)
        assertEquals(1, fakeEngine.playCalls) // Play was NOT called again
        assertFalse(coordinator.shouldPlayLocally)
    }

    @Test
    fun testBecomingNoisyPausesAndExplicitPlayResumes() {
        fakeFocus.focusResult = FocusResult.Granted
        coordinator.play()

        coordinator.onBecomingNoisy()

        assertTrue(coordinator.isBecomingNoisySuppressed)
        assertEquals(1, fakeEngine.pauseCalls)
        assertFalse(coordinator.shouldPlayLocally)

        // User taps play again
        coordinator.play()

        assertFalse(coordinator.isBecomingNoisySuppressed)
        assertEquals(2, fakeEngine.playCalls)
        assertTrue(coordinator.shouldPlayLocally)
    }

    @Test
    fun testRemoteCastTargetDoesNotHoldLocalFocus() {
        fakeFocus.focusResult = FocusResult.Granted
        coordinator.play()
        assertEquals(1, fakeFocus.requestCount)

        // Switch to Remote Cast
        coordinator.setPlaybackTarget(PlaybackTarget.REMOTE_CAST)

        assertEquals(1, fakeFocus.abandonCount)
        assertEquals(PlaybackCoordinator.FocusState.NONE, coordinator.audioFocusState)

        // Switch back to Local
        coordinator.setPlaybackTarget(PlaybackTarget.LOCAL)

        assertEquals(2, fakeFocus.requestCount)
        assertEquals(PlaybackCoordinator.FocusState.GRANTED, coordinator.audioFocusState)
    }

    @Test
    fun testReleaseAbandonsFocus() {
        fakeFocus.focusResult = FocusResult.Granted
        coordinator.play()

        coordinator.release()

        assertEquals(1, fakeFocus.abandonCount)
        assertFalse(coordinator.userPlayIntent)
    }
}
