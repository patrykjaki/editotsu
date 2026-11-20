package ani.dantotsu.home

import ani.dantotsu.home.OneTapPlayPolicy.EpisodeEntry
import ani.dantotsu.home.OneTapPlayPolicy.EpisodeSavedProgress
import ani.dantotsu.home.OneTapPlayPolicy.FallbackReason
import ani.dantotsu.home.OneTapPlayPolicy.OneTapSourcePlan
import ani.dantotsu.home.OneTapPlayPolicy.RememberedPlayback
import ani.dantotsu.home.OneTapPlayPolicy.TargetEpisode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OneTapPlayPolicyTest {

    private val sources = listOf("SourceA", "SourceB", "Local", "Downloaded")
    private val downloadedIndices = setOf(3)

    private fun remembered(
        sourceIndex: Int = 0,
        sourceName: String? = "SourceA",
        langIndex: Int = 0,
        preferDub: Boolean = false,
        serverName: String? = "Server 1",
        videoIndex: Int = 0,
        exactKey: String? = null,
        hasFamily: Boolean = false,
    ) = RememberedPlayback(
        sourceIndex = sourceIndex,
        sourceName = sourceName,
        langIndex = langIndex,
        preferDub = preferDub,
        serverName = serverName,
        videoIndex = videoIndex,
        exactKey = exactKey,
        hasFamily = hasFamily,
    )

    private fun numberedEpisodes(range: IntRange): List<EpisodeEntry> =
        range.map { EpisodeEntry(key = it.toString(), number = it.toString()) }

    // --- Source decision ---

    @Test
    fun `offline immediately falls back without forcing picker`() {
        val plan = OneTapPlayPolicy.decideSource(
            isOffline = true,
            isLocalMedia = false,
            sourcesReady = true,
            sourceNames = sources,
            downloadedSourceIndices = downloadedIndices,
            remembered = remembered(),
        )
        assertEquals(OneTapSourcePlan.Fallback(FallbackReason.OFFLINE, forcePicker = false), plan)
    }

    @Test
    fun `missing remembered Selected falls back to picker`() {
        val plan = OneTapPlayPolicy.decideSource(
            isOffline = false,
            isLocalMedia = false,
            sourcesReady = true,
            sourceNames = sources,
            downloadedSourceIndices = downloadedIndices,
            remembered = null,
        )
        assertEquals(OneTapSourcePlan.Fallback(FallbackReason.NO_REMEMBERED_SOURCE, forcePicker = true), plan)
    }

    @Test
    fun `selected without server exact key or family falls back`() {
        val plan = OneTapPlayPolicy.decideSource(
            isOffline = false,
            isLocalMedia = false,
            sourcesReady = true,
            sourceNames = sources,
            downloadedSourceIndices = downloadedIndices,
            remembered = remembered(serverName = null, exactKey = null, hasFamily = false),
        )
        assertEquals(OneTapSourcePlan.Fallback(FallbackReason.NO_REMEMBERED_STREAM, forcePicker = true), plan)
    }

    @Test
    fun `remembered source resolved by name even when extensions were reordered`() {
        val plan = OneTapPlayPolicy.decideSource(
            isOffline = false,
            isLocalMedia = false,
            sourcesReady = true,
            sourceNames = listOf("SourceB", "SourceA", "Local", "Downloaded"),
            downloadedSourceIndices = setOf(3),
            remembered = remembered(sourceIndex = 0, sourceName = "SourceA", serverName = "Mirror 1"),
        )
        assertEquals(
            OneTapSourcePlan.Resolve(
                resolvedSourceIndex = 1,
                isDownloadedSource = false,
                serverName = "Mirror 1",
                hasExactKey = false,
                hasFamily = false,
            ),
            plan,
        )
    }

    @Test
    fun `uninstalled remembered source name does not fall back to another extension at the same index`() {
        val plan = OneTapPlayPolicy.decideSource(
            isOffline = false,
            isLocalMedia = false,
            sourcesReady = true,
            sourceNames = listOf("SourceB", "Local", "Downloaded"),
            downloadedSourceIndices = setOf(2),
            remembered = remembered(sourceIndex = 0, sourceName = "RemovedSource"),
        )
        assertEquals(OneTapSourcePlan.Fallback(FallbackReason.SOURCE_MISSING, forcePicker = true), plan)
    }

    @Test
    fun `legacy saved index without source name still resolves when in bounds`() {
        val plan = OneTapPlayPolicy.decideSource(
            isOffline = false,
            isLocalMedia = false,
            sourcesReady = true,
            sourceNames = sources,
            downloadedSourceIndices = downloadedIndices,
            remembered = remembered(sourceIndex = 1, sourceName = null, serverName = "Mirror 2"),
        )
        assertEquals(
            OneTapSourcePlan.Resolve(
                resolvedSourceIndex = 1,
                isDownloadedSource = false,
                serverName = "Mirror 2",
                hasExactKey = false,
                hasFamily = false,
            ),
            plan,
        )
    }

    @Test
    fun `remembered family alone is enough to attempt resolution`() {
        val plan = OneTapPlayPolicy.decideSource(
            isOffline = false,
            isLocalMedia = false,
            sourcesReady = true,
            sourceNames = sources,
            downloadedSourceIndices = downloadedIndices,
            remembered = remembered(serverName = null, exactKey = null, hasFamily = true),
        )
        assertEquals(
            OneTapSourcePlan.Resolve(
                resolvedSourceIndex = 0,
                isDownloadedSource = false,
                serverName = null,
                hasExactKey = false,
                hasFamily = true,
            ),
            plan,
        )
    }

    @Test
    fun `remembered exact key alone is enough to attempt resolution`() {
        val plan = OneTapPlayPolicy.decideSource(
            isOffline = false,
            isLocalMedia = false,
            sourcesReady = true,
            sourceNames = sources,
            downloadedSourceIndices = downloadedIndices,
            remembered = remembered(serverName = null, exactKey = "release:abc", hasFamily = false),
        )
        assertEquals(
            OneTapSourcePlan.Resolve(
                resolvedSourceIndex = 0,
                isDownloadedSource = false,
                serverName = null,
                hasExactKey = true,
                hasFamily = false,
            ),
            plan,
        )
    }

    @Test
    fun `downloaded source resolves even without server name`() {
        val plan = OneTapPlayPolicy.decideSource(
            isOffline = false,
            isLocalMedia = false,
            sourcesReady = true,
            sourceNames = sources,
            downloadedSourceIndices = downloadedIndices,
            remembered = remembered(sourceIndex = 3, sourceName = "Downloaded", serverName = null),
        )
        assertEquals(
            OneTapSourcePlan.Resolve(
                resolvedSourceIndex = 3,
                isDownloadedSource = true,
                serverName = null,
                hasExactKey = false,
                hasFamily = false,
            ),
            plan,
        )
    }

    @Test
    fun `local source and uninitialized sources fall back`() {
        val localPlan = OneTapPlayPolicy.decideSource(
            isOffline = false,
            isLocalMedia = false,
            sourcesReady = true,
            sourceNames = sources,
            downloadedSourceIndices = downloadedIndices,
            remembered = remembered(sourceIndex = 2, sourceName = "Local"),
        )
        assertEquals(OneTapSourcePlan.Fallback(FallbackReason.LOCAL_MEDIA, forcePicker = true), localPlan)

        val notReadyPlan = OneTapPlayPolicy.decideSource(
            isOffline = false,
            isLocalMedia = false,
            sourcesReady = false,
            sourceNames = emptyList(),
            downloadedSourceIndices = emptySet(),
            remembered = remembered(),
        )
        assertEquals(OneTapSourcePlan.Fallback(FallbackReason.SOURCES_NOT_READY, forcePicker = true), notReadyPlan)
    }

    // --- Target episode decision ---

    @Test
    fun `starts progress plus one when nothing is left part-way`() {
        val target = OneTapPlayPolicy.chooseTargetEpisode(
            episodes = numberedEpisodes(1..12),
            userProgress = 4,
            currentEpKey = null,
            watchPercentage = 0.8f,
            savedProgress = { null },
        )
        assertEquals(TargetEpisode("5", resuming = false), target)
    }

    @Test
    fun `starts episode 1 when userProgress is zero or null`() {
        val target = OneTapPlayPolicy.chooseTargetEpisode(
            episodes = numberedEpisodes(1..12),
            userProgress = 0,
            currentEpKey = null,
            watchPercentage = 0.8f,
            savedProgress = { null },
        )
        assertEquals(TargetEpisode("1", resuming = false), target)
    }

    @Test
    fun `resumes next episode when left part-way`() {
        val saved = mapOf("5" to EpisodeSavedProgress(savedMs = 600_000L, maxMs = 1_440_000L))
        val target = OneTapPlayPolicy.chooseTargetEpisode(
            episodes = numberedEpisodes(1..12),
            userProgress = 4,
            currentEpKey = "5",
            watchPercentage = 0.8f,
            savedProgress = { saved[it] },
        )
        assertEquals(TargetEpisode("5", resuming = true), target)
    }

    @Test
    fun `resumes current progress episode when left part-way`() {
        val saved = mapOf("4" to EpisodeSavedProgress(savedMs = 450_000L, maxMs = 1_440_000L))
        val target = OneTapPlayPolicy.chooseTargetEpisode(
            episodes = numberedEpisodes(1..12),
            userProgress = 4,
            currentEpKey = "4",
            watchPercentage = 0.8f,
            savedProgress = { saved[it] },
        )
        assertEquals(TargetEpisode("4", resuming = true), target)
    }

    @Test
    fun `resumes when savedMs exists even without duration maxMs`() {
        val saved = mapOf("5" to EpisodeSavedProgress(savedMs = 300_000L, maxMs = null))
        val target = OneTapPlayPolicy.chooseTargetEpisode(
            episodes = numberedEpisodes(1..12),
            userProgress = 4,
            currentEpKey = "5",
            watchPercentage = 0.8f,
            savedProgress = { saved[it] },
        )
        assertEquals(TargetEpisode("5", resuming = true), target)
    }

    @Test
    fun `moves to progress plus one when current progress episode passed watch percentage`() {
        val saved = mapOf("4" to EpisodeSavedProgress(savedMs = 1_300_000L, maxMs = 1_440_000L))
        val target = OneTapPlayPolicy.chooseTargetEpisode(
            episodes = numberedEpisodes(1..12),
            userProgress = 4,
            currentEpKey = "4",
            watchPercentage = 0.8f,
            savedProgress = { saved[it] },
        )
        assertEquals(TargetEpisode("5", resuming = false), target)
    }

    @Test
    fun `advances past locally finished next episode when tracker was not updated`() {
        val saved = mapOf("5" to EpisodeSavedProgress(savedMs = 1_350_000L, maxMs = 1_440_000L))
        val target = OneTapPlayPolicy.chooseTargetEpisode(
            episodes = numberedEpisodes(1..12),
            userProgress = 4,
            currentEpKey = "5",
            watchPercentage = 0.8f,
            savedProgress = { saved[it] },
        )
        assertEquals(TargetEpisode("6", resuming = false), target)
    }

    @Test
    fun `ignores stale current episode behind userProgress`() {
        val saved = mapOf("2" to EpisodeSavedProgress(savedMs = 300_000L, maxMs = 1_440_000L))
        val target = OneTapPlayPolicy.chooseTargetEpisode(
            episodes = numberedEpisodes(1..12),
            userProgress = 5,
            currentEpKey = "2",
            watchPercentage = 0.8f,
            savedProgress = { saved[it] },
        )
        assertEquals(TargetEpisode("6", resuming = false), target)
    }

    @Test
    fun `rewatching with old finished last episode still starts progress plus one`() {
        val saved = mapOf("12" to EpisodeSavedProgress(savedMs = 1_400_000L, maxMs = 1_440_000L))
        val target = OneTapPlayPolicy.chooseTargetEpisode(
            episodes = numberedEpisodes(1..12),
            userProgress = 2,
            currentEpKey = "12",
            watchPercentage = 0.8f,
            savedProgress = { saved[it] },
        )
        assertEquals(TargetEpisode("3", resuming = false), target)
    }

    @Test
    fun `returns null when all available episodes are already watched`() {
        val target = OneTapPlayPolicy.chooseTargetEpisode(
            episodes = numberedEpisodes(1..12),
            userProgress = 12,
            currentEpKey = "12",
            watchPercentage = 0.8f,
            savedProgress = { mapOf("12" to EpisodeSavedProgress(1_400_000L, 1_440_000L))[it] },
        )
        assertNull(target)
    }

    @Test
    fun `returns null when next episode number is missing instead of silently skipping it`() {
        val episodes = listOf(1, 2, 3, 5).map { EpisodeEntry(it.toString(), it.toString()) }
        val target = OneTapPlayPolicy.chooseTargetEpisode(
            episodes = episodes,
            userProgress = 3,
            currentEpKey = null,
            watchPercentage = 0.8f,
            savedProgress = { null },
        )
        assertNull(target)
    }

    @Test
    fun `season two numbered from 13 falls back to index by userProgress`() {
        val target = OneTapPlayPolicy.chooseTargetEpisode(
            episodes = numberedEpisodes(13..24),
            userProgress = 1,
            currentEpKey = null,
            watchPercentage = 0.8f,
            savedProgress = { null },
        )
        assertEquals(TargetEpisode("14", resuming = false), target)
    }

    @Test
    fun `scanlator filtering drops hidden seasons and matches suffixed keys`() {
        val episodes = listOf(
            EpisodeEntry("1-Season 1", "1", "Season 1"),
            EpisodeEntry("2-Season 1", "2", "Season 1"),
            EpisodeEntry("1-Season 2", "1", "Season 2"),
            EpisodeEntry("2-Season 2", "2", "Season 2"),
        )
        val filtered = OneTapPlayPolicy.filterByScanlators(episodes, hiddenScanlators = listOf("Season 1"))
        assertEquals(listOf("1-Season 2", "2-Season 2"), filtered.map { it.key })

        val target = OneTapPlayPolicy.chooseTargetEpisode(
            episodes = filtered,
            userProgress = 1,
            currentEpKey = null,
            watchPercentage = 0.8f,
            savedProgress = { null },
        )
        assertEquals(TargetEpisode("2-Season 2", resuming = false), target)
    }

    // --- Playability ---

    @Test
    fun `playability gates blank urls and magnet links without torrent engine`() {
        assertTrue(OneTapPlayPolicy.isDirectlyPlayable("https://example.invalid/ep5.m3u8", false, false))
        assertFalse(OneTapPlayPolicy.isDirectlyPlayable("", false, true))
        assertFalse(OneTapPlayPolicy.isDirectlyPlayable("null", false, true))
        assertTrue(OneTapPlayPolicy.isDirectlyPlayable("", isOfflineServer = true, torrentAvailable = false))
        assertFalse(OneTapPlayPolicy.isDirectlyPlayable("magnet:?xt=urn:btih:1234", false, torrentAvailable = false))
        assertTrue(OneTapPlayPolicy.isDirectlyPlayable("magnet:?xt=urn:btih:1234", false, torrentAvailable = true))
    }
}
