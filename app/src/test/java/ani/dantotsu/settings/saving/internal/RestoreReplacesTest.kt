package ani.dantotsu.settings.saving.internal

import org.junit.Assert.assertEquals
import org.junit.Test

class RestoreReplacesTest {

    @Test
    fun `full backup lists every group in order`() {
        val payload = mapOf(
            "Protected" to setOf("AnilistToken", "AppPassword", "Socks5ProxyHost"),
            "General" to setOf("AnimeExtensionRepos", "SomeSetting"),
            "Irrelevant" to setOf("ContinueMedia"),
        )
        assertEquals(
            listOf(
                RestoreReplaces.Logins,
                RestoreReplaces.AppLock,
                RestoreReplaces.Proxy,
                RestoreReplaces.ExtensionRepos,
                RestoreReplaces.Settings,
                RestoreReplaces.History,
            ),
            BackupSections.replacedBy(payload)
        )
    }

    @Test
    fun `repos alone are not counted as settings`() {
        val payload = mapOf("General" to setOf("MangaExtensionRepos"))
        assertEquals(listOf(RestoreReplaces.ExtensionRepos), BackupSections.replacedBy(payload))
    }

    @Test
    fun `installed extension list is not a replaced group`() {
        val payload = mapOf(BackupSections.INSTALLED_EXTENSIONS to setOf("pkg"))
        assertEquals(emptyList<RestoreReplaces>(), BackupSections.replacedBy(payload))
    }
}
