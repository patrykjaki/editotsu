package ani.dantotsu.download.manga

/**
 * Lifecycle-safe routing for the authoritative manga chapter delete.
 *
 * A completed chapter is typically deleted long after [MangaDownloaderService] has
 * drained its queue and stopped (unregistering its dynamic receiver), so a dynamic
 * broadcast can silently become a no-op. The delete command is therefore an EXPLICIT
 * intent to the non-exported [MangaDownloaderService], handled in `onStartCommand`
 * (which runs however the service was started — already alive or freshly created),
 * never via a dynamic receiver.
 *
 * This object holds the pure, Android-free command validation so the routing contract
 * is unit-testable: only the exact delete action with non-blank title + chapter is a
 * command; anything else (wrong action, missing/blank extras) is rejected and must
 * never trigger a destructive transaction.
 */
object MangaDeleteCommand {

    /**
     * Validated delete command: the exact physical-chapter identity plus the optional
     * adapter uniqueNumber used only for the post-delete UI signal.
     */
    data class DeleteCommand(
        val title: String,
        val chapter: String,
        val uniqueNumber: String?,
    )

    /**
     * Validate a delete request. Returns null unless [action] is exactly the delete
     * action and both [title] and [chapter] are non-blank. [uniqueNumber] is opaque
     * UI routing data and may be null (the service falls back to queued task names).
     */
    fun parse(action: String?, title: String?, chapter: String?, uniqueNumber: String?): DeleteCommand? {
        if (action != MangaDownloaderService.ACTION_DELETE_CHAPTER) return null
        if (title.isNullOrBlank() || chapter.isNullOrBlank()) return null
        return DeleteCommand(title, chapter, uniqueNumber)
    }

    /** Validated scope-wide destructive command: one title, or the whole manga type. */
    sealed interface ScopeCommand {
        data class Title(val title: String) : ScopeCommand
        data object AllManga : ScopeCommand
    }

    /**
     * Validate a scope-wide destructive request. `ACTION_DELETE_TITLE` requires a
     * non-blank title; `ACTION_PURGE_MANGA` takes no identity. Anything else is
     * rejected and must never trigger a destructive transaction.
     */
    fun parseScope(action: String?, title: String?): ScopeCommand? = when (action) {
        MangaDownloaderService.ACTION_DELETE_TITLE ->
            if (title.isNullOrBlank()) null else ScopeCommand.Title(title)
        MangaDownloaderService.ACTION_PURGE_MANGA -> ScopeCommand.AllManga
        else -> null
    }
}
