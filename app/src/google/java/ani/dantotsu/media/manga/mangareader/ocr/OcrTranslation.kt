package ani.dantotsu.media.manga.mangareader.ocr

import androidx.fragment.app.FragmentActivity
import java.io.File

/** Reader OCR + translation (ML Kit), google build only. */
object OcrTranslation {
    fun open(activity: FragmentActivity, pageImage: File) {
        OcrTranslationBottomSheet.newInstance(pageImage)
            .show(activity.supportFragmentManager, "ocr_translate")
    }
}
