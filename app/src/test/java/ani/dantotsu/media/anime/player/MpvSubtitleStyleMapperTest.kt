package ani.dantotsu.media.anime.player

import org.junit.Assert.assertEquals
import org.junit.Test

class MpvSubtitleStyleMapperTest {

    @Test
    fun `default stroke maps to mpv's default outline and stays bounded`() {
        assertEquals(3.0, MpvSubtitleStyleMapper.strokeToMpvBorder(8f), 1e-9)
        assertEquals(0.0, MpvSubtitleStyleMapper.strokeToMpvBorder(0f), 1e-9)
        assertEquals(11.25, MpvSubtitleStyleMapper.strokeToMpvBorder(30f), 1e-9)
        assertEquals(0.0, MpvSubtitleStyleMapper.strokeToMpvBorder(-4f), 1e-9)
    }

    @Test
    fun `source size 100 percent keeps styles untouched, other sizes scale them`() {
        assertEquals("no" to 1.0, MpvSubtitleStyleMapper.sourceScaling(1f))
        assertEquals("scale" to 1.25, MpvSubtitleStyleMapper.sourceScaling(1.25f).let { it.first to (Math.round(it.second * 100) / 100.0) })
        assertEquals("scale" to 2.0, MpvSubtitleStyleMapper.sourceScaling(9f))
        assertEquals("scale" to 0.5, MpvSubtitleStyleMapper.sourceScaling(0.1f))
    }
}
