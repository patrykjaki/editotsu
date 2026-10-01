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
    )

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
                selected = sub.selected
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
     * HOLD4 B3: complete per-URI header routing. The base factory stays
     * header-free; every URI resolves its EXACT header set here:
     *
     * - primary manifest/segments → exact request headers;
     * - each subtitle/side-audio URI → its own headers, plus a safe
     *   User-Agent fallback (own → request → default) when it carries none.
     *
     * Primary Authorization/Cookie never reach another host: a distinct-host
     * subtitle only ever carries its own explicit context. License headers
     * are not an input and can never enter this map.
     */
    fun planRequestHeaders(
        primaryUri: String,
        requestHeaders: Map<String, String>,
        subtitles: List<SubAttachment>,
        audioSides: List<SideAudio>,
        defaultUa: String,
    ): Map<String, Map<String, String>> {
        val requestUa = requestHeaders.entries.firstOrNull {
            it.key.equals("User-Agent", ignoreCase = true)
        }
        fun withUaFallback(own: Map<String, String>): Map<String, String> {
            if (own.keys.any { it.equals("User-Agent", ignoreCase = true) }) return own.toMap()
            val fallback = requestUa?.let { it.key to it.value } ?: ("User-Agent" to defaultUa)
            return own + fallback
        }
        val out = LinkedHashMap<String, Map<String, String>>()
        out[primaryUri] = ensureUserAgent(requestHeaders, defaultUa)
        for (sub in subtitles) out[sub.url] = withUaFallback(sub.headers)
        for (side in audioSides) out[side.url] = withUaFallback(side.headers)
        return out
    }
}
