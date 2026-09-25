package ani.dantotsu.parsers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Final correction (review HOLD round 2, Finding 3): known origins never
 * fall back to another repository; unknown (legacy) origins keep the
 * first-match fallback. Identity is canonicalized with cleanRepoKey.
 */
class OriginSelectionTest {

    private data class FakeAvailable(val pkg: String, val repo: String?)

    private val a = FakeAvailable("example.extension", "https://example.test/a/index.pb")
    private val b = FakeAvailable("example.extension", "https://example.test/b/index.pb")

    private fun select(
        entries: List<FakeAvailable>,
        pkg: String,
        origin: String?
    ) = ExtensionRepoMetaHelper.selectOriginEntry(
        entries, pkg, origin,
        pkgOf = { it.pkg }, repoOf = { it.repo }
    )

    @Test
    fun `unknown legacy origin falls back to first match`() {
        assertEquals(a, select(listOf(a, b), a.pkg, null))
        assertEquals(b, select(listOf(b, a), b.pkg, null))
    }

    @Test
    fun `known A selects A among A and B`() {
        assertEquals(a, select(listOf(a, b), a.pkg, a.repo))
        assertEquals(a, select(listOf(b, a), a.pkg, a.repo))
    }

    @Test
    fun `known A with only B present selects nothing`() {
        // Replaces the old auto-fallback expectation: no automatic update
        // candidate from another repo without an explicit install.
        assertNull(select(listOf(b), a.pkg, a.repo))
    }

    @Test
    fun `known A matches normalized representation of A`() {
        val alias = FakeAvailable(
            "example.extension",
            "https://example.test/a/repo.json"
        )
        assertEquals(alias, select(listOf(alias), a.pkg, a.repo))
        assertEquals(alias, select(listOf(alias, b), a.pkg, a.repo))
    }

    @Test
    fun `known A rejects lookalike and different repos`() {
        val lookalike = FakeAvailable("example.extension", "https://example.test/a-evil/index.pb")
        assertNull(select(listOf(lookalike), a.pkg, a.repo))
        assertNull(select(listOf(b), a.pkg, a.repo))
        assertNull(select(listOf(a.copy(repo = null)), a.pkg, a.repo))
    }

    @Test
    fun `explicit install of B after A disappeared records B`() {
        // The install transaction (tested in InstallProvenanceTest) writes
        // the explicitly chosen origin; selection never invents it.
        val pkg = "sel.${System.nanoTime()}"
        val gen = ExtensionRepoMetaHelper.beginInstallProvenance(pkg, b.repo!!, "B")
        assertEquals(true, ExtensionRepoMetaHelper.commitInstallProvenance(pkg, gen))
        assertEquals(b.repo, ExtensionRepoMetaHelper.getInstalledRepo(pkg))
    }
}
