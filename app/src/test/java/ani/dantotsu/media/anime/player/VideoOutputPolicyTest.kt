package ani.dantotsu.media.anime.player

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoOutputPolicyTest {
    @Test
    fun `no shader chain keeps the default gpu output`() {
        assertEquals("gpu", VideoOutputPolicy.voFor(""))
        assertEquals("gpu", VideoOutputPolicy.voFor("   "))
    }

    @Test
    fun `any shader chain switches to gpu-next`() {
        assertEquals("gpu-next", VideoOutputPolicy.voFor("/data/x/cunny-gles-compat/CuNNy-veryfast-SOFT.glsl"))
        assertEquals("gpu-next", VideoOutputPolicy.voFor("/a.glsl:/b.glsl"))
    }
}
