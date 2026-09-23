package ani.dantotsu.media.anime.selector

/**
 * Persistence-owned family auto-selection.
 *
 * The hard family anchor is the EXACT provider package plus the
 * canonical `groupKey`. Only STORED soft fields participate in
 * ranking. The ranking is a strict lexicographic comparison in this
 * fixed order:
 *
 *   trackerKey
 *   resolution
 *   audioMode
 *   audioFormat
 *   sourceService
 *   codec
 *   hdr
 *
 * Candidates that resolve to the same exact stable key may be
 * collapsed to one underlying exact candidate. If two DISTINCT exact
 * keys remain tied at the best soft vector, the result is
 * `AMBIGUOUS` -> show picker.
 *
 * Seeders, arrival order, file index, and UI order are NEVER used to
 * break an ambiguous auto-select tie.
 */
data class FamilyCandidate(
    val exactKey: String,
    val providerPkg: String,
    val groupKey: String,
    val soft: Map<String, String> = emptyMap(),
)

sealed class FamilyAutoResult {
    object None : FamilyAutoResult()
    data class Chosen(val exactKey: String) : FamilyAutoResult()
    object Ambiguous : FamilyAutoResult()
}

object FamilyAutoSelector {
    private val SOFT_RANK_ORDER = listOf(
        "trackerKey", "resolution", "audioMode",
        "audioFormat", "sourceService", "codec", "hdr",
    )

    /**
     * Strict lexicographic comparison of two candidates against a
     * stored family. A match at the first priority position
     * dominates every later position. The first priority position
     * where the two candidates differ in their match status
     * (one matches, the other does not) decides the winner.
     *
     * Returns:
     *   > 0  -> a is better
     *   < 0  -> b is better
     *   = 0  -> tied at the full soft vector
     */
    private fun compareLex(
        stored: List<String?>,
        a: List<String?>,
        b: List<String?>,
    ): Int {
        for (i in stored.indices) {
            val s = stored[i]
            if (s == null) continue
            val aHit = a[i] == s
            val bHit = b[i] == s
            if (aHit == bHit) continue
            return if (aHit) 1 else -1
        }
        return 0
    }

    fun select(
        stored: FamilyPayload?,
        candidates: List<FamilyCandidate>,
    ): FamilyAutoResult {
        if (stored == null) return FamilyAutoResult.None
        if (candidates.isEmpty()) return FamilyAutoResult.None

        val hardMatched = candidates.filter {
            it.providerPkg == stored.providerPkg && it.groupKey == stored.groupKey
        }
        if (hardMatched.isEmpty()) return FamilyAutoResult.None

        // Collapse candidates with the same exact stable key into
        // one underlying exact candidate. The chosen representative
        // MUST be independent of input arrival order. We use a
        // total order: first the strict-lex match score against
        // the stored vector, then the number of non-null soft
        // fields, then the exact key, then the soft-map string
        // form. This guarantees a single deterministic pick per
        // exact-key group.
        val storedVec = SOFT_RANK_ORDER.map { stored.soft[it] }
        val collapsed = hardMatched
            .groupBy { it.exactKey }
            .map { (_, group) -> bestRepresentative(group, storedVec) }

        if (collapsed.size == 1) {
            return FamilyAutoResult.Chosen(collapsed[0].exactKey)
        }

        // Strict lexicographic ranking: only stored soft fields
        // participate. Pick the unique best; ties are AMBIGUOUS.
        var best: FamilyCandidate? = null
        var bestTies = 0
        for (cand in collapsed) {
            if (best == null) {
                best = cand
                continue
            }
            val bestVec = SOFT_RANK_ORDER.map { best!!.soft[it] }
            val candVec = SOFT_RANK_ORDER.map { cand.soft[it] }
            val cmp = compareLex(storedVec, bestVec, candVec)
            when {
                cmp < 0 -> {
                    best = cand
                    bestTies = 0
                }
                cmp == 0 -> bestTies += 1
                else -> {
                    // cand worse than best; do nothing
                }
            }
        }
        return if (bestTies == 0) FamilyAutoResult.Chosen(best!!.exactKey)
        else FamilyAutoResult.Ambiguous
    }

    /**
     * Pick the deterministic best representative of a group of
     * `FamilyCandidate` instances that share the same exact stable
     * key. The chosen candidate is the one whose soft vector
     * matches the STORED soft vector best under the SAME strict
     * lexicographic comparator used for distinct-exact-key
     * ranking. Strict-ties are broken by a total order that does
     * not depend on input position:
     *   1. larger soft map (more soft fields)
     *   2. lexicographically smaller exact key
     *   3. lexicographically smaller soft-map string form
     *
     * This guarantees that the chosen representative is independent
     * of the order in which the candidates appeared in the input.
     *
     * Example: stored [nyaasi, 1080p, ...]
     *   rep good [nyaasi, 720p, ...] beats rep bad [nekobt, 1080p, ...]
     *   because compareLex finds the first differing priority
     *   (position 0) and rep good matches it.
     */
    private fun bestRepresentative(
        group: List<FamilyCandidate>,
        storedVec: List<String?>,
    ): FamilyCandidate {
        var best = group[0]
        for (i in 1 until group.size) {
            val cand = group[i]
            val bestVec = SOFT_RANK_ORDER.map { best.soft[it] }
            val candVec = SOFT_RANK_ORDER.map { cand.soft[it] }
            val cmp = compareLex(storedVec, bestVec, candVec)
            if (cmp < 0) {
                best = cand
            } else if (cmp == 0 && tieBreakLess(best, cand)) {
                best = cand
            }
        }
        return best
    }

    /**
     * Deterministic total-order tie-break for two candidates whose
     * soft vectors are tied under the strict-lex comparator.
     * Returns true iff `b` should be preferred over `a`.
     */
    private fun tieBreakLess(
        a: FamilyCandidate,
        b: FamilyCandidate,
    ): Boolean {
        if (a.soft.size != b.soft.size) return b.soft.size > a.soft.size
        if (a.exactKey != b.exactKey) return b.exactKey < a.exactKey
        return softMapRepr(b.soft) < softMapRepr(a.soft)
    }

    private fun softMapRepr(m: Map<String, String>): String =
        m.entries.joinToString("|") { "${it.key}=${it.value}" }
}
