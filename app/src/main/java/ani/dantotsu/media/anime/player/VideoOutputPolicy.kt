package ani.dantotsu.media.anime.player

/**
 * Which mpv video output to use.
 *
 * `vo=gpu` on OpenGL ES stays the default: it's what playback has always used.
 *
 * User GLSL shaders (the CuNNy upscaler) are compute shaders. Neither mpv renderer can run
 * compute on Android GLES: mpv's `vo=gpu` loads no glDispatchCompute there ("compute
 * shaders=0"), and libplacebo (`vo=gpu-next`) only enables compute on desktop GL 4.3+ with
 * GLSL >= 420 (libplacebo v7.360.0 src/opengl/gpu.c:161); confirmed on a phone 2026-11-01.
 * Only libplacebo's Vulkan backend runs them,
 * which needs a libmpv built with Vulkan (opt-in, `-Peditotsu.libmpvAar=...`).
 *
 * With a Vulkan-capable libmpv, mpv's `gpu-api=auto` could pick Vulkan for normal playback
 * too, so the default path pins OpenGL explicitly and Vulkan is used only while a shader
 * chain is active.
 */
object VideoOutputPolicy {
    const val DEFAULT_VO = "gpu"
    const val SHADER_VO = "gpu-next"

    /** The mpv properties for one output choice; null gpu fields = leave mpv's own default. */
    data class Output(val vo: String, val gpuApi: String?, val gpuContext: String?)

    val DEFAULT = Output(DEFAULT_VO, null, null)

    fun outputFor(glslShaderChain: String, vulkanLibmpv: Boolean): Output {
        val shaders = glslShaderChain.isNotBlank()
        return when {
            !vulkanLibmpv -> if (shaders) Output(SHADER_VO, null, null) else DEFAULT
            shaders -> Output(SHADER_VO, "vulkan", "androidvk")
            else -> Output(DEFAULT_VO, "opengl", "android")
        }
    }

    fun voFor(glslShaderChain: String): String = outputFor(glslShaderChain, false).vo
}
