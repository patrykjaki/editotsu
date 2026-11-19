package ani.dantotsu.media

import ani.dantotsu.media.GenreTagGroups.Entry
import ani.dantotsu.media.GenreTagGroups.Kind
import ani.dantotsu.media.GenreTagGroups.Mark
import ani.dantotsu.media.GenreTagGroups.SelectedEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GenreTagGroupsTest {

    @Test
    fun `a tap moves blank to include to exclude to blank`() {
        assertEquals(Mark.INCLUDE, Mark.NONE.next())
        assertEquals(Mark.EXCLUDE, Mark.INCLUDE.next())
        assertEquals(Mark.NONE, Mark.EXCLUDE.next())
    }

    @Test
    fun `a name sits in at most one list`() {
        val inc = mutableListOf("Action")
        val exc = mutableListOf<String>()
        GenreTagGroups.set("Action", Mark.EXCLUDE, inc, exc)
        assertEquals(emptyList<String>(), inc)
        assertEquals(listOf("Action"), exc)
        assertEquals(Mark.EXCLUDE, GenreTagGroups.markOf("Action", inc, exc))
        GenreTagGroups.set("Action", Mark.NONE, inc, exc)
        assertEquals(emptyList<String>(), inc + exc)
        assertEquals(Mark.NONE, GenreTagGroups.markOf("Action", inc, exc))
    }

    @Test
    fun `genres first, then categories in order, unknown tags last`() {
        val groups = GenreTagGroups.groups(
            genres = listOf("Romance", "Action"),
            tags = listOf("Time Travel", "Female Protagonist", "Isekai", "Mystery Tag"),
            categories = mapOf(
                "Time Travel" to "Theme-Fantasy",
                "Isekai" to "Theme-Fantasy",
                "Female Protagonist" to "Cast-Main Cast",
            ),
            genresTitle = "Genres",
            otherTitle = "Tags",
        )
        assertEquals(listOf("Genres", "Cast / Main Cast", "Theme / Fantasy", "Tags"), groups.map { it.title })
        assertEquals(listOf("Action", "Romance"), groups[0].entries.map { it.name })
        assertEquals(Kind.GENRE, groups[0].entries[0].kind)
        assertEquals(listOf("Isekai", "Time Travel"), groups[2].entries.map { it.name })
        assertEquals(Kind.TAG, groups[2].entries[0].kind)
        assertEquals(listOf("Mystery Tag"), groups[3].entries.map { it.name })
    }

    @Test
    fun `no categories yet puts every tag under one group`() {
        val groups = GenreTagGroups.groups(listOf("Action"), listOf("b", "A"), emptyMap(), "Genres", "Tags")
        assertEquals(listOf("Genres", "Tags"), groups.map { it.title })
        assertEquals(listOf("A", "b"), groups[1].entries.map { it.name })
    }

    @Test
    fun `search keeps matching names and whole matching groups`() {
        val groups = GenreTagGroups.groups(
            listOf("Action", "Comedy"), listOf("Time Travel", "Travel", "Swordplay"),
            mapOf("Time Travel" to "Theme-Fantasy", "Travel" to "Setting-Scene", "Swordplay" to "Theme-Action"),
            "Genres", "Tags",
        )
        val travel = GenreTagGroups.search(groups, " travel ")
        assertEquals(listOf("Setting / Scene", "Theme / Fantasy"), travel.map { it.title })
        assertEquals(listOf("Travel"), travel[0].entries.map { it.name })

        val action = GenreTagGroups.search(groups, "action")
        // the genre matches by name, the "Theme / Action" group by its title
        assertEquals(listOf("Genres", "Theme / Action"), action.map { it.title })
        assertEquals(listOf("Action"), action[0].entries.map { it.name })
        assertEquals(listOf("Swordplay"), action[1].entries.map { it.name })

        assertEquals(groups, GenreTagGroups.search(groups, "  "))
        assertEquals(emptyList<GenreTagGroups.Group>(), GenreTagGroups.search(groups, "zzz"))
    }

    @Test
    fun `chip summary`() {
        assertNull(GenreTagGroups.summary(emptyList(), emptyList()))
        assertEquals("Action", GenreTagGroups.summary(listOf("Action"), emptyList()))
        assertEquals("Action +2", GenreTagGroups.summary(listOf("Action", "Isekai"), listOf("Romance")))
        assertEquals("\u2212Romance", GenreTagGroups.summary(emptyList(), listOf("Romance")))
        assertEquals("\u2212Romance +1", GenreTagGroups.summary(emptyList(), listOf("Romance", "Gore")))
    }

    @Test
    fun `categories survive the preference round trip`() {
        val map = mapOf("Isekai" to "Theme-Fantasy", "Female Protagonist" to "Cast-Main Cast")
        assertEquals(map, GenreTagGroups.decodeCategories(GenreTagGroups.encodeCategories(map)))
        assertEquals(emptyMap<String, String>(), GenreTagGroups.decodeCategories(setOf("broken", "\tx", "y\t")))
    }

    @Test
    fun `genres stay open while tag categories start collapsed and auto-expand on search`() {
        val groups = GenreTagGroups.groups(
            genres = listOf("Action"),
            tags = listOf("Isekai"),
            categories = mapOf("Isekai" to "Theme-Fantasy"),
            genresTitle = "Genres",
            otherTitle = "Tags",
        )
        val genresGroup = groups[0]
        val fantasyGroup = groups[1]

        assertFalse(genresGroup.isCollapsible)
        assertTrue(fantasyGroup.isCollapsible)

        assertTrue(GenreTagGroups.isGroupExpanded(genresGroup, emptySet(), ""))
        assertFalse(GenreTagGroups.isGroupExpanded(fantasyGroup, emptySet(), ""))
        assertTrue(GenreTagGroups.isGroupExpanded(fantasyGroup, setOf("Theme / Fantasy"), ""))
        assertTrue(GenreTagGroups.isGroupExpanded(fantasyGroup, emptySet(), "ise"))
    }

    @Test
    fun `selected lists pinned entries in order and counts selections per group`() {
        val groups = GenreTagGroups.groups(
            genres = listOf("Action", "Comedy"),
            tags = listOf("Isekai", "Swordplay"),
            categories = mapOf("Isekai" to "Theme-Fantasy", "Swordplay" to "Theme-Action"),
            genresTitle = "Genres",
            otherTitle = "Tags",
        )
        val incGenres = listOf("Action")
        val excGenres = listOf("Comedy")
        val incTags = listOf("Isekai")
        val excTags = listOf("Swordplay")

        assertEquals(
            listOf(
                SelectedEntry(Entry("Action", Kind.GENRE), Mark.INCLUDE),
                SelectedEntry(Entry("Comedy", Kind.GENRE), Mark.EXCLUDE),
                SelectedEntry(Entry("Isekai", Kind.TAG), Mark.INCLUDE),
                SelectedEntry(Entry("Swordplay", Kind.TAG), Mark.EXCLUDE),
            ),
            GenreTagGroups.selected(incGenres, excGenres, incTags, excTags),
        )
        assertEquals(2, GenreTagGroups.selectedCountInGroup(groups[0], incGenres, excGenres, incTags, excTags))
        assertEquals(1, GenreTagGroups.selectedCountInGroup(groups[1], incGenres, excGenres, incTags, excTags))
        assertEquals(1, GenreTagGroups.selectedCountInGroup(groups[2], incGenres, excGenres, incTags, excTags))
    }
}
