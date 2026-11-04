package ani.dantotsu.animethemes

import org.junit.Assert.assertEquals
import org.junit.Test

class ThemeTagsTest {
    @Test
    fun slugFallbackGetsTheSameFormatAsTypeAndSequence() {
        assertEquals("ED 1", ThemeTags.display("ED", null, "ED1"))
        assertEquals("OP 2", ThemeTags.display("OP", 2, "OP2"))
        assertEquals("ED 12", ThemeTags.normalize("ED12"))
        assertEquals("OP 1 TV", ThemeTags.normalize("OP1-TV"))
    }

    @Test
    fun tagsAlreadyInFormatOrUnknownStayAsTheyAre() {
        assertEquals("ED 1", ThemeTags.normalize("ED 1"))
        assertEquals("OP", ThemeTags.normalize("OP"))
        assertEquals("Insert", ThemeTags.normalize("Insert"))
    }
}
