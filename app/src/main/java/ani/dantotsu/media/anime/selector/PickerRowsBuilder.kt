package ani.dantotsu.media.anime.selector

import ani.dantotsu.parsers.Video
import ani.dantotsu.parsers.VideoExtractor

/**
 * Builds the picker rows for a snapshot of extractors. Summaries and
 * remember facts are cached per server/video text, so a new extractor
 * callback only parses the new extractor's rows (the old code re-parsed
 * every row on every callback, on the main thread).
 *
 * Not thread-safe: one instance is used by one worker at a time.
 */
class PickerRowsBuilder(
    private val summarize: (VideoExtractor, Video) -> SourceRowViewModel = ::defaultSummarize,
) {
    private data class ViewKey(
        val serverName: String,
        val embedUrl: String,
        val videoUrl: String,
        val format: String?,
        val quality: Int?,
        val internalData: String?,
    )

    private data class ServerKey(val serverName: String, val embedUrl: String, val provider: String?)

    private class ServerFacts(val exact: String?, val family: FamilyCandidate?)

    private val views = HashMap<ViewKey, SourceRowViewModel>()
    private val fields = HashMap<ViewKey, SourceRowClassifier.ReleaseFields?>()
    private val servers = HashMap<ServerKey, ServerFacts>()

    /** Summarizer calls made so far (tests check that cached rows are not parsed again). */
    var summarizeCalls = 0
        private set

    fun clear() {
        views.clear()
        fields.clear()
        servers.clear()
    }

    /** Rows plus the summary of each row, so binding never parses again. */
    class Built(val rows: List<PickerRow>, val views: Map<RowRef, SourceRowViewModel>)

    fun build(
        links: List<VideoExtractor>,
        storedKey: String?,
        storedFamily: FamilyPayload?,
        fallbackProvider: String?,
    ): List<PickerRow> = buildWithViews(links, storedKey, storedFamily, fallbackProvider).rows

    fun buildWithViews(
        links: List<VideoExtractor>,
        storedKey: String?,
        storedFamily: FamilyPayload?,
        fallbackProvider: String?,
    ): Built {
        val rows = mutableListOf<PickerRow>()
        val rowViews = HashMap<RowRef, SourceRowViewModel>()
        links.forEachIndexed { ei, extractor ->
            val server = extractor.server
            val provider = StableCandidateBuilder.providerOf(server)
            val facts = servers.getOrPut(ServerKey(server.name, server.embed.url, provider)) {
                runCatching {
                    ServerFacts(
                        exact = StableCandidateBuilder.exactKey(provider, server),
                        family = StableCandidateBuilder.familyCandidate(server),
                    )
                }.getOrDefault(ServerFacts(null, null))
            }
            val family = facts.family
            val aniVideo = server.video
            val data = EditotsuInternalData.of(aniVideo)
            // names only (no hoster prefix or 👤/💾/⚙️ line) when the source sent them
            val audioText = data?.torrentName?.let { listOfNotNull(it, data.fileName).joinToString("\n") } ?: server.name
            extractor.videos.forEachIndexed { vi, video ->
                val viewModel = viewOf(extractor, video)
                rowViews[RowRef(ei, vi)] = viewModel
                val height = ResolutionLabel.resolve(aniVideo?.resolution, server.name)
                    ?: video.quality?.takeIf { it >= 144 }
                rows += PickerRow(
                    ref = RowRef(ei, vi),
                    provider = provider ?: fallbackProvider,
                    presentation = viewModel.presentation,
                    header = viewModel.header?.takeIf { it.isNotBlank() } ?: viewModel.displayLabel,
                    resolution = height,
                    transport = TransportLabel.of(video.file.url, video.format.name),
                    audioKind = data?.audioKind() ?: AudioKindDetector.detect(audioText),
                    preferred = extractor.isPreferred || aniVideo?.preferred == true,
                    rememberedExact = storedKey != null && facts.exact == storedKey,
                    rememberedFamily = storedFamily != null && family != null &&
                        family.providerPkg == storedFamily.providerPkg &&
                        family.groupKey == storedFamily.groupKey,
                    facets = fieldsOf(extractor, video)?.let { RowFacets.of(it, height, data?.sizeBytes) }
                        ?: RowFacets(resolution = ResolutionLabel.label(height), height = height),
                )
            }
        }
        return Built(rows, rowViews)
    }

    private fun keyOf(extractor: VideoExtractor, video: Video): ViewKey {
        val server = extractor.server
        return ViewKey(
            serverName = server.name,
            embedUrl = server.embed.url,
            videoUrl = video.file.url,
            format = video.format.name,
            quality = video.quality,
            internalData = server.video?.internalData,
        )
    }

    /** Cached summary for one video row; parsed once per text. */
    fun viewOf(extractor: VideoExtractor, video: Video): SourceRowViewModel =
        views.getOrPut(keyOf(extractor, video)) {
            summarizeCalls++
            runCatching { summarize(extractor, video) }.getOrElse { fallback(extractor.server.name) }
        }

    /** Cached release fields for filters (null for stream rows or parse errors). */
    private fun fieldsOf(extractor: VideoExtractor, video: Video): SourceRowClassifier.ReleaseFields? =
        fields.getOrPut(keyOf(extractor, video)) {
            val name = extractor.server.name
            runCatching {
                val data = EditotsuInternalData.of(extractor.server.video)
                if (SourceRowClassifier.classifyPresentation(name, data) != SourcePresentationKind.RELEASE) null
                else SourceRowClassifier.parseRelease(name, data = data)
            }.getOrNull()
        }

    companion object {
        fun defaultSummarize(extractor: VideoExtractor, video: Video): SourceRowViewModel =
            SourceRowSummarizer.build(
                displayLabel = extractor.server.name,
                qualityText = extractor.server.name,
                format = video.format,
                url = video.file.url,
                data = EditotsuInternalData.of(extractor.server.video),
            )

        /** A row whose text could not be parsed still shows, titled with its raw text. */
        fun fallback(serverName: String): SourceRowViewModel = SourceRowViewModel(
            displayLabel = serverName,
            presentation = SourcePresentationKind.STREAM,
            transport = SourceTransport.OTHER,
            header = serverName.lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: serverName,
            cleanSubtitle = null,
            subtitle = serverName,
            pills = emptyList(),
            criticalPills = emptyList(),
            optionalPills = emptyList(),
            audioSummary = null,
            subtitleSummary = null,
            meta = emptyList(),
            details = emptyList(),
        )
    }
}
