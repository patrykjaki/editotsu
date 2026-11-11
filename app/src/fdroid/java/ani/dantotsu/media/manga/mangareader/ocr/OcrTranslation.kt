package ani.dantotsu.media.manga.mangareader.ocr

import androidx.fragment.app.FragmentActivity
import java.io.File

/** The fdroid build has no OCR (ML Kit); the reader hides the button. */
object OcrTranslation {
    @Suppress("UNUSED_PARAMETER")
    fun open(activity: FragmentActivity, pageImage: File) {
        pageImage.delete()
    }
}
