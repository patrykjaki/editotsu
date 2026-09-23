package ani.dantotsu.media.anime.selector

/**
 * Persistence-owned bounded JSON v1 family payload.
 *
 * Hard-bound caps (no truncation):
 *   total UTF-8 <= 512 bytes
 *   providerPkg UTF-8 <= 96 bytes
 *   groupKey <= 32 chars
 *   soft entries <= 7
 *   soft key <= 16 chars
 *   soft value <= 32 chars
 *
 * Wrong type / version / required hard field / oversize field ->
 * `isValid = false` and the preference is treated as absent.
 * The `groupKey` hard anchor must additionally be a canonical
 * `StableGroupCanonicalizer` key (exact match); malformed or
 * noncanonical anchors are rejected, never normalized.
 */
data class FamilyPayload(
    val providerPkg: String,
    val groupKey: String,
    val soft: Map<String, String> = emptyMap(),
) {
    val isValid: Boolean get() = true
}

object SelectedFamilyCodec {
    const val TOTAL_BYTES_LIMIT = 512
    const val PROVIDER_BYTES_LIMIT = 96
    const val GROUP_KEY_MAX = 32
    const val SOFT_ENTRIES_LIMIT = 7
    const val SOFT_KEY_MAX = 16
    const val SOFT_VALUE_MAX = 32
    const val VERSION = 1

    fun encode(payload: FamilyPayload): String? {
        if (!validateHard(payload)) return null
        val sb = StringBuilder()
        sb.append('{')
        sb.append("\"version\":").append(VERSION).append(',')
        sb.append("\"providerPkg\":").append(jsonString(payload.providerPkg)).append(',')
        sb.append("\"groupKey\":").append(jsonString(payload.groupKey))
        // Nested "soft" object as the v12 spec defines.
        if (payload.soft.isNotEmpty()) {
            sb.append(",\"soft\":{")
            var first = true
            for ((k, v) in payload.soft) {
                if (!first) sb.append(',')
                sb.append(jsonString(k)).append(':').append(jsonString(v))
                first = false
            }
            sb.append('}')
        }
        sb.append('}')
        val bytes = sb.toString().toByteArray(Charsets.UTF_8)
        if (bytes.size > TOTAL_BYTES_LIMIT) return null
        return sb.toString()
    }

    fun decode(raw: String?): FamilyPayload? {
        if (raw.isNullOrBlank()) return null
        if (raw.toByteArray(Charsets.UTF_8).size > TOTAL_BYTES_LIMIT) return null
        val map = try {
            parseFlatObject(raw)
        } catch (_: Throwable) {
            return null
        }
        val version = (map["version"] as? Long)?.toInt() ?: return null
        if (version != VERSION) return null
        val providerPkg = map["providerPkg"] as? String ?: return null
        if (providerPkg.isBlank()) return null
        if (providerPkg.toByteArray(Charsets.UTF_8).size > PROVIDER_BYTES_LIMIT) return null
        val groupKey = map["groupKey"] as? String ?: return null
        if (groupKey.isBlank()) return null
        if (groupKey.length > GROUP_KEY_MAX) return null
        // The family hard anchor must be a canonical group key.
        // Malformed / noncanonical anchors (wrong case, tracker
        // names, technical tokens, untrimmed text) are treated as
        // no valid family preference. Never auto-normalize
        // corrupted persisted JSON during decode; fail closed.
        if (StableGroupCanonicalizer.canonicalKey(groupKey) != groupKey) return null
        val soft = LinkedHashMap<String, String>()
        // A present `soft` field must be an object. A present
        // non-object `soft` (string, number, ...) is malformed
        // JSON for this schema and rejects the whole payload.
        if (map.containsKey("soft")) {
            val softObj = map["soft"] as? Map<*, *> ?: return null
            if (softObj.size > SOFT_ENTRIES_LIMIT) return null
            for ((k, v) in softObj) {
                val key = k as? String ?: return null
                if (key.length > SOFT_KEY_MAX) return null
                val value = v as? String ?: return null
                if (value.length > SOFT_VALUE_MAX) return null
                soft[key] = value
            }
        }
        return FamilyPayload(providerPkg, groupKey, soft)
    }

    private fun validateHard(p: FamilyPayload): Boolean {
        if (p.providerPkg.isBlank()) return false
        if (p.providerPkg.toByteArray(Charsets.UTF_8).size > PROVIDER_BYTES_LIMIT) return false
        if (p.groupKey.isBlank()) return false
        if (p.groupKey.length > GROUP_KEY_MAX) return false
        if (StableGroupCanonicalizer.canonicalKey(p.groupKey) != p.groupKey) return false
        if (p.soft.size > SOFT_ENTRIES_LIMIT) return false
        for ((k, v) in p.soft) {
            if (k.length > SOFT_KEY_MAX) return false
            if (v.length > SOFT_VALUE_MAX) return false
        }
        return true
    }

    private fun jsonString(s: String): String {
        val sb = StringBuilder(s.length + 2)
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c.code < 0x20) {
                    sb.append("\\u%04x".format(c.code))
                } else sb.append(c)
            }
        }
        sb.append('"')
        return sb.toString()
    }

    /**
     * Hand-rolled, narrowly-scoped flat-object JSON parser. Only
     * accepts top-level `key: value` pairs where the value is a
     * JSON string or a nested flat object. No arrays, no nested
     * objects beyond depth 1. Numbers are returned as Long.
     *
     * Strictly rejects:
     *   - duplicate occurrences of any known top-level schema key
     *     (`version`, `providerPkg`, `groupKey`, `soft`)
     *   - duplicate soft keys inside the nested `soft` object
     *   - unescaped JSON control characters (U+0000..U+001F)
     *     inside strings
     *   - trailing non-whitespace garbage after the closing `}`
     *     (i.e. any input past EOF is invalid)
     *
     * Unknown soft field NAMES remain allowed (forward-compatible)
     * after normal bounds validation.
     */
    private fun parseFlatObject(s: String): Map<String, Any?> {
        val p = Parser(s)
        p.skipWs()
        p.expect('{')
        p.skipWs()
        val out = LinkedHashMap<String, Any?>()
        val seenHardKeys = HashSet<String>()
        val hardKeys = setOf("version", "providerPkg", "groupKey", "soft")
        if (p.peek() == '}') {
            p.advance()
            p.skipWs()
            p.requireEof()
            return out
        }
        while (true) {
            p.skipWs()
            val k = p.readString()
            if (k in hardKeys) {
                if (!seenHardKeys.add(k)) {
                    throw IllegalStateException("duplicate hard key: $k")
                }
            }
            p.skipWs()
            p.expect(':')
            p.skipWs()
            val v: Any? = if (p.peek() == '{') {
                p.advance()
                val obj = LinkedHashMap<String, Any?>()
                p.skipWs()
                if (p.peek() != '}') {
                    while (true) {
                        p.skipWs()
                        val nk = p.readString()
                        p.skipWs()
                        p.expect(':')
                        p.skipWs()
                        val nv = p.readString()
                        // Duplicate soft keys are rejected rather
                        // than silently overwritten.
                        if (obj.containsKey(nk)) {
                            throw IllegalStateException("duplicate soft key: $nk")
                        }
                        obj[nk] = nv
                        p.skipWs()
                        if (p.peek() == ',') { p.advance(); continue }
                        break
                    }
                }
                p.expect('}')
                obj
            } else {
                p.readStringOrNumber()
            }
            out[k] = v
            p.skipWs()
            if (p.peek() == ',') { p.advance(); continue }
            break
        }
        p.expect('}')
        p.skipWs()
        p.requireEof()
        return out
    }

    private class Parser(val src: String) {
        private var pos = 0
        fun peek(): Char = if (pos < src.length) src[pos] else '\u0000'
        fun advance(): Char = src[pos++]
        fun skipWs() { while (pos < src.length && src[pos].isWhitespace()) pos++ }
        fun requireEof() {
            if (pos < src.length) {
                throw IllegalStateException(
                    "trailing garbage at $pos: '${src.substring(pos)}'",
                )
            }
        }
        fun expect(c: Char) {
            if (pos >= src.length || src[pos] != c) {
                throw IllegalStateException("expected '$c' at $pos")
            }
            pos++
        }
        fun readString(): String {
            expect('"')
            val sb = StringBuilder()
            while (pos < src.length) {
                val c = src[pos++]
                // Unescaped JSON control characters (U+0000..U+001F)
                // are invalid inside strings; reject them rather
                // than silently accepting malformed payloads.
                if (c.code < 0x20) {
                    throw IllegalStateException(
                        "unescaped control character U+%04X in string".format(c.code),
                    )
                }
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (pos >= src.length) throw IllegalStateException("unterminated escape")
                        when (val esc = src[pos++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'u' -> {
                                if (pos + 4 > src.length) {
                                    throw IllegalStateException("short unicode escape")
                                }
                                val code = src.substring(pos, pos + 4).toInt(16)
                                sb.append(code.toChar())
                                pos += 4
                            }
                            else -> throw IllegalStateException("bad escape \\$esc")
                        }
                    }
                    else -> sb.append(c)
                }
            }
            throw IllegalStateException("unterminated string")
        }
        /**
         * Strict JSON integer syntax: `-?(0|[1-9][0-9]*)`.
         * Malformed spellings such as `01`, `+1`, or a bare `-`
         * throw instead of being coerced (e.g. `01` must not
         * become version 1).
         */
        fun readStringOrNumber(): Any {
            if (peek() == '"') return readString()
            val start = pos
            if (peek() == '-') pos++
            if (pos >= src.length || !src[pos].isDigit()) {
                throw IllegalStateException("malformed number at $start")
            }
            if (src[pos] == '0') {
                pos++
            } else {
                while (pos < src.length && src[pos].isDigit()) pos++
            }
            return src.substring(start, pos).toLong()
        }
    }
}
