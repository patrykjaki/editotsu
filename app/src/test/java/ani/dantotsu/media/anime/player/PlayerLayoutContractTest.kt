package ani.dantotsu.media.anime.player

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PlayerLayoutContractTest {

    @Test
    fun testAllRequiredPlayerViewAndControlIdsExistInLayouts() {
        val projectDir = File(System.getProperty("user.dir") ?: ".")
        val resDir = File(projectDir, "src/main/res/layout").takeIf { it.exists() }
            ?: File(projectDir, "app/src/main/res/layout")

        assertTrue("Layout directory must exist at ${resDir.absolutePath}", resDir.exists())

        val playerViewXml = File(resDir, "exo_player_view.xml").readText()
        val controlViewXml = File(resDir, "exo_player_control_view.xml").readText()
        val combinedXml = playerViewXml + "\n" + controlViewXml

        val requiredIds = listOf(
            "exo_play",
            "exo_source",
            "exo_settings",
            "exo_sub",
            "exo_audio",
            "exo_rotate",
            "exo_playback_speed",
            "exo_screen",
            "exo_pip",
            "exo_skip_op_ed",
            "exo_skip",
            "exo_anime_title",
            "exo_ep_sel",
            "exo_cast",
            "exo_progress",
            "exo_position",
            "exo_duration",
            "exo_buffering",
            "exo_brightness",
            "exo_volume",
            "exo_brightness_cont",
            "exo_volume_cont",
            "exo_skip_timestamp",
            "exo_skip_timestamp_text",
            "exo_time_stamp_text",
            "exo_touch_view",
            "exo_controller",
            "exo_controller_cont",
            "exo_content_frame",
            "exo_shutter",
            "exo_controller_placeholder"
        )

        for (id in requiredIds) {
            val hasId = combinedXml.contains("android:id=\"@+id/$id\"") ||
                    combinedXml.contains("android:id=\"@id/$id\"")
            assertTrue("Required view ID '$id' must be defined in player layouts", hasId)
        }
    }
}
