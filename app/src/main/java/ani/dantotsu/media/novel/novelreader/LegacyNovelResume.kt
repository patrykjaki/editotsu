package ani.dantotsu.media.novel.novelreader

/**
 * MAX MERGE M4 correction (HOLD2 A1): one-shot migration of the legacy
 * ebook-reader resume position (`${book}_progress`, foliate-style EPUB CFI)
 * to a Readium chapter-start Locator.
 *
 * Readium ships no CFI resolver, and intra-chapter CFI→progression mapping
 * needs a layout engine, so migration honestly recovers the CHAPTER (spine
 * item) and resumes at its start; exact intra-chapter offsets are never
 * fabricated. Only the CFI package-document header is interpreted, per the
 * EPUB CFI specification (`/6/{even}` addresses the Nth spine itemref as
 * `step/2 - 1`); chapter-internal steps are never sliced or guessed.
 *
 * Pure seam: the activity executes the returned [Plan] (persist the
 * migrated Locator and drop the legacy key on [Plan.Migrate], keep the
 * legacy key untouched on [Plan.Miss]).
 */
object LegacyNovelResume {

    sealed interface Plan {
        /** A valid new Locator exists: it always wins, legacy is ignored. */
        data object UseSaved : Plan

        /**
         * Migrate: resolve [href] through the opened publication's reading
         * order, navigate to its start, persist that Locator, then drop the
         * legacy key.
         */
        data class Migrate(val spineIndex: Int, val href: String) : Plan

        /** No usable position anywhere: start normally, keep legacy as-is. */
        data object Miss : Plan
    }

    private val CFI_HEADER = Regex("""^epubcfi\(/6/(\d+)([!/:;.,\[].*)?\)$""")

    fun plan(
        savedLocatorPresent: Boolean,
        legacyCfi: String?,
        readingOrderHrefs: List<String>,
    ): Plan {
        if (savedLocatorPresent) return Plan.UseSaved
        val cfi = legacyCfi?.trim().orEmpty()
        if (cfi.isEmpty()) return Plan.Miss
        val step = CFI_HEADER.matchEntire(cfi)?.groupValues?.get(1)?.toIntOrNull()
            ?: return Plan.Miss
        if (step % 2 != 0 || step < 2) return Plan.Miss
        val spineIndex = step / 2 - 1
        val href = readingOrderHrefs.getOrNull(spineIndex)?.takeIf { it.isNotEmpty() }
            ?: return Plan.Miss
        return Plan.Migrate(spineIndex, href)
    }
}
