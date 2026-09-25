package ani.dantotsu.parsers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Upstream 4b8d09be: repository metadata parsing, badge derivation and
 * per-repo isolation. Pure helpers + in-memory cache (PrefManager
 * persistence degrades silently without Android, cache still works).
 */
class ExtensionRepoMetaTest {

    @Test
    fun `cleanRepoKey strips index variants`() {
        assertEquals(
            "https://example.test/repo",
            ExtensionRepoMetaHelper.cleanRepoKey("https://example.test/repo/index.pb")
        )
        assertEquals(
            "https://example.test/repo",
            ExtensionRepoMetaHelper.cleanRepoKey("https://example.test/repo/repo.json")
        )
        assertEquals(
            "https://example.test/repo",
            ExtensionRepoMetaHelper.cleanRepoKey("https://example.test/repo/index.min.json/")
        )
    }

    @Test
    fun `github raw url resolves to project page`() {
        assertEquals(
            "https://github.com/keiyoushi/extensions",
            ExtensionRepoMetaHelper.extractGitRepoPage(
                "https://raw.githubusercontent.com/keiyoushi/extensions/repo/index.min.json"
            )
        )
        assertNull(ExtensionRepoMetaHelper.extractGitRepoPage(null))
        assertNull(ExtensionRepoMetaHelper.extractGitRepoPage("not a url"))
    }

    @Test
    fun `badge prefers shortName then name then url`() {
        val repo = "https://example.test/custom-repo/index.pb"
        ExtensionRepoMetaHelper.saveMeta(
            repoUrl = repo,
            name = "Custom Repo",
            shortName = "Custom",
            website = "",
            discord = null
        )
        assertEquals("Custom", ExtensionRepoMetaHelper.getRepoBadgeName(repo))
    }

    @Test
    fun `missing metadata falls back to url-derived badge`() {
        val badge = ExtensionRepoMetaHelper.getRepoBadgeName(
            "https://example.test/some-repo/index.min.json"
        )
        assertEquals("some-repo", badge)
    }

    @Test
    fun `repos do not donate metadata to each other`() {
        val repoA = "https://example.test/repo-a-${System.nanoTime()}/index.pb"
        val repoB = "https://example.test/repo-b-${System.nanoTime()}/index.pb"
        ExtensionRepoMetaHelper.saveMeta(
            repoUrl = repoA,
            name = "Repo A",
            shortName = "A",
            website = "",
            discord = null
        )
        assertEquals("A", ExtensionRepoMetaHelper.getRepoBadgeName(repoA))
        // B was never saved: URL-derived badge, never A's metadata.
        val badgeB = ExtensionRepoMetaHelper.getRepoBadgeName(repoB)
        assertTrue(badgeB.startsWith("repo-b-"))
        assertNull(ExtensionRepoMetaHelper.getCachedMeta(repoB)?.name?.takeIf { it.isNotBlank() })
    }

    @Test
    fun `re-saving updates instead of going stale`() {
        val repo = "https://example.test/stale-${System.nanoTime()}/index.pb"
        ExtensionRepoMetaHelper.saveMeta(
            repoUrl = repo,
            name = "Old",
            shortName = "Old",
            website = "",
            discord = null
        )
        assertEquals("Old", ExtensionRepoMetaHelper.getRepoBadgeName(repo))
        ExtensionRepoMetaHelper.saveMeta(
            repoUrl = repo,
            name = "New",
            shortName = "New",
            website = "",
            discord = null
        )
        assertEquals("New", ExtensionRepoMetaHelper.getRepoBadgeName(repo))
    }

    @Test
    fun `repo page prefers github over website`() {
        val repo = "https://example.test/page-${System.nanoTime()}/index.pb"
        ExtensionRepoMetaHelper.saveMeta(
            repoUrl = repo,
            name = "P",
            shortName = null,
            website = "https://example.test",
            discord = null
        )
        // No github mapping for example.test: website is returned.
        assertEquals(
            "https://example.test",
            ExtensionRepoMetaHelper.getRepoPageUrl(repo)
        )
    }

    // Review HOLD Finding 1: link icon semantics. Only GitHub project
    // pages may use the GitHub icon; everything else is generic web.

    @Test
    fun `raw githubusercontent classifies as github`() {
        assertEquals(
            ExtensionRepoMetaHelper.RepoLinkKind.GITHUB,
            ExtensionRepoMetaHelper.classifyRepoPageUrl(
                "https://raw.githubusercontent.com/keiyoushi/extensions/repo/index.min.json"
            )
        )
    }

    @Test
    fun `github repo page classifies as github`() {
        assertEquals(
            ExtensionRepoMetaHelper.RepoLinkKind.GITHUB,
            ExtensionRepoMetaHelper.classifyRepoPageUrl("https://github.com/keiyoushi/extensions")
        )
    }

    @Test
    fun `gitlab page classifies as generic web`() {
        assertEquals(
            ExtensionRepoMetaHelper.RepoLinkKind.GENERIC_WEB,
            ExtensionRepoMetaHelper.classifyRepoPageUrl("https://gitlab.com/owner/repo")
        )
    }

    @Test
    fun `codeberg page classifies as generic web`() {
        assertEquals(
            ExtensionRepoMetaHelper.RepoLinkKind.GENERIC_WEB,
            ExtensionRepoMetaHelper.classifyRepoPageUrl("https://codeberg.org/owner/repo")
        )
    }

    @Test
    fun `generic website classifies as generic web`() {
        assertEquals(
            ExtensionRepoMetaHelper.RepoLinkKind.GENERIC_WEB,
            ExtensionRepoMetaHelper.classifyRepoPageUrl("https://example.test/some-page")
        )
    }

    @Test
    fun `lookalike domains do not classify as github`() {
        assertEquals(
            ExtensionRepoMetaHelper.RepoLinkKind.GENERIC_WEB,
            ExtensionRepoMetaHelper.classifyRepoPageUrl("https://github.com.evil.test/owner/repo")
        )
        assertEquals(
            ExtensionRepoMetaHelper.RepoLinkKind.GENERIC_WEB,
            ExtensionRepoMetaHelper.classifyRepoPageUrl("https://notgithub.com/owner/repo")
        )
    }

    @Test
    fun `missing url classifies as generic web`() {
        assertEquals(
            ExtensionRepoMetaHelper.RepoLinkKind.GENERIC_WEB,
            ExtensionRepoMetaHelper.classifyRepoPageUrl(null)
        )
    }
}
