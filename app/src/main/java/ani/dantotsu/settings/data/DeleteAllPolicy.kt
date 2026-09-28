package ani.dantotsu.settings.data

/**
 * MAX MERGE M3 HOLD (Blocker 2): delete-all dispatch policy. Every
 * advertised category is covered, each through its ownership-safe
 * SERVICE transaction (cancel → join → purge → report):
 * anime/novel run their coordinator transaction; manga keeps its native
 * scope-barrier transaction. The activity NEVER purges directories
 * directly. Framework-free, unit-proved (DeleteAllPolicyTest).
 */
enum class PurgeStep {
    AnimeServicePurge,
    NovelServicePurge,
    MangaBarrierPurge,
}

internal fun deleteAllDownloadsPlan(): List<PurgeStep> =
    listOf(
        PurgeStep.AnimeServicePurge,
        PurgeStep.NovelServicePurge,
        PurgeStep.MangaBarrierPurge
    )
