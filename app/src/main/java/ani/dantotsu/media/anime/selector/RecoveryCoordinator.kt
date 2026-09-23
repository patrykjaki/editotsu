package ani.dantotsu.media.anime.selector

import ani.dantotsu.parsers.VideoServer

/**
 * Persistence-owned 5-step recovery.
 *
 *   1. valid exact-name fast path
 *   2. stable exact-release-key match
 *   3. confident source-family continuity match
 *   4. bounded legacy exact-name fallback for unkeyable candidates,
 *      if still needed
 *   5. manual picker
 *
 * Family NEVER outranks a valid exact stable key.
 */
sealed class RecoveryResult {
    /**
     * A resolved runtime candidate. `exactKey` is the real stable
     * exact identity when the candidate is keyable, and null when
     * recovery fell back to an unkeyable exact-name / legacy
     * runtime handle. There are no synthetic sentinel keys.
     */
    data class Resolved(val server: VideoServer, val exactKey: String?) : RecoveryResult()
    object Ambiguous : RecoveryResult()
    object NoMatch : RecoveryResult()
}

object RecoveryCoordinator {
    fun resolve(
        storedExactKey: String?,
        storedFamily: FamilyPayload?,
        selectedName: String?,
        servers: List<VideoServer>,
    ): RecoveryResult {
        if (servers.isEmpty()) return RecoveryResult.NoMatch

        // 1. Exact-name fast path. An unkeyable name match still
        //    resolves the runtime handle, but carries a null exact
        //    key — never a synthetic sentinel.
        if (!selectedName.isNullOrBlank()) {
            val byName = servers.firstOrNull { it.name == selectedName }
            if (byName != null) {
                val provider = StableCandidateBuilder.providerOf(byName)
                val exact = StableCandidateBuilder.exactKey(provider, byName)
                return RecoveryResult.Resolved(byName, exact)
            }
        }

        // 2. Stable exact-key match.
        if (!storedExactKey.isNullOrBlank()) {
            val matches = servers.filter {
                StableCandidateBuilder.exactKey(
                    StableCandidateBuilder.providerOf(it), it,
                ) == storedExactKey
            }
            if (matches.isNotEmpty()) {
                // Two or more servers may share the SAME stored
                // exact key (provider returned a duplicate
                // representation). Collapse to a single deterministic
                // representative; do NOT return Ambiguous solely
                // because the same exact release appeared twice.
                val representative = pickDeterministicRepresentative(matches)
                return RecoveryResult.Resolved(representative, storedExactKey)
            }
        }

        // 3. Family continuity.
        if (storedFamily != null) {
            val candidates = servers.mapNotNull { s ->
                val cand = StableCandidateBuilder.familyCandidate(s) ?: return@mapNotNull null
                cand
            }
            when (val fr = FamilyAutoSelector.select(storedFamily, candidates)) {
                is FamilyAutoResult.Chosen -> {
                    // Several rows may derive the same chosen exact
                    // key. Collect them all and pick the runtime
                    // representative with the SAME deterministic
                    // policy as stored exact-key recovery, so input
                    // arrival order cannot change the outcome.
                    val sameKey = servers.filter {
                        StableCandidateBuilder.exactKey(
                            StableCandidateBuilder.providerOf(it), it,
                        ) == fr.exactKey
                    }
                    if (sameKey.isNotEmpty()) {
                        val resolved = pickDeterministicRepresentative(sameKey)
                        return RecoveryResult.Resolved(resolved, fr.exactKey)
                    }
                }
                is FamilyAutoResult.Ambiguous -> return RecoveryResult.Ambiguous
                is FamilyAutoResult.None -> Unit
            }
        }

        // 4. Bounded legacy exact-name fallback for unkeyable candidates.
        //    Allow a candidate that lacks an exact key to match ONLY when
        //    no other candidate has an exact key AND there is a single
        //    name match. This is the narrowest "legacy" safety net.
        if (!selectedName.isNullOrBlank()) {
            val withKeys = servers.count { server ->
                StableCandidateBuilder.exactKey(
                    StableCandidateBuilder.providerOf(server), server,
                ) != null
            }
            if (withKeys == 0) {
                val byName = servers.filter { it.name == selectedName }
                if (byName.size == 1) {
                    return RecoveryResult.Resolved(byName[0], null)
                }
            }
        }

        // 5. Manual picker.
        return RecoveryResult.NoMatch
    }

    /**
     * Choose a deterministic runtime representative from a list of
     * `VideoServer`s that all derive the SAME exact stable key.
     * Selection uses a total order on (server name, extraData key
     * set, embed URL) so the choice is independent of the input
     * list's arrival order.
     */
    private fun pickDeterministicRepresentative(
        candidates: List<VideoServer>,
    ): VideoServer {
        return candidates.minWith(
            compareBy<VideoServer>(
                { it.name },
                { it.embed.url },
                { it.extraData?.entries?.sortedBy { it.key }
                    ?.joinToString("|") { "${it.key}=${it.value}" } ?: "" },
            )
        )
    }
}
