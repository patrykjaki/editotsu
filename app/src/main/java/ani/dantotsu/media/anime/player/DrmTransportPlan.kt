package ani.dantotsu.media.anime.player

import androidx.media3.common.MimeTypes

/**
 * MAX MERGE M4 correction (HOLD3 D1/D2/D3): pure DRM transport plan. The
 * Media3 engine and its DataSource factories are Android-bound; every
 * transport decision they execute is computed here and host-tested:
 *
 * - request headers survive exactly into the media plan, with an extension
 *   User-Agent never overwritten (D1);
 * - license headers travel only the license path, never merged into media
 *   headers (D1);
 * - external audio becomes a deterministic merge plan (D2);
 * - every subtitle (initial or later-added) uses the same MIME inference
 *   with per-subtitle header context preserved (D3).
 */
object DrmTransportPlan {

    /** Exact copy: media/manifest/segment headers reach the plan untouched. */
    fun resolveMediaRequestProperties(requestHeaders: Map<String, String>): Map<String, String> =
        requestHeaders.toMap()

    /**
     * Default User-Agent applies only when the request carries none
     * (case-insensitive). An extension-provided User-Agent always wins and
     * is never replaced.
     */
    fun ensureUserAgent(headers: Map<String, String>, defaultUa: String): Map<String, String> {
        if (headers.keys.any { it.equals("User-Agent", ignoreCase = true) }) return headers.toMap()
        return headers + ("User-Agent" to defaultUa)
    }

    /** Deterministic audio MIME from URL extension; null lets ExoPlayer sniff. */
    fun audioMimeFor(url: String): String? {
        val lower = url.substringBefore("?").lowercase()
        return when {
            lower.endsWith(".mp3") -> "audio/mpeg"
            lower.endsWith(".m4a") || lower.endsWith(".mp4") -> "audio/mp4"
            lower.endsWith(".aac") -> "audio/aac"
            lower.endsWith(".ogg") -> "audio/ogg"
            lower.endsWith(".opus") -> "audio/opus"
            lower.endsWith(".wav") -> "audio/wav"
            lower.endsWith(".flac") -> "audio/flac"
            else -> null
        }
    }

    /** Shared subtitle MIME inference (initial request AND later-added subs). */
    fun subtitleMimeFor(url: String): String {
        val lower = url.substringBefore("?").lowercase()
        return when {
            lower.endsWith(".vtt") || lower.endsWith(".webvtt") -> MimeTypes.TEXT_VTT
            lower.endsWith(".srt") -> MimeTypes.APPLICATION_SUBRIP
            lower.endsWith(".ass") || lower.endsWith(".ssa") -> MimeTypes.TEXT_SSA
            else -> MimeTypes.TEXT_VTT
        }
    }

    /** Side-audio entry that survives planning (headers preserved per track). */
    data class SideAudio(
        val url: String,
        val mime: String?,
        val kind: AudioSourceKind,
        val headers: Map<String, String>,
        val language: String?,
        val title: String?,
    )

    /** Adaptive vs progressive side source, inferred deterministically. */
    enum class AudioSourceKind { PROGRESSIVE, HLS, DASH }

    fun audioSourceKind(url: String): AudioSourceKind {
        val lower = url.substringBefore("?").lowercase()
        return when {
            lower.endsWith(".m3u8") -> AudioSourceKind.HLS
            lower.endsWith(".mpd") -> AudioSourceKind.DASH
            else -> AudioSourceKind.PROGRESSIVE
        }
    }

    data class AudioMergePlan(
        val primaryUri: String,
        val sides: List<SideAudio>,
    ) {
        val hasSides: Boolean get() = sides.isNotEmpty()
    }

    /**
     * Merge plan: 0 usable side tracks → primary only; blank or
     * primary-duplicate URLs are skipped (never merged, never error).
     */
    fun planAudioMerge(
        primaryUri: String,
        tracks: List<ExternalAudioTrack>,
    ): AudioMergePlan {
        val sides = tracks.mapNotNull { track ->
            val url = track.url.trim()
            if (url.isEmpty() || url == primaryUri) return@mapNotNull null
            SideAudio(
                url = url,
                mime = audioMimeFor(url),
                kind = audioSourceKind(url),
                headers = track.headers.toMap(),
                language = track.language,
                title = track.title
            )
        }
        return AudioMergePlan(primaryUri, sides)
    }

    /** Subtitle attachment with MIME + preserved per-subtitle transport context. */
    data class SubAttachment(
        val url: String,
        val mime: String,
        val headers: Map<String, String>,
        val language: String?,
        val title: String?,
        val selected: Boolean,
        /** file://, content://, or absolute-path subs ride the multi-scheme path. */
        val local: Boolean,
    )

    /** Local (non-HTTP) subtitle/file detection for the multi-scheme path. */
    fun isLocalUri(url: String): Boolean {
        val lower = url.trim().lowercase()
        return lower.startsWith("file://") || lower.startsWith("content://") ||
            (!lower.contains("://") && lower.startsWith("/"))
    }

    /** Blank-URL subtitles are skipped; the rest keep their header context. */
    fun planSubtitleAttachments(subs: List<ExternalSubtitle>): List<SubAttachment> =
        subs.mapNotNull { sub ->
            val url = sub.url.trim()
            if (url.isEmpty()) return@mapNotNull null
            SubAttachment(
                url = url,
                mime = subtitleMimeFor(url),
                headers = sub.headers.toMap(),
                language = sub.language,
                title = sub.title,
                selected = sub.selected,
                local = isLocalUri(url)
            )
        }

    /**
     * HOLD4 H1: caption-style plan honoring SubtitleStyleMode.
     * SOURCE → embedded styles/sizes respected, no custom override.
     * CUSTOM → embedded styling disabled, Editotsu style applied
     * deterministically (colors, clamped size, margin, typeface hint).
     */
    data class CaptionPlan(
        val applyEmbeddedStyles: Boolean,
        val applyEmbeddedFontSizes: Boolean,
        val useCustomStyle: Boolean,
        val fgColor: Int,
        val bgColor: Int,
        val edgeColor: Int,
        val fontSizeSp: Float,
        val bottomMarginDp: Int,
        val fontFamily: String?,
        val fontFile: java.io.File?,
    )

    fun planCaptionStyle(style: SubtitleStyle): CaptionPlan {
        val custom = style.mode == SubtitleStyleMode.CUSTOM
        return CaptionPlan(
            applyEmbeddedStyles = !custom,
            applyEmbeddedFontSizes = !custom,
            useCustomStyle = custom,
            fgColor = style.textColor,
            bgColor = style.backgroundColor,
            edgeColor = style.borderColor,
            fontSizeSp = style.fontSizeSp.coerceIn(8f, 48f),
            bottomMarginDp = style.bottomMarginDp.coerceAtLeast(0),
            fontFamily = style.fontFamily?.takeIf { it.isNotBlank() },
            fontFile = style.fontFile
        )
    }

    /**
     * HOLD5 hardening: custom typeface source precedence (pure, unit-tested).
     * A valid bundled fontFile wins over Typeface.create(), which can return
     * a silent fallback for an uninstalled family name.
     */
    sealed interface CustomTypefaceSource {
        data class FontFile(val file: java.io.File) : CustomTypefaceSource
        data class Family(val name: String) : CustomTypefaceSource
        data object None : CustomTypefaceSource
    }

    fun resolveCustomTypefaceSource(
        fontFamily: String?,
        fontFile: java.io.File?,
    ): CustomTypefaceSource {
        val validFile = fontFile?.takeIf { it.isFile }
        if (validFile != null) return CustomTypefaceSource.FontFile(validFile)
        val family = fontFamily?.takeIf { it.isNotBlank() }
        if (family != null) return CustomTypefaceSource.Family(family)
        return CustomTypefaceSource.None
    }

    /**
     * HOLD5: complete per-source header routing. Every source gets its EXACT
     * header set, served by its own source-scoped factory (whose child
     * requests inherit it):
     *
     * - primary manifest/segments → exact request headers;
     * - each subtitle/side-audio URI → its own headers, plus a safe
     *   User-Agent fallback (own → request → default) when it carries none.
     *
     * Primary Authorization/Cookie never reach another host: a distinct-host
     * subtitle only ever carries its own explicit context. License headers
     * are not an input and can never enter this plan.
     */
    data class SourceHeaderPlan(
        val primary: Map<String, String>,
        val subtitles: Map<String, Map<String, String>>,
        val audioSides: Map<String, Map<String, String>>,
    )

    fun planSourceHeaders(
        primaryUri: String,
        requestHeaders: Map<String, String>,
        subtitles: List<SubAttachment>,
        audioSides: List<SideAudio>,
        defaultUa: String,
    ): SourceHeaderPlan {
        val requestUa = requestHeaders.entries.firstOrNull {
            it.key.equals("User-Agent", ignoreCase = true)
        }
        fun withUaFallback(own: Map<String, String>): Map<String, String> {
            if (own.keys.any { it.equals("User-Agent", ignoreCase = true) }) return own.toMap()
            val fallback = requestUa?.let { it.key to it.value } ?: ("User-Agent" to defaultUa)
            return own + fallback
        }
        return SourceHeaderPlan(
            primary = ensureUserAgent(requestHeaders, defaultUa),
            subtitles = subtitles.associate { it.url to withUaFallback(it.headers) },
            audioSides = audioSides.associate { it.url to withUaFallback(it.headers) }
        )
    }

    /**
     * HOLD5 B3: live subtitle attachment as a pure request transform.
     * Appends the new subtitle (blank/duplicate URLs are no-ops) while
     * preserving every other request field — position/play intent live
     * outside the request and are captured/restored by the engine around
     * the source rebuild.
     */
    fun withLiveSubtitle(
        existing: List<ExternalSubtitle>,
        added: ExternalSubtitle,
    ): List<ExternalSubtitle> {
        val url = added.url.trim()
        if (url.isEmpty()) return existing
        if (existing.any { it.url.trim() == url }) return existing
        return existing + added
    }

    fun attachLiveSubtitle(request: PlaybackRequest, sub: ExternalSubtitle): PlaybackRequest =
        request.copy(externalSubtitles = withLiveSubtitle(request.externalSubtitles, sub))

    /**
     * HOLD5 B4: authoritative primary MIME. An explicit caller MIME always
     * wins; otherwise VideoType decides (MPV behavior untouched — only the
     * DRM engine reads request.mimeType).
     */
    fun primaryMimeForFormat(
        explicit: String?,
        format: ani.dantotsu.parsers.VideoType,
    ): String? {
        if (!explicit.isNullOrBlank()) return explicit
        return when (format) {
            ani.dantotsu.parsers.VideoType.DASH -> MimeTypes.APPLICATION_MPD
            ani.dantotsu.parsers.VideoType.M3U8 -> MimeTypes.APPLICATION_M3U8
            else -> null
        }
    }
}
