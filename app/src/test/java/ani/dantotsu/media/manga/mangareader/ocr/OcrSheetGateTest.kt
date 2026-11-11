package ani.dantotsu.media.manga.mangareader.ocr

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrSheetGateTest {

    @Test
    fun `opens while the reader is in front`() {
        assertTrue(canShowOcrSheet(isStarted = true, isStateSaved = false, isFinishing = false))
    }

    @Test
    fun `does not open after the reader saved its state`() {
        assertFalse(canShowOcrSheet(isStarted = true, isStateSaved = true, isFinishing = false))
        assertFalse(canShowOcrSheet(isStarted = false, isStateSaved = true, isFinishing = false))
    }

    @Test
    fun `does not open in the background or while finishing`() {
        assertFalse(canShowOcrSheet(isStarted = false, isStateSaved = false, isFinishing = false))
        assertFalse(canShowOcrSheet(isStarted = true, isStateSaved = false, isFinishing = true))
    }
}
