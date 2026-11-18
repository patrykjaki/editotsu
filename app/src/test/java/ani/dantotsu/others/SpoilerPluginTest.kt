package ani.dantotsu.others

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpoilerPluginTest {
    private fun covered(text: String, re: java.util.regex.Pattern) =
        SpoilerPlugin.spoilerRanges(text, re).map { text.substring(it.start, it.end) }

    @Test
    fun spoilerOverSeveralLinesIsCovered() {
        val text = "before ~!line one\n\nline two!~ after"
        assertEquals(listOf("~!line one\n\nline two!~"), covered(text, SpoilerPlugin.ARE))
    }

    @Test
    fun labelStaysReadable() {
        val text = "~!{Ending}she dies!~"
        val r = SpoilerPlugin.spoilerRanges(text, SpoilerPlugin.ARE).single()
        assertEquals("she dies!~", text.substring(r.start, r.end))
        val hidden = r.hidden.joinToString("") { text.substring(it.first, it.last + 1) }
        assertEquals("~!{}!~", hidden)
    }

    @Test
    fun twoSpoilersStaySeparate() {
        assertEquals(listOf("~!a!~", "~!b!~"), covered("~!a!~ and ~!b!~", SpoilerPlugin.ARE))
        assertEquals(listOf("||a\nb||"), covered("x ||a\nb|| y", SpoilerPlugin.RE))
    }

    @Test
    fun htmlSpoilerClasses() {
        assertTrue(SpoilerTagHandler.isSpoiler("markdown-spoiler", null))
        assertTrue(SpoilerTagHandler.isSpoiler("x markdown-spoiler", null))
        assertTrue(SpoilerTagHandler.isSpoiler(null, "spoiler"))
        assertFalse(SpoilerTagHandler.isSpoiler("youtube", null))
        assertFalse(SpoilerTagHandler.isSpoiler(null, null))
    }
}
