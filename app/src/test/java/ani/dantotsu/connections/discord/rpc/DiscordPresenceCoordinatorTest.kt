package ani.dantotsu.connections.discord.rpc

import org.junit.Assert.*
import org.junit.Test

class DiscordPresenceCoordinatorTest {

    private fun coord(scheduler: ManualScheduler, windowMs: Long = 500L): Pair<DiscordPresenceCoordinator, MutableList<DiscordPresence>> {
        val sent = mutableListOf<DiscordPresence>()
        val c = DiscordPresenceCoordinator(
            sender = { sent.add(it) },
            clock = { 0L },
            scheduler = scheduler,
            windowMs = windowMs,
        )
        return c to sent
    }

    private fun token(kind: DiscordPresenceOwnership.OwnerKind, id: Long) =
        DiscordPresenceOwnership.OwnerToken(kind, id)

    private fun presence(name: String) = DiscordPresence(name)

    // ── Blocker 3: owner transition must publish immediately, never hidden behind previous frame ──

    @Test
    fun `anime send then manga within 100ms - manga must publish immediately`() {
        val sched = ManualScheduler()
        val (c, sent) = coord(sched)
        val anime = token(DiscordPresenceOwnership.OwnerKind.ANIME, 1)
        val manga = token(DiscordPresenceOwnership.OwnerKind.MANGA, 2)

        c.submit(anime, presence("anime-A"))
        c.submit(manga, presence("manga-B"))

        assertEquals(listOf("anime-A", "manga-B"), sent.map { it.name })
    }

    @Test
    fun `owner transition sends immediately even inside the coalescing window`() {
        val sched = ManualScheduler()
        val (c, sent) = coord(sched)
        val a = token(DiscordPresenceOwnership.OwnerKind.ANIME, 1)
        val b = token(DiscordPresenceOwnership.OwnerKind.ANIME, 2)

        c.submit(a, presence("A1"))
        c.submit(b, presence("A2")) // newer same-kind owner

        assertEquals(listOf("A1", "A2"), sent.map { it.name })
        assertTrue(sched.dueAction == null) // nothing pending for the old owner
    }

    // ── Blocker 3: same-owner update coalesces to latest-wins, flushed at end of window ──

    @Test
    fun `anime ep1 then ep2 within window - ep2 eventually publishes`() {
        val sched = ManualScheduler()
        val (c, sent) = coord(sched)
        val a = token(DiscordPresenceOwnership.OwnerKind.ANIME, 1)

        c.submit(a, presence("ep1"))
        assertEquals(listOf("ep1"), sent.map { it.name })

        c.submit(a, presence("ep2")) // same owner -> pending
        assertEquals(listOf("ep1"), sent.map { it.name }) // not sent yet
        assertNotNull(sched.dueAction)

        sched.runDue() // end of window
        assertEquals(listOf("ep1", "ep2"), sent.map { it.name })
    }

    @Test
    fun `same owner rapid updates only ever send the latest`() {
        val sched = ManualScheduler()
        val (c, sent) = coord(sched)
        val a = token(DiscordPresenceOwnership.OwnerKind.ANIME, 1)

        c.submit(a, presence("ep1"))
        c.submit(a, presence("ep2"))
        c.submit(a, presence("ep3"))
        sched.runDue()
        assertEquals(listOf("ep1", "ep3"), sent.map { it.name })
    }

    // ── Blocker 3: pending update for a previous owner must be cancelled on transition ──

    @Test
    fun `pending anime update then manga claims - pending anime never sends`() {
        val sched = ManualScheduler()
        val (c, sent) = coord(sched)
        val a = token(DiscordPresenceOwnership.OwnerKind.ANIME, 1)
        val m = token(DiscordPresenceOwnership.OwnerKind.MANGA, 2)

        c.submit(a, presence("anime-A"))
        c.submit(a, presence("anime-A2")) // pending
        c.submit(m, presence("manga-B"))  // transition -> cancels pending anime

        assertEquals(listOf("anime-A", "manga-B"), sent.map { it.name })
        sched.runDue() // would-be pending anime must not fire
        assertEquals(listOf("anime-A", "manga-B"), sent.map { it.name })
    }

    // ── Blocker 3: shutdown cancels pending sends ──

    @Test
    fun `shutdown cancels a pending same-owner update`() {
        val sched = ManualScheduler()
        val (c, sent) = coord(sched)
        val a = token(DiscordPresenceOwnership.OwnerKind.ANIME, 1)

        c.submit(a, presence("ep1"))
        c.submit(a, presence("ep2")) // pending
        c.shutdown()
        sched.runDue()
        assertEquals(listOf("ep1"), sent.map { it.name }) // ep2 never sent
    }
}
