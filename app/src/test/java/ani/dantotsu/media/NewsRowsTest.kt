package ani.dantotsu.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Calendar
import java.util.Date

class NewsRowsTest {

    @Test
    fun youtubeIdsFromEveryLinkShape() {
        assertEquals("W2xLuOCvtQo", youtubeId("https://www.youtube.com/watch?v=W2xLuOCvtQo"))
        assertEquals("W2xLuOCvtQo", youtubeId("https://youtu.be/W2xLuOCvtQo?t=3"))
        assertEquals("W2xLuOCvtQo", youtubeId("https://m.youtube.com/shorts/W2xLuOCvtQo"))
        assertEquals("W2xLuOCvtQo", youtubeId("https://www.youtube.com/watch?feature=x&v=W2xLuOCvtQo"))
        assertNull(youtubeId("https://x.com/someone/status/1"))
        assertNull(youtubeId("not a url"))
    }

    @Test
    fun kindsAndSites() {
        assertEquals(NewsKind.VIDEO, newsKind("https://www.youtube.com/watch?v=abcdefg"))
        assertEquals(NewsKind.POST, newsKind("https://twitter.com/a/status/1"))
        assertEquals(NewsKind.ARTICLE, newsKind("https://www.fujitv.co.jp/x"))
        assertEquals("X", newsSite("https://x.com/a"))
        assertEquals("fujitv.co.jp", newsSite("https://www.fujitv.co.jp/x"))
    }

    @Test
    fun groupsByWeekThenMonthThenUndated() {
        val day = 24L * 60 * 60 * 1000
        val cal = Calendar.getInstance().apply { clear(); set(2026, Calendar.NOVEMBER, 18, 12, 0) }
        val now = cal.timeInMillis
        fun item(t: Long?) = MediaDetailsViewModel.NewsItem("t", "u", t?.let { Date(it) })
        val rows = newsRows(
            listOf(item(now - 40 * day), item(null), item(now - day), item(now - 20 * day), item(now - 15 * day)),
            now,
        )
        // -1d this week; -15d is 3 November; -20d and -40d are October; one undated
        assertEquals(
            listOf(
                NewsRow.Header(0, null),
                NewsRow.Header(2026, Calendar.NOVEMBER),
                NewsRow.Header(2026, Calendar.OCTOBER),
                NewsRow.Header(-1, -1),
            ),
            rows.filterIsInstance<NewsRow.Header>(),
        )
        assertEquals(9, rows.size)
    }

    @Test
    fun showNameDroppedOnlyWhenItIsTheShow() {
        val names = listOf("[Oshi no Ko] 3rd Season", "OSHI NO KO Season 3")
        assertEquals("Season 4 unveils teaser visual",
            newsTitleWithoutShow("\"\u3010OSHI NO KO\u3011\" Season 4 unveils teaser visual", names))
        assertEquals("\"Frieren\" gets a movie", newsTitleWithoutShow("\"Frieren\" gets a movie", names))
        assertEquals("No quote here", newsTitleWithoutShow("No quote here", names))
    }

    @Test
    fun previewImageFromMetaTags() {
        val html = """<head><meta name="twitter:image" content="/t.jpg"><meta content="https://cdn.example/a.jpg?x=1&amp;y=2" property="og:image"></head>"""
        assertEquals("https://cdn.example/a.jpg?x=1&y=2", newsPreviewImage(html, "https://news.example/p/1"))
        assertEquals("https://news.example/t.jpg", newsPreviewImage("<meta name='twitter:image' content='/t.jpg'>", "https://news.example/p/1"))
        assertNull(newsPreviewImage("<head><title>x</title></head>", "https://news.example/"))
    }
}
