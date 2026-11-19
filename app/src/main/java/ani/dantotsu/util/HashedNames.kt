package ani.dantotsu.util

import java.security.MessageDigest
import java.util.Locale

/**
 * A set of names kept only as hashes: each entry is the name's length and the first
 * 16 hex digits of SHA-256 over its lowercase UTF-8 bytes. Used where behaviour has to
 * single out a few specific names that no structural rule can recognise.
 */
class HashedNames(entries: Map<String, Int>) {

    private val hashes: Set<String> = entries.keys
    private val lengths: IntArray = entries.values.distinct().sorted().toIntArray()

    /** True when [token], ignoring case and surrounding blanks, is one of the names. */
    fun matches(token: String?): Boolean {
        val t = token?.trim()?.lowercase(Locale.ROOT) ?: return false
        return t.length in lengths && hashOf(t) in hashes
    }

    /** True when one of the names occurs anywhere in [text], ignoring case. */
    fun occursIn(text: String?): Boolean {
        val t = text?.lowercase(Locale.ROOT) ?: return false
        for (len in lengths) {
            for (start in 0..t.length - len) {
                if (hashOf(t.substring(start, start + len)) in hashes) return true
            }
        }
        return false
    }

    companion object {
        fun hashOf(lowercaseName: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(lowercaseName.toByteArray(Charsets.UTF_8))
            return buildString(16) {
                for (i in 0 until 8) append("%02x".format(digest[i]))
            }
        }
    }
}
