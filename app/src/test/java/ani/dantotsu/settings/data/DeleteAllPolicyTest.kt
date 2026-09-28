package ani.dantotsu.settings.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * MAX MERGE M3 HOLD (Blocker 2): delete-all covers anime + manga + novel,
 * each through its service transaction — never direct UI deletion.
 */
class DeleteAllPolicyTest {

    @Test
    fun `all three categories covered through service transactions`() {
        assertEquals(
            listOf(
                PurgeStep.AnimeServicePurge,
                PurgeStep.NovelServicePurge,
                PurgeStep.MangaBarrierPurge
            ),
            deleteAllDownloadsPlan()
        )
    }
}
