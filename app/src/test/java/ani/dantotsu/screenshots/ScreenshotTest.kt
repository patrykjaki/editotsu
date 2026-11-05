package ani.dantotsu.screenshots

import android.os.Looper
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Duration

/**
 * Base for JVM screenshot tests. Device: the owner's Nothing Phone 3 as the app sees it
 * (1080 x 2392 px at 440 dpi = 393 x 870 dp). Reference images: <editotsu.screenshotDir>/<name>.png (outside git, see README.md).
 * Font-scale variants use @Config(fontScale = LARGE_FONT) on the test method.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = ScreenshotTest.PHONE, application = ScreenshotTestApp::class)
abstract class ScreenshotTest {

    companion object {
        const val PHONE = "w393dp-h870dp-normal-long-notround-any-440dpi-keyshidden-nonav"
        const val LARGE_FONT = 1.3f

        @OptIn(ExperimentalRoborazziApi::class)
        val OPTIONS = RoborazziOptions(
            // Text anti-aliasing can differ by a few pixels between runs; layout changes are far bigger.
            compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0.002f),
        )
    }

    /**
     * Runs posted work and lets short animations finish. List diffs (ListAdapter) and row
     * building run on background threads, so this waits for them a few times over.
     */
    protected fun idle(ms: Long = 500) {
        repeat(4) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms / 4))
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(25)
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    /** The whole screen, dialogs and bottom sheets included. */
    /**
     * Reference images live outside git (editotsu.screenshotDir). Without them, a fresh checkout or
     * CI still runs every screen's UI code but skips the image comparison; recording always captures.
     */
    private val capture: Boolean
        get() = System.getProperty("roborazzi.test.record") == "true" ||
            System.getProperty("editotsu.screenshotDir")?.let { java.io.File(it).isDirectory } == true

    protected fun shot(name: String) {
        idle()
        if (!capture) return
        (ani.dantotsu.App.context as? android.app.Activity)?.window?.decorView?.let(::stabilize)
        idle(100)
        captureScreenRoboImage("$name.png", roborazziOptions = OPTIONS)
    }

    protected fun shot(view: View, name: String) {
        idle()
        if (!capture) return
        stabilize(view.rootView)
        idle(100)
        view.captureRoboImage("$name.png", roborazziOptions = OPTIONS)
    }

    /**
     * Text that changes from run to run and machine to machine: the build id (label, commit,
     * dirty flag) and Robolectric's temp data directory. Replaced with fixed text before a capture.
     */
    private fun stabilize(root: View) {
        val buildId = ani.dantotsu.BuildConfig.EDITOTSU_BUILD_ID
        val tempDir = Regex("""/\S*?robolectric-[^/\s]*""")
        fun walk(v: View) {
            if (v is android.widget.TextView && v !is android.widget.EditText) {
                val t = v.text?.toString() ?: ""
                var fixed = if (buildId.isNotEmpty()) t.replace(buildId, "build") else t
                fixed = fixed.replace(tempDir, "/data")
                if (fixed != t) v.text = fixed
            }
            if (v is android.view.ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(root)
    }

    /**
     * Screen by screen down a scrolling list: name_p1, name_p2, ... A tenth of the height
     * overlaps so a row cut at the bottom of one page shows whole on the next.
     */
    /** Force every row to measure again until two passes agree (text that wraps can settle a pass late). */
    protected fun settle(list: RecyclerView) {
        fun forceAll(v: View) {
            v.forceLayout()
            if (v is android.view.ViewGroup) for (i in 0 until v.childCount) forceAll(v.getChildAt(i))
        }
        fun sizes() = (0 until list.childCount).map { list.getChildAt(it).let { c -> c.top to c.height } }
        idle()
        var last = sizes()
        repeat(4) {
            forceAll(list)
            list.requestLayout()
            idle()
            val now = sizes()
            if (now == last) return
            last = now
        }
    }

    protected fun shotPages(list: RecyclerView, name: String, maxPages: Int = 12) {
        list.scrollToPosition(0)
        idle()
        var page = 1
        while (true) {
            shot("${name}_p$page")
            if (!list.canScrollVertically(1) || page >= maxPages) break
            list.scrollBy(0, list.height * 9 / 10)
            // Rows that came on screen bind and measure in the next layout pass; run it fully.
            settle(list)
            page++
        }
    }
}
