package ani.dantotsu.media.anime.player

import org.junit.Assert.*
import org.junit.Test

class EpisodePlaybackSessionTest {
    private class Lane {
        val session = EpisodePlaybackSession()
        val engines = mutableListOf<SpeedFixture>()
        val saved = mutableMapOf<String, Long>()
        var selected: String? = null
        var lastPosition = 0L
        var lastDuration = 0L
        val completed = mutableListOf<String>()
        val requests = mutableListOf<PlaybackRequest>()

        fun start(key: String): SpeedFixture {
            val resume = session.resumePosition(key, saved[key] ?: 0L)
            val fixture = SpeedFixture().also { engines.add(it) }
            selected = key
            val binding = session.bind(key, fixture.engine)
            fixture.engine.addListener(session.listener(binding, { selected }, object : PlaybackListener {
                override fun onPositionChanged(positionMs: Long, durationMs: Long) {
                    lastPosition = positionMs
                    lastDuration = durationMs
                }
                override fun onDurationKnown(generationId: Long, durationMs: Long) {
                    lastDuration = durationMs
                }
                override fun onPlaybackStateChanged(state: PlaybackState) {
                    if (state is PlaybackState.Ended) publishCompletion()
                }
            }))
            val request = PlaybackRequest(uri = "https://example.invalid/$key.mp4", startPositionMs = resume, episodeNumber = key)
            requests.add(request)
            fixture.engine.loadMedia(request)
            fixture.dispatcher.drain()
            fixture.client.emitEvent(MpvPlaybackEngine.MPV_EVENT_START_FILE, playlistEntryId = 2L)
            fixture.dispatcher.drain()
            fixture.drainMain()
            return fixture
        }
        fun navigate(key: String): SpeedFixture {
            session.savePosition { key, position -> saved[key] = position }
            session.invalidate()
            return start(key)
        }
        fun position(fixture: SpeedFixture, seconds: Double, duration: Double = 1440.0, deliver: Boolean = true) {
            fixture.client.emitProperty("duration", duration)
            fixture.client.emitProperty("time-pos", seconds)
            fixture.dispatcher.drain()
            if (deliver) fixture.drainMain()
        }
        fun publishCompletion(force: Boolean = false) {
            val progress = session.progress(selected) ?: return
            if (progress.isComplete(0.8f, forceComplete = force)) completed.add(progress.episodeKey)
        }
        fun close() { engines.forEach { it.close() } }
    }

    @Test fun newEpisodeWithoutSavedProgressStartsAtBeginning() {
        val lane = Lane()
        try {
            val a = lane.start("A"); lane.position(a, 1260.0)
            lane.navigate("B")
            assertEquals(0L, lane.requests.last().startPositionMs)
            assertEquals(1260000L, lane.saved["A"])
            assertNull(lane.saved["B"])
        } finally { lane.close() }
    }

    @Test fun newEpisodeKeepsItsOwnFourMinuteThirtySecondResume() {
        val lane = Lane()
        try {
            lane.saved["B"] = 270000L
            val a = lane.start("A"); lane.position(a, 1260.0)
            val b = lane.navigate("B")
            assertEquals(270000L, b.engine.currentRequest!!.startPositionMs)
            val load = b.client.executedCommands.last { it.first() == "loadfile" }
            assertTrue(load.last().contains("start=270"))
            assertEquals(270000L, lane.saved["B"])
        } finally { lane.close() }
    }

    @Test fun queuedAProgressAndDurationCannotPublishIntoB() {
        val lane = Lane()
        try {
            val a = lane.start("A"); lane.position(a, 1260.0, deliver = false)
            lane.navigate("B")
            lane.lastPosition = 0L; lane.lastDuration = 0L
            a.drainMain()
            assertEquals(0L, lane.lastPosition)
            assertEquals(0L, lane.lastDuration)
            assertEquals("B", lane.session.progress("B")!!.episodeKey)
            assertEquals(0L, lane.session.progress("B")!!.positionMs)
        } finally { lane.close() }
    }

    @Test fun queuedAEndCannotMarkBWatchedOrAutoAdvanceIt() {
        val lane = Lane()
        try {
            val a = lane.start("A"); lane.position(a, 1440.0)
            a.client.emitEvent(MpvPlaybackEngine.MPV_EVENT_END_FILE, playlistEntryId = 2L, reason = "eof")
            a.dispatcher.drain()
            lane.navigate("B")
            a.drainMain()
            lane.publishCompletion()
            assertTrue(lane.completed.isEmpty())
            assertEquals("B", lane.selected)
        } finally { lane.close() }
    }

    @Test fun selectedIdentityChangingBeforeTeardownBlocksProgressPublication() {
        val lane = Lane()
        try {
            val a = lane.start("A"); lane.position(a, 1260.0)
            lane.selected = "B"
            assertNull(lane.session.progress(lane.selected))
            lane.publishCompletion(force = true)
            assertTrue(lane.completed.isEmpty())
        } finally { lane.close() }
    }

    @Test fun switchingWhileLoadingHasNoOwnerToPublishCompletion() {
        val lane = Lane()
        try {
            val a = lane.start("A"); lane.position(a, 1260.0)
            lane.session.invalidate(); lane.selected = "B"
            assertNull(lane.session.progress("B"))
            lane.publishCompletion(force = true)
            assertTrue(lane.completed.isEmpty())
        } finally { lane.close() }
    }

    @Test fun rapidAtoBtoCRejectsLateCallbacksFromBothRetiredEngines() {
        val lane = Lane()
        try {
            val a = lane.start("A"); lane.position(a, 1260.0, deliver = false)
            val b = lane.navigate("B"); lane.position(b, 270.0, deliver = false)
            lane.navigate("C"); lane.lastPosition = 0L
            a.drainMain(); b.drainMain()
            assertEquals(0L, lane.lastPosition)
            assertEquals(0L, lane.requests.last().startPositionMs)
        } finally { lane.close() }
    }

    @Test fun sameEpisodeSourceReloadPreservesLivePosition() {
        val lane = Lane()
        try {
            lane.saved["A"] = 270000L
            val a = lane.start("A"); lane.position(a, 1260.0)
            assertEquals(1260000L, lane.session.resumePosition("A", lane.saved["A"]!!))
        } finally { lane.close() }
    }

    @Test fun revisitingADoesNotReviveOldABinding() {
        val lane = Lane()
        try {
            val a = lane.start("A"); lane.position(a, 1260.0, deliver = false)
            lane.navigate("B"); lane.navigate("A")
            lane.lastPosition = 0L
            a.drainMain()
            assertEquals(0L, lane.lastPosition)
            assertEquals(1260000L, lane.requests.last().startPositionMs)
        } finally { lane.close() }
    }

    @Test fun manualSelectionUsesTargetResume() = navigationResume("A", "C")
    @Test fun nextNavigationUsesTargetResume() = navigationResume("A", "B")
    @Test fun previousNavigationUsesTargetResume() = navigationResume("B", "A")
    @Test fun autoNextUsesTargetResume() = navigationResume("A", "B", atEnd = true)
    // All four activity entry points use the same changeEpisode/session ownership seam.
    private fun navigationResume(from: String, target: String, atEnd: Boolean = false) {
        val lane = Lane()
        try {
            val a = lane.start(from); lane.position(a, if (atEnd) 1440.0 else 1260.0)
            if (atEnd) {
                a.client.emitEvent(MpvPlaybackEngine.MPV_EVENT_END_FILE, playlistEntryId = 2L, reason = "eof")
                a.dispatcher.drain(); a.drainMain()
            }
            lane.saved[target] = 270000L
            lane.navigate(target)
            assertEquals(270000L, lane.requests.last().startPositionMs)
            assertFalse(lane.session.progress(target)!!.isComplete(0.8f))
        } finally { lane.close() }
    }

    @Test fun legitimateBCompletionPublishesBOnly() {
        val lane = Lane()
        try {
            val a = lane.start("A"); lane.position(a, 1260.0)
            val b = lane.navigate("B"); lane.position(b, 1300.0)
            lane.publishCompletion()
            assertEquals(listOf("B"), lane.completed)
        } finally { lane.close() }
    }

    @Test fun lateCastHandoverPositionFromACannotSeekB() {
        val lane = Lane()
        try {
            val a = lane.start("A"); lane.position(a, 1260.0)
            val b = lane.navigate("B")
            assertFalse(lane.session.acceptsEngine(a.engine, "B"))
            assertTrue(lane.session.acceptsEngine(b.engine, "B"))
            assertFalse(lane.session.acceptsEngine(b.engine, "A"))
            assertFalse(lane.session.acceptsEngine(null, "B"))
            if (lane.session.acceptsEngine(a.engine, "B")) b.engine.seekTo(1260000L)
            b.dispatcher.drain()
            assertFalse(b.client.executedCommands.any { it.first() == "seek" })
        } finally { lane.close() }
    }

    @Test fun savingAfterSelectorChangesStillPersistsOnlyBoundEpisodeA() {
        val lane = Lane()
        try {
            val a = lane.start("A"); lane.position(a, 1260.0)
            lane.selected = "B"
            lane.session.savePosition { key, position -> lane.saved[key] = position }
            assertEquals(1260000L, lane.saved["A"])
            assertNull(lane.saved["B"])
        } finally { lane.close() }
    }

    @Test fun allListenerKindsAreRejectedForRetiredBinding() {
        val fixture = SpeedFixture()
        try {
            val session = EpisodePlaybackSession()
            val binding = session.bind("A", fixture.engine)
            var publications = 0
            val delegate = object : PlaybackListener {
                override fun onPlaybackStateChanged(state: PlaybackState) { publications++ }
                override fun onPositionChanged(positionMs: Long, durationMs: Long) { publications++ }
                override fun onPlaybackSpeedChanged(speed: Float) { publications++ }
                override fun onTracksChanged(tracks: List<PlayerTrack>) { publications++ }
                override fun onVideoSizeChanged(width: Int, height: Int) { publications++ }
                override fun onIsPlayingChanged(isPlaying: Boolean) { publications++ }
                override fun onPlayWhenReadyChanged(playWhenReady: Boolean) { publications++ }
                override fun onDurationKnown(generationId: Long, durationMs: Long) { publications++ }
                override fun onSnapshotChanged(snapshot: PlaybackSnapshot) { publications++ }
            }
            val listener = session.listener(binding, { "A" }, delegate)
            session.invalidate(); session.bind("A", fixture.engine)
            listener.onPlaybackStateChanged(PlaybackState.Ended())
            listener.onPositionChanged(1260000L, 1440000L)
            listener.onPlaybackSpeedChanged(3f); listener.onTracksChanged(emptyList())
            listener.onVideoSizeChanged(1920, 1080); listener.onIsPlayingChanged(true)
            listener.onPlayWhenReadyChanged(true); listener.onDurationKnown(1L, 1440000L)
            listener.onSnapshotChanged(PlaybackSnapshot(positionMs = 1260000L))
            assertEquals(0, publications)
        } finally { fixture.close() }
    }

    @Test fun queuedSpeedEventsRenderLatestAcknowledgedEngineValue() {
        val fixture = SpeedFixture()
        val displayed = mutableListOf<Float>()
        val hold = TemporaryPlaybackSpeed({ fixture.engine }) { displayed.add(it) }
        try {
            hold.start(2f); fixture.dispatcher.drain()
            fixture.client.emitProperty("speed", 2.0); fixture.dispatcher.drain()
            hold.update(2.5f); fixture.dispatcher.drain()
            fixture.client.emitProperty("speed", 2.5); fixture.dispatcher.drain()
            displayed.clear()
            fixture.drainMain()
            assertEquals(listOf(2.5f, 2.5f), displayed)
            hold.stop()
        } finally { fixture.close() }
    }

    @Test fun holdEndingAfterEngineReplacementCannotRestoreSpeedOnB() {
        val a = SpeedFixture(); val b = SpeedFixture()
        var active = a.engine
        var displayed = 0f
        val hold = TemporaryPlaybackSpeed({ active }) { displayed = it }
        try {
            hold.start(2f); a.acknowledgeSpeed()
            a.client.emitProperty("speed", 2.5); a.dispatcher.drain()
            active = b.engine
            a.drainMain()
            assertEquals(2f, displayed, 0f)
            hold.stop(); b.dispatcher.drain()
            assertFalse(b.client.doubleProperties.containsKey("speed"))
            assertEquals(1f, b.engine.playbackSpeed, 0f)
        } finally { a.close(); b.close() }
    }
}
