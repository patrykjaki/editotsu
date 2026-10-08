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
        val groupIndex: Int = 0,
        val indexInGroup: Int = 0,
    ) {
        /**
         * Discriminating identity: the resolved URI for externals (stable
         * across rebuilds), otherwise group + intra-group position +
         * language/name. Two distinct embedded tracks never share one, so
         * `firstOrNull` cannot retarget across them.
         */
        fun stableKey(): String = externalId ?: "emb:g$groupIndex:i$indexInGroup:${language}|${name}"

        /**
         * Order-insensitive fallback identity: URI for externals, otherwise
         * language/name only. Used solely to follow a confidently unique
         * track across an index reorder; ambiguous matches never resolve.
         */
        fun langKey(): String = externalId ?: "emb:${language}|${name}"
    }

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
     * Stable key of the current [SubtitleSelection.Track], used to survive
     * numeric id reassignment across player rebuilds.
     */
    private var trackKey: String? = null
    /** Order-insensitive identity companion to [trackKey] (reorder follow). */
    private var trackLangName: String? = null

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
        trackKey = null
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
     *
     * @param preferExplicit true when a persisted subtitle preference
     * (language or `Embedded:` target) exists: it beats Media3's automatic
     * choice. Without a saved preference, an existing Media3 selection is
     * left alone.
     */
    fun onTracksAvailable(
        generation: Long,
        tracks: List<TrackRef>,
        autoId: Int?,
        currentSelectedId: Int?,
        tracksAuthoritative: Boolean = true,
        preferExplicit: Boolean = false,
    ): Action {
        if (generation != this.generation) return Action.NoOp
        // A live-explicit external that just materialized beats everything
        // except a newer explicit Track selection.
        explicitPendingExternalId?.let { wanted ->
            val ref = tracks.firstOrNull { it.externalId == wanted }
            if (ref != null) {
                explicitPendingExternalId = null
                selection = SubtitleSelection.Track(ref.id)
                trackKey = ref.stableKey()
                trackLangName = ref.langKey()
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
                    trackKey = pending.stableKey()
                    trackLangName = pending.langKey()
                    Action.SelectTrack(pending.id)
                } else if (autoId != null && tracks.any { it.id == autoId }) {
                    // HOLD8-A: an explicit saved preference is honored even
                    // when Media3 already auto-selected something else; once
                    // applied, further callbacks are idempotent NoOps. Later
                    // explicit user selections leave Unset, so they still win.
                    if (currentSelectedId == autoId) {
                        Action.NoOp
                    } else if (preferExplicit || currentSelectedId == null) {
                        Action.SelectTrack(autoId)
                    } else {
                        Action.NoOp
                    }
                } else {
                    Action.NoOp
                }
            }
            is SubtitleSelection.Track -> {
                val byKey = trackKey?.let { key ->
                    tracks.firstOrNull { it.stableKey() == key }
                }
                if (byKey != null) {
                    if (byKey.id != current.id) {
                        // Rebuild reassigned numeric ids: follow the stable
                        // identity, never the recycled number.
                        selection = SubtitleSelection.Track(byKey.id)
                    }
                    Action.SelectTrack(byKey.id)
                } else if (trackKey != null) {
                    // HOLD9: no exact identity. A confidently unique
                    // language/name match may still follow a reorder; an
                    // ambiguous or absent match never silently switches.
                    val unique = trackLangName?.let { wanted ->
                        tracks.filter { it.langKey() == wanted }
                    }?.singleOrNull()
                    if (unique != null && unique.id != current.id) {
                        selection = SubtitleSelection.Track(unique.id)
                        trackKey = unique.stableKey()
                        trackLangName = unique.langKey()
                        Action.SelectTrack(unique.id)
                    } else if (unique != null) {
                        // Same index, refreshed identity (e.g. group moved
                        // without renumbering): converge to the exact key.
                        trackKey = unique.stableKey()
                        trackLangName = unique.langKey()
                        Action.SelectTrack(current.id)
                    } else if (!tracksAuthoritative) {
                        Action.NoOp
                    } else {
                        selection = SubtitleSelection.Off
                        trackKey = null
                        trackLangName = null
                        Action.DisableText
                    }
                } else if (tracks.any { it.id == current.id }) {
                    // No stable identity known: legacy numeric path.
                    Action.SelectTrack(current.id)
                } else if (!tracksAuthoritative) {
                    // Incomplete enumeration mid-attach: defer, never
                    // downgrade the explicit choice on partial data.
                    Action.NoOp
                } else {
                    // Authoritative list without the intended track: the
                    // documented MPV-consistent fallback is Off.
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
            trackKey = ref.stableKey()
            trackLangName = ref.langKey()
            Action.SelectTrack(ref.id)
        } else {
            explicitPendingExternalId = externalId
            Action.EnableTextAuto
        }
    }

    /**
     * Explicit user track selection (engine selectSubtitleTrack). Newest
     * intent wins. The engine passes the selected [TrackRef] when known so
     * rebuilds follow stable identity; a null ref keeps numeric behavior.
     */
    fun onSelectTrack(id: Int?, ref: TrackRef? = null): Action {
        explicitPendingExternalId = null
        return if (id == null) {
            selection = SubtitleSelection.Off
            trackKey = null
            trackLangName = null
            Action.DisableText
        } else {
            selection = SubtitleSelection.Track(id)
            trackKey = ref?.stableKey()
            trackLangName = ref?.langKey()
            Action.SelectTrack(id)
        }
    }
}

/**
 * HOLD9: adapter authority determination. The engine feeds every
 * [onTracksAvailable] evaluation with this (never a bare `isNotEmpty`),
 * and host tests drive this function directly: an enumeration is
 * authoritative for subtitle decisions only when it bears text tracks
 * AND every attached external URI is present. Anything else is a
 * transitional/partial list (prepare, merge, rebuild, single attach)
 * and must defer rather than mutate explicit intent.
 */
fun computeTracksAuthoritative(
    textRefs: List<DrmSubtitleIntentMachine.TrackRef>,
    attachedExternalIds: Set<String>,
): Boolean {
    if (textRefs.isEmpty()) return false
    return attachedExternalIds.all { wanted ->
        textRefs.any { it.externalId == wanted }
    }
}

/**
 * Narrow automatic subtitle resolution for Unset (pure, unit-tested):
 * persisted `Embedded:<id|lang>` restores by id then language; a language
 * preference matches language/name; a blank preference prefers English;
 * then default non-forced, then first track.
 *
 * HOLD8-A: an explicit preference that matches nothing resolves to null —
 * never a fabricated unrelated id. Only the blank-preference path falls
 * back to default/first. This is initial-intent parity, not the full MPV
 * scoring model (signs/songs weighting stays MPV-only).
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
        return null
    }
    tracks.firstOrNull {
        it.language?.equals("en", ignoreCase = true) == true ||
            it.language?.equals("eng", ignoreCase = true) == true ||
            it.name?.contains("english", ignoreCase = true) == true
    }?.let { return it.id }
    tracks.firstOrNull { it.default && !it.forced }?.let { return it.id }
    return tracks.firstOrNull()?.id
}
