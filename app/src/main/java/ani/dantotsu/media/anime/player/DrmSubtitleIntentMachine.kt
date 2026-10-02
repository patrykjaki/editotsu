package ani.dantotsu.media.anime.player

/**
 * MAX MERGE M4 correction (HOLD7): DRM subtitle-selection intent machine.
 * Pure, host-tested. Mirrors the MPV `SubtitleSelection` contract
 * (persisted Off stays Off, explicit embedded restores, request-carried
 * automatic externals never beat Off, latest live-explicit intent wins,
 * stale updates cannot overwrite newer intent) for the Media3 backend.
 * The engine is a thin adapter: it translates Media3 track state into
 * [TrackRef]s, executes returned [Action]s, and never guesses.
 *
 * Identity: [SubtitleSelection.Track.id] references [TrackRef.id] (the
 * engine's deterministic per-load text-track index); request-carried and
 * live externals are matched by [TrackRef.externalId] (the resolved URI
 * propagated through `SubtitleConfiguration.id`).
 */
class DrmSubtitleIntentMachine {

    data class TrackRef(
        val id: Int,
        val externalId: String?,
        val language: String?,
        val name: String?,
        val default: Boolean = false,
        val forced: Boolean = false,
    )

    sealed interface Action {
        data object NoOp : Action
        data object DisableText : Action
        data class SelectTrack(val id: Int) : Action
        data object EnableTextAuto : Action
    }

    var generation: Long = 0L
        private set
    var selection: SubtitleSelection = SubtitleSelection.Unset
        private set
    private var pendingExternalIds: LinkedHashSet<String> = LinkedHashSet()
    private var explicitPendingExternalId: String? = null

    /**
     * New load generation. preferredSubLang "None" starts Off (action
     * disables text immediately); anything else starts Unset. Externally
     * selected request-carried URIs become pending adoption candidates
     * unless Off already won.
     */
    fun onLoad(
        generation: Long,
        preferredSubLang: String?,
        requestSelectedExternalIds: List<String>,
    ): Action {
        this.generation = generation
        pendingExternalIds = LinkedHashSet()
        explicitPendingExternalId = null
        return if (preferredSubLang != null && preferredSubLang.equals("None", ignoreCase = true)) {
            selection = SubtitleSelection.Off
            Action.DisableText
        } else {
            selection = SubtitleSelection.Unset
            pendingExternalIds.addAll(requestSelectedExternalIds)
            Action.NoOp
        }
    }

    /**
     * Track enumeration for [generation]. Stale generations are ignored so
     * late updates can never resurrect older intent. Returns the single
     * action bringing the player to the authoritative state.
     */
    fun onTracksAvailable(
        generation: Long,
        tracks: List<TrackRef>,
        autoId: Int?,
        currentSelectedId: Int?,
        tracksAuthoritative: Boolean = true,
    ): Action {
        if (generation != this.generation) return Action.NoOp
        // A live-explicit external that just materialized beats everything
        // except a newer explicit Track selection.
        explicitPendingExternalId?.let { wanted ->
            val ref = tracks.firstOrNull { it.externalId == wanted }
            if (ref != null) {
                explicitPendingExternalId = null
                selection = SubtitleSelection.Track(ref.id)
                return Action.SelectTrack(ref.id)
            }
        }
        return when (val current = selection) {
            is SubtitleSelection.Off -> Action.DisableText
            is SubtitleSelection.Unset -> {
                val pending = pendingExternalIds.firstNotNullOfOrNull { wanted ->
                    tracks.firstOrNull { it.externalId == wanted }
                }
                if (pending != null) {
                    pendingExternalIds.remove(pending.externalId)
                    selection = SubtitleSelection.Track(pending.id)
                    Action.SelectTrack(pending.id)
                } else if (currentSelectedId == null && autoId != null &&
                    tracks.any { it.id == autoId }
                ) {
                    Action.SelectTrack(autoId)
                } else {
                    Action.NoOp
                }
            }
            is SubtitleSelection.Track -> {
                if (tracks.any { it.id == current.id }) {
                    Action.SelectTrack(current.id)
                } else if (!tracksAuthoritative) {
                    // Incomplete enumeration mid-attach: defer, never
                    // downgrade the explicit choice on partial data.
                    Action.NoOp
                } else {
                    selection = SubtitleSelection.Off
                    Action.DisableText
                }
            }
        }
    }

    /**
     * Live external attachment. select=false or non-explicit calls only
     * register a pending candidate (Unset only); an explicit select
     * immediately becomes the latest intent, re-enabling text when needed.
     */
    fun onLiveExternal(
        externalId: String,
        select: Boolean,
        explicit: Boolean,
        knownTracks: List<TrackRef>,
    ): Action {
        if (!select || !explicit) {
            if (selection is SubtitleSelection.Unset) pendingExternalIds.add(externalId)
            return Action.NoOp
        }
        val ref = knownTracks.firstOrNull { it.externalId == externalId }
        return if (ref != null) {
            explicitPendingExternalId = null
            selection = SubtitleSelection.Track(ref.id)
            Action.SelectTrack(ref.id)
        } else {
            explicitPendingExternalId = externalId
            Action.EnableTextAuto
        }
    }

    /** Explicit user track selection (engine selectSubtitleTrack). Newest intent wins. */
    fun onSelectTrack(id: Int?): Action {
        explicitPendingExternalId = null
        return if (id == null) {
            selection = SubtitleSelection.Off
            Action.DisableText
        } else {
            selection = SubtitleSelection.Track(id)
            Action.SelectTrack(id)
        }
    }
}

/**
 * Narrow automatic subtitle resolution for Unset (pure, unit-tested):
 * persisted `Embedded:<id|lang>` restores by id then language; a language
 * preference matches language/name; a blank preference prefers English;
 * then default non-forced, then first track. This is initial-intent parity,
 * not the full MPV scoring model (signs/songs weighting stays MPV-only).
 */
fun resolveDrmAutoSubtitleId(
    tracks: List<DrmSubtitleIntentMachine.TrackRef>,
    preferredSubLang: String?,
): Int? {
    if (tracks.isEmpty()) return null
    if (preferredSubLang != null && preferredSubLang.startsWith("Embedded:", ignoreCase = true)) {
        val target = preferredSubLang.removePrefix("Embedded:").trim()
        target.toIntOrNull()?.let { id ->
            tracks.firstOrNull { it.id == id }?.let { return it.id }
        }
        tracks.firstOrNull {
            it.language?.equals(target, ignoreCase = true) == true ||
                it.name?.contains(target, ignoreCase = true) == true
        }?.let { return it.id }
        return null
    }
    val lang = preferredSubLang?.takeIf { it.isNotBlank() }
    if (lang != null) {
        tracks.firstOrNull {
            it.language?.equals(lang, ignoreCase = true) == true ||
                it.name?.contains(lang, ignoreCase = true) == true
        }?.let { return it.id }
    } else {
        tracks.firstOrNull {
            it.language?.equals("en", ignoreCase = true) == true ||
                it.language?.equals("eng", ignoreCase = true) == true ||
                it.name?.contains("english", ignoreCase = true) == true
        }?.let { return it.id }
    }
    tracks.firstOrNull { it.default && !it.forced }?.let { return it.id }
    return tracks.firstOrNull()?.id
}
