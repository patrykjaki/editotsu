package ani.dantotsu.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchOrderWikiTest {
    // shaped like the real file: metadata, data[].watch_orders[].steps[] with an AniList media per step
    private val text = """
        {"metadata":{"version":"2.9"},"data":[
          {"title":"Andon","alternative_titles":[],"watch_orders":[
            {"name":"Airing Order","description":"x","steps":[
              {"step_title":"Andon [TV]","is_optional":false,"media":{"id":10,"idMal":110,"title":{"romaji":"Andon","userPreferred":"Andon"},"format":"TV","episodes":12,"seasonYear":2021,"coverImage":{"large":"c10"},"genres":["Drama"]}},
              {"step_title":"Andon: Lanterns [OVA]","is_optional":true,"media":{"id":11,"title":{"romaji":"Andon: Lanterns"},"format":"OVA","episodes":null,"seasonYear":2022}},
              {"step_title":"Andon 2","is_optional":false,"media":{"id":12,"title":{"userPreferred":"Andon 2"},"format":"TV_SHORT","seasonYear":2023}}
            ]},
            {"name":"Chronological Order","steps":[]},
            {"name":"Novel Order","steps":[
              {"step_title":"Andon 2","is_optional":false,"media":{"id":12,"format":"TV"}},
              {"step_title":"Andon","is_optional":false,"media":{"id":10,"format":"TV"}}
            ]}
          ]},
          {"title":"Crossover","watch_orders":[{"name":"Main Story","steps":[
            {"step_title":"Andon 2","is_optional":true,"media":{"id":12}},
            {"step_title":"Other","is_optional":false,"media":{"id":40}}
          ]}]}
        ]}
    """.trimIndent()

    private val index = watchOrderWikiIndex(text)

    @Test fun picksTheFranchiseAndDropsEmptyOrders() {
        val orders = watchOrderWikiPick(index, 12)
        assertEquals(listOf("Airing Order", "Novel Order"), orders.map { it.name })
        assertEquals(listOf("Main Story"), watchOrderWikiPick(index, 40).map { it.name })
        assertTrue(watchOrderWikiPick(index, 999).isEmpty())
    }

    @Test fun stepsBecomeRows() {
        val rows = watchOrderWikiItems(watchOrderWikiPick(index, 11).first(), 11)
        assertEquals(listOf("10", "11", "12"), rows.map { it.anilistId })
        assertEquals(listOf("Andon", "Andon: Lanterns", "Andon 2"), rows.map { it.name })
        assertEquals(listOf(false, true, false), rows.map { it.isCurrent })
        assertEquals(listOf(true, false, true), rows.map { it.mainStory })
        assertEquals(listOf("TV", "OVA", "TV Short"), rows.map { it.mediaType })
        assertEquals("12 eps", rows[0].episodes)
        assertEquals("", rows[1].episodes)
        assertEquals("c10", rows[0].image)
    }

    @Test fun currentIsMarkedOnce() {
        val order = WikiOrder("x", steps = listOf(WikiStep("a", media = WikiMedia(5)), WikiStep("a", media = WikiMedia(5))))
        assertEquals(listOf(true, false), watchOrderWikiItems(order, 5).map { it.isCurrent })
        assertFalse(watchOrderWikiItems(order, 6).any { it.isCurrent })
    }

    @Test fun chainDecidesMainWhenTheNotesListMore() {
        // Made in Abyss as the wiki writes it: the chain, then recap movies named in a note
        val order = WikiOrder(
            "Main Story",
            "Made in Abyss\n->\nMade in Abyss: Dawn of the Deep Soul\n->\nMade in Abyss: Golden City\nThere are also two recap movies that\ncould\nbe watched in place of S1.\nJourney's Dawn\nand\nWandering Twilight",
            listOf(
                WikiStep("Made in Abyss", media = WikiMedia(1)),
                WikiStep("Made in Abyss: Dawn of the Deep Soul", media = WikiMedia(2)),
                WikiStep("Made in Abyss: Golden City", media = WikiMedia(3)),
                WikiStep("Journey's Dawn", media = WikiMedia(4)),
                WikiStep("Wandering Twilight", media = WikiMedia(5)),
            ),
        )
        assertEquals(listOf(true, true, true, false, false), watchOrderWikiItems(order, 1).map { it.mainStory })
    }

    @Test fun noChainKeepsTheFlags() {
        val order = WikiOrder("x", "Just some text", listOf(WikiStep("a", media = WikiMedia(1)), WikiStep("b", optional = true, media = WikiMedia(2))))
        assertEquals(listOf(true, false), watchOrderWikiItems(order, 1).map { it.mainStory })
    }

    @Test fun alternativesSitAfterWhatTheyReplace() {
        fun row(id: String, alt: String? = null) =
            MediaDetailsViewModel.WatchOrderItem(id, id, "", id, "", alternativeTo = alt)
        val placed = watchOrderPlaceAlternatives(listOf(row("s1"), row("movie"), row("s2"), row("recap1", "s1"), row("recap2", "s1"), row("x", "gone")))
        assertEquals(listOf("s1", "recap1", "recap2", "movie", "s2", "x"), placed.map { it.anilistId })
    }

    @Test fun alternativeDirection() {
        // Made in Abyss: the season lists its recap films as SUMMARY, the films list the season as PARENT
        val abyss = watchOrderAlternatives(
            order = listOf(1, 2, 3, 4),
            format = mapOf(1 to "TV", 2 to "MOVIE", 3 to "MOVIE", 4 to "MOVIE"),
            edges = mapOf(
                1 to listOf("SUMMARY" to 2, "SUMMARY" to 3, "SEQUEL" to 4),
                2 to listOf("PARENT" to 1, "SEQUEL" to 3),
                3 to listOf("PARENT" to 1, "SEQUEL" to 4),
                4 to listOf("PREQUEL" to 1),
            ),
        )
        assertEquals(mapOf(2 to 1, 3 to 1), abyss)
        // Madoka: ALTERNATIVE both ways between the series and the recap films
        val madoka = watchOrderAlternatives(
            order = listOf(10, 11, 12),
            format = mapOf(10 to "TV", 11 to "MOVIE", 12 to "MOVIE"),
            edges = mapOf(10 to listOf("ALTERNATIVE" to 11, "ALTERNATIVE" to 12), 11 to listOf("ALTERNATIVE" to 10), 12 to listOf("ALTERNATIVE" to 10)),
        )
        assertEquals(mapOf(11 to 10, 12 to 10), madoka)
    }
}
