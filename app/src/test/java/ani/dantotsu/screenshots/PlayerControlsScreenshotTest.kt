package ani.dantotsu.screenshots

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.Spinner
import android.widget.TextView
import ani.dantotsu.NoPaddingArrayAdapter
import ani.dantotsu.R
import org.junit.Test
import org.robolectric.Robolectric
import org.robolectric.annotation.Config

/**
 * The player controller (exo_player_control_view.xml) over a black frame, landscape.
 * Episode and theme mode set the same views ExoplayerView sets (setupThemeMode and the
 * episode setup); if those change, change [themeMode] here too.
 */
@Config(qualifiers = PlayerControlsScreenshotTest.LANDSCAPE)
class PlayerControlsScreenshotTest : ScreenshotTest() {

    companion object {
        const val LANDSCAPE = "w870dp-h393dp-normal-long-notround-land-440dpi-keyshidden-nonav"
    }

    private fun controls(build: (View) -> Unit): View {
        val activity = Robolectric.buildActivity(ScreenHostActivity::class.java).setup().get()
        val frame = FrameLayout(activity).apply { setBackgroundColor(Color.BLACK) }
        activity.setContentView(frame)
        val view = LayoutInflater.from(activity).inflate(R.layout.exo_player_control_view, frame, false)
        frame.addView(view)
        build(view)
        return frame
    }

    private fun View.title(anime: String, items: List<String>) {
        findViewById<TextView>(R.id.exo_anime_title).text = anime
        findViewById<Spinner>(R.id.exo_ep_sel).adapter = NoPaddingArrayAdapter(context, R.layout.item_dropdown, items)
    }

    private fun episodeMode(v: View) {
        v.title("Andon Yakkyoku Nisshi", listOf("Episode 6: The Night Market"))
        // Subtitle and audio buttons show once the episode has tracks.
        v.findViewById<View>(R.id.exo_sub).visibility = View.VISIBLE
        v.findViewById<View>(R.id.exo_audio).visibility = View.VISIBLE
        v.findViewById<View>(R.id.exo_skip_op_ed).visibility = View.VISIBLE
    }

    private fun themeMode(v: View) {
        v.title("Andon Yakkyoku Nisshi", listOf("OP 1 · Paper Lanterns at Dawn"))
        listOf(
            R.id.exo_source, R.id.exo_sub, R.id.exo_audio, R.id.exo_settings,
            R.id.exo_skip_op_ed, R.id.exo_cast, R.id.exo_skip_timestamp, R.id.exo_skip,
        ).forEach { id -> v.findViewById<View>(id)?.visibility = View.GONE }
        v.findViewById<ImageButton>(R.id.exo_theme_repeat).apply {
            visibility = View.VISIBLE
            setImageResource(R.drawable.ic_round_repeat_24)
        }
        v.findViewById<View>(androidx.media3.ui.R.id.exo_playback_speed).visibility = View.VISIBLE
        v.findViewById<View>(R.id.exo_theme_favourite).visibility = View.VISIBLE
    }

    @Test fun episode() = shot(controls(::episodeMode), "player/episode")

    @Test fun episode_nowPlaying() = shot(controls { v ->
        episodeMode(v)
        v.findViewById<TextView>(R.id.exo_now_playing).apply {
            visibility = View.VISIBLE
            text = ani.dantotsu.animethemes.NowPlaying.label(Fixtures.themes.first().auto)
            setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_round_favorite_border_24, 0, 0, 0)
        }
    }, "player/episode_now_playing")

    @Test fun theme() = shot(controls(::themeMode), "player/theme")

    @Test fun theme_failed() = shot(controls { v ->
        themeMode(v)
        v.findViewById<View>(R.id.exo_center_cont).visibility = View.GONE
        v.findViewById<View>(R.id.exo_theme_failed).visibility = View.VISIBLE
    }, "player/theme_failed")

    @Test @Config(fontScale = LARGE_FONT)
    fun episode_largeFont() = shot(controls(::episodeMode), "player/episode_font130")
}
