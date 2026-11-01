package ani.dantotsu.media.anime.player

import ani.dantotsu.media.anime.player.VideoOutputPolicy.Output
import org.junit.Assert.assertEquals
import org.junit.Test

class VideoOutputPolicyTest {
    private val chain = "/data/x/cunny-gles-compat/CuNNy-veryfast-SOFT.glsl"

    @Test
    fun `no shader chain keeps the default gpu output`() {
        assertEquals("gpu", VideoOutputPolicy.voFor(""))
        assertEquals("gpu", VideoOutputPolicy.voFor("   "))
    }

    @Test
    fun `any shader chain switches to gpu-next`() {
        assertEquals("gpu-next", VideoOutputPolicy.voFor(chain))
        assertEquals("gpu-next", VideoOutputPolicy.voFor("/a.glsl:/b.glsl"))
    }

    @Test
    fun `stock libmpv leaves gpu-api and gpu-context to mpv`() {
        assertEquals(Output("gpu", null, null), VideoOutputPolicy.outputFor("", vulkanLibmpv = false))
        assertEquals(Output("gpu-next", null, null), VideoOutputPolicy.outputFor(chain, vulkanLibmpv = false))
    }

    @Test
    fun `vulkan libmpv uses vulkan only while a shader chain is active`() {
        assertEquals(Output("gpu-next", "vulkan", "androidvk"), VideoOutputPolicy.outputFor(chain, vulkanLibmpv = true))
        assertEquals(Output("gpu", "opengl", "android"), VideoOutputPolicy.outputFor(" ", vulkanLibmpv = true))
    }
}
