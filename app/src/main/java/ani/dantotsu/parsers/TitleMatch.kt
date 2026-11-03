package ani.dantotsu.parsers

import me.xdrop.fuzzywuzzy.FuzzySearch

/** Synonym searches autoSearch tries at most, after the main and romaji titles. */
internal const val MAX_SYNONYM_SEARCHES = 4

/**
 * How well an extension's [candidate] title matches [target], 0-100, ignoring case
 * and word order ("Kimetsu no Yaiba Demon Slayer" = "Demon Slayer Kimetsu no Yaiba").
 *
 * Unlike upstream, this does not use FuzzySearch.tokenSetRatio: it scores 100
 * whenever one title's words are all in the other, so "Attack on Titan" would
 * match "Attack on Titan Season 2" as well as the exact title.
 */
internal fun titleMatchScore(candidate: String, target: String): Int {
    val a = candidate.lowercase().trim()
    val b = target.lowercase().trim()
    if (a == b) return 100
    return maxOf(FuzzySearch.ratio(a, b), FuzzySearch.tokenSortRatio(a, b))
}
