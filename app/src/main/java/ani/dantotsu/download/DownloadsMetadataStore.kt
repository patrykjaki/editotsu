package ani.dantotsu.download

/**
 * Serialized in-memory + persisted store for the downloads metadata index
 * (`DownloadsKeys`).
 *
 * `downloadsList` used to be one plain mutable list with ad-hoc
 * mutate-then-serialize-then-write sequences and no shared lock: two unrelated
 * Manga completions (or a completion racing a title/purge cleanup) could
 * interleave mutation/serialization/persistence so a stale JSON snapshot
 * overwrote a newer one, losing valid COMPLETE entries after restart — and
 * concurrent iteration could hit ordinary mutable-list hazards.
 *
 * Every state transition goes through [transact]: list mutation, snapshot
 * serialization, and the [persist] write form ONE atomic critical section
 * relative to every competing store operation. Readers use [snapshot] and
 * never observe mid-mutation iteration.
 *
 * Rules: [block] must be pure in-memory list work — no suspension, no SAF, no
 * network (callers do provider work outside and transact only the result).
 * If [block] (or [persist]) throws, the membership change is rolled back and
 * nothing is persisted, so a failed operation performs zero metadata mutation.
 */
class DownloadsMetadataStore(
    initial: List<DownloadedType> = emptyList(),
    private val persist: (List<DownloadedType>) -> Unit = {},
) {
    private val lock = Any()
    private val entries: MutableList<DownloadedType> = initial.toMutableList()

    fun <T> transact(block: (MutableList<DownloadedType>) -> T): T {
        synchronized(lock) {
            val backup = entries.toList()
            try {
                val result = block(entries)
                persist(entries.toList())
                return result
            } catch (e: Throwable) {
                entries.clear()
                entries.addAll(backup)
                throw e
            }
        }
    }

    /** Consistent copy safe to iterate; reflects one atomic store state. */
    fun snapshot(): List<DownloadedType> = synchronized(lock) { entries.toList() }
}
