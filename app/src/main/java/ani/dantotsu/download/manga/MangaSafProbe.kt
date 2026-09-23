package ani.dantotsu.download.manga

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import ani.dantotsu.download.findValidName

/**
 * Authoritative SAF child probe for the manga destructive operations.
 *
 * AndroidX `DocumentFile` is lossy for exactly the error class CP5 must not
 * mistake for absence: `TreeDocumentFile.listFiles()` catches provider/query
 * exceptions and returns the (often empty) collected list, and
 * `DocumentsContractApi19.exists()` catches and returns false. A `null` /
 * `false` from those helpers therefore cannot prove `VerifiedAbsent`.
 *
 * This probe instead queries the provider DIRECTLY via `ContentResolver` /
 * `DocumentsContract`, where every failure throws into
 * [MangaPhysicalDelete.evaluate] (→ Unavailable, metadata preserved):
 * - successful query, no matching child → [ChildLookup.Missing] (the ONLY
 *   absence signal; backed by a completed provider query);
 * - successful query, matching child → [ChildLookup.Present] (presence
 *   verified by the same query);
 * - query/provider/security failure (including a null cursor) → throws.
 *
 * Physical identity semantics for destructive selection: [findValidName]
 * sanitization happens at the call sites (this probe takes the
 * already-sanitized name), matching order is exact → case-insensitive/trim,
 * directories only, title → chapter hierarchy. There is deliberately no fuzzy
 * fallback here (see [matchChild]); nothing else about WHICH on-disk
 * title/chapter is targeted changes — only how absence vs provider-failure
 * is distinguished.
 *
 * The query itself is injected ([ChildQuery]) so the adapter
 * ([findChildDocument]) is unit-testable with fake providers; production passes
 * [systemQuery].
 */
object MangaSafProbe {

    /** One child row from an authoritative provider query. */
    data class ChildEntry(
        val documentId: String,
        val displayName: String?,
        val isDirectory: Boolean,
        val size: Long = 0L,
    )

    /**
     * Functional provider query: lists the children of [parentDocumentId], or
     * THROWS on any provider/query/security failure. Never returns an
     * error-masquerading empty list.
     */
    fun interface ChildQuery {
        fun listChildren(parentDocumentId: String): List<ChildEntry>
    }

    sealed interface ChildLookup {
        data object Missing : ChildLookup
        data class Present(val documentId: String) : ChildLookup
    }

    /**
     * Pure identity match over one successful query result for DESTRUCTIVE
     * target selection: exact sanitized directory name first, then
     * case-insensitive/trimmed exact. Directories only.
     *
     * Deliberately NO fuzzy fallback: when the requested target is absent, a
     * fuzzy-similar sibling must resolve to Missing — never to the sibling's
     * document (which would delete the wrong directory). Fuzzy `compareName`
     * resolution remains available ONLY in the separate read-only legacy
     * `findFolder` helper, which must never select a destructive target.
     *
     * @param sanitizedName the already-[findValidName]-sanitized target name.
     */
    fun matchChild(
        children: List<ChildEntry>,
        sanitizedName: String,
    ): ChildEntry? {
        val dirs = children.filter { it.isDirectory }
        dirs.firstOrNull { it.displayName == sanitizedName }?.let { return it }
        return dirs.firstOrNull {
            it.displayName.equals(sanitizedName, ignoreCase = true) ||
                it.displayName?.trim().equals(sanitizedName.trim(), ignoreCase = true)
        }
    }

    /**
     * Authoritative child lookup: exactly one provider query (failures
     * propagate to the caller for Unavailable mapping) plus [matchChild].
     */
    fun findChildDocument(
        query: ChildQuery,
        parentDocumentId: String,
        sanitizedName: String,
    ): ChildLookup {
        val match = matchChild(query.listChildren(parentDocumentId), sanitizedName)
        return if (match == null) ChildLookup.Missing else ChildLookup.Present(match.documentId)
    }

    /**
     * Production query: direct `ContentResolver` child listing. A null cursor is
     * a provider failure (throws) — it is never an empty result.
     */
    fun systemQuery(context: Context, treeUri: Uri): ChildQuery = ChildQuery { parentDocumentId ->
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocumentId)
        val out = mutableListOf<ChildEntry>()
        val cursor = context.contentResolver.query(
            childrenUri,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE,
            ),
            null,
            null,
            null,
        ) ?: throw IllegalStateException(
            "SAF provider query returned null cursor for $parentDocumentId"
        )
        cursor.use {
            val idCol = it.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameCol = it.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeCol = it.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val sizeCol = it.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE)
            while (it.moveToNext()) {
                out.add(
                    ChildEntry(
                        documentId = it.getString(idCol),
                        displayName = it.getString(nameCol),
                        isDirectory = DocumentsContract.Document.MIME_TYPE_DIR ==
                            it.getString(mimeCol),
                        size = it.getLong(sizeCol),
                    )
                )
            }
        }
        out
    }

    /** Builds a tree-scoped document URI for a probed [ChildLookup.Present] handle. */
    fun documentUri(treeUri: Uri, documentId: String): Uri =
        DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
}
