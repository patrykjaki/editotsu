package ani.dantotsu.media.anime.player

import java.io.File

/**
 * Fonts that extensions download for their ASS subtitles (Aniyomi's `files/mpv/fonts`,
 * cache folders such as `reanime-fonts`) are copied into mpv's `sub-fonts-dir`, the
 * only folder mpv reads fonts from. mpv reads that folder again whenever a subtitle
 * track loads, so copying before `sub-add` is enough (upstream 2e310d77, 00008262).
 *
 * Copies are named `ext_<folder>_<file>` so they never replace the app's own fonts;
 * libass picks fonts by their internal family name, not the file name.
 */
object MpvExtensionFonts {
    private val FONT_EXTENSIONS = setOf("ttf", "otf", "ttc")
    private const val MAX_FONT_BYTES = 64L * 1024 * 1024

    fun sourceDirs(filesDir: File?, cacheDir: File?): List<File> = buildList {
        filesDir?.let { add(File(it, "mpv/fonts")) }
        cacheDir?.listFiles()
            ?.filter { it.isDirectory && it.name.contains("font", ignoreCase = true) }
            ?.sortedBy { it.name }
            ?.let { addAll(it) }
    }

    /** @return how many font files were copied (already present, same size: skipped). */
    fun stage(sources: List<File>, target: File): Int {
        if (!target.isDirectory) return 0
        val targetPath = target.canonicalFile
        var copied = 0
        val seen = mutableSetOf<String>()
        for (dir in sources) {
            if (!dir.isDirectory || dir.canonicalFile == targetPath) continue
            dir.walkTopDown().maxDepth(3)
                .filter { it.isFile && it.extension.lowercase() in FONT_EXTENSIONS }
                .filter { it.length() in 1..MAX_FONT_BYTES }
                .forEach { font ->
                    val name = "ext_${dir.name}_${font.name}"
                    if (!seen.add(name)) return@forEach
                    val dest = File(target, name)
                    if (dest.exists() && dest.length() == font.length()) return@forEach
                    val tmp = File(target, "$name.part")
                    runCatching {
                        font.inputStream().use { input -> tmp.outputStream().use { input.copyTo(it) } }
                        if (!tmp.renameTo(dest)) {
                            dest.delete()
                            check(tmp.renameTo(dest))
                        }
                        copied++
                    }.onFailure { tmp.delete() }
                }
        }
        return copied
    }
}
