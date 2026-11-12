package ani.dantotsu.media.manga.mangareader.ocr

import android.content.Context
import android.graphics.Bitmap
import ani.dantotsu.util.Logger
import java.io.File

object OcrPageImage {
    /** Writes [bitmap] to the cache for the OCR sheet. Call off the main thread. */
    fun write(context: Context, bitmap: Bitmap): File? = try {
        val dir = File(context.cacheDir, "ocr").apply { mkdirs() }
        val file = File(dir, "page.jpg")
        val source = if (bitmap.config == Bitmap.Config.HARDWARE) {
            bitmap.copy(Bitmap.Config.ARGB_8888, false)
        } else {
            bitmap
        }
        try {
            file.outputStream().use { source.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        } finally {
            if (source !== bitmap) source.recycle()
        }
        file
    } catch (e: Exception) {
        Logger.log("OCR: could not write page image: ${e.javaClass.simpleName}")
        null
    }
}
