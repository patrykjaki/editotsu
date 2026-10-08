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
     * Pre-rebuild (id, stableKey) snapshot from the engine, set on every
     * source rebuild that carries live intent. The next evaluations verify
     * append-growth against it (prefix-preserved superset ⇒ benign attach,
     * trust restored); anything else keeps positional trust void for
     * ambiguous selections. Observes enumerations, never predicts from
     * request URLs. Cleared on verified growth, explicit Off, fresh loads,
     * and exact-identity adoptions.
     */
    private var pendingVerification: List<Pair<Int, String>>? = null
    /**
     * HOLD13-A: whether the current selection was ambiguous (shared its
     * language identity with a sibling) in the pre-rebuild snapshot. A
     * formerly ambiguous selection can never become "confidently unique"
     * merely because a sibling disappeared — that survivor proves nothing
     * about the selected track. Computed at note time; cleared together
     * with [pendingVerification].
     */
    private var boundaryAmbiguous: Boolean = false

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
        trackLangName = null
        pendingVerification = null
        boundaryAmbiguous = false
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
                pendingVerification = null
                boundaryAmbiguous = false
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
                    pendingVerification = null
                    boundaryAmbiguous = false
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
                // HOLD12-B: verify post-rebuild order when the engine left a
                // snapshot. Append-growth (prefix-preserved superset) proves
                // the rebuild kept existing indices; anything else leaves
                // positional trust void for ambiguous selections below.
                pendingVerification?.let { prev ->
                    val cur = tracks.map { it.id to it.stableKey() }
                    if (isAppendGrowth(prev, cur)) {
                        pendingVerification = null
                        // Growth proves indices survived — but only a
                        // pairwise-distinct prefix proves no permutation is
                        // hiding inside it (identical duplicates could have
                        // swapped under identical key strings). Ambiguous
                        // pre-images keep the boundary guard.
                        if (prev.map { it.second }.distinct().size == prev.size) {
                            boundaryAmbiguous = false
                        }
                    }
                }
                // HOLD11: the selected index still holding the identical key
                // proves no observable change to the selected track — benign
                // additions elsewhere (or same-order rebuilds) keep it, even
                // when siblings share its language identity.
                val sameIndexKey = trackKey?.let { key ->
                    tracks.getOrNull(current.id)?.stableKey() == key
                } ?: false
                // Ambiguity is evaluated against the CURRENT enumeration: a
                // non-verified source boundary voids positional trust for
                // ambiguous selections (identical duplicates are
                // indistinguishable across a permutation).
                val ambiguous = trackLangName?.let { wanted ->
                    tracks.count { it.langKey() == wanted } > 1
                } ?: false
                val unverified = pendingVerification != null
                // HOLD13: boundaryAmbiguous persists across growth that
                // cannot prove the absence of a permutation (identical
                // duplicates swapping under identical key strings). Either
                // void source blocks positional trust for ambiguous picks.
                if (sameIndexKey && !(ambiguous && (unverified || boundaryAmbiguous))) {
                    reassert(current.id, currentSelectedId)
                } else {
                    val unique = trackLangName?.let { wanted ->
                        tracks.filter { it.langKey() == wanted }.singleOrNull()
                    }
                    if (unique != null && tracksAuthoritative && !boundaryAmbiguous) {
                        // Confident order-independent identity on a settled
                        // list, for a selection unambiguous since the
                        // boundary: follow it. (HOLD12-A: transitional
                        // partial lists must defer instead — a duplicate
                        // still unparsed looks falsely "unique". HOLD13-A:
                        // formerly ambiguous selections never resolve here —
                        // a surviving sibling proves nothing about the
                        // selected track.)
                        selection = SubtitleSelection.Track(unique.id)
                        trackKey = unique.stableKey()
                        trackLangName = unique.langKey()
                        pendingVerification = null
                        boundaryAmbiguous = false
                        reassert(unique.id, currentSelectedId)
                    } else if (trackKey == null && trackLangName == null &&
                        tracks.any { it.id == current.id } && tracksAuthoritative
                    ) {
                        // No stable identity known: legacy numeric path,
                        // settled lists only (numeric ids are meaningless
                        // mid-enumeration).
                        reassert(current.id, currentSelectedId)
                    } else if (!tracksAuthoritative) {
                        Action.NoOp
                    } else {
                        selection = SubtitleSelection.Off
                        trackKey = null
                        trackLangName = null
                        pendingVerification = null
                        boundaryAmbiguous = false
                        Action.DisableText
                    }
                }
            }
        }
    }

    /**
     * Engine-reported source boundary: snapshot of the pre-rebuild text
     * enumeration as (id, stableKey) pairs, taken by the engine from its
     * current track lists immediately before setMediaSource. The next
     * evaluations verify append-growth against it (prefix-preserved
     * superset ⇒ benign attach, trust restored); any other outcome keeps
     * positional trust void for ambiguous selections. Replaces any prior
     * pending snapshot.
     */
    fun noteSourceRebuilt(previousPairs: List<Pair<Int, String>>) {
        pendingVerification = previousPairs.toList()
        // HOLD13-A: ambiguity is judged against the PRE-rebuild siblings,
        // never the post-rebuild survivors. A selection ambiguous here
        // cannot become "confidently unique" later merely because a
        // sibling disappeared.
        boundaryAmbiguous = trackLangName?.let { wanted ->
            previousPairs.count { langKeyOfStableKey(it.second) == wanted } > 1
        } ?: false
    }

    /**
     * Recovers the order-insensitive language identity from a stored
     * stable key without needing the original track: externals keep their
     * URI, embedded keys carry `emb:<lang>|<name>` after the coordinates.
     * Pure and unit-tested alongside the machine.
     */
    fun langKeyOfStableKey(stableKey: String): String =
        if (stableKey.startsWith("emb:")) {
            "emb:" + stableKey.removePrefix("emb:").split(':').last()
        } else {
            stableKey
        }

    /**
     * Pure append-growth check (unit-tested): [current] is exactly
     * [previous] plus appended entries, so pre-existing indices provably
     * survived this rebuild. An empty previous snapshot proves nothing
     * (HOLD13 acceptance 4) and never counts as growth. Removal, reorder,
     * or same-size permutation of identical keys is NOT growth.
     */
    fun isAppendGrowth(
        previous: List<Pair<Int, String>>,
        current: List<Pair<Int, String>>,
    ): Boolean =
        previous.isNotEmpty() &&
            current.size > previous.size &&
            current.subList(0, previous.size) == previous

    /**
     * HOLD9 §5: reasserting an already-correct selection is a NoOp, never a
     * selector write — identical override writes cannot feed an
     * onTracksChanged feedback loop.
     */
    private fun reassert(id: Int, currentSelectedId: Int?): Action =
        if (currentSelectedId == id) Action.NoOp else Action.SelectTrack(id)



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
            pendingVerification = null
            boundaryAmbiguous = false
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
        pendingVerification = null
        boundaryAmbiguous = false
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

/**
 * Observed subtitle-selector state, read by the engine from the live
 * player and track selector at apply time.
 */
data class ObservedSubtitleSelectorState(
    val textDisabled: Boolean,
    val selectedSubtitleId: Int?,
)

/**
 * HOLD12-C: idempotence depends on actual applied/observed selector state,
 * not only the last requested action. A repeated action is skipped only
 * when the player already reflects its end state; any drift (e.g. another
 * track selected since) permits reapplication. [textOverrideActive] is
 * write-tracked by the engine (set on SelectTrack writes, cleared on
 * DisableText/EnableTextAuto writes, source rebuilds and release) because
 * ExoPlayer silently drops overrides for dead groups, making bare
 * observation unreliable exactly when it matters.
 */
fun shouldSkipSubtitleWrite(
    lastApplied: DrmSubtitleIntentMachine.Action?,
    next: DrmSubtitleIntentMachine.Action,
    observed: ObservedSubtitleSelectorState,
    textOverrideActive: Boolean,
): Boolean {
    if (next is DrmSubtitleIntentMachine.Action.NoOp) return true
    if (lastApplied != next) return false
    return when (next) {
        is DrmSubtitleIntentMachine.Action.DisableText -> observed.textDisabled
        is DrmSubtitleIntentMachine.Action.SelectTrack ->
            !observed.textDisabled && observed.selectedSubtitleId == next.id
        is DrmSubtitleIntentMachine.Action.EnableTextAuto ->
            !observed.textDisabled && !textOverrideActive
        else -> false
    }
}
