package ani.dantotsu.media.anime.selector

/**
 * Pure persistence-owned BTIH canonicalizer.
 *
 * Accepts both 40-hex and 32-Base32 v1 BTIH representations and returns
 * a 40-character lowercase-hex canonical string. Strict source-order
 * matching of the `xt=urn:btih:<value>` query parameter is enforced by
 * the caller; this function only normalises the captured value.
 *
 * Rejects partial / overlong / mixed-alphabet values rather than
 * silently accepting them.
 */
object BtihCanonicalizer {
    private val HEX_40 = Regex("""^[0-9a-fA-F]{40}$""")
    private val BASE32_32 = Regex("""^[A-Za-z2-7]{32}$""")
    private const val BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    fun canonicalHex40(token: String?): String? {
        if (token.isNullOrBlank()) return null
        val trimmed = token.trim()
        // Strict unpadded Base32 acceptance. 32 valid Base32 chars
        // only. Any "=" padding, any overlong form, any partial
        // form is rejected. The contract is "exactly 32 Base32
        // characters"; padding would imply a partial / overlong
        // form that must not be silently normalized.
        return when {
            HEX_40.matches(trimmed) -> trimmed.lowercase(java.util.Locale.ROOT)
            BASE32_32.matches(trimmed) -> base32ToHex40(trimmed.uppercase())
            else -> null
        }
    }

    fun isValidHex40(token: String?): Boolean =
        token != null && HEX_40.matches(token.trim())

    fun isValidBase32_32(token: String?): Boolean =
        token != null && BASE32_32.matches(token.trim())

    /**
     * Decode a 32-character RFC 4648 Base32 string into a 40-character
     * lowercase-hex string. The alphabet is the standard
     * "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567" with no padding.
     */
    fun base32ToHex40(input: String): String? {
        val s = input.uppercase(java.util.Locale.ROOT).trim()
        if (!BASE32_32.matches(s)) return null
        // Accumulate 5-bit groups, then emit 8-bit bytes. 32 chars *
        // 5 bits = 160 bits = 20 bytes.
        val out = ByteArray(20)
        var buffer = 0
        var bitsInBuffer = 0
        var byteIndex = 0
        for (c in s) {
            val v = BASE32_ALPHABET.indexOf(c)
            if (v < 0) return null
            buffer = (buffer shl 5) or v
            bitsInBuffer += 5
            if (bitsInBuffer >= 8) {
                bitsInBuffer -= 8
                out[byteIndex++] = ((buffer shr bitsInBuffer) and 0xFF).toByte()
            }
        }
        if (byteIndex != 20) return null
        return out.joinToString("") { "%02x".format(it) }
    }
}
