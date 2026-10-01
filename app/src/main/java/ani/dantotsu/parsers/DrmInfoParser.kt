package ani.dantotsu.parsers

/**
 * MAX MERGE M4B DRM (upstream be6c4d99 `parseDrmInfo`, adapted): pure
 * extension-`internalData` → [DrmInfo] mapping.
 *
 * The Android-bound `JSONObject` walk stays a thin adapter in
 * `AniyomiAdapter` (one value-shape conversion); every decision here works
 * on plain maps and is host-tested.
 *
 * Expected shape (all fields optional except licenseUrl):
 * ```json
 * {
 *   "drmScheme": "widevine",
 *   "licenseUrl": "https://example.com/license",
 *   "licenseHeaders": { "Authorization": "Bearer ..." },
 *   "offline": {
 *     "manifestUrl": "...", "licenseUrl": "...",
 *     "licenseHeaders": {...}, "headers": {...}
 *   }
 * }
 * ```
 * Returns null for anything absent or malformed, so a clear stream — or an
 * extension using internalData for its own unrelated purposes — is
 * unaffected and stays on the clear-content (MPV) path.
 */
fun drmInfoFromMap(root: Map<String, Any?>?): DrmInfo? {
    if (root.isNullOrEmpty()) return null
    val licenseUrl = (root["licenseUrl"] as? String)?.trim().orEmpty()
    if (licenseUrl.isEmpty()) return null

    fun headersOf(source: Map<String, Any?>?, name: String): Map<String, String> {
        val json = source?.get(name) as? Map<*, *> ?: return emptyMap()
        return json.entries.mapNotNull { (k, v) ->
            val key = k as? String ?: return@mapNotNull null
            key to (v as? String ?: "")
        }.toMap()
    }

    val offline = (root["offline"] as? Map<String, Any?>)?.let { off ->
        val manifest = (off["manifestUrl"] as? String)?.trim().orEmpty()
        val offlineLicense = (off["licenseUrl"] as? String)?.trim().orEmpty()
        if (manifest.isEmpty() || offlineLicense.isEmpty()) {
            null
        } else {
            OfflineDrmInfo(
                manifestUrl = manifest,
                licenseUrl = offlineLicense,
                licenseHeaders = headersOf(off, "licenseHeaders"),
                headers = headersOf(off, "headers"),
            )
        }
    }

    return DrmInfo(
        scheme = (root["drmScheme"] as? String)?.trim().ifNullOrBlank { "widevine" },
        licenseUrl = licenseUrl,
        licenseHeaders = headersOf(root, "licenseHeaders"),
        offline = offline,
    )
}

private fun String?.ifNullOrBlank(default: () -> String): String =
    if (isNullOrBlank()) default() else this

/**
 * Thin JSONObject → plain-map bridge for [drmInfoFromMap]. Recursive over
 * nested objects; arrays and non-string scalars are preserved as-is (the
 * pure mapper only reads strings and nested maps).
 */
internal fun org.json.JSONObject.toPlainMap(): Map<String, Any?> {
    val out = LinkedHashMap<String, Any?>()
    val keys = keys()
    while (keys.hasNext()) {
        val key = keys.next()
        out[key] = when (val value = opt(key)) {
            is org.json.JSONObject -> value.toPlainMap()
            org.json.JSONObject.NULL -> null
            else -> value
        }
    }
    return out
}
