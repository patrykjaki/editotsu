package ani.dantotsu.screenshots

import android.widget.FrameLayout
import androidx.recyclerview.widget.RecyclerView
import ani.dantotsu.R
import ani.dantotsu.media.MediaContentBottomSheet
import ani.dantotsu.media.MediaDetailsViewModel.WatchOrderItem
import ani.dantotsu.others.SkeletonView
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.robolectric.Robolectric

/**
 * The watch order sheet with rows already loaded (fictional show), and its loading skeleton.
 * Rows cover every state: completed, optional, current, coming soon with and without a date.
 */
class WatchOrderScreenshotTest : ScreenshotTest() {

    private val items = listOf(
        WatchOrderItem("1", "101", "", "Andon Yakkyoku Nisshi: Prologue", "Music", airDate = "2021", mediaType = "Music", episodes = "1", mainStory = false, listStatus = "COMPLETED"),
        WatchOrderItem("2", "102", "", "Andon Yakkyoku Nisshi", "Prequel", airDate = "2021", mediaType = "TV", episodes = "12", mainStory = true, listStatus = "COMPLETED"),
        WatchOrderItem("3", "103", "", "Andon Yakkyoku Nisshi: Lantern Festival", "Side Story", airDate = "2022", mediaType = "OVA", episodes = "2", mainStory = false),
        WatchOrderItem("4", "104", "", "Andon Yakkyoku Nisshi 2nd Season", "", isCurrent = true, airDate = "2023", mediaType = "TV", episodes = "13", mainStory = true, listStatus = "CURRENT"),
        WatchOrderItem("5", "105", "", "Andon Yakkyoku Nisshi 3rd Season", "Sequel", mediaType = "TV", mainStory = true, upcoming = true, startYear = 2027, startMonth = 4, startDay = 8),
        WatchOrderItem("6", "106", "", "Andon Yakkyoku Nisshi: The Last Lantern", "Sequel", mediaType = "Movie", mainStory = true, upcoming = true),
    )

    private fun open(hideOptional: Boolean) {
        PrefManager.setVal(PrefName.WatchOrderHideOptional, hideOptional)
        val activity = Robolectric.buildActivity(ScreenHostActivity::class.java).setup().get()
        val sheet = MediaContentBottomSheet.newWatchOrderInstance("", items)
        sheet.show(activity.supportFragmentManager, "order")
        idle()
        assertNotNull(sheet.view?.findViewById<RecyclerView>(R.id.repliesRecyclerView)?.adapter)
        sheet.view?.findViewById<RecyclerView>(R.id.repliesRecyclerView)?.let(::settle)
    }

    @Test fun rows() {
        open(hideOptional = false)
        shot("media/watch_order")
        PrefManager.setVal(PrefName.WatchOrderHideOptional, false)
    }

    @Test fun rows_optionalHidden() {
        open(hideOptional = true)
        shot("media/watch_order_hidden")
        PrefManager.setVal(PrefName.WatchOrderHideOptional, false)
    }

    @Test fun skeleton() {
        val activity = Robolectric.buildActivity(ScreenHostActivity::class.java).setup().get()
        val frame = FrameLayout(activity)
        activity.setContentView(frame)
        val view = SkeletonView(activity).apply { tag = "order" }
        frame.addView(view, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        shot(frame, "media/watch_order_skeleton")
    }
}
