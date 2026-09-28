package ani.dantotsu.forum

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * MAX MERGE M2 correction (HOLD2 Blocker 1): ONE shared catalog with
 * live-verified AniList IDs. Browse chips, creator dropdown and the
 * create fallback all consume it — General must never become Anime.
 */
class ForumCategoriesTest {

    @Test
    fun `visible chip labels map to exact AniList IDs`() {
        assertEquals(7, ForumCategories.idForLabel("General"))
        assertEquals(1, ForumCategories.idForLabel("Anime"))
        assertEquals(2, ForumCategories.idForLabel("Manga"))
        assertEquals(5, ForumCategories.idForLabel("Release Discussion"))
        assertEquals(8, ForumCategories.idForLabel("News"))
    }

    @Test
    fun `creator labels map to the same IDs as browsing`() {
        assertEquals(3, ForumCategories.idForLabel("Light Novels"))
        assertEquals(4, ForumCategories.idForLabel("Visual Novels"))
        assertEquals(9, ForumCategories.idForLabel("Music"))
        assertEquals(10, ForumCategories.idForLabel("Gaming"))
        // Browse and creator resolve through the same table.
        assertEquals(
            ForumCategories.idForLabel("Anime"),
            ForumCategories.ENTRIES.first { it.label == "Anime" }.id
        )
    }

    @Test
    fun `all means no browse filter`() {
        assertNull(ForumCategories.idForLabel("All"))
    }

    @Test
    fun `create fallback is explicitly General`() {
        assertEquals(7, ForumCategories.DEFAULT_CREATE_ID)
        assertEquals("General", ForumCategories.labelForId(ForumCategories.DEFAULT_CREATE_ID))
    }

    @Test
    fun `general never silently becomes anime`() {
        assertEquals("General", ForumCategories.labelForId(7))
        assertEquals("Anime", ForumCategories.labelForId(1))
    }

    @Test
    fun `release discussion and news are distinct and correct`() {
        assertEquals(5, ForumCategories.idForLabel("Release Discussion"))
        assertEquals("Release Discussion", ForumCategories.labelForId(5))
        assertEquals(8, ForumCategories.idForLabel("News"))
        assertEquals("News", ForumCategories.labelForId(8))
    }
}
