package ani.dantotsu.media.anime.player

/**
 * Which mpv video output to use.
 *
 * `vo=gpu` stays the default: it's what playback has always used. User GLSL shaders
 * (the CuNNy upscaler) are compute shaders, and on Android `vo=gpu` treats the GLES 3.2
 * context as having no compute support ("compute shaders=0"), so every pass fails with
 * INVALID_OPERATION and nothing is upscaled (phone evidence 2026-11-01:
 * state/_audit_2026-11-01/cunny-phone/RESULT.md). `vo=gpu-next` (libplacebo, built into the
 * shipped libmpv) runs them, so it's used only while a shader chain is active.
 */
object VideoOutputPolicy {
    const val DEFAULT_VO = "gpu"
    const val SHADER_VO = "gpu-next"

    fun voFor(glslShaderChain: String): String =
        if (glslShaderChain.isBlank()) DEFAULT_VO else SHADER_VO
}
