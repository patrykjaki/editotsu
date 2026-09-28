package ani.dantotsu.media.manga.mangareader

/**
 * MAX MERGE M3 HOLD (Blocker 7): smart-manga source identity policy.
 *
 * The next chapter may only be fetched from a deterministically resolved
 * origin: the currently selected source when it is an online dynamic
 * parser. When reading offline (downloaded slot) the origin is not
 * recorded anywhere — guessing "first installed dynamic parser" would
 * query the wrong extension with invalid chapter state. Unknown origin
 * is a safe no-op. Framework-free, unit-proved (SmartMangaSourceTest).
 */
internal fun selectSmartMangaSourceIndex(
    selectedSourceIndex: Int,
    selectedIsOnlineDynamic: Boolean,
): Int? {
    if (!selectedIsOnlineDynamic) return null
    if (selectedSourceIndex < 0) return null
    return selectedSourceIndex
}
