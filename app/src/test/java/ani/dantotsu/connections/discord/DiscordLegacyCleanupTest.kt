package ani.dantotsu.connections.discord

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Tests for the cleanup algorithm via [FakeCleanupBackend].
 * These exercise the same [cleanOnceWithBackend] path that production uses,
 * ensuring tests and production share the same decision/semantics.
 */
class DiscordLegacyCleanupTest {

    private lateinit var fake: FakeCleanupBackend

    @Before
    fun setUp() {
        fake = FakeCleanupBackend()
    }

    @Test
    fun `all cleanup operations succeed - guard is set`() {
        seedAll()

        var result: Boolean? = null
        DiscordLegacyCleanup.cleanOnceWithBackend(fake) { result = it }

        assertTrue("cleanOnceWithBackend should return true", result!!)
        assertTrue("guard should be set", fake.guardSet)
        assertFalse("discord dir should be deleted", fake.discordDirExists)
    }

    @Test
    fun `protected-pref removal fails - guard NOT set`() {
        seedAll()
        fake.failProtectedKeys.add("DiscordToken")

        var result: Boolean? = null
        DiscordLegacyCleanup.cleanOnceWithBackend(fake) { result = it }

        assertFalse("cleanOnceWithBackend should return false", result!!)
        assertFalse("guard should NOT be set", fake.guardSet)
    }

    @Test
    fun `irrelevant-pref batch commit fails - guard NOT set`() {
        seedAll()
        fake.irrelevantCommitSuccess = false

        var result: Boolean? = null
        DiscordLegacyCleanup.cleanOnceWithBackend(fake) { result = it }

        assertFalse("cleanOnceWithBackend should return false", result!!)
        assertFalse("guard should NOT be set", fake.guardSet)
    }

    @Test
    fun `token-directory deletion fails - guard NOT set`() {
        seedAll()
        fake.failDiscordDirDeletion = true

        var result: Boolean? = null
        DiscordLegacyCleanup.cleanOnceWithBackend(fake) { result = it }

        assertFalse("cleanOnceWithBackend should return false", result!!)
        assertFalse("guard should NOT be set", fake.guardSet)
    }

    @Test
    fun `webview origin deletion fails - guard NOT set`() {
        seedAll()
        fake.failWebViewDeletion = true

        var result: Boolean? = null
        DiscordLegacyCleanup.cleanOnceWithBackend(fake) { result = it }

        assertFalse("cleanOnceWithBackend should return false", result!!)
        assertFalse("guard should NOT be set", fake.guardSet)
    }

    @Test
    fun `guard commit fails - result false and guard not set`() {
        seedAll()
        fake.guardCommitSuccess = false

        var result: Boolean? = null
        DiscordLegacyCleanup.cleanOnceWithBackend(fake) { result = it }

        assertFalse("cleanOnceWithBackend should return false when guard commit fails", result!!)
        assertFalse("guard should NOT be set when commit fails", fake.guardSet)
    }

    @Test
    fun `next run retries after irrelevant-pref failure`() {
        seedAll()
        fake.irrelevantCommitSuccess = false

        // First run fails
        var result1: Boolean? = null
        DiscordLegacyCleanup.cleanOnceWithBackend(fake) { result1 = it }
        assertFalse(result1!!)
        assertFalse(fake.guardSet)

        // Fix the failure, second run succeeds
        fake.irrelevantCommitSuccess = true
        var result2: Boolean? = null
        DiscordLegacyCleanup.cleanOnceWithBackend(fake) { result2 = it }
        assertTrue(result2!!)
        assertTrue(fake.guardSet)
    }

    @Test
    fun `next run retries after guard commit failure`() {
        seedAll()
        fake.guardCommitSuccess = false

        // First run fails at guard commit
        var result1: Boolean? = null
        DiscordLegacyCleanup.cleanOnceWithBackend(fake) { result1 = it }
        assertFalse(result1!!)
        assertFalse(fake.guardSet)

        // Fix the failure, second run succeeds
        fake.guardCommitSuccess = true
        var result2: Boolean? = null
        DiscordLegacyCleanup.cleanOnceWithBackend(fake) { result2 = it }
        assertTrue(result2!!)
        assertTrue(fake.guardSet)
    }

    @Test
    fun `second successful run becomes guarded - no-op`() {
        seedAll()

        // First run succeeds
        DiscordLegacyCleanup.cleanOnceWithBackend(fake) {}
        assertTrue(fake.guardSet)

        // Reset state to verify no-op
        val protectedBefore = fake.storedProtectedKeys.toSet()
        val irrelevantBefore = fake.storedIrrelevantKeys.toSet()

        // Second run is a no-op
        var result: Boolean? = null
        DiscordLegacyCleanup.cleanOnceWithBackend(fake) { result = it }
        assertTrue(result!!)
        assertEquals(protectedBefore, fake.storedProtectedKeys)
        assertEquals(irrelevantBefore, fake.storedIrrelevantKeys)
    }

    @Test
    fun `unrelated preferences are preserved`() {
        fake.storedProtectedKeys.addAll(setOf("AnilistToken", "AnilistUserName", "DiscordToken"))
        fake.storedIrrelevantKeys.addAll(setOf("DiscordRichPresenceEnabled", "rpcEnabled", "someOtherKey"))
        fake.discordDirExists = true

        DiscordLegacyCleanup.cleanOnceWithBackend(fake) {}

        assertTrue("AnilistToken should be preserved", fake.storedProtectedKeys.contains("AnilistToken"))
        assertTrue("AnilistUserName should be preserved", fake.storedProtectedKeys.contains("AnilistUserName"))
        assertTrue("DiscordRichPresenceEnabled should be preserved", fake.storedIrrelevantKeys.contains("DiscordRichPresenceEnabled"))
        assertTrue("someOtherKey should be preserved", fake.storedIrrelevantKeys.contains("someOtherKey"))
    }

    @Test
    fun `DiscordRichPresenceEnabled is preserved`() {
        fake.storedIrrelevantKeys.addAll(setOf("DiscordRichPresenceEnabled", "rpcEnabled"))

        DiscordLegacyCleanup.cleanOnceWithBackend(fake) {}

        assertTrue("DiscordRichPresenceEnabled should be preserved",
            fake.storedIrrelevantKeys.contains("DiscordRichPresenceEnabled"))
        assertFalse("rpcEnabled should be removed",
            fake.storedIrrelevantKeys.contains("rpcEnabled"))
    }

    @Test
    fun `dead Discord irrelevant keys are removed`() {
        val deadKeys = listOf(
            "DiscordStatus", "DiscordRPCModeAnime", "DiscordRPCModeManga",
            "DiscordRPCShowIconAnime", "DiscordRPCShowIconManga",
            "DiscordShowButtons", "UseNewDiscordRpc"
        )
        fake.storedIrrelevantKeys.addAll(deadKeys)
        fake.storedIrrelevantKeys.add("DiscordRichPresenceEnabled")

        DiscordLegacyCleanup.cleanOnceWithBackend(fake) {}

        for (key in deadKeys) {
            assertFalse("$key should be removed", fake.storedIrrelevantKeys.contains(key))
        }
        assertTrue("DiscordRichPresenceEnabled should be preserved",
            fake.storedIrrelevantKeys.contains("DiscordRichPresenceEnabled"))
    }

    @Test
    fun `cleanup works when no legacy keys exist`() {
        fake.storedProtectedKeys.add("AnilistToken")
        fake.storedIrrelevantKeys.add("someOtherKey")

        var result: Boolean? = null
        DiscordLegacyCleanup.cleanOnceWithBackend(fake) { result = it }

        assertTrue(result!!)
        assertTrue(fake.guardSet)
        assertTrue(fake.storedProtectedKeys.contains("AnilistToken"))
        assertTrue(fake.storedIrrelevantKeys.contains("someOtherKey"))
    }

    @Test
    fun `legacy key lists match expected values`() {
        assertEquals(
            setOf("DiscordToken", "DiscordId", "DiscordUserName", "DiscordAvatar"),
            DiscordLegacyCleanup.LEGACY_PROTECTED_KEYS.toSet()
        )
        assertEquals(
            setOf(
                "rpcEnabled", "discord_activity_token",
                "DiscordStatus", "DiscordRPCModeAnime", "DiscordRPCModeManga",
                "DiscordRPCShowIconAnime", "DiscordRPCShowIconManga",
                "DiscordShowButtons", "UseNewDiscordRpc"
            ),
            DiscordLegacyCleanup.LEGACY_IRRELEVANT_KEYS.toSet()
        )
    }

    @Test
    fun `migration guard key is correct`() {
        assertEquals("has_cleaned_discord_legacy_v1", DiscordLegacyCleanup.MIGRATION_GUARD_KEY)
    }

    @Test
    fun `discord web origin constant is correct`() {
        assertEquals("https://discord.com", DiscordLegacyCleanup.DISCORD_WEB_ORIGIN)
    }

    // ── Async guard tests ────────────────────────────────────────────────────

    @Test
    fun `guard NOT set before webview callback fires`() {
        seedAll()
        fake.asyncWebViewDeletion = true

        DiscordLegacyCleanup.cleanOnceWithBackend(fake) {}

        // Callback hasn't fired yet — guard should NOT be set
        assertFalse("guard should NOT be set before callback", fake.guardSet)
    }

    @Test
    fun `guard set after successful webview callback`() {
        seedAll()
        fake.asyncWebViewDeletion = true

        var result: Boolean? = null
        DiscordLegacyCleanup.cleanOnceWithBackend(fake) { result = it }

        // Simulate async completion
        fake.fireWebViewCallback(true)

        assertTrue("result should be true", result!!)
        assertTrue("guard should be set after callback", fake.guardSet)
    }

    @Test
    fun `guard NOT set when webview callback reports failure`() {
        seedAll()
        fake.asyncWebViewDeletion = true

        var result: Boolean? = null
        DiscordLegacyCleanup.cleanOnceWithBackend(fake) { result = it }

        // Simulate async failure
        fake.fireWebViewCallback(false)

        assertFalse("result should be false", result!!)
        assertFalse("guard should NOT be set on callback failure", fake.guardSet)
    }

    @Test
    fun `unsupported webview feature - no guard set`() {
        seedAll()
        fake.webViewFeatureSupported = false

        var result: Boolean? = null
        DiscordLegacyCleanup.cleanOnceWithBackend(fake) { result = it }

        assertFalse("result should be false for unsupported feature", result!!)
        assertFalse("guard should NOT be set when feature unsupported", fake.guardSet)
    }

    @Test
    fun `no callback provided - sync steps still execute`() {
        seedAll()

        // Call without onComplete — should not crash
        DiscordLegacyCleanup.cleanOnceWithBackend(fake, onComplete = null)

        assertFalse("discord dir should be deleted", fake.discordDirExists)
        // In the fake backend, web deletion is synchronous so the guard gets set
        // even without a callback. The important thing is no crash occurred.
    }

    private fun seedAll() {
        fake.storedProtectedKeys.addAll(DiscordLegacyCleanup.LEGACY_PROTECTED_KEYS)
        fake.storedIrrelevantKeys.addAll(DiscordLegacyCleanup.LEGACY_IRRELEVANT_KEYS)
        fake.discordDirExists = true
    }
}

/**
 * Fake [CleanupBackend] using in-memory sets. Allows injecting failures at each step
 * to verify that the cleanup algorithm correctly refuses to set the guard on partial failure.
 *
 * Supports both synchronous (default) and asynchronous web deletion modes for testing
 * the guard-before-callback invariant.
 */
internal class FakeCleanupBackend : CleanupBackend {
    val storedProtectedKeys = mutableSetOf<String>()
    val storedIrrelevantKeys = mutableSetOf<String>()
    var discordDirExists = false
    var guardSet = false

    /** Failure injection */
    val failProtectedKeys = mutableSetOf<String>()
    var irrelevantCommitSuccess = true
    var failDiscordDirDeletion = false
    var failWebViewDeletion = false
    var guardCommitSuccess = true

    /** Async web deletion support */
    var asyncWebViewDeletion = false
    var webViewFeatureSupported = true
    private var pendingWebViewCallback: ((Boolean) -> Unit)? = null

    override fun isGuardSet(): Boolean = guardSet

    override fun removeProtectedPref(key: String): Boolean {
        if (key in failProtectedKeys) return false
        storedProtectedKeys.remove(key)
        return true
    }

    override fun removeIrrelevantPrefs(keys: List<String>): Boolean {
        if (!irrelevantCommitSuccess) return false
        for (key in keys) {
            storedIrrelevantKeys.remove(key)
        }
        return true
    }

    override fun deleteDiscordDir(): Boolean {
        if (failDiscordDirDeletion) return false
        discordDirExists = false
        return true
    }

    override fun deleteWebViewOrigin(origin: String, onComplete: (Boolean) -> Unit) {
        if (!webViewFeatureSupported) {
            onComplete(false)
            return
        }
        if (failWebViewDeletion) {
            onComplete(false)
            return
        }
        if (asyncWebViewDeletion) {
            // Store callback for manual firing — simulates async behavior
            pendingWebViewCallback = onComplete
        } else {
            // Synchronous — fire inline
            onComplete(true)
        }
    }

    /** Fire the pending web view callback — used in async tests */
    fun fireWebViewCallback(success: Boolean) {
        pendingWebViewCallback?.invoke(success)
        pendingWebViewCallback = null
    }

    override fun setGuard(): Boolean {
        if (!guardCommitSuccess) return false
        guardSet = true
        return true
    }
}
