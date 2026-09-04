package ani.dantotsu.connections.discord.rpc

import org.junit.Assert.*
import org.junit.Test

class DiscordRpcSessionControllerTest {

    private data class Harness(
        val transport: FakeTransport,
        val ownership: DiscordPresenceOwnership,
        val scheduler: ManualScheduler,
        val controller: DiscordRpcSessionController,
        val enabled: MutableList<Boolean>,
    ) {
        fun isEnabled() = enabled.last()
        fun anime(id: Long) = ownership.createToken(DiscordPresenceOwnership.OwnerKind.ANIME).also { /* id ignored, token carries id */ }
        fun manga(id: Long) = ownership.createToken(DiscordPresenceOwnership.OwnerKind.MANGA)
    }

    private fun newHarness(enabled: Boolean = true): Harness {
        val transport = FakeTransport()
        val ownership = DiscordPresenceOwnership()
        val scheduler = ManualScheduler()
        val coordinator = DiscordPresenceCoordinator(
            sender = transport::setPresence,
            clock = { 0L },
            scheduler = scheduler,
            windowMs = 500L,
        )
        val enabledList = mutableListOf(enabled)
        val controller = DiscordRpcSessionController(
            transport = transport,
            ownership = ownership,
            coordinator = coordinator,
            isEnabled = { enabledList.last() },
        )
        return Harness(transport, ownership, scheduler, controller, enabledList)
    }

    private fun presence(name: String) = DiscordPresence(name)

    // ── Blocker 1: routing switch must actually gate the new publisher ──

    @Test
    fun `routing disabled - publish sends nothing and tears down the session`() {
        val h = newHarness(enabled = false)
        val a = h.anime(1)
        h.controller.publish(a, presence("A"))

        assertEquals(0, h.transport.setPresenceCalls.size)
        assertEquals(1, h.transport.clearPresenceCount)
        assertEquals(1, h.transport.shutdownCount)
    }

    @Test
    fun `routing flips true to false - existing session is cleared and shut down`() {
        val h = newHarness(enabled = true)
        val a = h.anime(1)
        h.controller.publish(a, presence("A"))
        assertEquals(1, h.transport.setPresenceCalls.size)

        h.enabled[0] = false
        h.controller.publish(a, presence("A2"))

        assertEquals(1, h.transport.setPresenceCalls.size) // no new publish
        assertEquals(1, h.transport.clearPresenceCount)
        assertEquals(1, h.transport.shutdownCount)
    }

    @Test
    fun `routing disabled - clear also tears down (legacy path re-enabled)`() {
        val h = newHarness(enabled = true)
        val a = h.anime(1)
        h.controller.publish(a, presence("A"))
        h.enabled[0] = false
        val released = h.controller.clear(a)

        assertFalse(released) // clear is a teardown, not an authorised release
        assertEquals(1, h.transport.clearPresenceCount)
        assertEquals(1, h.transport.shutdownCount)
    }

    // ── Blocker 4: active final owner release clears + shuts down exactly once ──

    @Test
    fun `last active owner release - clear and shutdown exactly once`() {
        val h = newHarness(enabled = true)
        val a = h.anime(1)
        h.controller.publish(a, presence("A"))
        val released = h.controller.clear(a)

        assertTrue(released)
        assertEquals(1, h.transport.setPresenceCalls.size)
        assertEquals(1, h.transport.clearPresenceCount)
        assertEquals(1, h.transport.shutdownCount)
    }

    @Test
    fun `stale old owner release - no clear and no shutdown`() {
        val h = newHarness(enabled = true)
        val a = h.anime(1)
        val b = h.anime(2)
        h.controller.publish(a, presence("A"))
        h.controller.publish(b, presence("B")) // newer owner supersedes
        val released = h.controller.clear(a)    // stale A

        assertFalse(released)
        assertEquals(2, h.transport.setPresenceCalls.size) // both published, none cleared
        assertEquals(0, h.transport.clearPresenceCount)
        assertEquals(0, h.transport.shutdownCount)
    }

    // ── Blocker 4: a fresh owner can bind after shutdown ──

    @Test
    fun `new owner after shutdown can publish again`() {
        val h = newHarness(enabled = true)
        val a = h.anime(1)
        h.controller.publish(a, presence("A"))
        h.controller.shutdown()

        val c = h.anime(3) // new owner after shutdown
        h.controller.publish(c, presence("C"))
        assertEquals(2, h.transport.setPresenceCalls.size)
        assertEquals("C", h.transport.setPresenceCalls.last().name)
    }

    // ── Suppression keeps the session + ownership so the same owner can resume ──

    @Test
    fun `active owner suppressClear - presence cleared, ownership retained, resume publishes`() {
        val h = newHarness(enabled = true)
        val a = h.anime(1)
        h.controller.publish(a, presence("A"))
        assertTrue(h.controller.suppressClear(a))
        assertEquals(1, h.transport.clearPresenceCount)

        h.controller.publish(a, presence("A2")) // same owner -> pending
        h.scheduler.runDue()                    // flush latest
        assertEquals(listOf("A", "A2"), h.transport.setPresenceCalls.map { it.name })
    }

    // ── v3: suppression must be owner-token gated — a stale owner is a complete no-op ──

    @Test
    fun `anime A to anime B - stale A suppressClear leaves B visible`() {
        val h = newHarness(enabled = true)
        val a = h.anime(1)
        val b = h.anime(2)
        h.controller.publish(a, presence("A"))
        h.controller.publish(b, presence("B")) // newer owner supersedes

        assertFalse(h.controller.suppressClear(a)) // stale A
        assertEquals(0, h.transport.clearPresenceCount)
        assertEquals(listOf("A", "B"), h.transport.setPresenceCalls.map { it.name })
    }

    @Test
    fun `manga A to manga B - stale A suppressClear leaves B visible`() {
        val h = newHarness(enabled = true)
        val a = h.manga(1)
        val b = h.manga(2)
        h.controller.publish(a, presence("A"))
        h.controller.publish(b, presence("B"))

        assertFalse(h.controller.suppressClear(a))
        assertEquals(0, h.transport.clearPresenceCount)
    }

    @Test
    fun `anime to manga - stale anime suppressClear leaves manga visible`() {
        val h = newHarness(enabled = true)
        val a = h.anime(1)
        val m = h.manga(2)
        h.controller.publish(a, presence("anime-A"))
        h.controller.publish(m, presence("manga-B"))

        assertFalse(h.controller.suppressClear(a)) // stale anime
        assertEquals(0, h.transport.clearPresenceCount)
        assertEquals(listOf("anime-A", "manga-B"), h.transport.setPresenceCalls.map { it.name })
    }

    @Test
    fun `stale suppression cannot cancel the active owner's pending same-owner update`() {
        val h = newHarness(enabled = true)
        val a = h.anime(1)
        val b = h.anime(2)
        h.controller.publish(a, presence("A"))
        h.controller.publish(b, presence("B"))   // B now active, owns session
        // B schedules a pending update for itself.
        h.controller.publish(b, presence("B2"))  // same owner B -> pending
        // A late stale suppression from the old manager must NOT cancel B's pending.
        assertFalse(h.controller.suppressClear(a))
        h.scheduler.runDue()                     // B's pending must still flush
        assertEquals(listOf("A", "B", "B2"), h.transport.setPresenceCalls.map { it.name })
    }
}
