package ani.dantotsu.home

import android.content.Intent
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.media3.common.util.UnstableApi
import ani.dantotsu.FileUrl
import ani.dantotsu.isOnline
import ani.dantotsu.media.Media
import ani.dantotsu.media.MediaDetailsActivity
import ani.dantotsu.media.Selected
import ani.dantotsu.media.SelectedSource
import ani.dantotsu.media.anime.Episode
import ani.dantotsu.media.anime.ExoplayerView
import ani.dantotsu.media.anime.player.EpisodeResumeKeys
import ani.dantotsu.media.anime.resumePositionKey
import ani.dantotsu.media.anime.selector.FamilyPayload
import ani.dantotsu.media.anime.selector.RecoveryCoordinator
import ani.dantotsu.media.anime.selector.RecoveryResult
import ani.dantotsu.media.anime.selector.RememberedSelectionPolicy
import ani.dantotsu.media.anime.selector.SelectedFamilyStore
import ani.dantotsu.media.anime.selector.SelectedKeyStore
import ani.dantotsu.media.anime.selector.SourceLink
import ani.dantotsu.media.anime.selector.StableCandidateBuilder
import ani.dantotsu.parsers.AnimeParser
import ani.dantotsu.parsers.AnimeSources
import ani.dantotsu.parsers.DynamicAnimeParser
import ani.dantotsu.parsers.HAnimeSources
import ani.dantotsu.parsers.VideoExtractor
import ani.dantotsu.parsers.WatchSources
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.snackString
import ani.dantotsu.torrent.TorrentLinks
import ani.dantotsu.torrent.TorrentServerManager
import ani.dantotsu.util.Logger
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.Serializable
import java.util.Collections

/**
 * Resolves a one-tap play request from a Continue Watching card on Home.
 *
 * Uses only the show's existing persisted source/release memory. On success it starts
 * [ExoplayerView] directly; on any missing memory, offline state, timeout, or resolution
 * failure it falls back to opening the Watch tab / source picker for that episode.
 * Never writes to the user's AniList / MAL list.
 */
class OneTapPlayCoordinator(
    private val onLoadingChanged: (mediaId: Int?) -> Unit,
) {
    private var activeMediaId: Int? = null
    private var activeJob: Job? = null

    val loadingMediaId: Int? get() = activeMediaId

    fun cancel() {
        activeJob?.cancel()
        activeJob = null
        if (activeMediaId != null) {
            activeMediaId = null
            onLoadingChanged(null)
        }
    }

    @OptIn(UnstableApi::class)
    fun play(
        activity: FragmentActivity,
        scope: CoroutineScope,
        media: Media,
        isHostActive: () -> Boolean,
    ) {
        if (media.anime == null || media.id < 0) return
        // Second tap on the same card while resolving is ignored.
        if (activeMediaId == media.id && activeJob?.isActive == true) return

        // Tapping a different card supersedes any previous in-flight resolve.
        activeJob?.cancel()
        activeMediaId = media.id
        onLoadingChanged(media.id)

        val job = scope.launch {
            try {
                val outcome = withContext(Dispatchers.IO) {
                    withTimeoutOrNull(RESOLVE_TIMEOUT_MS) {
                        resolvePlayable(activity, media)
                    } ?: ResolveOutcome.Fallback(episodeKey = null, forcePicker = true)
                }
                if (!isHostActive() || activity.isFinishing || activity.isDestroyed) return@launch
                when (outcome) {
                    is ResolveOutcome.Ready -> {
                        ExoplayerView.media = outcome.media
                        ExoplayerView.initialized = true
                        activity.startActivity(Intent(activity, ExoplayerView::class.java))
                    }
                    is ResolveOutcome.Fallback -> {
                        outcome.message?.let { snackString(it) }
                        openPickerFallback(
                            activity = activity,
                            media = media,
                            episodeKey = outcome.episodeKey,
                            forcePicker = outcome.forcePicker,
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.log("OneTapPlay failed for media=${media.id}: ${e.message}")
                if (isHostActive() && !activity.isFinishing && !activity.isDestroyed) {
                    openPickerFallback(activity, media, episodeKey = null, forcePicker = true)
                }
            } finally {
                if (activeMediaId == media.id) {
                    activeMediaId = null
                    activeJob = null
                    onLoadingChanged(null)
                }
            }
        }
        activeJob = job
    }

    private sealed interface ResolveOutcome {
        data class Ready(val media: Media) : ResolveOutcome
        data class Fallback(
            val episodeKey: String?,
            val forcePicker: Boolean,
            val message: String? = null,
        ) : ResolveOutcome
    }

    private suspend fun resolvePlayable(activity: FragmentActivity, media: Media): ResolveOutcome {
        val offline = !isOnline(activity) || PrefManager.getVal<Boolean>(PrefName.OfflineMode)
        val isLocal = media.format == "LOCAL"
        val savedSelected = PrefManager.getNullableCustomVal(
            "Selected-${media.id}",
            null,
            Selected::class.java,
        )

        if (!offline && !isLocal && savedSelected != null && !AnimeSources.isInitialized) {
            awaitSourcesReady()
        }

        val watchSources: WatchSources = if (media.isAdult) HAnimeSources else AnimeSources
        val sourceNames = watchSources.names
        val downloadedIndices = sourceNames.indices.filterTo(mutableSetOf()) {
            watchSources.isDownloadedSource(it)
        }

        val savedSourceName = PrefManager.getNullableCustomVal(
            "${SelectedSource.KEY}${media.id}",
            null,
            String::class.java,
        )
        val storedExactKey = SelectedKeyStore.load(media.id)
        val storedFamily = SelectedFamilyStore.load(media.id)

        val remembered = savedSelected?.let { sel ->
            OneTapPlayPolicy.RememberedPlayback(
                sourceIndex = sel.sourceIndex,
                sourceName = savedSourceName,
                langIndex = sel.langIndex,
                preferDub = sel.preferDub,
                serverName = sel.server,
                videoIndex = sel.video,
                exactKey = storedExactKey,
                hasFamily = storedFamily != null,
                hiddenScanlators = sel.scanlators.orEmpty(),
            )
        }

        val plan = when (
            val decision = OneTapPlayPolicy.decideSource(
                isOffline = offline,
                isLocalMedia = isLocal,
                sourcesReady = AnimeSources.isInitialized,
                sourceNames = sourceNames,
                downloadedSourceIndices = downloadedIndices,
                remembered = remembered,
            )
        ) {
            is OneTapPlayPolicy.OneTapSourcePlan.Fallback ->
                return ResolveOutcome.Fallback(episodeKey = null, forcePicker = decision.forcePicker)
            is OneTapPlayPolicy.OneTapSourcePlan.Resolve -> decision
        }
        val selectedCopy = savedSelected!!.copy(sourceIndex = plan.resolvedSourceIndex)

        val playMedia = media.copy(
            anime = media.anime?.copy(),
            selected = selectedCopy,
        )

        val parser = watchSources[plan.resolvedSourceIndex]
        (parser as? DynamicAnimeParser)?.sourceLanguage = selectedCopy.langIndex
        parser.selectDub = selectedCopy.preferDub

        val watchPercentage = PrefManager.getVal<Float>(PrefName.WatchPercentage)
        val currentEpKey = PrefManager.getNullableCustomVal(
            "${media.id}_current_ep",
            null,
            String::class.java,
        )

        // Try the episode cache first when safe so cached/finished shows skip an extra network round-trip.
        val cacheKey = parser.saveName.ifBlank { parser.name }
        val cachedEpisodes = if (!plan.isDownloadedSource) {
            WatchSources.episodeCache.load(cacheKey, media.id)
        } else null

        if (!cachedEpisodes.isNullOrEmpty()) {
            val cachedFiltered = filterEpisodes(cachedEpisodes, selectedCopy.scanlators.orEmpty())
            val cachedTarget = pickTarget(cachedFiltered, media.id, media.userProgress, currentEpKey, watchPercentage)
            val isReleasingLastEp = media.status == "RELEASING" &&
                cachedTarget != null &&
                cachedFiltered.keys.lastOrNull() == cachedTarget.key
            if (cachedTarget != null && !isReleasingLastEp) {
                val cachedOutcome = resolveEpisodeStream(
                    playMedia = playMedia,
                    watchSources = watchSources,
                    parser = parser,
                    plan = plan,
                    selected = selectedCopy,
                    storedExactKey = storedExactKey,
                    storedFamily = storedFamily,
                    episodes = cachedFiltered,
                    targetKey = cachedTarget.key,
                )
                if (cachedOutcome is ResolveOutcome.Ready) return cachedOutcome
                if (cachedOutcome is ResolveOutcome.Fallback && cachedOutcome.message != null) {
                    return cachedOutcome
                }
            }
        }

        // Fresh episode list from the remembered source.
        val freshEpisodes = watchSources.loadEpisodesFromMedia(plan.resolvedSourceIndex, playMedia)
        if (freshEpisodes.isEmpty()) {
            return ResolveOutcome.Fallback(episodeKey = null, forcePicker = true)
        }
        val filteredEpisodes = filterEpisodes(freshEpisodes, selectedCopy.scanlators.orEmpty())
        val target = pickTarget(filteredEpisodes, media.id, media.userProgress, currentEpKey, watchPercentage)
            ?: return ResolveOutcome.Fallback(episodeKey = null, forcePicker = true)

        return resolveEpisodeStream(
            playMedia = playMedia,
            watchSources = watchSources,
            parser = parser,
            plan = plan,
            selected = selectedCopy,
            storedExactKey = storedExactKey,
            storedFamily = storedFamily,
            episodes = filteredEpisodes,
            targetKey = target.key,
        )
    }

    private suspend fun resolveEpisodeStream(
        playMedia: Media,
        watchSources: WatchSources,
        parser: AnimeParser,
        plan: OneTapPlayPolicy.OneTapSourcePlan.Resolve,
        selected: Selected,
        storedExactKey: String?,
        storedFamily: FamilyPayload?,
        episodes: MutableMap<String, Episode>,
        targetKey: String,
    ): ResolveOutcome {
        val ep = episodes[targetKey] ?: return ResolveOutcome.Fallback(targetKey, forcePicker = true)
        val link = ep.link ?: return ResolveOutcome.Fallback(targetKey, forcePicker = true)
        val sEpisode = ep.sEpisode ?: return ResolveOutcome.Fallback(targetKey, forcePicker = true)
        val makeDefault = PrefManager.getVal<Boolean>(PrefName.MakeDefault)

        var chosenExtractor: VideoExtractor? = null
        var chosenVideoIndex = 0
        var resolvedExactKey: String? = null
        var resolvedViaRecovery = false

        if (plan.isDownloadedSource) {
            val all = loadAllExtractors(parser, link, ep.extra, sEpisode)
            val first = all.firstOrNull { it.videos.isNotEmpty() }
            if (first != null) {
                ep.extractors = all
                ep.allStreams = true
                chosenExtractor = first
                chosenVideoIndex = 0
            }
        } else if (plan.serverName != null && !plan.hasExactKey) {
            val single = parser.loadSingleVideoServer(plan.serverName, link, ep.extra, sEpisode, post = false)
            if (single != null && single.videos.isNotEmpty()) {
                val exactNameMatch = single.server.name == plan.serverName
                if (exactNameMatch || !plan.hasFamily) {
                    val idx = if (selected.video in single.videos.indices) selected.video else 0
                    val url = single.videos[idx].file.url
                    if (OneTapPlayPolicy.isDirectlyPlayable(url, single.server.offline, torrentAvailable = true)) {
                        ep.extractors = mutableListOf(single)
                        ep.allStreams = false
                        chosenExtractor = single
                        chosenVideoIndex = idx
                        if (makeDefault && exactNameMatch) {
                            val provider = StableCandidateBuilder.providerOf(single.server)
                            val exact = StableCandidateBuilder.exactKey(provider, single.server)
                            SelectedKeyStore.save(playMedia.id, exact)
                        }
                    }
                }
            }
        }

        // Release-based sources (exact key / family) or fallback recovery when single-server missed.
        if (chosenExtractor == null && (plan.hasExactKey || plan.hasFamily)) {
            val all = loadAllExtractors(parser, link, ep.extra, sEpisode)
            if (all.isNotEmpty()) {
                val recovery = RecoveryCoordinator.resolve(
                    storedExactKey = storedExactKey,
                    storedFamily = storedFamily,
                    selectedName = if (plan.hasExactKey) null else plan.serverName,
                    servers = all.map { it.server },
                )
                if (recovery is RecoveryResult.Resolved) {
                    val matched = all.firstOrNull { it.server === recovery.server }
                        ?: all.firstOrNull { it.server.name == recovery.server.name }
                    if (matched != null && matched.videos.isNotEmpty()) {
                        ep.extractors = all
                        ep.allStreams = true
                        chosenExtractor = matched
                        chosenVideoIndex = if (
                            matched.server.name == plan.serverName &&
                            selected.video in matched.videos.indices
                        ) selected.video else 0
                        resolvedExactKey = recovery.exactKey
                        resolvedViaRecovery = true
                    }
                }
            }
        }

        val extractor = chosenExtractor
            ?: return ResolveOutcome.Fallback(targetKey, forcePicker = true)
        var picked = extractor.videos.getOrNull(chosenVideoIndex)
            ?: return ResolveOutcome.Fallback(targetKey, forcePicker = true)

        // Direct link check for torrent releases whose source opts in (same contract as SelectorDialogFragment).
        val asked = extractor.server.video
        val httpSource = (parser as? DynamicAnimeParser)
            ?.extension?.sources?.getOrNull(parser.sourceLanguage) as? AnimeHttpSource
        if (asked != null && httpSource != null && SourceLink.wanted(asked.internalData, picked.file.url)) {
            val answer = withTimeoutOrNull(SourceLink.TIMEOUT_MS) {
                runCatching { httpSource.resolveVideo(asked)?.videoUrl }
            }
            val refusal = SourceLink.refusal(answer?.exceptionOrNull())
            if (refusal != null) {
                return ResolveOutcome.Fallback(targetKey, forcePicker = true, message = refusal)
            }
            val directLink = answer?.getOrNull()
            if (SourceLink.usable(directLink)) {
                picked = picked.copy(file = FileUrl(directLink!!, picked.file.headers))
                extractor.videos = extractor.videos.toMutableList().also {
                    it[chosenVideoIndex] = picked
                }
            }
        }

        val finalUrl = picked.file.url
        val torrentManager = Injekt.get<TorrentServerManager>()
        val torrentAvailable = torrentManager.isAvailable()
        if (!OneTapPlayPolicy.isDirectlyPlayable(finalUrl, extractor.server.offline, torrentAvailable)) {
            return ResolveOutcome.Fallback(targetKey, forcePicker = true)
        }

        if (finalUrl.startsWith("magnet:", ignoreCase = true) || finalUrl.endsWith(".torrent", ignoreCase = true)) {
            val index = TorrentLinks.fileIndex(finalUrl)
            torrentManager.prebufferTorrent(finalUrl, index, picked.quality.toString())
        }

        // Apply the same remembered-selection persistence as SelectorDialogFragment auto-recovery.
        if (resolvedViaRecovery) {
            val persistence = RememberedSelectionPolicy.autoRecoveryPersistence(makeDefault, resolvedExactKey)
            if (persistence.persistLegacyServer) {
                selected.server = extractor.server.name
                selected.video = chosenVideoIndex
                PrefManager.setCustomVal("Selected-${playMedia.id}", selected)
                val name = SelectedSource.nameFor(
                    selected.sourceIndex,
                    watchSources.names,
                    AnimeSources.isInitialized,
                )
                if (name != null) PrefManager.setCustomVal("${SelectedSource.KEY}${playMedia.id}", name)
            }
            when (persistence.exactAction) {
                RememberedSelectionPolicy.ExactSlotAction.WRITE ->
                    SelectedKeyStore.save(playMedia.id, resolvedExactKey)
                RememberedSelectionPolicy.ExactSlotAction.CLEAR ->
                    SelectedKeyStore.save(playMedia.id, null)
                RememberedSelectionPolicy.ExactSlotAction.NO_CHANGE -> Unit
            }
        }

        ep.selectedExtractor = extractor.server.name
        ep.selectedVideo = chosenVideoIndex
        playMedia.selected = selected
        playMedia.anime?.episodes = episodes
        playMedia.anime?.selectedEpisode = targetKey
        return ResolveOutcome.Ready(playMedia)
    }

    private suspend fun loadAllExtractors(
        parser: AnimeParser,
        link: String,
        extra: Map<String, String>?,
        sEpisode: eu.kanade.tachiyomi.animesource.model.SEpisode,
    ): MutableList<VideoExtractor> {
        val collected = Collections.synchronizedList(mutableListOf<VideoExtractor>())
        parser.loadByVideoServers(link, extra, sEpisode) { extractor ->
            if (extractor.videos.isNotEmpty()) {
                collected.add(extractor)
            }
        }
        return synchronized(collected) { collected.toMutableList() }
    }

    private suspend fun awaitSourcesReady() {
        var remaining = SOURCES_WAIT_MS
        while (remaining > 0L && !AnimeSources.isInitialized) {
            delay(50L)
            remaining -= 50L
        }
    }

    private fun openPickerFallback(
        activity: FragmentActivity,
        media: Media,
        episodeKey: String?,
        forcePicker: Boolean,
    ) {
        val intent = Intent(activity, MediaDetailsActivity::class.java)
            .putExtra("media", media as Serializable)
            .putExtra(MediaDetailsActivity.EXTRA_OPEN_PICKER, true)
            .putExtra(MediaDetailsActivity.EXTRA_FORCE_PICKER, forcePicker)
        if (!episodeKey.isNullOrBlank()) {
            intent.putExtra(MediaDetailsActivity.EXTRA_PICKER_EPISODE, episodeKey)
        }
        ContextCompat.startActivity(activity, intent, null)
    }

    companion object {
        private const val RESOLVE_TIMEOUT_MS = 25_000L
        private const val SOURCES_WAIT_MS = 3_000L

        internal fun filterEpisodes(
            episodes: MutableMap<String, Episode>,
            hiddenScanlators: List<String>,
        ): MutableMap<String, Episode> {
            val entries = episodes.map { (key, ep) ->
                OneTapPlayPolicy.EpisodeEntry(key, ep.number, ep.sEpisode?.scanlator)
            }
            val keptKeys = OneTapPlayPolicy.filterByScanlators(entries, hiddenScanlators)
                .mapTo(mutableSetOf()) { it.key }
            if (keptKeys.size == episodes.size) return episodes
            val out = LinkedHashMap<String, Episode>(keptKeys.size)
            for ((k, v) in episodes) {
                if (k in keptKeys) out[k] = v
            }
            return out
        }

        internal fun pickTarget(
            episodes: Map<String, Episode>,
            mediaId: Int,
            userProgress: Int?,
            currentEpKey: String?,
            watchPercentage: Float,
        ): OneTapPlayPolicy.TargetEpisode? {
            val keys = episodes.keys
            val entries = episodes.map { (key, ep) ->
                OneTapPlayPolicy.EpisodeEntry(key, ep.number, ep.sEpisode?.scanlator)
            }
            return OneTapPlayPolicy.chooseTargetEpisode(
                episodes = entries,
                userProgress = userProgress,
                currentEpKey = currentEpKey,
                watchPercentage = watchPercentage,
                savedProgress = { key -> loadSavedEpisodeProgress(mediaId, key, keys) },
            )
        }

        private fun loadSavedEpisodeProgress(
            mediaId: Int,
            key: String,
            allKeys: Set<String>,
        ): OneTapPlayPolicy.EpisodeSavedProgress? {
            val alias = EpisodeResumeKeys.compatibleAlias(key, allKeys)
            val savedMs = PrefManager.getCustomVal(resumePositionKey(mediaId, key), 0L)
                .takeIf { it > 0L }
                ?: alias?.let {
                    PrefManager.getCustomVal(resumePositionKey(mediaId, it), 0L).takeIf { v -> v > 0L }
                }
                ?: return null
            val maxMs = PrefManager.getCustomVal("${mediaId}_${key}_max", Long.MAX_VALUE)
                .takeIf { it != Long.MAX_VALUE && it > 0L }
                ?: alias?.let {
                    PrefManager.getCustomVal("${mediaId}_${it}_max", Long.MAX_VALUE)
                        .takeIf { v -> v != Long.MAX_VALUE && v > 0L }
                }
            return OneTapPlayPolicy.EpisodeSavedProgress(savedMs = savedMs, maxMs = maxMs)
        }
    }
}
