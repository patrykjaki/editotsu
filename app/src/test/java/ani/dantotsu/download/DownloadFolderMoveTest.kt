package ani.dantotsu.download

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadFolderMoveTest {

    @Test
    fun `invalid target counts as moved only when verified at the target`() {
        assertTrue(isMoveVerified(targetHasBase = true, sourceHasBase = false))
        // Target already had a Dantotsu folder and nothing moved.
        assertFalse(isMoveVerified(targetHasBase = true, sourceHasBase = true))
        assertFalse(isMoveVerified(targetHasBase = false, sourceHasBase = true))
        assertFalse(isMoveVerified(targetHasBase = false, sourceHasBase = false))
    }

    @Test
    fun `any busy download type refuses the move`() {
        assertFalse(downloadsBusy(false, false, false))
        assertTrue(downloadsBusy(false, true, false))
        assertTrue(downloadsBusy(true, false, false))
    }
}
