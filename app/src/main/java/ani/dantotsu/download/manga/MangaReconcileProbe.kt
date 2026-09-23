package ani.dantotsu.download.manga

/**
 * Authoritative per-chapter reconciliation probe for CP5 COMPLETE metadata.
 *
 * The v9 defect: reconciliation resolved chapters through lossy `DocumentFile`
 * (`findFolder`/`listFiles` collapse provider/query failures into null/empty),
 * so a transient per-chapter provider failure pruned valid COMPLETE entries.
 * Every state here comes from direct authoritative provider queries (analogous
 * to [MangaSafProbe]), all the way through chapter lookup and page
 * enumeration:
 * - [Status.Complete]: a succeeded query chain proved the exact expected page
 *   set present (via [MangaDownloadValidator]);
 * - [Status.VerifiedIncomplete]: a succeeded query chain proved the chapter
 *   missing or its page set incomplete — the ONLY prunable state;
 * - [Status.Unavailable]: any query failure — preserve metadata.
 *
 * [shouldPrune] is the single prune choke point: legacy `pageCount == null`
 * entries are trusted without consulting the probe, any probe failure
 * preserves, and only [Status.VerifiedIncomplete] prunes.
 */
object MangaReconcileProbe {

    sealed interface Status {
        data object Complete : Status
        data object VerifiedIncomplete : Status
        data object Unavailable : Status
    }

    /**
     * Authoritative per-chapter status. Every provider interaction throws
     * outward (the caller maps it to preserve); only a SUCCEEDED query chain
     * decides [Status.Complete] vs [Status.VerifiedIncomplete].
     *
     * @param pageLister lists page files (name + size + file/distinction via
     * [PageFile]) for a verified chapter document; throws propagate.
     */
    fun statusOf(
        query: MangaSafProbe.ChildQuery,
        baseDocumentId: String,
        safeTitle: String,
        safeChapter: String,
        pageCount: Int,
        pageLister: (String) -> List<PageFile>,
    ): Status {
        val title = when (
            val t = MangaSafProbe.findChildDocument(query, baseDocumentId, safeTitle)
        ) {
            is MangaSafProbe.ChildLookup.Missing -> return Status.VerifiedIncomplete
            is MangaSafProbe.ChildLookup.Present -> t
        }
        val chapter = when (
            val c = MangaSafProbe.findChildDocument(query, title.documentId, safeChapter)
        ) {
            is MangaSafProbe.ChildLookup.Missing -> return Status.VerifiedIncomplete
            is MangaSafProbe.ChildLookup.Present -> c
        }
        val pages = pageLister(chapter.documentId)
        return if (MangaDownloadValidator.isChapterComplete(pages, pageCount)) {
            Status.Complete
        } else {
            Status.VerifiedIncomplete
        }
    }

    /**
     * Whether the entry may be pruned: legacy unknown page counts are trusted
     * (the probe never runs), any probe failure preserves, and only
     * [Status.VerifiedIncomplete] prunes.
     */
    fun shouldPrune(pageCount: Int?, probe: (Int) -> Status): Boolean {
        val count = pageCount ?: return false
        return try {
            probe(count) == Status.VerifiedIncomplete
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
    }
}
