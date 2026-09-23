package ani.dantotsu.media.anime.player

import `is`.xyz.mpv.MPVNode
import ani.dantotsu.media.anime.player.ExternalSubtitle
import kotlin.collections.ArrayDeque
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * CP3 integration tests. Drives [MpvPlaybackEngine] through [FakeMpvClient] to prove the
 * operation/generation-bound explicit subtitle-selection semantics, including:
 *  - stale/superseded-load callback provenance vs legitimate next-load callbacks (review v3 P0),
 *  - transient-track-list handling,
 *  - external-subtitle handshake distinguishing live user selection from request-carried
 *    attachment (review v2 P1), and not adopting an already-selected older external track
 *    (review v2 P1/P2).
 *
 * All engine callbacks (events and property changes) are serialized onto the engine's own
 * dispatcher. A [QueuedEngineDispatcher] lets the tests control execution order deterministically
 * (the reviewer's "drain/await-idle" primitive) instead of relying on `Thread.sleep`.
 */
class SubtitleSelectionEngineTest {

    /** Deterministic dispatcher: queues actions, executes them only on [drain]. */
    private class QueuedEngineDispatcher : EngineDispatcher {
        private val queue = ArrayDeque<() -> Unit>()
        override fun post(action: () -> Unit) { queue.addLast(action) }
        override fun quit() {}
        fun drain() { while (queue.isNotEmpty()) queue.removeFirst().invoke() }
    }

    private lateinit var fake: FakeMpvClient
    private lateinit var dq: QueuedEngineDispatcher
    private lateinit var engine: MpvPlaybackEngine

    @Before
    fun setup() {
        fake = FakeMpvClient()
        dq = QueuedEngineDispatcher()
        engine = MpvPlaybackEngine(null, fake, engineDispatcher = dq, postToMain = { it.run() })
    }

    private fun drain() = dq.drain()

    // --- test helpers ---

    private fun sub(id: Int, selected: Boolean = false, external: Boolean = false, lang: String? = null, uri: String? = null): MPVNode {
        val map = mutableMapOf<String, MPVNode>(
            "id" to MPVNode.IntNode(id.toLong()),
            "type" to MPVNode.StringNode("sub"),
            "selected" to MPVNode.BooleanNode(selected),
            "external" to MPVNode.BooleanNode(external)
        )
        if (lang != null) map["lang"] = MPVNode.StringNode(lang)
        if (external && uri != null) map["external-filename"] = MPVNode.StringNode(uri)
        return MPVNode.MapNode(map)
    }

    private fun setTrackList(vararg tracks: MPVNode) {
        fake.nodeProperties["track-list"] = MPVNode.ArrayNode(Array(tracks.size) { tracks[it] })
    }

    private fun emitTrackList(vararg tracks: MPVNode) {
        setTrackList(*tracks)
        fake.emitProperty("track-list", 0.0)
    }

    private fun loadAndStart(request: PlaybackRequest, entryId: Long) {
        engine.loadMedia(request)
        drain()
        fake.emitEvent(MpvPlaybackEngine.MPV_EVENT_START_FILE, playlistEntryId = entryId)
        fake.emitEvent(MpvPlaybackEngine.MPV_EVENT_FILE_LOADED)
        drain()
    }

    // --- P1: Explicit Off survives FILE_LOADED and track refresh ---

    @Test
    fun offSurvivesFileLoadedAndRefresh() {
        val req = PlaybackRequest(uri = "https://cdn.anime.com/ep1.m3u8", preferredSubLang = "None")
        engine.loadMedia(req)
        drain()
        fake.emitEvent(MpvPlaybackEngine.MPV_EVENT_START_FILE, playlistEntryId = 10L)
        fake.emitEvent(MpvPlaybackEngine.MPV_EVENT_FILE_LOADED)
        drain()

        emitTrackList(sub(1, selected = true, lang = "eng"), sub(2, lang = "spa"))
        drain()

        assertEquals(SubtitleSelection.Off, engine.getCurrentSubtitleSelection())
        assertEquals("no", fake.stringProperties["sid"])

        emitTrackList(sub(3, selected = true, lang = "eng"))
        drain()
        assertEquals(SubtitleSelection.Off, engine.getCurrentSubtitleSelection())
        assertEquals("no", fake.stringProperties["sid"])
    }

    // --- P1: Explicit Track survives reload ---

    @Test
    fun explicitTrackSurvivesReload() {
        val req = PlaybackRequest(uri = "https://cdn.anime.com/ep1.m3u8")
        loadAndStart(req, 10L)

        engine.selectSubtitleTrack(5)
        drain()
        emitTrackList(sub(4, lang = "spa"), sub(5, selected = true, lang = "eng"))
        drain()

        assertEquals(SubtitleSelection.Track(5), engine.getCurrentSubtitleSelection())
        assertEquals(5, fake.intProperties["sid"])

        emitTrackList(sub(5, selected = true, lang = "eng"), sub(6, lang = "fre"))
        drain()
        assertEquals(SubtitleSelection.Track(5), engine.getCurrentSubtitleSelection())
        assertEquals(5, fake.intProperties["sid"])
    }

    // --- P1: Missing explicit track from authoritative list -> Off ---

    @Test
    fun missingTrackAuthoritativeFallsBackToOff() {
        val req = PlaybackRequest(uri = "https://cdn.anime.com/ep1.m3u8")
        loadAndStart(req, 10L)

        engine.selectSubtitleTrack(5)
        drain()
        emitTrackList(sub(1, selected = true, lang = "eng"), sub(2, lang = "spa"))
        drain()

        assertEquals(SubtitleSelection.Off, engine.getCurrentSubtitleSelection())
        assertEquals("no", fake.stringProperties["sid"])
    }

    // --- P1: Transient/incomplete list must NOT prematurely downgrade Track -> Off ---

    @Test
    fun transientListDoesNotPrematurelyDowngrade() {
        val req = PlaybackRequest(uri = "https://cdn.anime.com/ep1.m3u8")
        engine.loadMedia(req)
        drain()
        fake.emitEvent(MpvPlaybackEngine.MPV_EVENT_START_FILE, playlistEntryId = 10L)
        engine.selectSubtitleTrack(5)
        drain()

        emitTrackList(sub(1, selected = true, lang = "eng"))
        drain()
        assertEquals(SubtitleSelection.Track(5), engine.getCurrentSubtitleSelection())

        fake.emitEvent(MpvPlaybackEngine.MPV_EVENT_FILE_LOADED)
        drain()
        emitTrackList(sub(2, selected = true, lang = "eng"), sub(3, lang = "spa"))
        drain()
        assertEquals(SubtitleSelection.Off, engine.getCurrentSubtitleSelection())
        assertEquals("no", fake.stringProperties["sid"])
    }

    // --- P0 (review v2): stale/superseded load callback cannot change current intent ---

    @Test
    fun staleTrackListCannotMutateCurrentOperation() {
        val reqA = PlaybackRequest(uri = "https://cdn.anime.com/epA.m3u8")
        loadAndStart(reqA, 10L)
        engine.selectSubtitleTrack(5)
        drain()

        val reqB = PlaybackRequest(uri = "https://cdn.anime.com/epB.m3u8")
        engine.loadMedia(reqB)
        drain()
        fake.emitEvent(MpvPlaybackEngine.MPV_EVENT_START_FILE, playlistEntryId = 11L)
        fake.emitEvent(MpvPlaybackEngine.MPV_EVENT_FILE_LOADED)
        drain()

        emitTrackList(sub(1, selected = true, lang = "eng"), sub(2, lang = "spa"))
        drain()
        assertNotEquals(SubtitleSelection.Track(5), engine.getCurrentSubtitleSelection())
        assertEquals(SubtitleSelection.Unset, engine.getCurrentSubtitleSelection())
    }

    @Test
    fun staleOffCallbackCannotAffectCurrentOperation() {
        val reqA = PlaybackRequest(uri = "https://cdn.anime.com/epA.m3u8", preferredSubLang = "None")
        loadAndStart(reqA, 10L)
        assertEquals(SubtitleSelection.Off, engine.getCurrentSubtitleSelection())

        val reqB = PlaybackRequest(uri = "https://cdn.anime.com/epB.m3u8")
        engine.loadMedia(reqB)
        drain()
        fake.emitEvent(MpvPlaybackEngine.MPV_EVENT_START_FILE, playlistEntryId = 11L)
        fake.emitEvent(MpvPlaybackEngine.MPV_EVENT_FILE_LOADED)
        drain()
        emitTrackList(sub(1, selected = true, lang = "eng"))
        drain()

        assertNotEquals(SubtitleSelection.Off, engine.getCurrentSubtitleSelection())
        assertEquals(SubtitleSelection.Unset, engine.getCurrentSubtitleSelection())
    }

    /**
     * Reviewer-required (review v3 P0): a property callback observed for a SUPERSEDED load must be
     * rejected. A's track-list is queued (carrying A's observed entry id) BEFORE B's START_FILE is
     * even processed; B then explicitly selects Track(9). At execution, A's callback's observed
     * entry id no longer matches the playing entry, so it is discarded and B keeps Track(9).
     */
    @Test
    fun stalePreviousLoadNotificationDoesNotMutateCurrent() {
        val reqA = PlaybackRequest(uri = "https://cdn.anime.com/epA.m3u8")
        loadAndStart(reqA, 10L)
        engine.selectSubtitleTrack(5)
        drain()

        // Queue A's track-list callback NOW (observed with entry 10), before B even starts.
        setTrackList(sub(1, selected = true, lang = "eng")) // A's list (no track 9)
        fake.emitProperty("track-list", 0.0)                 // captured observedEntryId=10, queued

        // Now B starts and explicitly selects Track(9); B's own events follow (observed with 11).
        val reqB = PlaybackRequest(uri = "https://cdn.anime.com/epB.m3u8")
        engine.loadMedia(reqB) // queued (creates B's LoadOperation)
        fake.emitEvent(MpvPlaybackEngine.MPV_EVENT_START_FILE, playlistEntryId = 11L)
        fake.emitEvent(MpvPlaybackEngine.MPV_EVENT_FILE_LOADED)
        engine.selectSubtitleTrack(9)
        setTrackList(sub(9, selected = true, lang = "eng"), sub(2, lang = "spa"))
        fake.emitProperty("track-list", 0.0)                 // captured observedEntryId=11, queued

        drain()
        // A's stale callback (entry 10) is rejected; B keeps explicit Track(9).
        assertEquals(SubtitleSelection.Track(9), engine.getCurrentSubtitleSelection())
        assertEquals(9, fake.intProperties["sid"])
    }

    /**
     * Reviewer-required (review v3 P0): a property callback for the NEXT load that is observed while
     * the engine dispatcher has NOT yet processed that load's START_FILE must NOT be discarded.
     * We publish the next entry id synchronously on the native START_FILE, so B's track-list
     * (observed before B's transition is drained) is recognized as legitimate and B, being Unset
     * with a preferred language, still performs normal auto-selection.
     */
    @Test
    fun legitimateNextLoadTrackListSurvivesDispatcherLag() {
        val reqA = PlaybackRequest(uri = "https://cdn.anime.com/epA.m3u8")
        loadAndStart(reqA, 10L)
        engine.selectSubtitleTrack(5)
        drain()

        // Observe B's START_FILE but do NOT drain its engine action yet. The entry id is published
        // synchronously, so the next property callback already carries B's entry id.
        val reqB = PlaybackRequest(uri = "https://cdn.anime.com/epB.m3u8", preferredSubLang = "eng")
        engine.loadMedia(reqB) // queued (creates B's LoadOperation; not drained yet)
        fake.emitEvent(MpvPlaybackEngine.MPV_EVENT_START_FILE, playlistEntryId = 11L)
        // B's track-list observed while activeOperation is still A.
        setTrackList(sub(9, selected = false, lang = "eng"), sub(2, lang = "spa"))
        fake.emitProperty("track-list", 0.0) // captured observedEntryId=11, queued, NOT drained

        // Finish B's transition.
        fake.emitEvent(MpvPlaybackEngine.MPV_EVENT_FILE_LOADED)
        drain()

        // B's track-list (observed with entry 11) is processed, not rejected; B auto-selects 9.
        assertEquals(SubtitleSelection.Unset, engine.getCurrentSubtitleSelection())
        assertEquals(9, fake.intProperties["sid"])
    }

    /**
     * Reviewer-required (review v3 P1): two live explicit external selections issued before the
     * first one materializes. The handshake matches each request to the track it actually produces
     * (by external filename), so the LATEST request's track wins and the earlier one is not adopted.
     */
    @Test
    fun latestLiveExternalSelectionWinsWhenFirstNotYetMaterialized() {
        val req = PlaybackRequest(uri = "https://cdn.anime.com/ep1.m3u8")
        loadAndStart(req, 10L)

        // Live user selects external A, then (before A appears) selects external B.
        engine.addExternalSubtitle("https://cdn.anime.com/subA.vtt", "A", "eng", true, explicitUserAction = true)
        drain()
        engine.addExternalSubtitle("https://cdn.anime.com/subB.vtt", "B", "eng", true, explicitUserAction = true)
        drain()

        // A materializes first; it is NOT the latest request, so the handshake keeps waiting.
        emitTrackList(sub(40, selected = true, external = true, lang = "eng", uri = "https://cdn.anime.com/subA.vtt"))
        drain()
        assertEquals(SubtitleSelection.Unset, engine.getCurrentSubtitleSelection())

        // B materializes; the latest request wins -> Track(41).
        emitTrackList(
            sub(41, selected = true, external = true, lang = "eng", uri = "https://cdn.anime.com/subB.vtt"),
            sub(40, external = true, lang = "eng", uri = "https://cdn.anime.com/subA.vtt")
        )
        drain()
        assertEquals(SubtitleSelection.Track(41), engine.getCurrentSubtitleSelection())
        assertEquals(41, fake.intProperties["sid"])
    }

    // --- P1 (review v2): External/server/local explicit subtitle adopts Track(id) ---

    @Test
    fun externalSubtitleAdoptsTrackAndSurvivesRefresh() {
        val req = PlaybackRequest(uri = "https://cdn.anime.com/ep1.m3u8")
        loadAndStart(req, 10L)

        // Live external subtitle selection (select = true).
        engine.addExternalSubtitle("https://cdn.anime.com/sub_en.vtt", "English", "eng", true, explicitUserAction = true)
        drain()
        emitTrackList(sub(1, lang = "eng"), sub(99, selected = true, external = true, lang = "eng", uri = "https://cdn.anime.com/sub_en.vtt"))
        drain()

        assertEquals(SubtitleSelection.Track(99), engine.getCurrentSubtitleSelection())
        assertEquals(99, fake.intProperties["sid"])

        emitTrackList(sub(99, selected = true, external = true, lang = "eng"))
        drain()
        assertEquals(SubtitleSelection.Track(99), engine.getCurrentSubtitleSelection())
        assertEquals(99, fake.intProperties["sid"])
    }

    @Test
    fun offDisablesAlreadySelectedExternalSubtitle() {
        val req = PlaybackRequest(uri = "https://cdn.anime.com/ep1.m3u8")
        loadAndStart(req, 10L)

        engine.addExternalSubtitle("https://cdn.anime.com/sub_en.vtt", "English", "eng", true, explicitUserAction = true)
        drain()
        emitTrackList(sub(99, selected = true, external = true, lang = "eng", uri = "https://cdn.anime.com/sub_en.vtt"))
        drain()
        assertEquals(SubtitleSelection.Track(99), engine.getCurrentSubtitleSelection())

        engine.selectSubtitleTrack(null)
        drain()
        assertEquals(SubtitleSelection.Off, engine.getCurrentSubtitleSelection())
        assertEquals("no", fake.stringProperties["sid"])

        emitTrackList(sub(99, selected = true, external = true, lang = "eng"))
        drain()
        assertEquals(SubtitleSelection.Off, engine.getCurrentSubtitleSelection())
        assertEquals("no", fake.stringProperties["sid"])
    }

    // --- P1 (review v2): live external selection OVERRIDES a prior Off ---

    @Test
    fun liveExternalSelectionOverridesOff() {
        val req = PlaybackRequest(uri = "https://cdn.anime.com/ep1.m3u8", preferredSubLang = "None")
        loadAndStart(req, 10L)
        assertEquals(SubtitleSelection.Off, engine.getCurrentSubtitleSelection())

        // User explicitly selects an external subtitle now -> latest intent changed.
        engine.addExternalSubtitle("https://cdn.anime.com/sub_en.vtt", "English", "eng", true, explicitUserAction = true)
        drain()
        emitTrackList(sub(99, selected = true, external = true, lang = "eng", uri = "https://cdn.anime.com/sub_en.vtt"))
        drain()

        assertEquals(SubtitleSelection.Track(99), engine.getCurrentSubtitleSelection())
        assertEquals(99, fake.intProperties["sid"])
    }

    // --- P1 (review v2): request-carried external does NOT override a persisted Off ---

    @Test
    fun requestCarriedExternalDoesNotOverridePersistedOff() {
        val req = PlaybackRequest(
            uri = "https://cdn.anime.com/ep1.m3u8",
            preferredSubLang = "None",
            externalSubtitles = listOf(
                ExternalSubtitle(url = "https://cdn.anime.com/sub_en.vtt", title = "English", language = "eng", selected = true)
            )
        )
        loadAndStart(req, 10L)
        assertEquals(SubtitleSelection.Off, engine.getCurrentSubtitleSelection())

        // mpv reports the request-carried external track as selected; persisted Off must win.
        emitTrackList(sub(99, selected = true, external = true, lang = "eng", uri = "https://cdn.anime.com/sub_en.vtt"))
        drain()

        assertEquals(SubtitleSelection.Off, engine.getCurrentSubtitleSelection())
        assertEquals("no", fake.stringProperties["sid"])
    }

    // --- P1/P2 (review v2): handshake must not adopt an already-selected older external track ---

    @Test
    fun externalHandshakeAdoptsOnlyNewlyRequestedTrack() {
        val req = PlaybackRequest(uri = "https://cdn.anime.com/ep1.m3u8")
        loadAndStart(req, 10L)

        // User live-selects external track 40; it appears and is adopted.
        engine.addExternalSubtitle("https://cdn.anime.com/sub40.vtt", "First", "eng", true, explicitUserAction = true)
        drain()
        emitTrackList(sub(40, selected = true, external = true, lang = "eng", uri = "https://cdn.anime.com/sub40.vtt"))
        drain()
        assertEquals(SubtitleSelection.Track(40), engine.getCurrentSubtitleSelection())

        // User live-selects a NEW external subtitle (41). An older selected external (40) is
        // still present; a refresh before 41 is assigned must NOT satisfy/consume the handshake.
        engine.addExternalSubtitle("https://cdn.anime.com/sub41.vtt", "Second", "eng", true, explicitUserAction = true)
        drain()
        emitTrackList(sub(40, selected = true, external = true, lang = "eng", uri = "https://cdn.anime.com/sub40.vtt"))
        drain()
        // Still the old track (41 not yet present); handshake must not re-adopt 40.
        assertEquals(SubtitleSelection.Track(40), engine.getCurrentSubtitleSelection())

        // Now 41 is assigned and selected.
        emitTrackList(sub(41, selected = true, external = true, lang = "eng", uri = "https://cdn.anime.com/sub41.vtt"), sub(40, external = true, lang = "eng", uri = "https://cdn.anime.com/sub40.vtt"))
        drain()
        assertEquals(SubtitleSelection.Track(41), engine.getCurrentSubtitleSelection())
        assertEquals(41, fake.intProperties["sid"])
    }

    // --- P0/P1: explicit Off beats automatic selection (serialized callbacks) ---

    @Test
    fun offBeatsScheduledAutoSelection() {
        val req = PlaybackRequest(uri = "https://cdn.anime.com/ep1.m3u8", preferredSubLang = "eng")
        loadAndStart(req, 10L)

        emitTrackList(sub(5, lang = "eng"), sub(6, lang = "spa"))
        drain()
        assertEquals(SubtitleSelection.Unset, engine.getCurrentSubtitleSelection())
        assertEquals(5, fake.intProperties["sid"])

        engine.selectSubtitleTrack(null)
        drain()
        assertEquals(SubtitleSelection.Off, engine.getCurrentSubtitleSelection())
        assertEquals("no", fake.stringProperties["sid"])

        emitTrackList(sub(5, selected = true, lang = "eng"))
        drain()
        assertEquals(SubtitleSelection.Off, engine.getCurrentSubtitleSelection())
        assertEquals("no", fake.stringProperties["sid"])
    }

    // --- P1: FILE_LOADED with mpv auto-selected default still respects Off ---

    @Test
    fun fileLoadedDefaultSelectionRespectsOff() {
        val req = PlaybackRequest(uri = "https://cdn.anime.com/ep1.m3u8", preferredSubLang = "None")
        engine.loadMedia(req)
        drain()
        fake.emitEvent(MpvPlaybackEngine.MPV_EVENT_START_FILE, playlistEntryId = 10L)
        fake.emitEvent(MpvPlaybackEngine.MPV_EVENT_FILE_LOADED)
        drain()
        assertEquals(SubtitleSelection.Off, engine.getCurrentSubtitleSelection())
        assertEquals("no", fake.stringProperties["sid"])
    }

    // --- P1 (review v4): explicit Track(id) cancels an OLDER pending live external intent ---

    @Test
    fun liveExternalPendingThenEmbeddedTrackWins() {
        val req = PlaybackRequest(uri = "https://cdn.anime.com/ep1.m3u8")
        loadAndStart(req, 10L)

        // User requests external A, but it does not materialize yet.
        engine.addExternalSubtitle("https://cdn.anime.com/subA.vtt", "A", "eng", true, explicitUserAction = true)
        drain()
        assertEquals(SubtitleSelection.Unset, engine.getCurrentSubtitleSelection())

        // Before A appears, the user explicitly selects embedded Track(5). This is the latest
        // explicit intent and must cancel the older pending external handshake.
        engine.selectSubtitleTrack(5)
        drain()
        assertEquals(SubtitleSelection.Track(5), engine.getCurrentSubtitleSelection())
        assertEquals(5, fake.intProperties["sid"])

        // A materializes later; it must NOT replace the explicit Track(5).
        emitTrackList(
            sub(5, selected = true, lang = "eng"),
            sub(40, selected = true, external = true, lang = "eng", uri = "https://cdn.anime.com/subA.vtt")
        )
        drain()
        assertEquals(SubtitleSelection.Track(5), engine.getCurrentSubtitleSelection())
        assertEquals(5, fake.intProperties["sid"])
    }

    // --- P1 (review v4): explicit Track(id) cancels an OLDER pending request-carried intent ---

    @Test
    fun requestCarriedExternalPendingThenEmbeddedTrackWins() {
        val req = PlaybackRequest(
            uri = "https://cdn.anime.com/ep1.m3u8",
            externalSubtitles = listOf(
                ExternalSubtitle(url = "https://cdn.anime.com/subA.vtt", title = "A", language = "eng", selected = true)
            )
        )
        loadAndStart(req, 10L)
        // Request-carried external is pending (state is Unset, not Off), so it may later adopt.
        assertEquals(SubtitleSelection.Unset, engine.getCurrentSubtitleSelection())

        // Before it materializes, the user explicitly selects embedded Track(5).
        engine.selectSubtitleTrack(5)
        drain()
        assertEquals(SubtitleSelection.Track(5), engine.getCurrentSubtitleSelection())
        assertEquals(5, fake.intProperties["sid"])

        // The request-carried external appears later; it must NOT replace Track(5).
        emitTrackList(
            sub(5, selected = true, lang = "eng"),
            sub(40, selected = true, external = true, lang = "eng", uri = "https://cdn.anime.com/subA.vtt")
        )
        drain()
        assertEquals(SubtitleSelection.Track(5), engine.getCurrentSubtitleSelection())
        assertEquals(5, fake.intProperties["sid"])
    }

    // --- P1 (review v4): a NEWER live external selection still beats an earlier explicit Track ---

    @Test
    fun newerExternalSelectionBeatsEarlierTrack() {
        val req = PlaybackRequest(uri = "https://cdn.anime.com/ep1.m3u8")
        loadAndStart(req, 10L)

        engine.selectSubtitleTrack(5)
        drain()
        assertEquals(SubtitleSelection.Track(5), engine.getCurrentSubtitleSelection())

        // Afterward the user explicitly requests external B (the latest explicit intent).
        engine.addExternalSubtitle("https://cdn.anime.com/subB.vtt", "B", "eng", true, explicitUserAction = true)
        drain()

        // B materializes (mpv deselects the earlier embedded track); being the latest user
        // action, it overrides the earlier Track(5).
        emitTrackList(
            sub(5, selected = false, lang = "eng"),
            sub(41, selected = true, external = true, lang = "eng", uri = "https://cdn.anime.com/subB.vtt")
        )
        drain()
        assertEquals(SubtitleSelection.Track(41), engine.getCurrentSubtitleSelection())
        assertEquals(41, fake.intProperties["sid"])
    }
}
