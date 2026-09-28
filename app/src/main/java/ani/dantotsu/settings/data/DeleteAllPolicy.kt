package ani.dantotsu.settings.data

/**
 * MAX MERGE M3 HOLD (Blocker 5): delete-all dispatch policy. Every
 * advertised category is covered, each through its ownership-safe path:
 * anime/novel cancel active work first (existing per-task paths) then
 * purge; manga travels its service scope barrier (direct purge refuses).
 * Framework-free, unit-proved (DeleteAllPolicyTest).
 */
enum class PurgeStep {
    AnimeCancelAll,
    AnimePurge,
    NovelCancelAll,
    NovelPurge,
    MangaBarrierPurge,
}

internal fun deleteAllDownloadsPlan(): List<PurgeStep> =
    listOf(
        PurgeStep.AnimeCancelAll,
        PurgeStep.AnimePurge,
        PurgeStep.NovelCancelAll,
        PurgeStep.NovelPurge,
        PurgeStep.MangaBarrierPurge
    )
