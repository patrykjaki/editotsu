package ani.dantotsu.connections.discord.rpc

import java.net.URI

/**
 * Pure resolution of the **large-image (cover artwork)** value for the tokenless Discord Social SDK RPC.
 *
 * Per Discord's Social SDK docs, `assets.large_image` accepts either a registered application asset key
 * OR an external image URL (fetched by Discord server-side). The clean-room [DiscordRpcCodec] already
 * forwards whatever string we put in `large_image` verbatim, so this resolver only decides **which** value
 * to send:
 *
 *  - A well-formed `http(s)` cover URL is sent **verbatim** (the Social SDK supports both schemes directly;
 *    we must not rewrite `http` to `https` because a host/path served only over plain HTTP would otherwise
 *    become a nonexistent URL).
 *  - The Social SDK accepts external image URLs up to **300 characters**. A longer URL cannot be safely
 *    truncated (cutting it mid-URL yields a different, broken URL), so over-limit URLs resolve to `null`.
 *  - Anything else (null / blank / malformed / unsupported scheme / no host) also resolves to `null`, which
 *    the codec **omits**, leaving Discord to render the application default instead of a broken question-mark
 *    image. This is the truthful fallback required by the parity pass — we never send a value Discord cannot
 *    render, and we never hardcode a per-title application asset.
 *
 * No Discord account token, WebView, network call, or proprietary SDK is involved. Free of Android imports so
 * it is unit-testable on a plain host JVM.
 */
object DiscordCoverResolver {

    // Discord Social SDK external image URL / asset-key length bound (review v2, BLOCKER 1B).
    const val IMAGE_URL_MAX = 300

    fun resolveLargeImage(coverUrl: String?): String? {
        if (coverUrl.isNullOrBlank()) return null
        val trimmed = coverUrl.trim()
        val uri = runCatching { URI(trimmed) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        // Both schemes are supported by the Social SDK; preserve the origin scheme verbatim (no upgrade).
        if (scheme != "http" && scheme != "https") return null
        val host = uri.host
        if (host.isNullOrBlank() || !host.contains('.')) return null
        // An over-limit external URL cannot be truncated into a valid URL, so omit it (truthful fallback).
        if (trimmed.length > IMAGE_URL_MAX) return null
        return trimmed
    }
}
