package ani.dantotsu.media.anime.player

import java.net.URI

/**
 * Authoritative explicit subtitle-selection state for a playback context.
 *
 * - [Unset]: the user has not explicitly chosen a subtitle state for this playback
 *   context. Only [Unset] allows automatic subtitle selection.
 * - [Off]: the user explicitly disabled subtitles. Automatic selection must NEVER
 *   override it, and it must survive FILE_LOADED / track refreshes / playback restarts.
 * - [Track]: the user explicitly selected a particular subtitle track. When the track
 *   still exists it is re-selected; when it disappears a deterministic fallback applies
 *   (see [SubtitleSelectionDecider]).
 */
sealed interface SubtitleSelection {
    data object Unset : SubtitleSelection
    data object Off : SubtitleSelection
    data class Track(val id: Int) : SubtitleSelection
}

/**
 * Result of a [SubtitleSelectionDecider.decide] evaluation.
 *
 * @param sid the desired mpv `sid` value. `null` means subtitles off ("no").
 * @param selection the resulting authoritative selection after applying this decision.
 */
data class SubtitleDecision(
    val sid: Int?,
    val selection: SubtitleSelection
)

/**
 * Pure, testable decision logic for explicit subtitle-selection semantics.
 *
 * This deliberately contains no Android / mpv dependencies so the state machine can be
 * unit-tested directly.
 */
object SubtitleSelectionDecider {

    /**
     * Decide the desired subtitle state for the current [current] selection given the
     * observable player state.
     *
     * @param current the authoritative selection before this evaluation.
     * @param currentSelectedSubId the subtitle id currently selected in the player, or
     *     `null` if none is selected.
     * @param pendingTrackId a track id queued for application on the next load, or `null`.
     * @param autoTrackId the best automatically-resolved subtitle id for [SubtitleSelection.Unset]
     *     mode (already computed by the engine's full scoring logic), or `null` if none.
     * @param explicitTrackExists whether the track referenced by
     *     [SubtitleSelection.Track.id] still exists in the current track list.
     * @param tracksAuthoritative whether the current track list is the complete, post-load
     *     enumeration for this generation. When `false` (e.g. a transient/incomplete refresh
     *     during load or external attachment), a missing explicit track is deferred rather
     *     than immediately downgraded to [SubtitleSelection.Off].
     */
    fun decide(
        current: SubtitleSelection,
        currentSelectedSubId: Int?,
        pendingTrackId: Int?,
        autoTrackId: Int?,
        explicitTrackExists: Boolean,
        tracksAuthoritative: Boolean = true
    ): SubtitleDecision {
        return when (current) {
            is SubtitleSelection.Off -> {
                // Explicit Off always wins. Never auto-select.
                SubtitleDecision(null, SubtitleSelection.Off)
            }

            is SubtitleSelection.Unset -> {
                if (pendingTrackId != null) {
                    // A queued explicit selection takes precedence; FILE_LOADED applies it.
                    // Do not run automatic selection while a pending choice exists.
                    SubtitleDecision(currentSelectedSubId, SubtitleSelection.Unset)
                } else if (currentSelectedSubId != null) {
                    // Something is already selected and the user has not expressed intent;
                    // leave it untouched (do not re-run automatic selection).
                    SubtitleDecision(currentSelectedSubId, SubtitleSelection.Unset)
                } else {
                    // Automatic selection is allowed and nothing is selected yet.
                    SubtitleDecision(autoTrackId, SubtitleSelection.Unset)
                }
            }

            is SubtitleSelection.Track -> {
                if (explicitTrackExists) {
                    // Re-assert the explicit track (covers refreshes / restarts where the
                    // player may have dropped the selection).
                    SubtitleDecision(current.id, SubtitleSelection.Track(current.id))
                } else if (!tracksAuthoritative) {
                    // The track list is not yet authoritative for this generation (transient
                    // or incomplete enumeration during load/external attachment). Defer the
                    // decision; do NOT prematurely revert the user's explicit choice.
                    SubtitleDecision(currentSelectedSubId, current)
                } else {
                    // The explicitly chosen track is gone from an authoritative list. We must
                    // NOT revert to [Unset], because that would let unrelated automatic
                    // selection silently replace the user's choice. The deterministic,
                    // documented fallback is Off.
                    SubtitleDecision(null, SubtitleSelection.Off)
                }
            }
        }
    }
}

/**
 * Retained for backward compatibility with callers that resolve subtitle URIs
 * (e.g. [DantotsuPlayerManager]). Not part of the explicit selection state machine.
 */
object SubtitleSelectionResolver {

    fun resolveSubtitleUri(subUrl: String, embedUrl: String?, mediaUrl: String?): String {
        val trimmed = subUrl.trim()
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://") ||
            trimmed.startsWith("file://") || trimmed.startsWith("content://")
        ) {
            return trimmed
        }

        if (trimmed.startsWith("//")) {
            val baseScheme = when {
                embedUrl?.startsWith("https://") == true -> "https:"
                embedUrl?.startsWith("http://") == true -> "http:"
                mediaUrl?.startsWith("https://") == true -> "https:"
                else -> "https:"
            }
            return "$baseScheme$trimmed"
        }

        val baseUriStr = embedUrl ?: mediaUrl
        if (baseUriStr != null) {
            try {
                val base = URI(baseUriStr)
                return base.resolve(trimmed).toString()
            } catch (_: Exception) {}
        }

        return trimmed
    }
}
