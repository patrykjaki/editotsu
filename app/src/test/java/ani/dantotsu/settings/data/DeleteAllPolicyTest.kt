package ani.dantotsu.settings.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MAX MERGE M3 HOLD (Blocker 5): delete-all covers anime + manga + novel,
 * each through its safe path.
 */
class DeleteAllPolicyTest {

    @Test
    fun `all three categories covered`() {
        val plan = deleteAllDownloadsPlan()
        assertTrue(plan.contains(PurgeStep.AnimePurge))
        assertTrue(plan.contains(PurgeStep.NovelPurge))
        assertTrue(plan.contains(PurgeStep.MangaBarrierPurge))
    }

    @Test
    fun `actives cancelled before physical purge`() {
        val plan = deleteAllDownloadsPlan()
        assertTrue(plan.indexOf(PurgeStep.AnimeCancelAll) < plan.indexOf(PurgeStep.AnimePurge))
        assertTrue(plan.indexOf(PurgeStep.NovelCancelAll) < plan.indexOf(PurgeStep.NovelPurge))
    }

    @Test
    fun `exact plan`() {
        assertEquals(
            listOf(
                PurgeStep.AnimeCancelAll,
                PurgeStep.AnimePurge,
                PurgeStep.NovelCancelAll,
                PurgeStep.NovelPurge,
                PurgeStep.MangaBarrierPurge
            ),
            deleteAllDownloadsPlan()
        )
    }
}
