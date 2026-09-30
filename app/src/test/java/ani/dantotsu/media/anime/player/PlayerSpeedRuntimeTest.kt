package ani.dantotsu.media.anime.player

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.util.ArrayDeque

/** Uses the real MPV engine with queued engine/main dispatch and a fake native client. */
@RunWith(Parameterized::class)
class PlayerSpeedRuntimeTest(private val speed: Float) {
    @Test fun normalSelectorIndexResolvesToActualSpeed() {
        val fixture = SpeedFixture()
        try {
            val speeds = PlaybackSpeedSelection.values(speed >= 2.5f)
            val selectedIndex = speeds.indexOf(speed)
            assertTrue(selectedIndex >= 0)
            val requested = speeds[selectedIndex]
            fixture.engine.setPlaybackSpeed(requested)
            fixture.acknowledgeSpeed()
            assertEquals(speed.toDouble(), fixture.client.doubleProperties["speed"]!!, 0.0)
            assertEquals(speed, fixture.engine.playbackSpeed, 0f)
            assertEquals(speed, "${requested}x".removeSuffix("x").toFloat(), 0f)
        } finally { fixture.close() }
    }

    @Test fun temporaryHoldLabelFollowsAcknowledgedSpeedDespiteAsyncCommand() {
        val fixture = SpeedFixture()
        var displayed = 0f
        val hold = TemporaryPlaybackSpeed({ fixture.engine }) { displayed = it }
        try {
            hold.start(speed)
            // The command is queued: the current state still describes actual 1x.
            assertEquals(1f, displayed, 0f)
            assertEquals(1f, fixture.engine.playbackSpeed, 0f)
            fixture.acknowledgeSpeed()
            assertEquals(speed, displayed, 0f)
            assertEquals(fixture.engine.playbackSpeed, PlayerDisplayFormat.speed(displayed).removeSuffix("x").toFloat(), 0f)
            hold.stop()
            fixture.acknowledgeSpeed()
            assertEquals(1f, fixture.engine.playbackSpeed, 0f)
        } finally { fixture.close() }
    }

    @Test fun temporaryDragLabelUpdatesAfterEveryAcknowledgement() {
        val fixture = SpeedFixture()
        var displayed = 0f
        val hold = TemporaryPlaybackSpeed({ fixture.engine }) { displayed = it }
        try {
            hold.start(2f)
            fixture.acknowledgeSpeed()
            assertEquals(2f, displayed, 0f)
            hold.update(speed)
            fixture.acknowledgeSpeed()
            assertEquals(speed, displayed, 0f)
            assertEquals(speed, fixture.engine.playbackSpeed, 0f)
            hold.stop()
        } finally { fixture.close() }
    }

    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}x")
        fun speeds() = listOf(0.5f, 0.75f, 1f, 1.5f, 2f, 2.5f, 3f).map { arrayOf(it) }
    }
}

internal class QueuedRuntimeDispatcher : EngineDispatcher {
    private val pending = ArrayDeque<() -> Unit>()
    override fun post(action: () -> Unit) { pending.add(action) }
    override fun quit() {}
    fun drain() { while (pending.isNotEmpty()) pending.removeFirst().invoke() }
}

internal class SpeedFixture {
    val client = FakeMpvClient()
    val dispatcher = QueuedRuntimeDispatcher()
    val main = ArrayDeque<Runnable>()
    val engine = MpvPlaybackEngine(null, client, dispatcher) { main.add(it) }
    init {
        engine.loadMedia(PlaybackRequest(uri = "https://example.invalid/speed.mp4"))
        dispatcher.drain()
        client.emitEvent(MpvPlaybackEngine.MPV_EVENT_START_FILE, playlistEntryId = 1L)
        dispatcher.drain()
        drainMain()
    }
    fun acknowledgeSpeed() {
        dispatcher.drain()
        client.emitProperty("speed", client.doubleProperties["speed"] ?: 1.0)
        dispatcher.drain()
        drainMain()
    }
    fun drainMain() { while (main.isNotEmpty()) main.removeFirst().run() }
    fun close() { engine.release(); dispatcher.drain(); drainMain() }
}
