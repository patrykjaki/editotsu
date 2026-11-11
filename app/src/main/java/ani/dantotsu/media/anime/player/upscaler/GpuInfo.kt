package ani.dantotsu.media.anime.player.upscaler

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES20

/**
 * The GPU's GL_RENDERER string (e.g. "Adreno (TM) 825"), read once per process from a 1x1
 * offscreen GLES context. Null when EGL is not usable. Call off the main thread when possible;
 * it takes a few milliseconds the first time.
 */
object GpuInfo {
    @Volatile private var cached: String? = null
    @Volatile private var done = false

    val renderer: String?
        get() {
            if (!done) synchronized(this) {
                if (!done) {
                    cached = runCatching { query() }.getOrNull()
                    done = true
                }
            }
            return cached
        }

    private fun query(): String? {
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (display == EGL14.EGL_NO_DISPLAY) return null
        val version = IntArray(2)
        if (!EGL14.eglInitialize(display, version, 0, version, 1)) return null
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        val attribs = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_NONE,
        )
        if (!EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, count, 0) || count[0] == 0) return null
        val config = configs[0] ?: return null
        val context = EGL14.eglCreateContext(
            display, config, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0,
        )
        if (context == EGL14.EGL_NO_CONTEXT) return null
        val surface = EGL14.eglCreatePbufferSurface(
            display, config, intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0,
        )
        // Keep whatever context this thread had (none on the engine thread).
        val prevContext = EGL14.eglGetCurrentContext()
        val prevDraw = EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW)
        val prevRead = EGL14.eglGetCurrentSurface(EGL14.EGL_READ)
        val prevDisplay = EGL14.eglGetCurrentDisplay()
        return try {
            if (surface == EGL14.EGL_NO_SURFACE || !EGL14.eglMakeCurrent(display, surface, surface, context)) null
            else GLES20.glGetString(GLES20.GL_RENDERER)?.trim()?.takeIf { it.isNotEmpty() }
        } finally {
            if (prevContext != EGL14.EGL_NO_CONTEXT) {
                EGL14.eglMakeCurrent(prevDisplay, prevDraw, prevRead, prevContext)
            } else {
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            }
            if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
            EGL14.eglDestroyContext(display, context)
        }
    }
}
