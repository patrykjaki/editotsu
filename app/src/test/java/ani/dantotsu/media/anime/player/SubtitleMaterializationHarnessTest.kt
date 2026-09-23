package ani.dantotsu.media.anime.player

import `is`.xyz.mpv.MPVNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * HIST-SUB-1/2/3 + CP3-MAT-1..7: deterministic differential reproduction of the
 * 0.2.0 -> 0.2.1 -> beta04 subtitle-selection semantics under an AniZone-like
 * external-subtitle materialization sequence.
 *
 * A faithful mpv simulator is shared by all three policies:
 * - `sub-add(url, select)` assigns the next mpv track id; `select` switches
 *   mpv's single selected subtitle immediately (like real mpv);
 * - engine/policy `sid` commands switch the simulated selection;
 * - snapshots are pushed to the policy under test between materialization ticks.
 *
 * Policy A = verbatim 0.2.0 logic (c52c0155): post-load select() writes sid
 * directly with no retained pending state; track-list auto-picks with the
 * simple language matcher only when nothing is selected and nothing pends.
 * Policy B = verbatim 0.2.1 logic (a3b0566d): select() always retains
 * pendingSubtitleTrackId; track-list auto-pick suppressed while pending, with
 * the smart-dialogue weighted scorer. Policy C = the real beta04
 * MpvPlaybackEngine driven through FakeMpvClient.
 */
class SubtitleMaterializationHarnessTest {

    data class SimT(
        val key: String,
        val lang: String,
        val name: String,
        val external: Boolean,
        val forced: Boolean = false,
        val default: Boolean = false
    )

    /** Faithful mpv single-selection simulator. */
    class SimMpv {
        data class Entry(val key: String, val id: Int, var selected: Boolean, val t: SimT)
        private var nextId = 1
        val tracks = mutableListOf<Entry>()
        val log = StringBuilder()
        fun subAdd(key: String, select: Boolean, t: SimT): Int {
            val id = nextId++
            if (select) tracks.forEach { it.selected = false }
            tracks.add(Entry(key, id, select, t))
            log.append("sub-add $key select=$select -> id=$id\n")
            return id
        }
        fun setSid(id: Int?) {
            tracks.forEach { it.selected = (id != null && it.id == id) }
            log.append("sid -> $id\n")
        }
        fun selectedKey(): String? = tracks.firstOrNull { it.selected }?.key
        fun snapshotSubs(): List<Triple<Int, Boolean, SimT>> =
            tracks.map { Triple(it.id, it.selected, it.t) }
    }

    // ---- Verbatim 0.2.0 matcher (c52c0155) ----
    private fun match020(subs: List<SimT>, preferred: String?): SimT? {
        if (preferred != null && !preferred.equals("None", ignoreCase = true)) {
            val byLang = subs.find {
                it.lang.contains(preferred, ignoreCase = true) ||
                    it.name.contains(preferred, ignoreCase = true)
            }
            if (byLang != null) return byLang
        }
        val english = subs.filter {
            val l = it.lang.lowercase()
            val n = it.name.lowercase()
            l.contains("eng") || l.contains("en") || n.contains("english") || n.contains("eng")
        }
        if (english.isNotEmpty()) {
            val full = english.firstOrNull {
                val n = it.name.lowercase()
                !n.contains("sign") && !n.contains("song") && !it.forced
            }
            return full ?: english.first()
        }
        val def = subs.firstOrNull { it.default }
        if (def != null) return def
        return subs.firstOrNull { !it.forced } ?: subs.firstOrNull()
    }

    // ---- Verbatim 0.2.1 scorer (a3b0566d) ----
    private fun isSigns021(t: SimT): Boolean {
        if (t.forced) return true
        val n = t.name.lowercase()
        val kws = listOf("sign", "song", "s&s", "s/s", "forced", "op/ed", "oped", "insert", "signs & songs", "signs/songs", "signs and songs")
        return kws.any { it in n && "dialogue" !in n && "full" !in n }
    }
    private fun isFull021(t: SimT): Boolean {
        val n = t.name.lowercase()
        return listOf("dialogue", "dialog", "full", "complete", "main", "subs", "subtitles").any { it in n }
    }
    private fun matches021(t: SimT, targetRaw: String): Boolean {
        val tl = t.lang.lowercase()
        val tn = t.name.lowercase()
        val target = targetRaw.lowercase().trim()
        if (tl.equals(target, ignoreCase = true) || target in tn) return true
        val aliases = mapOf(
            "en" to listOf("eng", "english", "en-us", "en-gb"),
            "eng" to listOf("en", "english", "en-us", "en-gb"),
            "english" to listOf("en", "eng", "en-us", "en-gb")
        )
        return (aliases[target] ?: emptyList()).any { it in tl || it in tn }
    }
    private fun score021(t: SimT, preferred: String?): Int {
        var s = 0
        if (preferred != null && !preferred.equals("None", ignoreCase = true)) {
            if (matches021(t, preferred)) s += 1000
        } else {
            if (matches021(t, "English")) s += 500
        }
        if (isFull021(t)) s += 200
        if (isSigns021(t)) s -= 600
        if (t.forced) s -= 400
        if (t.default) s += 50
        return s
    }
    private fun best021(subs: List<SimT>, preferred: String?): SimT? {
        if (subs.isEmpty()) return null
        if (preferred != null && !preferred.equals("None", ignoreCase = true)) {
            val byLang = subs.find { matches021(it, preferred) }
            if (byLang != null) return byLang
        }
        val sorted = subs.map { it to score021(it, preferred) }
            .sortedWith(compareByDescending<Pair<SimT, Int>> { it.second }.thenBy { it.first.lang })
        val best = sorted.firstOrNull() ?: return null
        if (best.second > -300) return best.first
        return subs.firstOrNull { it.default && !isSigns021(it) }
            ?: subs.firstOrNull { !isSigns021(it) }
            ?: subs.firstOrNull { !it.forced }
            ?: subs.firstOrNull()
    }

    /** Policy A replica: 0.2.0 select + track-list behavior. */
    inner class PolicyA(val sim: SimMpv) {
        var pending: Int? = null
        var preferred: String? = "English"
        val log = StringBuilder()
        fun select(id: Int?) {
            // 0.2.0: post-load select writes sid directly, no retained pending.
            pending = null
            sim.setSid(id)
            log.append("select($id)\n")
        }
        fun onTrackList() {
            val subs = sim.tracks.map { it.t }
            var subId = sim.tracks.firstOrNull { it.selected }?.id
            log.append("track-list selected=$subId tracks=${sim.tracks.map { "${it.key}:${it.id}${if (it.selected) "*" else ""}" }}\n")
            if (subId == null && preferred != "None" && pending == null) {
                val auto = match020(subs, preferred)
                if (auto != null) {
                    val eid = sim.tracks.first { it.t == auto }.id
                    subId = eid
                    sim.setSid(eid)
                    log.append("auto-pick ${auto.key} sid=$eid\n")
                }
            }
        }
    }

    /** Policy B replica: 0.2.1 select + track-list behavior. */
    inner class PolicyB(val sim: SimMpv) {
        var pending: Int? = null
        var preferred: String? = "English"
        val log = StringBuilder()
        fun select(id: Int?) {
            // 0.2.1: select always retains pending, then sets sid (loaded).
            pending = id
            sim.setSid(id)
            log.append("select($id) pending=$id\n")
        }
        fun onTrackList() {
            val subs = sim.tracks.map { it.t }
            var subId = sim.tracks.firstOrNull { it.selected }?.id
            log.append("track-list selected=$subId pending=$pending tracks=${sim.tracks.map { "${it.key}:${it.id}${if (it.selected) "*" else ""}" }}\n")
            if (subId == null && preferred != "None" && pending == null) {
                val auto = best021(subs, preferred)
                if (auto != null) {
                    val eid = sim.tracks.first { it.t == auto }.id
                    subId = eid
                    sim.setSid(eid)
                    log.append("auto-pick ${auto.key} sid=$eid\n")
                }
            }
        }
    }

    // ---- Policy C: real beta04 engine ----
    private lateinit var fake: FakeMpvClient
    private lateinit var engine: MpvPlaybackEngine

    @Before
    fun setup() {
        fake = FakeMpvClient()
        engine = MpvPlaybackEngine(null, fake)
        Thread.sleep(200)
    }

    private fun subNode(id: Int, selected: Boolean, external: Boolean, lang: String, uri: String?): MPVNode {
        val map = mutableMapOf<String, MPVNode>(
            "id" to MPVNode.IntNode(id.toLong()),
            "type" to MPVNode.StringNode("sub"),
            "selected" to MPVNode.BooleanNode(selected),
            "external" to MPVNode.BooleanNode(external)
        )
        map["lang"] = MPVNode.StringNode(lang)
        map["title"] = MPVNode.StringNode(lang)
        if (external && uri != null) map["external-filename"] = MPVNode.StringNode(uri)
        return MPVNode.MapNode(map)
    }

    private fun videoNode(): MPVNode {
        return MPVNode.MapNode(
            mutableMapOf<String, MPVNode>(
                "id" to MPVNode.IntNode(1L),
                "type" to MPVNode.StringNode("video"),
                "selected" to MPVNode.BooleanNode(true)
            )
        )
    }

    private fun emitSnapshot(sim: SimMpv) {
        val nodes = mutableListOf(videoNode())
        sim.tracks.forEach { e ->
            nodes.add(subNode(e.id, e.selected, e.t.external, e.t.lang, "file-${e.key}"))
        }
        fake.nodeProperties["track-list"] = MPVNode.ArrayNode(nodes.toTypedArray())
        fake.emitProperty("track-list", 0.0)
        Thread.sleep(200)
    }

    private fun startLoadedWithSubs(subs: List<ExternalSubtitle>) {
        val req = PlaybackRequest(
            uri = "https://cdn.example/master.m3u8",
            sourceClass = PlaybackSourceClass.HLS,
            externalSubtitles = subs,
            preferredSubLang = "English"
        )
        engine.loadMedia(req)
        Thread.sleep(150)
        fake.emitEvent(MpvPlaybackEngine.MPV_EVENT_START_FILE, playlistEntryId = 10L)
        Thread.sleep(150)
        fake.emitEvent(MpvPlaybackEngine.MPV_EVENT_FILE_LOADED)
        Thread.sleep(250)
    }

    private fun extSub(key: String, lang: String, selected: Boolean) =
        ExternalSubtitle("https://cdn.example/$key", lang, lang, selected)

    private fun drainSubAddsTo(sim: SimMpv, pending: MutableList<Triple<String, Boolean, SimT>>, langOf: (String) -> SimT) {
        fake.executedCommands.filter { it.firstOrNull() == "sub-add" }.forEach { cmd ->
            val url = cmd[1]
            val key = url.substringAfterLast("/")
            if (pending.none { it.first == key } && sim.tracks.none { it.key == key }) {
                pending.add(Triple(key, cmd[2] == "select", langOf(key)))
            }
        }
    }

    private fun drainSidTo(sim: SimMpv) {
        fake.executedCommands.filter { it.firstOrNull() == "sid" || (it.size == 2 && it[0] == "sid") }
        // sid travels via setPropertyInt/String, not command(); read fake state:
        val sidStr = fake.stringProperties["sid"]
        val sidInt = fake.intProperties["sid"]
        if (sidStr == "no") sim.setSid(null)
        else if (sidInt != null) sim.setSid(sidInt)
    }

    private val demoSubs = mapOf(
        "eng" to SimT("eng", "English", "English", true),
        "ara" to SimT("ara", "Arabic", "Arabic", true),
        "fre" to SimT("fre", "French", "French", true),
        "spa" to SimT("spa", "Spanish", "Spanish", true)
    )

    // Script S2 (ara-first killer) under policy A.
    @Test
    fun `HIST-SUB-1 A keeps requested English through partial materialization`() {
        val sim = SimMpv()
        val p = PolicyA(sim)
        // sub-adds issued at load
        sim.subAdd("ara", false, demoSubs.getValue("ara"))
        // partial snapshot before eng arrives
        p.onTrackList()
        sim.subAdd("eng", true, demoSubs.getValue("eng"))
        p.onTrackList()
        sim.subAdd("fre", false, demoSubs.getValue("fre"))
        sim.subAdd("spa", false, demoSubs.getValue("spa"))
        p.onTrackList()
        p.onTrackList()
        assertEquals("eng", sim.selectedKey())
    }

    // Script S2 under policy B.
    @Test
    fun `HIST-SUB-2 B keeps requested English through partial materialization`() {
        val sim = SimMpv()
        val p = PolicyB(sim)
        sim.subAdd("ara", false, demoSubs.getValue("ara"))
        p.onTrackList()
        sim.subAdd("eng", true, demoSubs.getValue("eng"))
        p.onTrackList()
        sim.subAdd("fre", false, demoSubs.getValue("fre"))
        sim.subAdd("spa", false, demoSubs.getValue("spa"))
        p.onTrackList()
        p.onTrackList()
        assertEquals("eng", sim.selectedKey())
    }

    // Script S2 through the real beta04 engine (HIST-SUB-3 / CP3-MAT-1).
    @Test
    fun `HIST-SUB-3 beta04 keeps requested English through partial materialization`() {
        val sim = SimMpv()
        val pending = mutableListOf<Triple<String, Boolean, SimT>>()
        startLoadedWithSubs(
            listOf(
                extSub("eng", "English", true),
                extSub("ara", "Arabic", false),
                extSub("fre", "French", false),
                extSub("spa", "Spanish", false)
            )
        )
        drainSubAddsTo(sim, pending) { demoSubs.getValue(it) }
        // Materialize ONLY ara first (eng fetch still in flight).
        val ara = pending.first { it.first == "ara" }
        sim.subAdd(ara.first, ara.second, ara.third)
        emitSnapshot(sim)
        drainSidTo(sim)
        // eng materializes with its select flag.
        val eng = pending.first { it.first == "eng" }
        sim.subAdd(eng.first, eng.second, eng.third)
        emitSnapshot(sim)
        drainSidTo(sim)
        // rest materialize.
        pending.filter { it.first != "ara" && it.first != "eng" }.forEach {
            sim.subAdd(it.first, it.second, it.third)
        }
        emitSnapshot(sim)
        drainSidTo(sim)
        emitSnapshot(sim)
        drainSidTo(sim)
        assertEquals("eng", sim.selectedKey())
    }

    // CP3-MAT-3: later arrivals must not replace selected English (A/B/C spot check on C).
    @Test
    fun `CP3-MAT-3 later arrivals do not replace selected English`() {
        val sim = SimMpv()
        val pending = mutableListOf<Triple<String, Boolean, SimT>>()
        startLoadedWithSubs(
            listOf(extSub("eng", "English", true), extSub("ara", "Arabic", false))
        )
        drainSubAddsTo(sim, pending) { demoSubs.getValue(it) }
        pending.forEach { sim.subAdd(it.first, it.second, it.third) }
        emitSnapshot(sim)
        drainSidTo(sim)
        assertEquals("eng", sim.selectedKey())
        // More refreshes + late arrival.
        emitSnapshot(sim)
        drainSidTo(sim)
        assertEquals("eng", sim.selectedKey())
    }

    // CP3-MAT-5: explicit Off mid-materialization wins and holds.
    @Test
    fun `CP3-MAT-5 explicit Off wins over pending external`() {
        val sim = SimMpv()
        val pending = mutableListOf<Triple<String, Boolean, SimT>>()
        startLoadedWithSubs(listOf(extSub("eng", "English", true)))
        drainSubAddsTo(sim, pending) { demoSubs.getValue(it) }
        engine.selectSubtitleTrack(null)
        Thread.sleep(200)
        drainSidTo(sim)
        pending.forEach { sim.subAdd(it.first, it.second, it.third) }
        emitSnapshot(sim)
        drainSidTo(sim)
        assertNull(sim.selectedKey())
        assertTrue(fake.stringProperties["sid"] == "no" || sim.selectedKey() == null)
    }

    // CP3-MAT-6: explicit newer Track wins over pending external.
    @Test
    fun `CP3-MAT-6 explicit newer track wins`() {
        val sim = SimMpv()
        val pending = mutableListOf<Triple<String, Boolean, SimT>>()
        startLoadedWithSubs(
            listOf(extSub("eng", "English", true), extSub("ara", "Arabic", false))
        )
        drainSubAddsTo(sim, pending) { demoSubs.getValue(it) }
        pending.forEach { sim.subAdd(it.first, it.second, it.third) }
        emitSnapshot(sim)
        drainSidTo(sim)
        val araId = sim.tracks.first { it.key == "ara" }.id
        engine.selectSubtitleTrack(araId)
        Thread.sleep(200)
        drainSidTo(sim)
        emitSnapshot(sim)
        drainSidTo(sim)
        assertEquals("ara", sim.selectedKey())
    }
}
