package ani.dantotsu.media.anime.selector

import ani.dantotsu.parsers.VideoType

/**
 * Builds [SourceRowViewModel] instances from raw extension output.
 *
 * Pure: takes strings + format, returns the view-model. No I/O, no Android
 * types. Selection contract is not touched.
 */
object SourceRowSummarizer {

    /**
     * Maximum number of pills to surface on the collapsed card. Per the
     * visual-hierarchy brief: "maximum 3-4 visible chips".
     */
    private const val MAX_VISIBLE_PILLS = 4

    fun build(
        displayLabel: String,
        qualityText: String,
        format: VideoType?,
        url: String,
    ): SourceRowViewModel {
        val presentation = SourceRowClassifier.classifyPresentation(qualityText)
        val formatName = format?.name ?: ""
        val transport = SourceRowClassifier.classifyTransport(
            url = url,
            formatIsContainer = format == VideoType.CONTAINER,
            formatName = formatName,
        )
        return when (presentation) {
            SourcePresentationKind.RELEASE -> buildRelease(displayLabel, transport, url)
            SourcePresentationKind.STREAM -> buildStream(displayLabel, qualityText, transport, formatName)
        }
    }

    fun build(
        displayLabel: String,
        qualityText: String,
        formatName: String?,
        url: String,
    ): SourceRowViewModel {
        val presentation = SourceRowClassifier.classifyPresentation(qualityText)
        val effectiveName = formatName ?: ""
        val transport = SourceRowClassifier.classifyTransport(
            url = url,
            formatIsContainer = effectiveName.equals("CONTAINER", ignoreCase = true),
            formatName = effectiveName,
        )
        return when (presentation) {
            SourcePresentationKind.RELEASE -> buildRelease(displayLabel, transport, url)
            SourcePresentationKind.STREAM -> buildStream(displayLabel, qualityText, transport, effectiveName)
        }
    }

    private fun buildRelease(
        displayLabel: String,
        transport: SourceTransport,
        url: String,
    ): SourceRowViewModel {
        val fields = SourceRowClassifier.parseRelease(displayLabel)
        val pills = buildPills(fields)
        val meta = buildReleaseMeta(fields)
        val details = buildReleaseDetails(fields, transport)
        // Build a UI-only clean title that removes tokens already shown
        // elsewhere (group, resolution, codec, bit depth, source service).
        // The exact raw release name is preserved in details for identity.
        val cleanTitle = SourceRowClassifier.cleanDisplayTitle(
            releaseName = fields.releaseName,
            releaseGroup = fields.releaseGroup,
            resolution = fields.resolution,
            codec = fields.codec,
            bitDepth = fields.bitDepth,
            sourceService = fields.sourceService,
        )
        return SourceRowViewModel(
            displayLabel = displayLabel,
            presentation = SourcePresentationKind.RELEASE,
            transport = transport,
            header = fields.releaseGroup,
            cleanSubtitle = cleanTitle,
            subtitle = fields.releaseName,
            pills = pills,
            audioSummary = fields.audioSummary,
            subtitleSummary = fields.subtitleSummary,
            subtitleLanguages = fields.subtitleLanguages,
            subtitleFlags = fields.subtitleFlags,
            audioLanguages = fields.audioLanguages,
            audioFlags = fields.audioFlags,
            meta = meta,
            details = details,
            tracker = fields.tracker,
        )
    }

    /**
     * Build the right-aligned pill list, deduplicated and priority-ordered.
     *
     * Priority (per the visual-hierarchy brief):
     *   1. resolution (always first)
     *   2. HDR variant (HDR10+ > HDR10 > Dolby Vision > plain HDR)
     *   3. codec + bit-depth as separate pills
     *   4. audio format / audio mode
     *   5. subtitle summary
     *
     * The list is capped at [MAX_VISIBLE_PILLS]; the remainder lives in
     * details.
     */
    private fun buildPills(fields: SourceRowClassifier.ReleaseFields): List<SourceRowPill> {
        val out = mutableListOf<SourceRowPill>()

        // 1. Resolution (with 2160p -> 4K display alias per v3.3 brief).
        fields.resolution?.let { res ->
            val alias = SourceRowClassifier.resolutionDisplayAlias(res) ?: res
            val label = if (fields.hdr) "$alias HDR" else alias
            out += SourceRowPill(label, SourcePillKind.QUALITY)
        }

        // 2. Codec (one pill).
        fields.codec?.let { out += SourceRowPill(it, SourcePillKind.CODEC) }

        // 3. HDR variant (one pill). The plain "HDR" pill is suppressed
        // when the resolution pill already says "1080p HDR" so we do not
        // duplicate the signal. The precise variants (HDR10, HDR10+,
        // Dolby Vision) are always emitted.
        fields.hdrVariant?.let { variant ->
            when (variant) {
                "HDR10+" -> out += SourceRowPill("HDR10+", SourcePillKind.HDR10_PLUS)
                "HDR10" -> out += SourceRowPill("HDR10", SourcePillKind.HDR10)
                "Dolby Vision" -> out += SourceRowPill("DV", SourcePillKind.DOLBY_VISION)
                "HDR" -> {
                    if (fields.resolution == null || !fields.hdr) {
                        out += SourceRowPill("HDR", SourcePillKind.HDR)
                    }
                }
            }
        }
        // Bit depth as a separate pill only when distinct from HDR.
        if (fields.hdrVariant == null) {
            fields.bitDepth?.let { out += SourceRowPill(it, SourcePillKind.BIT_DEPTH) }
        }

        // 4. Audio (format / mode).
        fields.audioFormat?.let { out += SourceRowPill(it, SourcePillKind.AUDIO_FMT) }
        fields.audioSummary?.let {
            // Skip if the audio summary IS the same as the audio format
            // (e.g. "AAC 2.0" is a format, not a mode).
            if (!it.equals(fields.audioFormat, ignoreCase = true)) {
                out += SourceRowPill(it, SourcePillKind.AUDIO_MODE)
            }
        }

        // 5. Subtitle summary (flags + codes).
        if (fields.subtitleFlags.isNotEmpty() || fields.subtitleLanguages.isNotEmpty() ||
            fields.subtitleSummary != null
        ) {
            val label = buildSubtitlePillLabel(fields)
            out += SourceRowPill(label, SourcePillKind.SUB_LANG)
        }

        return out.take(MAX_VISIBLE_PILLS)
    }

    private fun buildSubtitlePillLabel(fields: SourceRowClassifier.ReleaseFields): String {
        // Prefer flag glyphs when present (the brief: "preserve the original
        // flag glyph"). Fall back to the compact summary otherwise.
        if (fields.subtitleFlags.isNotEmpty()) {
            return if (fields.subtitleFlags.size <= 3) {
                fields.subtitleFlags.joinToString(" ")
            } else {
                "${fields.subtitleFlags.take(3).joinToString(" ")} +${fields.subtitleFlags.size - 3}"
            }
        }
        return fields.subtitleSummary ?: "Multi-subs"
    }

    /**
     * Build the third-line meta (seeders · size · indexer). The seeder
     * number is the most important signal; the indexer (e.g. NyaaSi) is
     * intentionally demoted to secondary metadata per the brief.
     */
    private fun buildReleaseMeta(fields: SourceRowClassifier.ReleaseFields): List<SourceMetaChip> {
        val out = mutableListOf<SourceMetaChip>()
        fields.seeders?.let { out += SourceMetaChip(SourceMetaIcon.SEEDERS, "$it seeders") }
        fields.sizeText?.let { out += SourceMetaChip(SourceMetaIcon.SIZE, it) }
        fields.indexer?.let { out += SourceMetaChip(SourceMetaIcon.INDEXER, it) }
        return out
    }

    private fun buildStream(
        displayLabel: String,
        qualityText: String,
        transport: SourceTransport,
        formatName: String,
    ): SourceRowViewModel {
        val firstLine = qualityText.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() }
            ?: qualityText.trim()
        val splitParts = firstLine.split(" - ").map { it.trim() }.filter { it.isNotEmpty() }
        val host = splitParts.firstOrNull() ?: firstLine
        val resolution = splitParts.lastOrNull()
            ?.takeIf { SourceRowClassifier.parseResolution(it) != null }
            ?.let { SourceRowClassifier.parseResolution(it) }
        val trackKind = splitParts.getOrNull(1)?.takeIf { it in setOf("Sub", "Dub", "Dual") }
        val audioSummary = trackKind
        val pills = mutableListOf<SourceRowPill>()
        resolution?.let { pills += SourceRowPill(it, SourcePillKind.QUALITY) }
        trackKind?.let { pills += SourceRowPill(it, SourcePillKind.AUDIO_MODE) }
        val meta = if (trackKind != null) listOf(SourceMetaChip(SourceMetaIcon.TRACK, trackKind))
        else emptyList()
        val details = buildStreamDetails(host, resolution, trackKind, transport, formatName, qualityText)
        return SourceRowViewModel(
            displayLabel = displayLabel,
            presentation = SourcePresentationKind.STREAM,
            transport = transport,
            header = host,
            cleanSubtitle = null,
            subtitle = null,
            pills = pills,
            audioSummary = audioSummary,
            subtitleSummary = null,
            subtitleLanguages = emptyList(),
            subtitleFlags = emptyList(),
            audioLanguages = emptyList(),
            audioFlags = emptyList(),
            meta = meta,
            details = details,
            tracker = null,
        )
    }

    private fun buildReleaseDetails(
        fields: SourceRowClassifier.ReleaseFields,
        transport: SourceTransport,
    ): List<SourceDetailLine> {
        val out = mutableListOf<SourceDetailLine>()
        // Semantic order: structural fields first, raw title at the end.
        // Hide unknown rows; never repeat "Not supplied by extension".
        fields.releaseGroup?.let {
            out += SourceDetailLine(label = "Release group", value = it)
        }
        fields.tracker?.let {
            out += SourceDetailLine(label = "Tracker", value = it)
        }
        fields.resolution?.let { res ->
            // Display: 2160p -> "4K (2160p)"; otherwise the raw value.
            val display = if (res == "2160p") "4K (2160p)" else res
            out += SourceDetailLine(label = "Resolution", value = display)
        }
        fields.codec?.let {
            val parts = listOfNotNull(it, fields.bitDepth)
            out += SourceDetailLine(label = "Codec", value = parts.joinToString(" "))
        }
        if (fields.hdrVariant != null) {
            out += SourceDetailLine(label = "HDR", value = fields.hdrVariant)
        } else if (fields.hdr) {
            out += SourceDetailLine(label = "HDR", value = "HDR")
        }
        fields.audioFormat?.let {
            out += SourceDetailLine(label = "Audio format", value = it)
        }
        fields.audioSummary?.let { summary ->
            // Skip if the summary is the same as the audio format
            // (e.g. "AAC 2.0" appears as both).
            if (!summary.equals(fields.audioFormat, ignoreCase = true)) {
                out += SourceDetailLine(label = "Audio", value = summary)
            }
        }
        if (fields.audioLanguages.isNotEmpty()) {
            out += SourceDetailLine(
                label = "Audio languages",
                value = fields.audioLanguages.joinToString(" + "),
            )
        }
        if (fields.subtitleFlags.isNotEmpty()) {
            out += SourceDetailLine(
                label = "Subtitles",
                value = fields.subtitleFlags.joinToString(" "),
            )
        } else if (fields.subtitleLanguages.isNotEmpty()) {
            out += SourceDetailLine(
                label = "Subtitle languages",
                value = fields.subtitleLanguages.joinToString(" + "),
            )
        }
        fields.sourceService?.let {
            out += SourceDetailLine(label = "Source service", value = it)
        }
        fields.sourceClass?.let {
            out += SourceDetailLine(label = "Source class", value = it)
        }
        out += SourceDetailLine(label = "Transport", value = transportLabelString(transport))
        out += SourceDetailLine(
            label = "Raw release name",
            value = fields.releaseName,
            isUnknownPlaceholder = false,
        )
        return out
    }

    private fun buildStreamDetails(
        host: String,
        resolution: String?,
        trackKind: String?,
        transport: SourceTransport,
        formatName: String,
        rawLabel: String,
    ): List<SourceDetailLine> {
        val out = mutableListOf<SourceDetailLine>()
        out += SourceDetailLine(label = "Host", value = host)
        resolution?.let { out += SourceDetailLine(label = "Resolution", value = it) }
        trackKind?.let { out += SourceDetailLine(label = "Track", value = it) }
        out += SourceDetailLine(label = "Transport", value = transportLabelString(transport))
        out += SourceDetailLine(label = "Raw label", value = rawLabel)
        return out
    }

    private fun transportLabelString(transport: SourceTransport): String = when (transport) {
        SourceTransport.TORRENT -> "Torrent"
        SourceTransport.HLS -> "HLS"
        SourceTransport.DASH -> "DASH"
        SourceTransport.DIRECT_HTTP -> "Direct"
        SourceTransport.OTHER -> "Other"
    }
}