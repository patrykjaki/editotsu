package ani.dantotsu.media.anime.selector

import ani.dantotsu.parsers.VideoServer

/**
 * Pure persistence-owned candidate builder. Produces a [FamilyCandidate]
 * (provider + groupKey + soft fields + exact key) for a `VideoServer`
 * without touching UI presentation code.
 */
object StableCandidateBuilder {
    const val PROVIDER_EXTRA_KEY = "editotsu-picker-provider"

    fun providerOf(server: VideoServer): String? {
        val raw = server.extraData?.get(PROVIDER_EXTRA_KEY)
        if (raw.isNullOrBlank()) return null
        return raw
    }

    fun exactKey(
        provider: String?,
        server: VideoServer,
    ): String? {
        val url = server.embed.url
        // Magnet?
        if (url.startsWith("magnet:?")) {
            return MagnetKeyBuilder.build(provider, url)
        }
        // HTTP torrent? Classify from the validated canonical URI's
        // rawPath, not from `endsWith` on the raw URL. This
        // correctly handles signed URLs like
        // https://nyaa.si/a.torrent?token=a#x where the
        // path is the discriminator and the query/fragment are
        // authentication artefacts that must NOT affect transport
        // identity.
        val canonical = UrlCanonicaliser.canonicalise(url)
        if (canonical != null && canonical.rawPath.endsWith(".torrent",
                ignoreCase = true)
        ) {
            return HttpTorrentKeyBuilder.build(provider, url)
        }
        // Direct / debid. Only build a stable direct key when the
        // persistence-owned metadata confidently says the candidate
        // is RELEASE-shaped. Generic direct streams (e.g. a bare
        // "1080p" label) stay on the legacy exact-name/runtime
        // path with no cross-episode stable identity.
        val meta = StableReleaseMetadataExtractor.extract(server.name)
        if (!meta.isReleaseShaped) {
            return null
        }
        return DirectReleaseKeyBuilder.build(
            exactCaseProvider = provider,
            group = meta.group,
            resolution = meta.resolution,
            audioFormat = meta.audioFormat,
            audioMode = meta.audioMode,
            sourceService = meta.sourceService,
            rawTitle = meta.rawTitle.ifEmpty { server.name },
        )
    }

    fun familyCandidate(server: VideoServer): FamilyCandidate? {
        val provider = providerOf(server) ?: return null
        val exactKey = exactKey(provider, server) ?: return null
        val meta = StableReleaseMetadataExtractor.extract(server.name)
        val groupKey = StableGroupCanonicalizer.canonicalKey(meta.group) ?: return null
        val soft = LinkedHashMap<String, String>()
        TrackerCanonicalizer.canonicalKey(meta.tracker)?.let { soft["trackerKey"] = it }
        StableSoftCanonicalizer.canonicalResolution(meta.resolution)?.let { soft["resolution"] = it }
        StableSoftCanonicalizer.canonicalText(meta.audioMode)?.let { soft["audioMode"] = it }
        StableSoftCanonicalizer.canonicalText(meta.audioFormat)?.let { soft["audioFormat"] = it }
        SourceServiceCanonicalizer.canonicalKey(meta.sourceService)?.let { soft["sourceService"] = it }
        StableSoftCanonicalizer.canonicalText(meta.codec)?.let { soft["codec"] = it }
        StableSoftCanonicalizer.canonicalText(meta.hdr)?.let { soft["hdr"] = it }
        return FamilyCandidate(
            exactKey = exactKey,
            providerPkg = provider,
            groupKey = groupKey,
            soft = soft,
        )
    }
}
