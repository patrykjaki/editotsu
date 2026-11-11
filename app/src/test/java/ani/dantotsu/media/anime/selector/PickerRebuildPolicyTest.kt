package ani.dantotsu.media.anime.selector

import org.junit.Assert.assertEquals
import org.junit.Test

class PickerRebuildPolicyTest {
    @Test
    fun firstListIsImmediateLaterOnesAreBatched() {
        assertEquals(0L, PickerRebuildPolicy.delayMs(nothingShownYet = true))
        assertEquals(PickerRebuildPolicy.DELAY_MS, PickerRebuildPolicy.delayMs(nothingShownYet = false))
    }
}
