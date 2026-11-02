package ani.dantotsu.animethemes

object AnimeThemeVideoSelector {

    fun selectPreferredVideo(
        variants: List<ThemeVideoVariant>,
        prefs: ThemePlaybackPreferences
    ): ThemeVideoVariant? {
        if (variants.isEmpty()) return null

        // Stage 0: Safety Gate
        val safeVariants = variants.filter { !it.isSpoiler && !it.isNsfw }
        val pool = if (safeVariants.isNotEmpty()) safeVariants else variants

        // Lexicographic Tiered Selection
        return pool.minWithOrNull(createComparator(prefs))
    }

    private fun createComparator(prefs: ThemePlaybackPreferences): Comparator<ThemeVideoVariant> {
        return compareBy<ThemeVideoVariant> { variant ->
            // Stage 1: Resolution Tier
            getResolutionTier(variant.resolution, prefs.resolution)
        }.thenBy { variant ->
            // Stage 2: Source Match (0 = preferred/auto, 1 = fallback)
            getSourcePenalty(variant, prefs.source)
        }.thenBy { variant ->
            // Stage 3: Credits Match (0 = preferred/auto, 1 = fallback)
            getCreditsPenalty(variant, prefs.credits)
        }.thenBy { variant ->
            // Stage 4: Subtitles Match (0 = preferred/auto, 1 = fallback)
            getSubtitlesPenalty(variant, prefs.subtitles)
        }.thenByDescending { variant ->
            // Stage 5: Neutral Auto Recommendation (Exact v5 preservation)
            neutralAutoRank(variant)
        }.thenBy { variant ->
            // Deterministic Tie-Breaker (ID / URL stability)
            variant.videoId
        }
    }

    private fun getResolutionTier(resolution: Int?, pref: ResolutionPref): Int {
        val res = resolution ?: 0
        return when (pref) {
            ResolutionPref.P720 -> when {
                res == 720 -> 0 // Exact 720p
                res in 1..719 -> 1000 - res // Lower (480p penalty 520, 360p penalty 640 -> 480p wins)
                res > 720 -> 2000 + res // Higher (1080p penalty 3080 -> lower always beats higher)
                else -> 9999
            }
            ResolutionPref.P1080 -> when {
                res == 1080 -> 0 // Exact 1080p
                res in 1..1079 -> 1000 - res // Lower (720p penalty 280, 480p penalty 520 -> 720p wins)
                res > 1080 -> 2000 + res // Higher (1440p, 4k)
                else -> 9999
            }
            ResolutionPref.P480_OR_LOWER -> when {
                res in 1..480 -> 500 - res // Highest <= 480p (480p penalty 20, 360p penalty 140 -> 480p wins)
                res > 480 -> 2000 + res // Higher fallback (720p penalty 2720, 1080p penalty 3080 -> 720p wins)
                else -> 9999
            }
            ResolutionPref.AUTO -> 0 // Auto: Neutral ranking decides
        }
    }

    private fun getSourcePenalty(variant: ThemeVideoVariant, pref: SourcePref): Int {
        val src = variant.source?.uppercase() ?: ""
        return when (pref) {
            SourcePref.BD -> if (src == "BD") 0 else 1
            SourcePref.WEB -> if (src == "WEB") 0 else 1
            SourcePref.AUTO -> 0
        }
    }

    private fun getCreditsPenalty(variant: ThemeVideoVariant, pref: CreditsPref): Int {
        return when (pref) {
            CreditsPref.NC -> if (variant.isCreditless) 0 else 1
            CreditsPref.WITH_CREDITS -> if (!variant.isCreditless) 0 else 1
            CreditsPref.AUTO -> 0
        }
    }

    private fun getSubtitlesPenalty(variant: ThemeVideoVariant, pref: SubtitlesPref): Int {
        return when (pref) {
            SubtitlesPref.NO_SUBTITLES -> if (!variant.isSubbed) 0 else 1
            SubtitlesPref.SUBTITLED -> if (variant.isSubbed) 0 else 1
            SubtitlesPref.AUTO -> 0
        }
    }

    fun neutralAutoRank(variant: ThemeVideoVariant): Int {
        var score = 0
        if (variant.isCreditless) score += 1000

        when (variant.source?.uppercase()) {
            "BD" -> score += 500
            "WEB" -> score += 300
            "DVD" -> score += 100
        }

        score += (variant.resolution ?: 720) / 10

        if (!variant.isSubbed) score += 200

        if (variant.overlap.equals("NONE", ignoreCase = true) || variant.overlap.equals("TRANS", ignoreCase = true)) {
            score += 100
        }

        return score
    }

    fun extractVariants(theme: ThemeUiModel): List<ThemeVideoVariant> {
        val rawVariants = mutableListOf<ThemeVideoVariant>()
        val hasMultipleVersions = theme.alternateVersions.size > 1

        for (entry in theme.alternateVersions) {
            for (video in entry.videos) {
                val label = buildVariantLabel(entry, video, hasMultipleVersions)
                val warning = when {
                    entry.isSpoiler && entry.isNsfw -> ThemeWarningType.SPOILER_AND_NSFW
                    entry.isSpoiler -> ThemeWarningType.SPOILER
                    entry.isNsfw -> ThemeWarningType.NSFW
                    else -> ThemeWarningType.NONE
                }
                rawVariants.add(
                    ThemeVideoVariant(
                        videoId = video.videoId,
                        videoUrl = video.videoUrl,
                        resolution = video.resolution,
                        isCreditless = video.isCreditless,
                        source = video.source,
                        overlap = video.overlap,
                        tags = video.tags,
                        isSubbed = video.isSubbed,
                        entryVersion = entry.version,
                        episodes = entry.episodes,
                        isSpoiler = entry.isSpoiler,
                        isNsfw = entry.isNsfw,
                        displayLabel = label,
                        warningType = warning
                    )
                )
            }
        }

        // Canonical Deduplication across distinct video attributes including normalized episodes
        return rawVariants
            .groupBy { 
                listOf(
                    it.resolution, 
                    it.source?.uppercase(), 
                    it.isCreditless, 
                    it.isSubbed, 
                    it.overlap?.uppercase(), 
                    it.entryVersion, 
                    it.episodes?.trim(), 
                    it.isSpoiler, 
                    it.isNsfw
                ) 
            }
            .values
            .map { group -> group.maxByOrNull { neutralAutoRank(it) } ?: group.first() }
    }

    private fun buildVariantLabel(
        entry: ThemeVersionUiModel,
        video: ThemeVideoUiModel,
        hasMultipleVersions: Boolean
    ): String {
        val parts = mutableListOf<String>()
        if (hasMultipleVersions && entry.version > 1) {
            parts.add("V${entry.version}")
        }
        if (!entry.episodes.isNullOrBlank()) {
            parts.add("Ep ${entry.episodes}")
        }
        if (video.resolution != null && video.resolution > 0) {
            parts.add("${video.resolution}p")
        }
        if (!video.source.isNullOrBlank()) {
            parts.add(video.source)
        }
        if (video.isCreditless) {
            parts.add("NC")
        }
        if (video.isSubbed) {
            parts.add("Sub")
        }
        return parts.joinToString(" • ")
    }
}
