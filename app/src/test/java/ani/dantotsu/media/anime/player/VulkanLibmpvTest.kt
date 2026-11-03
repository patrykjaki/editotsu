package ani.dantotsu.media.anime.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VulkanLibmpvTest {
    @Test
    fun `gpu context and vo init errors count as a failed start`() {
        assertTrue(VulkanLibmpv.isVoInitFailure(20, "Failed initializing any suitable GPU context!\n"))
        assertTrue(VulkanLibmpv.isVoInitFailure(10, "Error opening/initializing the selected video_out (--vo) device.\n"))
    }

    @Test
    fun `same text below error level or other errors do not`() {
        assertFalse(VulkanLibmpv.isVoInitFailure(30, "Failed initializing any suitable GPU context!"))
        assertFalse(VulkanLibmpv.isVoInitFailure(20, "Failed dispatching COMPUTE shader"))
    }
}
