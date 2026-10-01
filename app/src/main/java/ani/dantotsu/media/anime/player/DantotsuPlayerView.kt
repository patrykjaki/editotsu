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
        // HOLD4 H2: the overlay lives in the video content frame directly
        // above the SurfaceView (below controller chrome), never on top of
        // the root hierarchy. MPV draws its own OSD; DRM-only, removed on
        // unbind as before.
        if (engine is Media3DrmPlaybackEngine) {
            val overlay = androidx.media3.ui.SubtitleView(context).apply {
                layoutParams = LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }
            val frame = findViewById<ViewGroup>(androidx.media3.ui.R.id.exo_content_frame) ?: this
            val anchor = frame.indexOfChild(surfaceView)
            if (anchor >= 0) frame.addView(overlay, anchor + 1)
            else frame.addView(overlay)
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
        // HOLD4 H1: SOURCE respects embedded styling; CUSTOM disables it
        // and applies the Editotsu style deterministically.
        val plan = DrmTransportPlan.planCaptionStyle(style)
        overlay.setApplyEmbeddedStyles(plan.applyEmbeddedStyles)
        overlay.setApplyEmbeddedFontSizes(plan.applyEmbeddedFontSizes)
        if (plan.useCustomStyle) {
            // HOLD5 hardening: resolve precedence purely; Android Typeface
            // construction stays guarded with fallthrough.
            val typeface = when (val source =
                DrmTransportPlan.resolveCustomTypefaceSource(plan.fontFamily, plan.fontFile)) {
                is DrmTransportPlan.CustomTypefaceSource.FontFile ->
                    runCatching { android.graphics.Typeface.createFromFile(source.file) }.getOrNull()
                is DrmTransportPlan.CustomTypefaceSource.Family ->
                    runCatching { android.graphics.Typeface.create(source.name, android.graphics.Typeface.NORMAL) }.getOrNull()
                DrmTransportPlan.CustomTypefaceSource.None -> null
            }
            overlay.setStyle(
                androidx.media3.ui.CaptionStyleCompat(
                    plan.fgColor,
                    plan.bgColor,
                    android.graphics.Color.TRANSPARENT,
                    androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_OUTLINE,
                    plan.edgeColor,
                    typeface
                )
            )
            overlay.setFixedTextSize(
                android.util.TypedValue.COMPLEX_UNIT_SP,
                plan.fontSizeSp
            )
        } else {
            overlay.setUserDefaultStyle()
            overlay.setUserDefaultTextSize()
        }
        val marginPx = (plan.bottomMarginDp * resources.displayMetrics.density).toInt()
        overlay.setPadding(
            overlay.paddingLeft,
            overlay.paddingTop,
            overlay.paddingRight,
            marginPx
        )
    }

    fun unbindEngine() {
        engine?.removeListener(this)
        engine?.setVideoSurface(null)
        (engine as? Media3DrmPlaybackEngine)?.setCuesListener(null)
        (engine as? Media3DrmPlaybackEngine)?.setStyleApplier(null)
        drmSubtitleView?.let { (it.parent as? ViewGroup)?.removeView(it) }
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
