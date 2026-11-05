package ani.dantotsu.screenshots

import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.RecyclerView
import ani.dantotsu.R
import ani.dantotsu.media.MediaDetailsViewModel
import ani.dantotsu.media.anime.Episode
import ani.dantotsu.media.anime.SelectorDialogFragment
import ani.dantotsu.parsers.VideoExtractor
import ani.dantotsu.parsers.WatchSources
import com.google.android.material.chip.Chip
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.Robolectric
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

/**
 * The source picker sheet (SelectorDialogFragment) with fixed releases, opened the way the
 * episode list opens it (forcePicker), and its filter-and-sort sheet. No network: the episode
 * already has its extractors (allStreams), so the picker only builds rows.
 */
class SourcePickerScreenshotTest : ScreenshotTest() {

    private object NoSources : WatchSources() {
        override val list: List<ani.dantotsu.Lazier<ani.dantotsu.parsers.BaseParser>> = emptyList()
    }

    private lateinit var picker: SelectorDialogFragment

    private fun openPicker(extractors: List<VideoExtractor>) {
        val activity = Robolectric.buildActivity(ScreenHostActivity::class.java).setup().get()
        val ep = Episode("6", link = "ep6", title = "The Night Market").apply {
            this.extractors = extractors.toMutableList()
            allStreams = true
        }
        val model = ViewModelProvider(activity)[MediaDetailsViewModel::class.java]
        model.watchSources = NoSources
        model.setMedia(Fixtures.media(mapOf("6" to ep)))
        picker = SelectorDialogFragment.newInstance(isDownload = false, episodes = arrayListOf("6"), forcePicker = true)
        picker.show(activity.supportFragmentManager, "picker")
        // Rows are built on a background worker, then applied on the main thread.
        val list = waitFor { picker.view?.findViewById<RecyclerView>(R.id.selectorRecyclerView)?.takeIf { (it.adapter?.itemCount ?: 0) > 0 } }
        assertNotNull("picker list stayed empty", list)
        idle()
    }

    private fun <T> waitFor(timeoutMs: Long = 10_000, get: () -> T?): T? {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            idle(50)
            get()?.let { return it }
            Thread.sleep(20)
        }
        return null
    }

    private fun chips(root: View): List<Chip> {
        val out = ArrayList<Chip>()
        fun walk(v: View) {
            if (v is Chip) out += v
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(root)
        return out
    }

    private fun openFilterSheet(): View {
        val filters = picker.requireView().findViewById<ViewGroup>(R.id.selectorFilters)
        val title = picker.getString(R.string.picker_filter_title)
        val button = chips(filters).firstOrNull { it.contentDescription?.toString()?.let { cd -> cd == title || cd.any(Char::isDigit) } == true }
        assertNotNull("no filter chip in ${chips(filters).map { it.text }}", button)
        button!!.performClick()
        idle()
        return ShadowDialog.getLatestDialog().window!!.decorView
    }

    private fun Chip.tapAndSettle() {
        performClick()
        idle()
    }

    @Test fun releases() {
        openPicker(Fixtures.releases)
        shot("picker/releases")
    }

    @Test @Config(fontScale = LARGE_FONT)
    fun releases_largeFont() {
        openPicker(Fixtures.releases)
        shot("picker/releases_font130")
    }

    @Test fun streams() {
        openPicker(Fixtures.streams)
        shot("picker/streams")
    }

    @Test fun filter_sheet() {
        openPicker(Fixtures.releases)
        openFilterSheet()
        shot("picker/filter_sheet")
    }

    @Test fun filter_sheet_selected_and_more_groups() {
        openPicker(Fixtures.releases)
        var sheet = openFilterSheet()
        chips(sheet).first { it.text.startsWith("1080p") }.tapAndSettle()
        sheet = ShadowDialog.getLatestDialog().window!!.decorView
        chips(sheet).first { it.text.startsWith("+") }.tapAndSettle()
        sheet = ShadowDialog.getLatestDialog().window!!.decorView
        assertTrue("clear chip hidden", chips(sheet).any { it.text == picker.getString(R.string.picker_filter_clear) && it.isShown })
        shot("picker/filter_sheet_1080p_all_groups")
    }

    @Test @Config(fontScale = LARGE_FONT)
    fun filter_sheet_largeFont() {
        openPicker(Fixtures.releases)
        openFilterSheet()
        shot("picker/filter_sheet_font130")
    }
}
