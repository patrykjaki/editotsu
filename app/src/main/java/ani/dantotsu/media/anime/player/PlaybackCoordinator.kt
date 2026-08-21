package ani.dantotsu.media.anime.player

enum class PlaybackTarget {
    LOCAL,
    REMOTE_CAST
}

class PlaybackCoordinator(
    private val getEngine: () -> PlaybackEngine?,
    private val audioFocusController: AudioFocusController
) {

    var userPlayIntent: Boolean = true
        private set

    var audioFocusState: FocusState = FocusState.NONE
        private set

    var isBecomingNoisySuppressed: Boolean = false
        private set

    var isLifecycleSuppressed: Boolean = false
        private set

    var playbackTarget: PlaybackTarget = PlaybackTarget.LOCAL
        private set

    enum class FocusState {
        NONE,
        GRANTED,
        DELAYED,
        FAILED,
        TRANSIENT_LOSS,
        TRANSIENT_CAN_DUCK
    }

    init {
        audioFocusController.setListener { change ->
            handleAudioFocusChange(change)
        }
    }

    val shouldPlayLocally: Boolean
        get() = playbackTarget == PlaybackTarget.LOCAL &&
                userPlayIntent &&
                audioFocusState == FocusState.GRANTED &&
                !isBecomingNoisySuppressed &&
                !isLifecycleSuppressed

    fun play() {
        userPlayIntent = true
        isBecomingNoisySuppressed = false

        if (playbackTarget == PlaybackTarget.REMOTE_CAST) {
            // Handled by remote cast player
            return
        }

        val result = audioFocusController.requestFocus()
        when (result) {
            is FocusResult.Granted -> {
                audioFocusState = FocusState.GRANTED
                getEngine()?.play()
            }
            is FocusResult.Delayed -> {
                audioFocusState = FocusState.DELAYED
                getEngine()?.pause()
            }
            is FocusResult.Failed -> {
                audioFocusState = FocusState.FAILED
                getEngine()?.pause()
            }
        }
    }

    fun pause() {
        userPlayIntent = false
        if (playbackTarget == PlaybackTarget.LOCAL) {
            getEngine()?.pause()
        }
    }

    fun togglePlayPause() {
        if (userPlayIntent) {
            pause()
        } else {
            play()
        }
    }

    private fun handleAudioFocusChange(change: FocusChangeType) {
        if (playbackTarget == PlaybackTarget.REMOTE_CAST) return

        when (change) {
            FocusChangeType.GAIN -> {
                audioFocusState = FocusState.GRANTED
                if (userPlayIntent && !isBecomingNoisySuppressed && !isLifecycleSuppressed) {
                    getEngine()?.play()
                }
            }
            FocusChangeType.LOSS_TRANSIENT,
            FocusChangeType.LOSS_TRANSIENT_CAN_DUCK -> {
                audioFocusState = FocusState.TRANSIENT_LOSS
                getEngine()?.pause()
            }
            FocusChangeType.LOSS -> {
                audioFocusState = FocusState.NONE
                userPlayIntent = false
                getEngine()?.pause()
                audioFocusController.abandonFocus()
            }
        }
    }

    fun onBecomingNoisy() {
        isBecomingNoisySuppressed = true
        if (playbackTarget == PlaybackTarget.LOCAL) {
            getEngine()?.pause()
        }
    }

    fun setLifecycleSuppressed(suppressed: Boolean) {
        isLifecycleSuppressed = suppressed
        if (playbackTarget == PlaybackTarget.LOCAL) {
            if (suppressed) {
                getEngine()?.pause()
            } else if (userPlayIntent && audioFocusState == FocusState.GRANTED && !isBecomingNoisySuppressed) {
                getEngine()?.play()
            }
        }
    }

    fun setPlaybackTarget(target: PlaybackTarget) {
        if (playbackTarget == target) return
        playbackTarget = target

        if (target == PlaybackTarget.REMOTE_CAST) {
            getEngine()?.pause()
            audioFocusController.abandonFocus()
            audioFocusState = FocusState.NONE
        } else {
            if (userPlayIntent) {
                play()
            }
        }
    }

    fun release() {
        audioFocusController.abandonFocus()
        audioFocusController.setListener(null)
        audioFocusState = FocusState.NONE
        userPlayIntent = false
    }
}
