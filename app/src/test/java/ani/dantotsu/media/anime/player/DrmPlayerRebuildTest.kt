package ani.dantotsu.media.anime.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DrmPlayerRebuildTest {
    private val ua = "Editotsu/test"
    private val primary = "https://cdn.example/v/manifest.mpd"

    private fun plan(
        headers: Map<String, String> = mapOf("Referer" to "https://site.example/"),
        subs: List<DrmTransportPlan.SubAttachment> = emptyList(),
        audio: List<DrmTransportPlan.SideAudio> = emptyList(),
    ) = DrmTransportPlan.planSourceHeaders(primary, headers, subs, audio, ua)

    @Test
    fun `first build needs a player`() {
        assertTrue(DrmTransportPlan.needsPlayerRebuild(null, plan()))
    }

    @Test
    fun `adding a subtitle keeps the player`() {
        val before = plan()
        val after = plan(subs = DrmTransportPlan.planSubtitleAttachments(listOf(
            ExternalSubtitle(url = "https://subs.example/a.vtt", title = "English", language = "en", selected = true, id = "a")
        )))
        assertTrue(before != after)
        assertFalse(DrmTransportPlan.needsPlayerRebuild(before, after))
    }

    @Test
    fun `primary or audio header changes rebuild`() {
        val before = plan()
        assertTrue(DrmTransportPlan.needsPlayerRebuild(before, plan(headers = mapOf("Referer" to "https://other.example/"))))
        val withAudio = plan(audio = DrmTransportPlan.planAudioMerge(primary, listOf(
            ExternalAudioTrack(url = "https://cdn.example/a/en.m3u8", title = "English", language = "en")
        )).sides)
        assertTrue(DrmTransportPlan.needsPlayerRebuild(before, withAudio))
    }
}
