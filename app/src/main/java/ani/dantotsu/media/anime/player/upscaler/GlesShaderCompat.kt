package ani.dantotsu.media.anime.player.upscaler

import java.io.File

/**
 * Makes mpv user shaders that declare optional GLSL extensions inside a pass body
 * compile under mpv's `vo=gpu` OpenGL ES renderer.
 *
 * Each CuNNy pass starts its body with
 *   `#extension GL_EXT_shader_explicit_arithmetic_types_float16 : enable`
 * followed by `#ifdef GL_EXT_shader_explicit_arithmetic_types_float16` (fp16 types) and an
 * `#else` plain-float fallback. `vo=gpu` pastes pass bodies after its own code, and GLSL only
 * allows `#extension` before any non-preprocessor token, so the compile fails ("Extension
 * directives must occur before any non-preprocessor tokens") and mpv plays without the shader
 * (seen on an Adreno 825, GLES 3.2, 2026-11-01).
 *
 * The patch comments out those `#extension` lines and turns the matching `#ifdef` into
 * `#if 0`. The `#ifdef` has to go too: a driver defines the extension macro whenever it
 * supports the extension, even without `enable`, which would select fp16 types that then
 * fail to compile. Result: the plain-float path, same math. Line count is unchanged so mpv's
 * error line numbers still match the upstream file.
 *
 * The installed bundle stays byte-identical to upstream (its checksums are verified); mpv
 * gets a derived copy (see [derivedCopy]).
 */
object GlesShaderCompat {
    /** Optional extensions whose `#ifdef` blocks have a plain fallback in the shipped shaders. */
    val OPTIONAL_EXTENSIONS = setOf("GL_EXT_shader_explicit_arithmetic_types_float16")

    private val extensionLine = Regex("""^\s*#\s*extension\s+(\w+)\s*:\s*\w+\s*$""")
    private val ifdefLine = Regex("""^(\s*)#\s*ifdef\s+(\w+)\s*$""")

    fun patch(source: String): String {
        val lines = source.split("\n")
        val out = lines.map { line ->
            val ext = extensionLine.matchEntire(line.trimEnd('\r'))
            val ifdef = ifdefLine.matchEntire(line.trimEnd('\r'))
            when {
                ext != null && ext.groupValues[1] in OPTIONAL_EXTENSIONS ->
                    "// editotsu-gles: removed mid-body ${ext.groupValues[1]} directive"
                ifdef != null && ifdef.groupValues[2] in OPTIONAL_EXTENSIONS ->
                    "${ifdef.groupValues[1]}#if 0 // editotsu-gles: was #ifdef ${ifdef.groupValues[2]}"
                else -> line
            }
        }
        return out.joinToString("\n")
    }

    fun needsPatch(source: String): Boolean = patch(source) != source

    /**
     * Writes the patched copy of [shader] into [outDir] (same file name) if it differs, and
     * returns the file mpv should load. Rewrites only when the content changed; atomic rename.
     * Returns [shader] itself when no patch applies, and null if the copy can't be written.
     */
    fun derivedCopy(shader: File, outDir: File): File? {
        val source = try { shader.readText() } catch (_: Exception) { return null }
        val patched = patch(source)
        if (patched == source) return shader
        return try {
            outDir.mkdirs()
            val target = File(outDir, shader.name)
            if (!target.isFile || target.readText() != patched) {
                val tmp = File(outDir, shader.name + ".tmp")
                tmp.writeText(patched)
                if (!tmp.renameTo(target)) { tmp.delete(); return null }
            }
            target
        } catch (_: Exception) {
            null
        }
    }
}
