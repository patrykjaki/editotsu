package ani.dantotsu.connections.discord.rpc

import org.junit.Assert.*
import org.junit.Test

class DiscordPresenceOwnershipTest {

    private fun newOwnership() = DiscordPresenceOwnership()

    @Test
    fun `createToken mints unique per-instance tokens of the requested kind`() {
        val o = newOwnership()
        val a1 = o.createToken(DiscordPresenceOwnership.OwnerKind.ANIME)
        val a2 = o.createToken(DiscordPresenceOwnership.OwnerKind.ANIME)
        val m1 = o.createToken(DiscordPresenceOwnership.OwnerKind.MANGA)
        assertEquals(DiscordPresenceOwnership.OwnerKind.ANIME, a1.kind)
        assertEquals(DiscordPresenceOwnership.OwnerKind.MANGA, m1.kind)
        assertNotEquals(a1.id, a2.id)
        assertNotEquals(a1.id, m1.id)
    }

    @Test
    fun `fresh claim owns and excludes other owners`() {
        val o = newOwnership()
        val a = o.createToken(DiscordPresenceOwnership.OwnerKind.ANIME)
        o.claim(a)
        assertTrue(o.owns(a))
        assertFalse(o.owns(o.createToken(DiscordPresenceOwnership.OwnerKind.MANGA)))
    }

    // ── Blocker 2: same-kind recreation must not let a stale owner clear a newer owner ──

    @Test
    fun `anime A to anime B - stale A cannot clear B`() {
        val o = newOwnership()
        val a = o.createToken(DiscordPresenceOwnership.OwnerKind.ANIME)
        val b = o.createToken(DiscordPresenceOwnership.OwnerKind.ANIME)
        o.claim(a)
        o.claim(b) // newer anime owner supersedes
        assertFalse(o.owns(a))
        assertTrue(o.owns(b))
        assertFalse(o.release(a)) // stale A
        assertTrue(o.owns(b))
        assertTrue(o.release(b)) // active B
        assertFalse(o.owns(b))
    }

    @Test
    fun `manga A to manga B - stale A cannot clear B`() {
        val o = newOwnership()
        val a = o.createToken(DiscordPresenceOwnership.OwnerKind.MANGA)
        val b = o.createToken(DiscordPresenceOwnership.OwnerKind.MANGA)
        o.claim(a)
        o.claim(b)
        assertFalse(o.release(a))
        assertTrue(o.release(b))
    }

    @Test
    fun `anime to manga - stale anime cannot clear manga`() {
        val o = newOwnership()
        val a = o.createToken(DiscordPresenceOwnership.OwnerKind.ANIME)
        val m = o.createToken(DiscordPresenceOwnership.OwnerKind.MANGA)
        o.claim(a)
        o.claim(m)
        assertFalse(o.release(a))
        assertTrue(o.owns(m))
        assertTrue(o.release(m))
    }

    @Test
    fun `manga to anime - stale manga cannot clear anime`() {
        val o = newOwnership()
        val m = o.createToken(DiscordPresenceOwnership.OwnerKind.MANGA)
        val a = o.createToken(DiscordPresenceOwnership.OwnerKind.ANIME)
        o.claim(m)
        o.claim(a)
        assertFalse(o.release(m))
        assertTrue(o.owns(a))
        assertTrue(o.release(a))
    }

    @Test
    fun `same owner re-claim keeps ownership until released`() {
        val o = newOwnership()
        val a = o.createToken(DiscordPresenceOwnership.OwnerKind.ANIME)
        o.claim(a)
        o.claim(a)
        assertTrue(o.owns(a))
        assertTrue(o.release(a))
        assertFalse(o.owns(a))
    }

    @Test
    fun `reset drops any owner`() {
        val o = newOwnership()
        val a = o.createToken(DiscordPresenceOwnership.OwnerKind.ANIME)
        o.claim(a)
        o.reset()
        assertFalse(o.owns(a))
        assertFalse(o.release(a))
    }
}
