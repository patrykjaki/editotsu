package ani.dantotsu.connections.discord

import android.content.Context
import android.webkit.WebStorage
import androidx.webkit.WebStorageCompat
import androidx.webkit.WebViewFeature
import ani.dantotsu.settings.saving.PrefManager
import java.io.File

/**
 * One-time idempotent cleanup of legacy Discord auth/token/WebView residue.
 *
 * Runs once after upgrade, guarded by a dedicated migration_prefs key. Safe to call
 * multiple times — subsequent calls are no-ops.
 *
 * The migration guard is set ONLY when every cleanup step succeeds, including the guard
 * commit itself. If any step fails, the guard is not set and cleanup retries on next
 * process start.
 *
 * Removes:
 * - Protected SharedPreferences: DiscordToken, DiscordId, DiscordUserName, DiscordAvatar
 * - Irrelevant SharedPreferences: rpcEnabled, discord_activity_token, and dead legacy keys
 * - filesDir/discord/ token cache directory
 * - Discord-origin WebView localStorage token (via AndroidX site-scoped deletion)
 */
object DiscordLegacyCleanup {

    internal const val MIGRATION_GUARD_KEY = "has_cleaned_discord_legacy_v1"
    internal const val DISCORD_WEB_ORIGIN = "https://discord.com"

    internal val LEGACY_PROTECTED_KEYS = listOf(
        "DiscordToken",
        "DiscordId",
        "DiscordUserName",
        "DiscordAvatar"
    )

    internal val LEGACY_IRRELEVANT_KEYS = listOf(
        "rpcEnabled",
        "discord_activity_token",
        "DiscordStatus",
        "DiscordRPCModeAnime",
        "DiscordRPCModeManga",
        "DiscordRPCShowIconAnime",
        "DiscordRPCShowIconManga",
        "DiscordShowButtons",
        "UseNewDiscordRpc"
    )

    /**
     * Run the one-time cleanup. Must be called after [PrefManager.init].
     * Uses the production backend.
     *
     * Synchronous steps (prefs, files) complete immediately. The WebView site-data deletion
     * is asynchronous; [onComplete] fires when the migration guard has been committed.
     * If [WebViewFeature.DELETE_BROWSING_DATA] is unsupported, [onComplete] fires with
     * `false` — the guard is NOT set and cleanup will retry on next startup.
     */
    fun cleanOnce(context: Context, onComplete: ((Boolean) -> Unit)? = null) {
        val backend = ProductionCleanupBackend(context)
        cleanOnceWithBackend(backend, onComplete)
    }

    /**
     * Core cleanup algorithm, shared by production and tests.
     *
     * Synchronous phase: prefs + directory removal. Returns immediately with `false` if
     * any sync step fails (guard not set, no callback).
     *
     * If sync steps succeed, initiates site-scoped WebView deletion. The guard is set
     * ONLY after the completion callback fires successfully. [onComplete] receives the
     * final result.
     *
     * For test backends where [CleanupBackend.deleteWebViewOrigin] is synchronous,
     * the callback fires inline and [onComplete] is invoked before the function returns.
     */
    internal fun cleanOnceWithBackend(
        backend: CleanupBackend,
        onComplete: ((Boolean) -> Unit)? = null
    ) {
        if (backend.isGuardSet()) {
            onComplete?.invoke(true)
            return
        }

        var allSucceeded = true

        // Step 1: Remove legacy protected prefs (synchronous commit per key)
        for (key in LEGACY_PROTECTED_KEYS) {
            if (!backend.removeProtectedPref(key)) {
                allSucceeded = false
            }
        }

        // Step 2: Remove legacy irrelevant prefs (synchronous batch commit)
        if (!backend.removeIrrelevantPrefs(LEGACY_IRRELEVANT_KEYS)) {
            allSucceeded = false
        }

        // Step 3: Delete legacy token cache directory
        if (!backend.deleteDiscordDir()) {
            allSucceeded = false
        }

        // Step 4: If sync steps failed, bail — no guard, no async work
        if (!allSucceeded) {
            onComplete?.invoke(false)
            return
        }

        // Step 5: Site-scoped WebView deletion (may be async in production).
        // Guard is set inside the completion callback — not before.
        backend.deleteWebViewOrigin(DISCORD_WEB_ORIGIN) { webDeletionSucceeded ->
            if (webDeletionSucceeded) {
                val guardResult = backend.setGuard()
                onComplete?.invoke(guardResult)
            } else {
                onComplete?.invoke(false)
            }
        }
    }
}

/**
 * Abstraction for cleanup operations. Production uses real SharedPreferences (commit-based
 * for synchronous success checking), File operations, and AndroidX WebKit for site-scoped
 * WebView deletion; tests use deterministic in-memory fakes.
 */
internal interface CleanupBackend {
    fun isGuardSet(): Boolean
    fun removeProtectedPref(key: String): Boolean
    fun removeIrrelevantPrefs(keys: List<String>): Boolean
    fun deleteDiscordDir(): Boolean

    /**
     * Delete site-scoped browsing data for [origin]. The [onComplete] callback MUST be
     * invoked exactly once with the deletion result. In synchronous implementations
     * (tests), the callback may be invoked inline before the function returns.
     */
    fun deleteWebViewOrigin(origin: String, onComplete: (Boolean) -> Unit)

    fun setGuard(): Boolean
}

/**
 * Production cleanup backend. All preference removals use synchronous [android.content.SharedPreferences.Editor.commit]
 * so every step's success is observable before the migration guard is persisted.
 *
 * WebView site-data deletion uses [WebStorageCompat.deleteBrowsingDataForSite] when
 * available ([WebViewFeature.DELETE_BROWSING_DATA]), falling back to framework
 * [WebStorage.deleteOrigin] on older devices. The guard is committed only after the
 * site-deletion completion callback fires.
 */
internal class ProductionCleanupBackend(private val context: Context) : CleanupBackend {

    private val guardPrefs = context.getSharedPreferences("migration_prefs", Context.MODE_PRIVATE)
    private val protectedPrefs = context.getSharedPreferences(
        "ani.dantotsu.protected",
        Context.MODE_PRIVATE
    )
    private val irrelevantPrefs = context.getSharedPreferences(
        "ani.dantotsu.irrelevant",
        Context.MODE_PRIVATE
    )

    override fun isGuardSet(): Boolean =
        guardPrefs.getBoolean(DiscordLegacyCleanup.MIGRATION_GUARD_KEY, false)

    override fun removeProtectedPref(key: String): Boolean =
        protectedPrefs.edit().remove(key).commit()

    /**
     * Batch-remove all legacy irrelevant keys in a single synchronous commit.
     * Returns true only when the commit succeeds.
     */
    override fun removeIrrelevantPrefs(keys: List<String>): Boolean {
        val editor = irrelevantPrefs.edit()
        for (key in keys) {
            editor.remove(key)
        }
        return editor.commit()
    }

    override fun deleteDiscordDir(): Boolean {
        val discordDir = File(context.filesDir, "discord")
        if (!discordDir.exists()) return true // Already absent — success
        if (!discordDir.isDirectory) return false
        return discordDir.deleteRecursively()
    }

    override fun deleteWebViewOrigin(origin: String, onComplete: (Boolean) -> Unit) {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DELETE_BROWSING_DATA)) {
            // AndroidX site-scoped deletion — removes HTML5 localStorage, cookies,
            // cache, and other site data for the specific origin.
            WebStorageCompat.deleteBrowsingDataForSite(
                WebStorage.getInstance(),
                origin
            ) { onComplete(true) }
        } else {
            // Fallback: framework deleteOrigin (Web SQL Database only).
            // Guard will NOT be set for the WebView step on unsupported devices;
            // cleanup retries on next startup.
            try {
                WebStorage.getInstance().deleteOrigin(origin)
                // Framework fallback — mark as best-effort; guard will not be set
                // because we cannot guarantee localStorage removal.
                onComplete(false)
            } catch (_: Exception) {
                onComplete(false)
            }
        }
    }

    override fun setGuard(): Boolean =
        guardPrefs.edit().putBoolean(DiscordLegacyCleanup.MIGRATION_GUARD_KEY, true).commit()
}
