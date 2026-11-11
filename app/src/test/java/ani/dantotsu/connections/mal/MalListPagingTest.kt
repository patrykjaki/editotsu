package ani.dantotsu.connections.mal

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MalListPagingTest {

    private fun entry(eps: Int) = MalListEntry(
        node = MalAnimeNode(id = eps, title = "t$eps"),
        listStatus = MalListStatus(numEpisodesWatched = eps),
    )

    private fun page(vararg eps: Int, next: Boolean) = MalListResponse(
        data = eps.map { entry(it) },
        paging = MalPaging(next = if (next) "https://api.myanimelist.net/next" else null),
    )

    @Test
    fun `sums every page and follows offsets`() = runBlocking {
        val offsets = mutableListOf<Int>()
        val pages = listOf(page(1, 2, next = true), page(3, 4, next = true), page(5, next = false))
        val total = MALQueries.sumAllListPages(
            fetch = { offset -> offsets += offset; pages[offsets.size - 1] },
            value = { it.listStatus?.numEpisodesWatched ?: 0 },
        )
        assertEquals(15, total)
        assertEquals(listOf(0, 2, 4), offsets)
    }

    @Test
    fun `first page failure is unknown`() = runBlocking {
        assertNull(MALQueries.sumAllListPages(fetch = { null }, value = { 1 }))
    }

    @Test
    fun `later page failure keeps what was read`() = runBlocking {
        var calls = 0
        val total = MALQueries.sumAllListPages(
            fetch = { if (calls++ == 0) page(7, next = true) else null },
            value = { it.listStatus?.numEpisodesWatched ?: 0 },
        )
        assertEquals(7, total)
    }
}
