package ani.dantotsu.download.manga

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract

/**
 * CP5-owned provider-level destructive seam for Manga chapter/title/purge.
 *
 * v7 had an authoritative provider LOOKUP but delegated the decisive delete to
 * `DocumentFile.deleteRecursively()` (SimpleStorage over AndroidX
 * DocumentFile), whose boolean can collapse provider failures into success
 * (`empty list after query error + root delete false + exists false after
 * query error => true`). CP5 never uses that boolean as an authoritative
 * success signal anymore.
 *
 * Required flow (see [runAuthoritativeDelete]):
 * - authoritative pre-probe: Missing → VerifiedAbsent; Present → continue;
 *   query failure → Unavailable;
 * - physical deletion: direct provider operations; any throw →
 *   Unavailable/Failed with metadata preserved. Provider return values are
 *   deliberately NOT consulted — they are never sufficient;
 * - authoritative post-probe ([MangaSafProbe]): target Missing → Deleted;
 *   target Present → Failed; query failure → Unavailable, metadata preserved.
 *
 * Purge-all uses the same honest boundary over enumerated children
 * ([runPurgeChildren]) under the already-held whole-Manga ownership gate; the
 * (already empty or emptied) base directory itself is left in place.
 */
object MangaSafDelete {

    /**
     * Provider-level operations; every failure throws (never error-as-empty).
     * Extends [MangaSafProbe.ChildQuery] so a provider also serves as the
     * authoritative probe query source.
     */
    interface Provider : MangaSafProbe.ChildQuery {
        fun deleteDocument(documentId: String): Boolean
    }

    /** Production provider: direct ContentResolver/DocumentsContract operations. */
    fun systemProvider(context: Context, treeUri: Uri): Provider =
        object : Provider {
            private val query = MangaSafProbe.systemQuery(context, treeUri)
            override fun listChildren(parentDocumentId: String) =
                query.listChildren(parentDocumentId)

            override fun deleteDocument(documentId: String): Boolean =
                DocumentsContract.deleteDocument(
                    context.contentResolver,
                    MangaSafProbe.documentUri(treeUri, documentId),
                )
        }

    /**
     * Provider-level recursive delete of one document subtree. Throws propagate
     * (→ Unavailable upstream). Provider `false` returns are intentionally NOT
     * consulted here: only the post-probe decides success, so a lying `true`
     * can never yield Deleted and a `false` with a vanished target still yields
     * the verified outcome.
     *
     * Every document is deleted EXACTLY once: a directory child is deleted by
     * its own recursive call (which ends with its own delete) and never again
     * by the caller — a second provider delete may legally throw
     * `FileNotFoundException`, which must not turn a successful delete into
     * Unavailable with stale metadata left behind.
     */
    fun deleteTree(provider: Provider, targetDocumentId: String) {
        for (child in provider.listChildren(targetDocumentId)) {
            if (child.isDirectory) {
                // Recursion deletes the child directory itself exactly once;
                // falling through to another delete here would be a duplicate.
                deleteTree(provider, child.documentId)
            } else {
                provider.deleteDocument(child.documentId)
            }
        }
        provider.deleteDocument(targetDocumentId)
    }

    /** Chapter target probes (pre == post shape): title level, then chapter level. */
    fun chapterLookup(
        query: MangaSafProbe.ChildQuery,
        baseDocumentId: String,
        safeTitle: String,
        safeChapter: String,
    ): () -> MangaSafProbe.ChildLookup = {
        when (
            val title = MangaSafProbe.findChildDocument(query, baseDocumentId, safeTitle)
        ) {
            is MangaSafProbe.ChildLookup.Missing -> MangaSafProbe.ChildLookup.Missing
            is MangaSafProbe.ChildLookup.Present -> MangaSafProbe.findChildDocument(
                query, title.documentId, safeChapter
            )
        }
    }

    /** Title target probe. */
    fun titleLookup(
        query: MangaSafProbe.ChildQuery,
        baseDocumentId: String,
        safeTitle: String,
    ): () -> MangaSafProbe.ChildLookup = {
        MangaSafProbe.findChildDocument(query, baseDocumentId, safeTitle)
    }

    /**
     * Authoritative delete orchestration for one probed target. The delete
     * action runs ONLY for a Present pre-probe; its return value never decides
     * the outcome — only the post-probe does.
     */
    fun runAuthoritativeDelete(
        preProbe: () -> MangaSafProbe.ChildLookup,
        deleteTree: (String) -> Unit,
        postProbe: () -> MangaSafProbe.ChildLookup,
    ): MangaPhysicalDelete.Outcome {
        val targetId = try {
            when (val target = preProbe()) {
                is MangaSafProbe.ChildLookup.Missing ->
                    return MangaPhysicalDelete.Outcome.VerifiedAbsent
                is MangaSafProbe.ChildLookup.Present -> target.documentId
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            return MangaPhysicalDelete.Outcome.Unavailable(
                "pre-delete probe failed (${e.javaClass.simpleName}); metadata preserved"
            )
        }
        try {
            deleteTree(targetId)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            return MangaPhysicalDelete.Outcome.Unavailable(
                "physical delete threw (${e.javaClass.simpleName}); metadata preserved"
            )
        }
        return try {
            when (postProbe()) {
                is MangaSafProbe.ChildLookup.Missing -> MangaPhysicalDelete.Outcome.Deleted
                is MangaSafProbe.ChildLookup.Present -> MangaPhysicalDelete.Outcome.Failed(
                    "target still present after authoritative delete; metadata preserved"
                )
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            MangaPhysicalDelete.Outcome.Unavailable(
                "post-delete probe failed (${e.javaClass.simpleName}); metadata preserved"
            )
        }
    }

    /** Purge outcome plus whether any children existed (for honest result mapping). */
    data class PurgeResult(
        val outcome: MangaPhysicalDelete.Outcome,
        val hadChildren: Boolean,
    )

    /**
     * Authoritative purge orchestration over enumerated children: pre-list
     * (throw → Unavailable), provider-level subtree delete per child (throw →
     * Unavailable), post-list (empty → Deleted, non-empty → Failed, throw →
     * Unavailable). The base directory itself is left in place; an emptied base
     * with removed metadata still maps to Deleted via the metadata rule.
     */
    fun runPurgeChildren(
        listChildren: () -> List<MangaSafProbe.ChildEntry>,
        deleteTree: (String) -> Unit,
    ): PurgeResult {
        val pre = try {
            listChildren()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            return PurgeResult(
                MangaPhysicalDelete.Outcome.Unavailable(
                    "pre-purge probe failed (${e.javaClass.simpleName}); metadata preserved"
                ),
                hadChildren = false,
            )
        }
        if (pre.isNotEmpty()) {
            try {
                for (child in pre) deleteTree(child.documentId)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                return PurgeResult(
                    MangaPhysicalDelete.Outcome.Unavailable(
                        "purge delete threw (${e.javaClass.simpleName}); metadata preserved"
                    ),
                    hadChildren = true,
                )
            }
        }
        return try {
            val post = listChildren()
            if (post.isEmpty()) {
                PurgeResult(MangaPhysicalDelete.Outcome.Deleted, pre.isNotEmpty())
            } else {
                PurgeResult(
                    MangaPhysicalDelete.Outcome.Failed(
                        "children still present after authoritative purge; metadata preserved"
                    ),
                    hadChildren = true,
                )
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            PurgeResult(
                MangaPhysicalDelete.Outcome.Unavailable(
                    "post-purge probe failed (${e.javaClass.simpleName}); metadata preserved"
                ),
                hadChildren = true,
            )
        }
    }
}
