package ani.dantotsu.settings

import ani.dantotsu.settings.ExtensionEmptyState.Kind
import org.junit.Assert.assertEquals
import org.junit.Test

class ExtensionRowTextTest {

    @Test
    fun `all fields are joined with a dot`() {
        assertEquals(
            "English · 16.10 · 18+ · Yūzōnō",
            ExtensionRowText.versionLine("English", "16.10", true, "Yūzōnō")
        )
    }

    @Test
    fun `blank fields are dropped`() {
        assertEquals("Multi · 14.11", ExtensionRowText.versionLine("Multi", "14.11", false, null))
        assertEquals("1.4.62", ExtensionRowText.versionLine(" ", "1.4.62", false, ""))
    }

    @Test
    fun `items present means no empty state`() {
        assertEquals(Kind.NONE, ExtensionEmptyState.kind(3, "x", installedPage = false, hasRepos = false))
    }

    @Test
    fun `search with no match wins over the other states`() {
        assertEquals(Kind.NO_MATCH, ExtensionEmptyState.kind(0, "pahe", installedPage = true, hasRepos = true))
        assertEquals(Kind.NO_MATCH, ExtensionEmptyState.kind(0, "pahe", installedPage = false, hasRepos = false))
    }

    @Test
    fun `installed page with nothing installed`() {
        assertEquals(Kind.NO_INSTALLED, ExtensionEmptyState.kind(0, " ", installedPage = true, hasRepos = false))
    }

    @Test
    fun `available page without repositories`() {
        assertEquals(Kind.NO_REPOS, ExtensionEmptyState.kind(0, "", installedPage = false, hasRepos = false))
    }

    @Test
    fun `available page with repositories but no items`() {
        assertEquals(Kind.NO_AVAILABLE, ExtensionEmptyState.kind(0, "", installedPage = false, hasRepos = true))
    }
}
