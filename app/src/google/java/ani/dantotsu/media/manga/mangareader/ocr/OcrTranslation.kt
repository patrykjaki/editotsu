package ani.dantotsu.media.manga.mangareader.ocr

import androidx.fragment.app.FragmentActivity
import ani.dantotsu.R
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.util.customAlertDialog
import java.io.File

/** Reader OCR + translation (ML Kit), google build only. */
object OcrTranslation {
    /**
     * Translation sends recognised page text to an online service. Before the
     * first use the user confirms that once; cancel leaves nothing sent.
     */
    fun confirmFirstUse(activity: FragmentActivity, onConfirmed: () -> Unit) {
        if (PrefManager.getVal<Boolean>(PrefName.OcrTranslateNoticeAccepted)) {
            onConfirmed()
            return
        }
        activity.customAlertDialog().apply {
            setTitle(R.string.ocr_notice_title)
            setMessage(R.string.ocr_notice_message)
            setPosButton(R.string.ocr_notice_confirm) {
                PrefManager.setVal(PrefName.OcrTranslateNoticeAccepted, true)
                onConfirmed()
            }
            setNegButton(R.string.cancel)
            show()
        }
    }

    fun open(activity: FragmentActivity, pageImage: File) {
        OcrTranslationBottomSheet.newInstance(pageImage)
            .show(activity.supportFragmentManager, "ocr_translate")
    }
}
