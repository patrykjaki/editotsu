package ani.dantotsu.media.anime.selector

import java.util.Locale

/**
 * Persistence-owned exact stable-key builders.
 *
 * No UI presentation is called. The provider8 is the first 8 lowercase
 * hex characters of SHA-256(exactCaseProviderPkg). For null/blank
 * provider, no stable key is produced.
 */
object ProviderHash {
    fun provider8(exactCaseProvider: String?): String? {
        if (exactCaseProvider.isNullOrBlank()) return null
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(exactCaseProvider.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(8)
    }
}

object MagnetKeyBuilder {
    fun build(
        exactCaseProvider: String?,
        magnetUrl: String?,
    ): String? {
        val provider8 = ProviderHash.provider8(exactCaseProvider) ?: return null
        val btih40 = extractFirstValidBtih(magnetUrl) ?: return null
        val index = CanonicalIndexParser.canonicalIndex(magnetUrl) ?: return null
        return "editotsu-source:v1:magnet:$provider8:$btih40:$index"
    }

    private val XT_TOKENS = Regex("""(?<=[&;?]|^)xt=urn:btih:([^&;#]+)(?=[&;#]|$)""")

    private fun extractFirstValidBtih(magnetUrl: String?): String? {
        if (magnetUrl.isNullOrBlank()) return null
        val query = magnetUrl.substringAfter('?', "")
        for (m in XT_TOKENS.findAll(query)) {
            val canonical = BtihCanonicalizer.canonicalHex40(m.groupValues[1])
            if (canonical != null) return canonical
        }
        return null
    }
}

object HttpTorrentKeyBuilder {
    private const val PREFIX = "editotsu-source:v1:http-torrent:"

    fun build(
        exactCaseProvider: String?,
        url: String?,
    ): String? {
        val provider8 = ProviderHash.provider8(exactCaseProvider) ?: return null
        val canonical = UrlCanonicaliser.canonicalise(url) ?: return null
        val canonicalUrl = canonical.canonicalUrlString()
        return PREFIX + provider8 + ":" + canonicalUrl
    }
}

object DirectReleaseKeyBuilder {
    private const val PREFIX = "editotsu-source:v1:direct:"

    fun build(
        exactCaseProvider: String?,
        group: String?,
        resolution: String?,
        audioFormat: String?,
        audioMode: String?,
        sourceService: String?,
        rawTitle: String,
    ): String? {
        val provider8 = ProviderHash.provider8(exactCaseProvider) ?: return null
        val id16 = StableReleaseCanonicalizer.releaseStableId16(
            group, resolution, audioFormat, audioMode, sourceService, rawTitle,
        ) ?: return null
        return PREFIX + provider8 + ":" + id16
    }
}
