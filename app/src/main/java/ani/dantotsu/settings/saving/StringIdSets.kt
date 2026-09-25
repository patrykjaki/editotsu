package ani.dantotsu.settings.saving

/**
 * Batch 2 correction (review HOLD): type-safe core of the legacy
 * Set<Int>/Set<Long> preference migration (upstream 5ffa682b).
 *
 * ID sets (removeList, activities) are string-backed. These pure helpers
 * implement the exact normalization contract used by PrefManager's
 * sanitize branches and by every migrated call site, so the contract is
 * unit-testable without SharedPreferences/Robolectric:
 *
 * legacy Int set → same IDs as strings (hide preserved) → add/remove by
 * Int ID → write-normalized strings → re-sanitized identically
 * (round-trip stable), including the initially-empty case.
 */
internal object StringIdSets {

    /** Mirrors PrefManager's sanitize branches: any legacy shape → strings. */
    fun sanitizeToStrings(raw: Any?): Set<String> = when (raw) {
        is Set<*> -> raw.mapNotNull { it?.toString() }.toSet()
        is Collection<*> -> raw.mapNotNull { it?.toString() }.toSet()
        else -> emptySet()
    }

    /** Domain boundary: string storage → Int IDs (drops unparseable). */
    fun toIntIds(strings: Set<String>): Set<Int> =
        strings.mapNotNull { it.toIntOrNull() }.toSet()

    /** Domain boundary: string storage → Long IDs (drops unparseable). */
    fun toLongIds(strings: Set<String>): Set<Long> =
        strings.mapNotNull { it.toLongOrNull() }.toSet()

    fun containsId(strings: Set<String>, id: Int): Boolean =
        toIntIds(strings).contains(id)

    fun plusId(strings: Set<String>, id: Int): Set<String> =
        strings.plus(id.toString())

    fun minusId(strings: Set<String>, id: Int): Set<String> =
        strings.minus(id.toString())

    /** Mirrors setCustomVal write normalization: every element → string. */
    fun normalizeForWrite(set: Set<*>): Set<String> =
        set.map { it.toString() }.toSet()
}
