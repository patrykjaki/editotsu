package ani.dantotsu.parsers

import eu.kanade.tachiyomi.animesource.model.Hoster
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Phase 5 lazy-compat probe (upstream 61e8248b, NOT ported): does the
 * lazy-first rule discard valid later sources?
 *
 * The probe encodes upstream's selection rule verbatim
 * (`active = sorted.filterNot(lazy)`; fetch `[first]` if active empty but
 * sorted non-empty, else active) against fixtures, plus a simulated fetch
 * where each hoster either resolves or fails. No production code is
 * involved — the question is a property of the rule itself.
 */
class LazyCompatProbeTest {

    private fun hoster(name: String, lazy: Boolean) = Hoster(
        hosterUrl = "https://$name.test/v",
        hosterName = name,
        lazy = lazy
    )

    /** Verbatim replica of upstream 61e8248b's selection rule. */
    private fun upstreamRule(sortedHosters: List<Hoster>): List<Hoster> {
        val activeHosters = sortedHosters.filterNot { it.lazy }
        return if (activeHosters.isEmpty() && sortedHosters.isNotEmpty()) {
            listOf(sortedHosters.first())
        } else {
            activeHosters
        }
    }

    /** Baseline behavior: every hoster is fetched. */
    private fun baselineRule(sortedHosters: List<Hoster>): List<Hoster> = sortedHosters

    /** Simulates resolution: names in [failing] resolve to nothing. */
    private fun fetch(
        rule: (List<Hoster>) -> List<Hoster>,
        hosters: List<Hoster>,
        failing: Set<String> = emptySet()
    ): List<String> = rule(hosters)
        .filterNot { it.hosterName in failing }
        .map { it.hosterName }

    @Test
    fun `ordinary list unchanged`() {
        val hs = listOf(hoster("a", false), hoster("b", false))
        assertEquals(listOf("a", "b"), fetch(::upstreamRule, hs))
    }

    @Test
    fun `mixed list drops lazy entries`() {
        val hs = listOf(hoster("a", false), hoster("b", true))
        assertEquals(listOf("a"), fetch(::upstreamRule, hs))
        assertEquals(listOf("a", "b"), fetch(::baselineRule, hs))
    }

    @Test
    fun `all-lazy fetches first only`() {
        val hs = listOf(hoster("a", true), hoster("b", true))
        assertEquals(listOf("a"), fetch(::upstreamRule, hs))
    }

    @Test
    fun `all-lazy with invalid first discards valid later source`() {
        // The decisive case: baseline recovers via "b"; the upstream rule
        // returns nothing — a valid playable source disappears from results.
        val hs = listOf(hoster("a", true), hoster("b", true))
        assertEquals(emptyList<String>(), fetch(::upstreamRule, hs, failing = setOf("a")))
        assertEquals(listOf("b"), fetch(::baselineRule, hs, failing = setOf("a")))
    }

    @Test
    fun `non-lazy first failure still recovers later entries`() {
        val hs = listOf(hoster("a", false), hoster("b", false))
        assertEquals(listOf("b"), fetch(::upstreamRule, hs, failing = setOf("a")))
    }
}
