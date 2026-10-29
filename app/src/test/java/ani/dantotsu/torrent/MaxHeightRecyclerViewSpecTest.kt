package ani.dantotsu.torrent

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * M7A G2 landscape correction: the file-list cap must never loosen a tighter
 * parent constraint (landscape / large font), otherwise the list claims
 * 320dp and pushes Play/Download/Cancel off-screen again.
 */
class MaxHeightRecyclerViewSpecTest {

    private val exactly = 1 shl 30
    private val atMost = 2 shl 30
    private val unspecified = 0
    private val mask = 0x3 shl 30

    private fun mode(spec: Int) = spec and mask
    private fun size(spec: Int) = spec and mask.inv()
    private val cap = 840 // 320dp at 2.625 density

    @Test
    fun atMostLargerThanCapIsCapped() {
        val r = MaxHeightRecyclerView.cappedHeightSpec(atMost or 2000, cap)
        assertEquals(atMost, mode(r)); assertEquals(cap, size(r))
    }

    @Test
    fun atMostSmallerThanCapKeepsParentLimit() {
        val r = MaxHeightRecyclerView.cappedHeightSpec(atMost or 300, cap)
        assertEquals(atMost, mode(r)); assertEquals(300, size(r))
    }

    @Test
    fun exactlySmallerThanCapStaysExact() {
        // LinearLayout weight pass shrinking the list in landscape.
        val r = MaxHeightRecyclerView.cappedHeightSpec(exactly or 250, cap)
        assertEquals(exactly, mode(r)); assertEquals(250, size(r))
    }

    @Test
    fun exactlyLargerThanCapIsCappedExact() {
        val r = MaxHeightRecyclerView.cappedHeightSpec(exactly or 1500, cap)
        assertEquals(exactly, mode(r)); assertEquals(cap, size(r))
    }

    @Test
    fun unspecifiedBecomesAtMostCap() {
        val r = MaxHeightRecyclerView.cappedHeightSpec(unspecified, cap)
        assertEquals(atMost, mode(r)); assertEquals(cap, size(r))
    }
}
