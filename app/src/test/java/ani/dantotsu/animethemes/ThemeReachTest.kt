package ani.dantotsu.animethemes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeReachTest {
    @Test
    fun classifiesCdnAnswers() {
        assertEquals(ThemeReachResult.Ok, ThemeReach.classify(206, null))
        assertEquals(ThemeReachResult.Ok, ThemeReach.classify(200, null))
        assertEquals(ThemeReachResult.RateLimited(ThemeReach.DEFAULT_RETRY_AFTER_S), ThemeReach.classify(429, null))
        assertEquals(ThemeReachResult.RateLimited(20), ThemeReach.classify(429, "20"))
        assertEquals(ThemeReachResult.RateLimited(5), ThemeReach.classify(503, "5"))
        assertEquals(ThemeReachResult.HttpError(503), ThemeReach.classify(503, null))
        assertEquals(ThemeReachResult.HttpError(404), ThemeReach.classify(404, null))
    }

    @Test
    fun retryAfterIsClamped() {
        assertEquals(ThemeReach.MAX_RETRY_AFTER_S, ThemeReach.retryAfterSeconds("3600"))
        assertEquals(1, ThemeReach.retryAfterSeconds("0"))
        assertNull(ThemeReach.retryAfterSeconds("Wed, 21 Oct 2026 07:28:00 GMT"))
        assertNull(ThemeReach.retryAfterSeconds(null))
    }

    @Test
    fun onlyNetworkUrlsAreChecked() {
        assertTrue(ThemeReach.needsCheck("https://v.animethemes.moe/a.webm"))
        assertFalse(ThemeReach.needsCheck("content://downloads/all_downloads/3"))
        assertFalse(ThemeReach.needsCheck("/storage/emulated/0/Download/a.webm"))
    }
}
