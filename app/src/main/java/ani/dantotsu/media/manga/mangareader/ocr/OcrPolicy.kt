package ani.dantotsu.media.manga.mangareader.ocr

import kotlinx.coroutines.CancellationException

/**
 * MAX MERGE M4 correction (HOLD2 OCR): pure OCR/translation policy helpers
 * (host-tested). The ML Kit recognizers, bottom sheet, and network engines
 * are Android/device-bound; every branching decision they implement lives
 * here so it is pinned by unit tests.
 *
 * Script ranges use explicit escapes: Hangul syllables U+AC00-U+D7AF and
 * Jamo U+1100-U+11FF, Hiragana U+3040-U+309F and Katakana U+30A0-U+30FF,
 * CJK Unified U+4E00-U+9FFF. Same boundaries as upstream.
 */
object OcrPolicy {

    /** Upstream rule: all-ASCII text is already English — skip translation. */
    fun isLikelyLatin(text: String): Boolean = text.all { it.code <= 127 }

    /** Upstream source-language guess for the MyMemory fallback engine. */
    /** Upstream source-language guess for the MyMemory fallback engine. */
    fun detectLanguage(text: String): String = when {
        text.any { it in '\uAC00'..'\uD7AF' || it in '\u1100'..'\u11FF' } -> "ko"
        text.any { it in '\u3040'..'\u309F' || it in '\u30A0'..'\u30FF' } -> "ja"
        text.any { it in '\u4E00'..'\u9FFF' } -> "zh"
        else -> "ja"
    }

    /** What the sheet shows and what the copy button copies. */
    data class Display(
        val detectedLine: String,
        val translatedLine: String,
        val copyText: String,
    )

    /**
     * @param detected raw OCR text (may be blank)
     * @param translated translation result, or null when every engine failed
     * @param noTextLabel UI "no text" string (Android-side)
     * @param translationFailedLabel UI failure line builder (Android-side)
     */
    fun resolveDisplay(
        detected: String,
        translated: String?,
        noTextLabel: String,
        translationFailedLabel: (String) -> String,
    ): Display {
        if (detected.isBlank()) {
            return Display(noTextLabel, noTextLabel, "")
        }
        if (isLikelyLatin(detected)) {
            return Display(detected, detected, detected)
        }
        return if (translated != null) {
            Display(detected, translated, translated)
        } else {
            // Translation failure must never lose the detected OCR text.
            Display(detected, translationFailedLabel(detected), detected)
        }
    }

    /**
     * Engine-call wrapper with structured-cancellation hygiene: ordinary
     * failures become null (fall through to the next engine), but
     * [CancellationException] always propagates.
     */
    suspend fun <T> runEngine(block: suspend () -> T?): T? {
        try {
            return block()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return null
        }
    }
}
