package ani.dantotsu.parsers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Review HOLD Finding 3: same package from multiple repositories.
 * Available-list identity is pkg+repo; selection must follow the
 * installed origin and never let mere discovery relabel it.
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
    fun `installing A records A`() {
        val pkg = "sel.${System.nanoTime()}"
        ExtensionRepoMetaHelper.beginInstallProvenance(pkg, a.repo!!, "A")
        assertEquals(a.repo, ExtensionRepoMetaHelper.getInstalledRepo(pkg))
    }

    @Test
    fun `discovery of B does not relabel installed A`() {
        // Installed origin A; refresh lists A and B.
        assertEquals(a, select(listOf(a, b), a.pkg, a.repo))
        assertEquals(a, select(listOf(b, a), a.pkg, a.repo))
    }

    @Test
    fun `failed B install leaves A intact`() {
        val pkg = "sel.${System.nanoTime()}"
        ExtensionRepoMetaHelper.saveInstalledExtensionRepo(pkg, a.repo!!, "A")
        val previous = ExtensionRepoMetaHelper.beginInstallProvenance(pkg, b.repo!!, "B")
        ExtensionRepoMetaHelper.rollbackInstallProvenance(pkg, b.repo!!, "B", previous)
        assertEquals(a.repo, ExtensionRepoMetaHelper.getInstalledRepo(pkg))
    }

    @Test
    fun `explicit switch to B records B`() {
        val pkg = "sel.${System.nanoTime()}"
        ExtensionRepoMetaHelper.saveInstalledExtensionRepo(pkg, a.repo!!, "A")
        ExtensionRepoMetaHelper.beginInstallProvenance(pkg, b.repo!!, "B")
        // Success path: no rollback — B is now the origin.
        assertEquals(b.repo, ExtensionRepoMetaHelper.getInstalledRepo(pkg))
    }

    @Test
    fun `unknown origin falls back to first pkg match`() {
        assertEquals(a, select(listOf(a, b), a.pkg, null))
        assertEquals(b, select(listOf(b, a), b.pkg, null))
        assertNull(select(listOf(a, b), "other.pkg", "https://example.test/a/index.pb"))
    }

    @Test
    fun `origin gone from list falls back without crash`() {
        // A was unlisted by its repo; only B offers the pkg now.
        assertEquals(b, select(listOf(b), a.pkg, a.repo))
    }
}
