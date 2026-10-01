package ani.dantotsu.media.anime.player

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import ani.dantotsu.R

class DantotsuPlayerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr), SurfaceHolder.Callback, PlaybackListener {

    val surfaceView: SurfaceView = SurfaceView(context)
    private var engine: PlaybackEngine? = null
    // MAX MERGE M4B DRM: cue overlay used only by the Media3 DRM backend
    // (MPV draws its own OSD). Created on bind, removed on unbind.
    private var drmSubtitleView: androidx.media3.ui.SubtitleView? = null

    var isControllerFullyVisible: Boolean = true
        private set
    private var controllerVisibilityListener: ((visibility: Int) -> Unit)? = null
    var controllerShowTimeoutMs: Int = 5000

    private val hideRunnable = Runnable { hideController() }

    init {
        // 1. Inflate base player view container
        LayoutInflater.from(context).inflate(R.layout.exo_player_view, this, true)

        // 2. Inflate control view into placeholder position
        val controllerPlaceholder = findViewById<View>(androidx.media3.ui.R.id.exo_controller_placeholder)
        if (controllerPlaceholder != null) {
            val controllerParent = controllerPlaceholder.parent as? ViewGroup
            if (controllerParent != null) {
                val controllerIndex = controllerParent.indexOfChild(controllerPlaceholder)
                controllerParent.removeView(controllerPlaceholder)
                val controllerView = LayoutInflater.from(context).inflate(R.layout.exo_player_control_view, controllerParent, false)
                controllerParent.addView(controllerView, controllerIndex)
            } else {
                LayoutInflater.from(context).inflate(R.layout.exo_player_control_view, this, true)
            }
        } else {
            LayoutInflater.from(context).inflate(R.layout.exo_player_control_view, this, true)
        }

        // 3. Attach SurfaceView inside content frame
        surfaceView.layoutParams = LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        val contentFrame = findViewById<ViewGroup>(androidx.media3.ui.R.id.exo_content_frame)
        if (contentFrame != null) {
            contentFrame.addView(surfaceView, 0)
        } else {
            addView(surfaceView, 0)
        }

        // 4. Hide default shutter overlay so video surface is unobscured
        findViewById<View>(androidx.media3.ui.R.id.exo_shutter)?.visibility = View.GONE

        surfaceView.holder.addCallback(this)
    }

    fun bindEngine(engine: PlaybackEngine) {
        this.engine?.removeListener(this)
        this.engine = engine
        engine.addListener(this)

        if (surfaceView.holder.surface.isValid) {
            engine.setVideoSurface(surfaceView.holder.surface)
            if (surfaceView.width > 0 && surfaceView.height > 0) {
                engine.setVideoSurfaceSize(surfaceView.width, surfaceView.height)
            }
        }

        // DRM backend renders cues through a SubtitleView overlay; MPV draws
        // its own OSD so the overlay only exists for the DRM engine.
        if (engine is Media3DrmPlaybackEngine) {
            val overlay = androidx.media3.ui.SubtitleView(context).apply {
                layoutParams = LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }
            addView(overlay)
            drmSubtitleView = overlay
            engine.setCuesListener { cues -> post { overlay.setCues(cues) } }
            engine.setStyleApplier { style -> post { applyDrmSubtitleStyle(overlay, style) } }
        }
    }

    /**
     * HOLD3 parity: the shared [SubtitleStyle] reaches the DRM cue overlay
     * (colors + fixed size, embedded styles stay on for the rest) instead of
     * being silently stored.
     */
    private fun applyDrmSubtitleStyle(
        overlay: androidx.media3.ui.SubtitleView,
        style: SubtitleStyle,
    ) {
        overlay.setStyle(
            androidx.media3.ui.CaptionStyleCompat(
                style.textColor,
                style.backgroundColor,
                android.graphics.Color.TRANSPARENT,
                androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_OUTLINE,
                style.borderColor,
                null
            )
        )
        overlay.setFixedTextSize(
            android.util.TypedValue.COMPLEX_UNIT_SP,
            style.fontSizeSp.coerceIn(8f, 48f)
        )
    }

    fun unbindEngine() {
        engine?.removeListener(this)
        engine?.setVideoSurface(null)
        (engine as? Media3DrmPlaybackEngine)?.setCuesListener(null)
        (engine as? Media3DrmPlaybackEngine)?.setStyleApplier(null)
        drmSubtitleView?.let { removeView(it) }
        drmSubtitleView = null
        engine = null
    }

    fun showController() {
        isControllerFullyVisible = true
        val controllerCont = findViewById<View>(R.id.exo_controller_cont)
        val controller = findViewById<View>(R.id.exo_controller)
        val topCont = findViewById<View>(R.id.exo_top_cont)
        val bottomCont = findViewById<View>(R.id.exo_bottom_cont)
        val timelineCont = findViewById<View>(R.id.exo_timeline_cont)

        controllerCont?.visibility = View.VISIBLE
        controller?.visibility = View.VISIBLE
        controller?.alpha = 1f
        topCont?.translationY = 0f
        bottomCont?.translationY = 0f
        timelineCont?.translationY = 0f

        controllerVisibilityListener?.invoke(View.VISIBLE)
        removeCallbacks(hideRunnable)
        if (controllerShowTimeoutMs > 0) {
            postDelayed(hideRunnable, controllerShowTimeoutMs.toLong())
        }
    }

    fun hideController() {
        isControllerFullyVisible = false
        val controllerCont = findViewById<View>(R.id.exo_controller_cont)
        val controller = findViewById<View>(R.id.exo_controller)
        controllerCont?.visibility = View.GONE
        controller?.visibility = View.GONE
        controllerVisibilityListener?.invoke(View.GONE)
        removeCallbacks(hideRunnable)
    }

    fun setControllerVisibilityListener(listener: (visibility: Int) -> Unit) {
        this.controllerVisibilityListener = listener
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        engine?.setVideoSurface(holder.surface)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        engine?.setVideoSurfaceSize(width, height)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        engine?.setVideoSurface(null)
    }
}
