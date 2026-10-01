package ani.dantotsu.media.manga.mangareader.ocr

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

/**
 * MAX MERGE M4 correction (HOLD2 OCR): OCR/translation policy pinned on the
 * JVM. Recognizers, sheet UI, and network engines stay device-validated.
 */
class OcrPolicyTest {

    @Test
    fun `ascii text skips translation`() {
        assertTrue(OcrPolicy.isLikelyLatin("Hello World 123!?"))
        assertFalse(OcrPolicy.isLikelyLatin("こんにちは"))
        assertFalse(OcrPolicy.isLikelyLatin("Hello 世界"))
    }

    @Test
    fun `language guess follows script`() {
        assertEquals("ko", OcrPolicy.detectLanguage("안녕하세요"))
        assertEquals("ja", OcrPolicy.detectLanguage("こんにちは"))
        assertEquals("ja", OcrPolicy.detectLanguage("カタカナ"))
        assertEquals("zh", OcrPolicy.detectLanguage("你好世界"))
        assertEquals("ja", OcrPolicy.detectLanguage("Hello"))
        assertEquals("ja", OcrPolicy.detectLanguage(""))
    }

    @Test
    fun `no image or blank ocr yields safe unavailable`() {
        val d = OcrPolicy.resolveDisplay("", null, "NO_TEXT", { "FAIL:$it" })
        assertEquals("NO_TEXT", d.detectedLine)
        assertEquals("NO_TEXT", d.translatedLine)
        assertEquals("", d.copyText)
        val blank = OcrPolicy.resolveDisplay("   ", null, "NO_TEXT", { "FAIL:$it" })
        assertEquals("", blank.copyText)
    }

    @Test
    fun `latin ocr shows text directly`() {
        val d = OcrPolicy.resolveDisplay("STOP", "ignored", "NO_TEXT", { "FAIL:$it" })
        assertEquals("STOP", d.detectedLine)
        assertEquals("STOP", d.translatedLine)
        assertEquals("STOP", d.copyText)
    }

    @Test
    fun `ocr text survives translation failure`() {
        val d = OcrPolicy.resolveDisplay("こんにちは", null, "NO_TEXT", { "FAIL:$it" })
        assertEquals("こんにちは", d.detectedLine)
        assertEquals("FAIL:こんにちは", d.translatedLine)
        assertEquals("こんにちは", d.copyText)
    }

    @Test
    fun `successful translation shows both`() {
        val d = OcrPolicy.resolveDisplay("こんにちは", "Hello", "NO_TEXT", { "FAIL:$it" })
        assertEquals("こんにちは", d.detectedLine)
        assertEquals("Hello", d.translatedLine)
        assertEquals("Hello", d.copyText)
    }

    @Test
    fun `engine wrapper falls through on ordinary failure`() = runBlocking {
        assertEquals("ok", OcrPolicy.runEngine { "ok" })
        assertNull(OcrPolicy.runEngine<String> { throw IOException("net down") })
    }

    @Test
    fun `engine wrapper propagates cancellation`() = runBlocking {
        try {
            OcrPolicy.runEngine<String> { throw CancellationException("gone") }
            fail("cancellation must escape")
        } catch (e: CancellationException) {
            assertEquals("gone", e.message)
        }
    }
}
