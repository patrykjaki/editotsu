package ani.dantotsu.media.novel.novelreader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MAX MERGE M4 correction (HOLD2 A1): legacy `_progress` CFI migration
 * contract. The activity executes the returned plan (persist on Migrate,
 * preserve legacy on Miss); PrefManager IO itself is Android-bound and
 * stays device-validated.
 */
class LegacyNovelResumeTest {

    private val hrefs = listOf("cover.xhtml", "ch1.xhtml", "ch2.xhtml")

    @Test
    fun `new locator wins over legacy`() {
        assertTrue(
            LegacyNovelResume.plan(
                savedLocatorPresent = true,
                legacyCfi = "epubcfi(/6/6!/4/2)",
                readingOrderHrefs = hrefs
            ) is LegacyNovelResume.Plan.UseSaved
        )
    }

    @Test
    fun `saved present with garbage legacy still wins`() {
        assertTrue(
            LegacyNovelResume.plan(
                savedLocatorPresent = true,
                legacyCfi = "garbage",
                readingOrderHrefs = hrefs
            ) is LegacyNovelResume.Plan.UseSaved
        )
    }

    @Test
    fun `valid legacy-only state migrates to chapter href`() {
        // /6/6 -> spine index 6/2-1 = 2 -> ch2.xhtml
        assertEquals(
            LegacyNovelResume.Plan.Migrate(spineIndex = 2, href = "ch2.xhtml"),
            LegacyNovelResume.plan(
                savedLocatorPresent = false,
                legacyCfi = "epubcfi(/6/6!/4/2/1:0)",
                readingOrderHrefs = hrefs
            )
        )
    }

    @Test
    fun `first spine item migrates`() {
        // /6/2 -> spine index 0
        assertEquals(
            LegacyNovelResume.Plan.Migrate(spineIndex = 0, href = "cover.xhtml"),
            LegacyNovelResume.plan(
                savedLocatorPresent = false,
                legacyCfi = "epubcfi(/6/2)",
                readingOrderHrefs = hrefs
            )
        )
    }

    @Test
    fun `invalid legacy state safely misses`() {
        val bad = listOf(
            null,
            "",
            "   ",
            "garbage",
            "epubcfi(/6/5!/4/2)", // odd step: not an element address
            "epubcfi(/6/0)", // below first itemref
            "epubcfi(/6/1)",
            "epubcfi(/6/8!/4/2)", // spine index 3, out of range
            "epubcfi(/6/999999999999999999999)", // overflow guard
            "epubcfi(/4/2)", // not a spine path
            "/6/4", // missing wrapper
        )
        for (cfi in bad) {
            assertTrue(
                "cfi=$cfi",
                LegacyNovelResume.plan(
                    savedLocatorPresent = false,
                    legacyCfi = cfi,
                    readingOrderHrefs = hrefs
                ) is LegacyNovelResume.Plan.Miss
            )
        }
    }

    @Test
    fun `empty reading order misses instead of fabricating`() {
        assertTrue(
            LegacyNovelResume.plan(
                savedLocatorPresent = false,
                legacyCfi = "epubcfi(/6/4!/2)",
                readingOrderHrefs = emptyList()
            ) is LegacyNovelResume.Plan.Miss
        )
    }
}
