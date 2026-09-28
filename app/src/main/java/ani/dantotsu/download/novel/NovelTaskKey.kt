package ani.dantotsu.download.novel

/**
 * MAX MERGE M3 HOLD (Blocker 3): exact novel task identity.
 *
 * Job records are keyed `"${title}_${chapter}"`. Chapter-only identity is
 * ambiguous (two novels can share chapter `"1"`), so all NEW cancellation
 * paths use exact keys. Framework-free, unit-proved (NovelTaskKeyTest).
 */
data class NovelTaskId(val title: String, val chapter: String) {
    fun key(): String = "${title}_${chapter}"
}

internal fun novelTaskKey(title: String, chapter: String): String =
    NovelTaskId(title, chapter).key()

/** Exact keys for an all-cancel sweep (queued + active, distinct, stable). */
internal fun novelAllCancelKeys(tasks: List<NovelTaskId>): List<String> =
    tasks.map { it.key() }.distinct()

/** Exact keys in scope for one single-item cancel — never a sibling title. */
internal fun novelSingleCancelKeys(
    tasks: List<NovelTaskId>,
    title: String,
    chapter: String,
): List<String> =
    tasks.filter { it.title == title && it.chapter == chapter }.map { it.key() }.distinct()
