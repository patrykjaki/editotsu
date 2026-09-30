package ani.dantotsu.media.anime.player

/** Activity/main-thread ownership of an engine, independent of mutable selector state. */
class EpisodePlaybackSession {
    class Binding internal constructor(val episodeKey: String, val engine: PlaybackEngine)

    var binding: Binding? = null
        private set

    fun bind(episodeKey: String, engine: PlaybackEngine): Binding =
        Binding(episodeKey, engine).also { binding = it }

    fun invalidate() { binding = null }

    fun owns(candidate: Binding, selectedKey: String?): Boolean =
        binding === candidate && candidate.episodeKey == selectedKey

    fun resumePosition(episodeKey: String, savedPositionMs: Long): Long =
        binding?.takeIf { it.episodeKey == episodeKey }?.engine?.positionMs ?: savedPositionMs

    fun acceptsEngine(engine: PlaybackEngine?, selectedKey: String?): Boolean {
        val active = binding ?: return false
        return engine != null && active.engine === engine && owns(active, selectedKey)
    }

    fun savePosition(save: (episodeKey: String, positionMs: Long) -> Unit) {
        val active = binding ?: return
        save(active.episodeKey, active.engine.positionMs)
    }

    fun progress(selectedKey: String?): EpisodePlaybackProgress? {
        val active = binding ?: return null
        if (!owns(active, selectedKey)) return null
        return EpisodePlaybackProgress(
            active.episodeKey, active.engine.positionMs, active.engine.durationMs,
            active.engine.playbackState is PlaybackState.Ended
        )
    }

    fun listener(
        candidate: Binding,
        selectedKey: () -> String?,
        delegate: PlaybackListener
    ): PlaybackListener = object : PlaybackListener {
        private inline fun publish(action: () -> Unit) {
            if (owns(candidate, selectedKey())) action()
        }
        override fun onPlaybackStateChanged(state: PlaybackState) = publish { delegate.onPlaybackStateChanged(state) }
        override fun onPositionChanged(positionMs: Long, durationMs: Long) = publish { delegate.onPositionChanged(positionMs, durationMs) }
        override fun onPlaybackSpeedChanged(speed: Float) = publish { delegate.onPlaybackSpeedChanged(speed) }
        override fun onTracksChanged(tracks: List<PlayerTrack>) = publish { delegate.onTracksChanged(tracks) }
        override fun onVideoSizeChanged(width: Int, height: Int) = publish { delegate.onVideoSizeChanged(width, height) }
        override fun onIsPlayingChanged(isPlaying: Boolean) = publish { delegate.onIsPlayingChanged(isPlaying) }
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean) = publish { delegate.onPlayWhenReadyChanged(playWhenReady) }
        override fun onDurationKnown(generationId: Long, durationMs: Long) = publish { delegate.onDurationKnown(generationId, durationMs) }
        override fun onSnapshotChanged(snapshot: PlaybackSnapshot) = publish { delegate.onSnapshotChanged(snapshot) }
    }
}

data class EpisodePlaybackProgress(
    val episodeKey: String,
    val positionMs: Long,
    val durationMs: Long,
    val ended: Boolean
) {
    fun isComplete(watchPercentage: Float, episodeLength: Float = 0f, forceComplete: Boolean = false): Boolean {
        val duration = if (episodeLength > 0f) episodeLength else durationMs.toFloat()
        return forceComplete || ended || (duration > 0f && positionMs.toFloat() / duration > watchPercentage)
    }
}
