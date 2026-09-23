package ani.dantotsu.media.anime.selector

/**
 * Persistence-owned stable release canonicalizer.
 *
 * Builds a canonical stable release representation for direct / debid
 * candidates that lack a BTIH identity. NEVER uses
 * `SourceRowClassifier.cleanDisplayTitle`. Inputs are
 * persistence-owned fields from the candidate / provider
 * metadata only.
 *
 * The `rawTitle` parameter is treated as the candidate's RAW
 * description text. The canonicalizer runs
 * `StableRawTitleExtractor` to strip volatile / provenance-only
 * metadata (📂 tracker lines, 👤 seeders, 💾 size expressions, URLs,
 * cog lines, and key/value provenance pairs) BEFORE the title is
 * included in the canonical `raw=` component. The result is that a
 * release with the same group/res/audio/service/title that is
 * sourced from different trackers or carries different
 * seeder/size metadata produces the SAME direct stable key.
 *
 * Changing the actual release title still changes the direct
 * stable key.
 *
 * Order of fields in the joined canonical release:
 *
 *   group=<stableGroupKey>            (if present)
 *   res=<canonicalInternalResolution> (if present)
 *   afmt=<canonicalAudioFormat>       (if present)
 *   audio=<canonicalAudioMode>        (only when afmt absent)
 *   svc=<canonicalSourceServiceKey>   (if present)
 *   raw=<stable normalized raw release title>
 */
object StableReleaseCanonicalizer {
    /**
     * Nullable / fail-closed canonicalization. Returns null when any
     * supplied field cannot be safely canonicalized, and ALWAYS
     * when there is no meaningful stable raw release title — even
     * if group/res/audio/svc fields exist. A canonical release
     * without a stable raw title is not a valid direct identity
     * (`raw=` is never emitted with an empty value). Callers MUST
     * treat null as "no direct stable key".
     */
    fun canonicalize(
        group: String?,
        resolution: String?,
        audioFormat: String?,
        audioMode: String?,
        sourceService: String?,
        rawTitle: String,
    ): String? {
        val parts = mutableListOf<String>()
        if (!group.isNullOrBlank()) {
            val groupKey = StableGroupCanonicalizer.canonicalKey(group)
                ?: return null
            parts += "group=$groupKey"
        }
        // Structured fields use the SAME persistence-owned
        // canonicalizers as the family soft values, so DIRECT exact
        // identity agrees with family continuity (4K == 2160p,
        // "Dual   Audio" == "Dual Audio"). Never UI formatting.
        StableSoftCanonicalizer.canonicalResolution(resolution)?.let {
            parts += "res=$it"
        }
        val afmt = StableSoftCanonicalizer.canonicalText(audioFormat)
        if (afmt != null) {
            parts += "afmt=$afmt"
        } else {
            StableSoftCanonicalizer.canonicalText(audioMode)?.let {
                parts += "audio=$it"
            }
        }
        if (!sourceService.isNullOrBlank()) {
            val svc = SourceServiceCanonicalizer.canonicalKey(sourceService)
                ?: return null
            parts += "svc=$svc"
        }
        // Strip volatile / provenance metadata from the raw title
        // BEFORE hashing. This ensures the direct stable key is
        // independent of tracker / seeder / size / etc. metadata.
        // No meaningful stable raw title -> NO direct stable key,
        // regardless of which other fields are present. Hashing a
        // metadata-only input (or emitting `raw=` empty) would
        // create a constant collision domain between distinct
        // releases.
        val stableRaw = StableRawTitleExtractor.extract(rawTitle) ?: return null
        parts += "raw=$stableRaw"
        return parts.joinToString("|")
    }

    fun releaseStableId16(
        group: String?,
        resolution: String?,
        audioFormat: String?,
        audioMode: String?,
        sourceService: String?,
        rawTitle: String,
    ): String? {
        val canonical = canonicalize(group, resolution, audioFormat,
            audioMode, sourceService, rawTitle) ?: return null
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(16)
    }
}
