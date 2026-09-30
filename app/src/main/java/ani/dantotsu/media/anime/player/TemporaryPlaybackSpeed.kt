package ani.dantotsu.media.anime.player

/** Hold speed is rendered from acknowledged engine state, never a pre-command snapshot. */
class TemporaryPlaybackSpeed(
    private val getEngine: () -> PlaybackEngine?,
    private val displaySpeed: (Float) -> Unit
) {
    private var owner: PlaybackEngine? = null
    private var listener: PlaybackListener? = null
    private var originalSpeed = 1f

    fun start(speed: Float) {
        stop()
        val engine = getEngine() ?: return
        owner = engine
        originalSpeed = engine.playbackSpeed
        val observer = object : PlaybackListener {
            override fun onPlaybackSpeedChanged(speed: Float) {
                if (owner === engine && getEngine() === engine) displaySpeed(engine.playbackSpeed)
            }
        }
        listener = observer
        engine.addListener(observer)
        displaySpeed(engine.playbackSpeed)
        engine.setPlaybackSpeed(speed)
    }

    fun update(speed: Float) {
        owner?.takeIf { it === getEngine() }?.setPlaybackSpeed(speed)
    }

    fun stop() {
        val engine = owner ?: return
        owner = null
        listener?.let { engine.removeListener(it) }
        listener = null
        // A gesture ending after an episode switch must never restore A's speed on B.
        if (engine === getEngine()) engine.setPlaybackSpeed(originalSpeed)
    }
}
